package com.stockanalysis.backtest;

/** Comparison operators. CROSS_* compare the current bar against the previous bar. */
public enum Operator {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    CROSS_ABOVE,
    CROSS_BELOW
}
