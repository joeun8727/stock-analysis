package com.stockanalysis.backtest;

/**
 * Indicators available on each bar. These map positionally onto the 15-column excel schema
 * (the moving-average columns reuse bare integer headers, so we bind by position, not name).
 */
public enum Indicator {
    OPEN,
    HIGH,
    LOW,
    CLOSE,
    MA5,
    MA10,
    MA20,
    MA60,
    VOLUME,
    VOL_MA5,
    VOL_MA20,
    VOL_MA60,
    VOL_MA120
}
