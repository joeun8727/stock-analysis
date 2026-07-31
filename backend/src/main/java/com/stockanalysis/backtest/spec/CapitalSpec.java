package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How much money a strategy puts to work, so results can be reported in won and not only in
 * percent. {@code amount} is the per-trade budget in {@link CapitalMode#FIXED} and the starting
 * balance in {@link CapitalMode#COMPOUND}.
 *
 * <p>Commission is deliberately <em>not</em> here: it belongs to the symbol, not the strategy, so it
 * lives on {@code dataset.fee_rate_pct} and reaches the engine as a {@code FeeSchedule}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapitalSpec {

    private double amount = 10_000_000.0;
    private CapitalMode mode = CapitalMode.FIXED;

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public CapitalMode getMode() {
        return mode;
    }

    public void setMode(CapitalMode mode) {
        this.mode = mode == null ? CapitalMode.FIXED : mode;
    }
}
