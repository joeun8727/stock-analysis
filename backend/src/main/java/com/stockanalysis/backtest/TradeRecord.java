package com.stockanalysis.backtest;

import java.time.LocalDateTime;

/**
 * One completed round-trip trade. {@code instrument} names which side was traded in ETF
 * pre-market mode (LEVERAGE / INVERSE); it is {@code null} for single-dataset backtests.
 *
 * <p>{@code quantity} / {@code profitAmount} / {@code feeAmount} are the money view of the trade,
 * filled in by the engine's capital pass from the strategy's {@code CapitalSpec}. They stay 0 until
 * that pass runs, since sizing depends on the running balance in compound mode.
 */
public record TradeRecord(
        LocalDateTime entryTs,
        LocalDateTime exitTs,
        double entryPrice,
        double exitPrice,
        double returnPct,
        ExitReason exitReason,
        boolean success,
        String instrument,
        long quantity,
        double profitAmount,
        double feeAmount
) {
    /** Price-only trade; money fields are filled in later by the capital pass. */
    public TradeRecord(LocalDateTime entryTs, LocalDateTime exitTs, double entryPrice, double exitPrice,
                       double returnPct, ExitReason exitReason, boolean success, String instrument) {
        this(entryTs, exitTs, entryPrice, exitPrice, returnPct, exitReason, success, instrument, 0L, 0.0, 0.0);
    }

    public TradeRecord withMoney(long quantity, double profitAmount, double feeAmount) {
        return new TradeRecord(entryTs, exitTs, entryPrice, exitPrice, returnPct, exitReason,
                success, instrument, quantity, profitAmount, feeAmount);
    }

    public int entryHour() {
        return entryTs.getHour();
    }
}
