package com.stockanalysis.backtest;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single intraday candle (3-minute or 1-minute, depending on the dataset) with precomputed
 * moving averages.
 * Missing values are represented as {@link Double#NaN} so comparisons involving them are false.
 */
public record Bar(
        LocalDateTime ts,
        double open,
        double high,
        double low,
        double close,
        double ma5,
        double ma10,
        double ma20,
        double ma60,
        double volume,
        double volMa5,
        double volMa20,
        double volMa60,
        double volMa120
) {

    public LocalDate date() {
        return ts.toLocalDate();
    }

    /** Value of an indicator on this bar. */
    public double value(Indicator indicator) {
        return switch (indicator) {
            case OPEN -> open;
            case HIGH -> high;
            case LOW -> low;
            case CLOSE -> close;
            case MA5 -> ma5;
            case MA10 -> ma10;
            case MA20 -> ma20;
            case MA60 -> ma60;
            case VOLUME -> volume;
            case VOL_MA5 -> volMa5;
            case VOL_MA20 -> volMa20;
            case VOL_MA60 -> volMa60;
            case VOL_MA120 -> volMa120;
        };
    }
}
