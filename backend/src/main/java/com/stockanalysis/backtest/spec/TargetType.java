package com.stockanalysis.backtest.spec;

/**
 * What a strategy trades against.
 * SINGLE: one dataset (normal stock or a single ETF), rule-based entry.
 * ETF: an ETF leverage/inverse pair; with {@link PremarketSpec} enabled, the pre-market
 * futures trend picks which side to buy each day.
 */
public enum TargetType {
    SINGLE,
    ETF
}
