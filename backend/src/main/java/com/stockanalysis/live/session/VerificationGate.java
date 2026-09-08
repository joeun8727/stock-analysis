package com.stockanalysis.live.session;

import com.stockanalysis.backtest.BacktestResult;
import com.stockanalysis.domain.BacktestRun;
import com.stockanalysis.domain.BacktestRunRepository;
import com.stockanalysis.domain.LiveConfig;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.domain.StrategyRepository;
import com.stockanalysis.run.RunParams;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 전략은 돈을 걸기 전에 백테스트를 거쳐야 한다는 규칙.
 *
 * <p>이 프로젝트가 전제하는 흐름은 로직 저장 → 백테스트 → 모의투자 → 실계좌입니다. 그 순서는
 * 강제될 때만 의미가 있어서, 사용자가 기억하기를 기대하는 대신 세 군데에서 확인합니다 —
 * 실투자 설정을 저장할 때, 그날을 활성화할 때, 그리고 매 세션 시작 때.
 *
 * <p>실제로 값을 하는 검사는 스펙 해시입니다. 나머지는 빠뜨린 것을 잡아내는 정도지만, 해시는
 * 위험한 경우를 잡습니다: 검증받은 전략을 그 뒤에 고쳐놓고, 더는 그 전략을 설명하지 못하는
 * 백테스트를 근거로 계속 매매하는 경우입니다.
 */
@Service
public class VerificationGate {

    private final StrategyRepository strategies;
    private final BacktestRunRepository runs;

    public VerificationGate(StrategyRepository strategies, BacktestRunRepository runs) {
        this.strategies = strategies;
        this.runs = runs;
    }

    /** 이 전략을 매매해도 되는지, 그리고 그 판단의 근거. */
    public record Status(
            boolean verified,
            String reason,
            Long runId,
            String runCreatedAt,
            Integer totalTrades,
            Double winRate,
            Double profitLossRatio,
            Double maxDrawdown,
            Double totalProfitAmount,
            String fromTs,
            String toTs,
            Integer spanDays
    ) {
        public static Status blocked(String reason) {
            return new Status(false, reason, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 실투자 설정의 기준값에 비추어 전략을 평가합니다. 절대 예외를 던지지 않습니다 — 화면은
     * 사용자에게 보여줄 사유가 필요하고, 스케줄러에는 예외가 아니라 판단이 필요합니다.
     */
    @Transactional(readOnly = true)
    public Status evaluate(Long strategyId, LiveConfig config) {
        if (strategyId == null) {
            return Status.blocked("실투자에 사용할 전략을 선택해주세요.");
        }
        Optional<Strategy> found = strategies.findById(strategyId);
        if (found.isEmpty()) {
            return Status.blocked("전략을 찾을 수 없습니다: " + strategyId);
        }
        Strategy strategy = found.get();
        if (!strategy.getSpec().usesPremarket()) {
            return Status.blocked("실투자는 ETF 장전추세 전략만 지원합니다. 전략의 대상을 ETF로 두고 장전추세를 켜주세요.");
        }

        BacktestRun run = latestRunFor(strategyId);
        if (run == null) {
            return Status.blocked("이 전략은 아직 백테스트를 한 적이 없습니다. 먼저 백테스트로 검증하세요.");
        }

        Status evidence = describe(run);

        // 핵심 검사: 저장된 로직이 아직 그때 점수를 받은 그 로직인가? 기록된 해시가 있으면 그것이
        // 기준이고, 없으면 "마지막 수정 이후에 돌린 실행인가?"로 폴백합니다.
        String currentHash = SpecHasher.hash(strategy.getSpec());
        String verifiedHash = config == null ? null : config.getVerifiedSpecHash();
        if (verifiedHash != null && !verifiedHash.isBlank()) {
            if (!verifiedHash.equals(currentHash)) {
                return Status.blocked("검증 이후 로직이 바뀌었습니다. 바뀐 규칙으로 다시 백테스트한 뒤 실투자를 시작하세요.");
            }
        } else if (strategy.getUpdatedAt() != null && run.getCreatedAt() != null
                && run.getCreatedAt().isBefore(strategy.getUpdatedAt())) {
            return Status.blocked("가장 최근 백테스트가 전략 수정보다 오래됐습니다. 지금 규칙으로 다시 백테스트하세요.");
        }

        if (config != null) {
            Integer trades = evidence.totalTrades();
            if (trades == null || trades < config.getMinVerifiedTrades()) {
                return Status.blocked("검증 근거가 부족합니다: 거래 " + (trades == null ? 0 : trades)
                        + "건 (최소 " + config.getMinVerifiedTrades() + "건). 기간을 넓혀 다시 백테스트하세요.");
            }
            Integer span = evidence.spanDays();
            if (span == null || span < config.getMinVerifiedDays()) {
                return Status.blocked("검증 기간이 짧습니다: " + (span == null ? 0 : span)
                        + "일 (최소 " + config.getMinVerifiedDays() + "일). 기간을 넓혀 다시 백테스트하세요.");
            }
        }

        return new Status(true, "검증됨", evidence.runId(), evidence.runCreatedAt(),
                evidence.totalTrades(), evidence.winRate(), evidence.profitLossRatio(),
                evidence.maxDrawdown(), evidence.totalProfitAmount(),
                evidence.fromTs(), evidence.toTs(), evidence.spanDays());
    }

    /** 전략의 가장 최근 백테스트. 한 번도 돌린 적 없으면 null. */
    @Transactional(readOnly = true)
    public BacktestRun latestRunFor(Long strategyId) {
        return runs.findAll().stream()
                .filter(r -> strategyId.equals(r.getStrategyId()))
                .max(Comparator.comparing(BacktestRun::getCreatedAt))
                .orElse(null);
    }

    /** 매매 후보가 될 수 있는 모든 전략과 각각의 검증 상태. */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(LiveConfig config) {
        List<Candidate> out = new ArrayList<>();
        for (Strategy s : strategies.findAll()) {
            if (!s.getSpec().usesPremarket()) {
                continue; // 실투자에 연결된 건 ETF 장전 모드뿐입니다
            }
            // 후보는 설정된 전략의 해시가 아니라 각자의 근거로 판단합니다 — 아니면 선택된 것
            // 하나를 빼고 전부 "변경됨"으로 읽힙니다.
            LiveConfig scoped = config;
            if (config != null && !s.getId().equals(config.getStrategyId())) {
                scoped = thresholdsOnly(config);
            }
            out.add(new Candidate(s.getId(), s.getName(), s.getSource(), evaluate(s.getId(), scoped)));
        }
        return out;
    }

    public record Candidate(Long strategyId, String name, String source, Status status) {
    }

    /** 최소 기준만 담은 복사본. 다른 전략이 이 전략의 해시로 판정되지 않게 하려고 씁니다. */
    private static LiveConfig thresholdsOnly(LiveConfig config) {
        LiveConfig copy = new LiveConfig();
        copy.setMinVerifiedTrades(config.getMinVerifiedTrades());
        copy.setMinVerifiedDays(config.getMinVerifiedDays());
        return copy;
    }

    private static Status describe(BacktestRun run) {
        BacktestResult.Summary summary = run.getSummary() == null ? null : run.getSummary().summary();
        BacktestResult.MoneySummary money = run.getSummary() == null ? null : run.getSummary().money();
        RunParams params = run.getParams();

        Double ratio = null;
        if (summary != null && summary.avgLossPct() != 0) {
            ratio = Math.abs(summary.avgWinPct() / summary.avgLossPct());
        }
        String fromTs = params == null ? null : params.barFromTs();
        String toTs = params == null ? null : params.barToTs();
        Integer span = spanDays(fromTs, toTs);

        return new Status(true, "검증됨", run.getId(),
                run.getCreatedAt() == null ? null : run.getCreatedAt().toString(),
                summary == null ? null : summary.totalTrades(),
                summary == null ? null : summary.winRate(),
                ratio,
                summary == null ? null : summary.maxDrawdownPct(),
                money == null ? null : money.totalProfitAmount(),
                fromTs, toTs, span);
    }

    /** 실행이 실제로 덮은 봉들의 달력 일수 — "얼마나 긴 기간인가"를 정직하게 재는 값. */
    private static Integer spanDays(String fromTs, String toTs) {
        if (fromTs == null || toTs == null) {
            return null;
        }
        try {
            LocalDate from = LocalDate.parse(fromTs.substring(0, 10));
            LocalDate to = LocalDate.parse(toTs.substring(0, 10));
            return (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
