package com.stockanalysis.backtest.spec;

/**
 * Trade direction. Only LONG is modelled for now — inverse ETFs are themselves inverse
 * instruments, so a "buy" on an inverse ETF already expresses a bearish view.
 */
public enum Position {
    LONG
}
