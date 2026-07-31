package com.stockanalysis.backtest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Full backtest output: headline metrics, per-trade records, an equity curve, and a
 * breakdown of where losses occurred (by exit reason and by time of day).
 */
public record BacktestResult(
        Summary summary,
        MoneySummary money,
        Diagnosis diagnosis,
        List<TradeRecord> trades,
        List<EquityPoint> equityCurve,
        FailureAnalysis failureAnalysis
) {

    /** Plain-Korean read of what went wrong, built from deterministic rules. */
    public record Diagnosis(String headline, List<Finding> findings) {
    }

    public record Finding(Severity severity, String title, String detail, String suggestion) {
    }

    /** Ordered worst-first; the UI sorts and colours by this. */
    public enum Severity {
        HIGH,
        MEDIUM,
        INFO
    }

    /**
     * The same run expressed in won, from the strategy's {@code CapitalSpec}. Amounts are net of
     * commission. {@code unaffordableTrades} counts signals where the budget couldn't buy a single
     * share — in compound mode that means the balance was ground down below one share's price.
     */
    public record MoneySummary(
            String mode,
            double investAmount,
            Map<String, Double> feeRatesPct,
            double totalProfitAmount,
            double totalFeeAmount,
            double avgProfitPerTrade,
            double finalBalance,
            double returnOnCapitalPct,
            double bestTradeAmount,
            double worstTradeAmount,
            int unaffordableTrades
    ) {
    }

    /** Headline metrics. Returns are in percent. */
    public record Summary(
            int totalTrades,
            int wins,
            int losses,
            double winRate,
            double lossRate,
            double totalReturnPct,
            double compoundedReturnPct,
            double avgWinPct,
            double avgLossPct,
            double maxDrawdownPct,
            int maxConsecutiveLosses
    ) {
    }

    public record EquityPoint(LocalDateTime ts, double equity) {
    }

    /** Where failures happened. */
    public record FailureAnalysis(
            Map<ExitReason, ReasonStat> byExitReason,
            Map<Integer, HourStat> byHour,
            List<TradeRecord> worstTrades
    ) {
    }

    public record ReasonStat(int count, double avgReturnPct, double totalReturnPct) {
    }

    public record HourStat(int lossCount, double avgLossPct) {
    }
}
