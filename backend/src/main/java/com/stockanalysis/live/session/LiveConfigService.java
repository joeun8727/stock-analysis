package com.stockanalysis.live.session;

import com.stockanalysis.domain.BacktestRun;
import com.stockanalysis.domain.LiveConfig;
import com.stockanalysis.domain.LiveConfigRepository;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.domain.StrategyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 실투자 설정 단일 행을 읽고 갱신합니다.
 *
 * <p>이 서비스가 받지 <em>않는</em> 것에 주목하세요: 매매 규칙은 하나도 받지 않습니다. 익절,
 * 손절, 시간대 밴드, 장전 임계치는 전략 화면에서만 편집합니다. 그래야 매매되는 것이 백테스트한
 * 것과 바이트 단위로 같게 유지됩니다.
 */
@Service
public class LiveConfigService {

    private final LiveConfigRepository repo;
    private final StrategyRepository strategies;
    private final VerificationGate gate;

    public LiveConfigService(LiveConfigRepository repo, StrategyRepository strategies, VerificationGate gate) {
        this.repo = repo;
        this.strategies = strategies;
        this.gate = gate;
    }

    /** 사용자가 바꿀 수 있는 설정. 여기 있는 건 전부 실행 환경이고 전략이 아닙니다. */
    public record Update(
            Long strategyId,
            Long etfGroupId,
            String futuresTicker,
            Double maxOrderAmount,
            Double maxDailyLoss,
            Integer pollIntervalSec,
            String dayEndExitTime,
            Integer minVerifiedTrades,
            Integer minVerifiedDays
    ) {
    }

    @Transactional(readOnly = true)
    public LiveConfig get() {
        return repo.findById(LiveConfig.ID).orElseGet(() -> {
            // V8 마이그레이션이 이 행을 심어둡니다. 혹시라도 없으면 화면을 실패시키는 대신
            // 영속화되지 않은 기본값으로 폴백합니다.
            LiveConfig fallback = new LiveConfig();
            fallback.setId(LiveConfig.ID);
            return fallback;
        });
    }

    @Transactional
    public LiveConfig update(Update req) {
        LiveConfig config = repo.findById(LiveConfig.ID).orElseGet(() -> {
            LiveConfig fresh = new LiveConfig();
            fresh.setId(LiveConfig.ID);
            return fresh;
        });

        boolean strategyChanged = req.strategyId() != null
                && !req.strategyId().equals(config.getStrategyId());

        if (req.strategyId() != null) {
            config.setStrategyId(req.strategyId());
        }
        if (req.etfGroupId() != null) {
            config.setEtfGroupId(req.etfGroupId());
        }
        if (req.futuresTicker() != null) {
            config.setFuturesTicker(req.futuresTicker());
        }
        if (req.maxOrderAmount() != null) {
            config.setMaxOrderAmount(requirePositive(req.maxOrderAmount(), "1회 최대 주문금액"));
        }
        if (req.maxDailyLoss() != null) {
            config.setMaxDailyLoss(requirePositive(req.maxDailyLoss(), "일일 손실 한도"));
        }
        if (req.pollIntervalSec() != null) {
            int seconds = req.pollIntervalSec();
            if (seconds < 1 || seconds > 60) {
                throw new IllegalArgumentException("시세 조회 주기는 1~60초 사이여야 합니다.");
            }
            config.setPollIntervalSec(seconds);
        }
        if (req.dayEndExitTime() != null) {
            config.setDayEndExitTime(parseTime(req.dayEndExitTime()));
        }
        if (req.minVerifiedTrades() != null) {
            config.setMinVerifiedTrades(Math.max(0, req.minVerifiedTrades()));
        }
        if (req.minVerifiedDays() != null) {
            config.setMinVerifiedDays(Math.max(0, req.minVerifiedDays()));
        }

        // 전략을 고르면 "검증됨"의 의미가 새로 정해집니다: 근거가 되는 실행과, 그 실행이 점수를
        // 매긴 정확한 규칙. 나중에 규칙을 바꾸면 이 해시와 더는 맞지 않습니다.
        if (strategyChanged || config.getVerifiedSpecHash() == null) {
            rebaseVerification(config);
        }

        // 설정 변경은 오늘 매매하겠다는 동의가 아닙니다. 재활성화는 별개의 의도적인 행동입니다.
        config.setArmedDate(null);
        return repo.save(config);
    }

    /** 전략의 현재 규칙과 최신 백테스트를 검증 근거로 기록합니다. */
    @Transactional
    public LiveConfig rebaseVerification(LiveConfig config) {
        config.setVerifiedRunId(null);
        config.setVerifiedSpecHash(null);
        if (config.getStrategyId() == null) {
            return config;
        }
        Strategy strategy = strategies.findById(config.getStrategyId()).orElse(null);
        if (strategy == null) {
            return config;
        }
        BacktestRun run = gate.latestRunFor(config.getStrategyId());
        if (run == null) {
            return config; // 백테스트한 적 없음 — 게이트가 그렇게 알려줍니다
        }
        if (strategy.getUpdatedAt() != null && run.getCreatedAt() != null
                && run.getCreatedAt().isBefore(strategy.getUpdatedAt())) {
            // 가장 최근 실행이 가장 최근 수정보다 앞서므로, 지금 이 규칙을 시험한 것이 아닙니다.
            // 해시를 비워두면 게이트가 정확히 그 지적으로 폴백합니다.
            return config;
        }
        config.setVerifiedRunId(run.getId());
        config.setVerifiedSpecHash(SpecHasher.hash(strategy.getSpec()));
        return config;
    }

    /** 특정 날짜에 대해 매매를 켭니다. 전략이 검증되지 않았으면 거부합니다. */
    @Transactional
    public LiveConfig arm(LocalDate date) {
        LiveConfig config = repo.findById(LiveConfig.ID)
                .orElseThrow(() -> new IllegalArgumentException("실투자 설정이 없습니다."));
        if (config.getEtfGroupId() == null) {
            throw new IllegalArgumentException("ETF 그룹을 선택해주세요.");
        }
        if (config.getFuturesTicker() == null) {
            throw new IllegalArgumentException("장전 추세를 읽을 선물 종목코드를 입력해주세요.");
        }
        VerificationGate.Status status = gate.evaluate(config.getStrategyId(), config);
        if (!status.verified()) {
            throw new IllegalArgumentException(status.reason());
        }
        config.setArmedDate(date == null ? LocalDate.now() : date);
        return repo.save(config);
    }

    @Transactional
    public LiveConfig disarm() {
        LiveConfig config = repo.findById(LiveConfig.ID)
                .orElseThrow(() -> new IllegalArgumentException("실투자 설정이 없습니다."));
        config.setArmedDate(null);
        return repo.save(config);
    }

    private static double requirePositive(double value, String label) {
        if (Double.isNaN(value) || value <= 0) {
            throw new IllegalArgumentException(label + "은(는) 0보다 커야 합니다.");
        }
        return value;
    }

    private static String parseTime(String text) {
        try {
            return LocalTime.parse(text.trim()).toString().substring(0, 5);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("장 마감 청산 시각은 HH:mm 형식이어야 합니다: " + text);
        }
    }
}
