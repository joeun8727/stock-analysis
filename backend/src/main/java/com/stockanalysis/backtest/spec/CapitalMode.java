package com.stockanalysis.backtest.spec;

/** How the investment amount is deployed across trades. */
public enum CapitalMode {
    /** Every entry buys with the same amount; profits are pocketed, not reinvested. */
    FIXED,
    /** Profits roll back in: each entry spends the running balance. */
    COMPOUND
}
