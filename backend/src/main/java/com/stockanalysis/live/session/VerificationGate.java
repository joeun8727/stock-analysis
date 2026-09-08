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
 * The rule that a strategy must be backtested before it can trade money.
 *
 * <p>The workflow this project is built around is: save a logic, backtest it, then run it on a
 * paper account, then a real one. That order only means anything if it is enforced, so this is
 * checked in three places — when the live config is saved, when the day is armed, and again on
 * every session start — rather than being left to the user to remember.
 *
 * <p>The check that actually earns its keep is the spec hash. Everything else catches an omission;
 * the hash catches the dangerous case, where a verified strategy was edited afterwards and would
 * otherwise keep trading on a backtest that no longer describes it.
 */
@Service
public class VerificationGate {

    private final StrategyRepository strategies;
    private final BacktestRunRepository runs;

    public VerificationGate(StrategyRepository strategies, BacktestRunRepository runs) {
        this.strategies = strategies;
        this.runs = runs;
    }

    /** Why a strategy may or may not be traded, and the evidence behind it. */
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
     * Evaluates a strategy against a live config's thresholds. Never throws — the screen needs the
     * reason to show the user, and the scheduler needs a decision, not an exception.
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

        // The core check: is the saved logic still the logic that was scored? A recorded hash is
        // authoritative; without one we fall back to "was the run made after the last edit?".
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

    /** The most recent backtest for a strategy, or null when it has never been run. */
    @Transactional(readOnly = true)
    public BacktestRun latestRunFor(Long strategyId) {
        return runs.findAll().stream()
                .filter(r -> strategyId.equals(r.getStrategyId()))
                .max(Comparator.comparing(BacktestRun::getCreatedAt))
                .orElse(null);
    }

    /** Every strategy that could be traded, each with its verification status. */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(LiveConfig config) {
        List<Candidate> out = new ArrayList<>();
        for (Strategy s : strategies.findAll()) {
            if (!s.getSpec().usesPremarket()) {
                continue; // only the pre-market ETF mode is wired for live trading
            }
            // Candidates are listed against their own evidence, not the configured strategy's
            // hash — otherwise every strategy but the selected one would read as "changed".
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

    /** A copy carrying only the minimums, so another strategy isn't judged by this one's hash. */
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

    /** Calendar days covered by the run's actual bars — the honest measure of "how long". */
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
