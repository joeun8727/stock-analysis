package com.stockanalysis.run;

import com.stockanalysis.backtest.BacktestResult;

import java.util.List;
import java.util.Map;

/**
 * Persisted form of a backtest result (stored in {@code backtest_run.summary_json}).
 * All timestamps are ISO strings and enum keys are strings so Hibernate's Jackson JSON
 * mapper (which lacks the Java-time module) can serialize it without extra config.
 */
public record StoredResult(
        BacktestResult.Summary summary,
        BacktestResult.MoneySummary money,
        BacktestResult.Diagnosis diagnosis,
        List<StoredEquityPoint> equityCurve,
        Map<String, BacktestResult.ReasonStat> failureByReason,
        Map<String, BacktestResult.HourStat> failureByHour,
        List<StoredTrade> worstTrades
) {

    public record StoredEquityPoint(String ts, double equity) {
    }

    public record StoredTrade(
            String entryTs,
            String exitTs,
            double entryPrice,
            double exitPrice,
            double returnPct,
            String exitReason,
            boolean success,
            String instrument,
            long quantity,
            double profitAmount,
            double feeAmount
    ) {
    }
}
