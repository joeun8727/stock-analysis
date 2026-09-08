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
 * Reads and updates the single live-trading setup row.
 *
 * <p>Note what this service does <em>not</em> accept: any trading rule. Take-profit, stop-loss,
 * time bands and the pre-market threshold are edited on the strategy screen and nowhere else, so
 * the thing being traded stays byte-identical to the thing that was backtested.
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

    /** Settings the user may change. Everything here is execution environment, not strategy. */
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
            // The V8 migration seeds this row; fall back to a transient default rather than
            // failing the screen if it is ever missing.
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

        // Picking a strategy re-establishes what "verified" means: the run it is judged against and
        // the exact rules that run scored. Changing the rules later will no longer match this hash.
        if (strategyChanged || config.getVerifiedSpecHash() == null) {
            rebaseVerification(config);
        }

        // A settings change is not consent to trade today. Re-arming is a separate, deliberate act.
        config.setArmedDate(null);
        return repo.save(config);
    }

    /** Records the strategy's current rules and its latest backtest as the verification basis. */
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
            return config; // never backtested — the gate will say so
        }
        if (strategy.getUpdatedAt() != null && run.getCreatedAt() != null
                && run.getCreatedAt().isBefore(strategy.getUpdatedAt())) {
            // The newest run predates the newest edit, so it did not test these rules. Leaving the
            // hash unset makes the gate fall back to exactly that complaint.
            return config;
        }
        config.setVerifiedRunId(run.getId());
        config.setVerifiedSpecHash(SpecHasher.hash(strategy.getSpec()));
        return config;
    }

    /** Turns trading on for a specific day. Refuses when the strategy is not verified. */
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
