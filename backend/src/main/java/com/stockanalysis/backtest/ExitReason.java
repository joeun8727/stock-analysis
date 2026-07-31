package com.stockanalysis.backtest;

/** Why a position was closed. Ordered by the engine's exit-check priority. */
public enum ExitReason {
    STOP_LOSS,
    TAKE_PROFIT,
    SIGNAL,
    TIME,
    DAY_END,
    END_OF_DATA
}
