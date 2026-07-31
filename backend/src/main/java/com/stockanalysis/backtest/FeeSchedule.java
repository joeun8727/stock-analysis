package com.stockanalysis.backtest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Commission per traded symbol, in percent per side. Rates are per symbol rather than per strategy
 * because a leverage ETF and its inverse can sit at different rates, and an ETF pre-market run
 * trades both — so one number per run would be wrong.
 *
 * <p>Keys match {@link TradeRecord#instrument()}: {@code null} for a single-dataset run, otherwise
 * {@code LEVERAGE} / {@code INVERSE}. The futures series is only read for its trend, never traded,
 * so it has no rate here.
 */
public record FeeSchedule(double singlePct, double leveragePct, double inversePct) {

    public static final String LEVERAGE = "LEVERAGE";
    public static final String INVERSE = "INVERSE";
    /** Key used in reporting for a single-dataset run, where {@code instrument} is null. */
    public static final String SINGLE = "SINGLE";

    /** The same rate everywhere — for single-dataset runs and tests. */
    public static FeeSchedule flat(double pct) {
        return new FeeSchedule(pct, pct, pct);
    }

    /** Commission-free: the price-only baseline. */
    public static FeeSchedule free() {
        return flat(0.0);
    }

    public static FeeSchedule ofEtf(double leveragePct, double inversePct) {
        return new FeeSchedule(0.0, leveragePct, inversePct);
    }

    /** Rate for a trade's instrument; unknown instruments fall back to the single-dataset rate. */
    public double rateFor(String instrument) {
        if (instrument == null) {
            return singlePct;
        }
        return switch (instrument) {
            case LEVERAGE -> leveragePct;
            case INVERSE -> inversePct;
            default -> singlePct;
        };
    }

    /** The rates that actually applied, keyed by instrument, for reporting on the result. */
    public Map<String, Double> ratesFor(Iterable<TradeRecord> trades) {
        Map<String, Double> used = new LinkedHashMap<>();
        for (TradeRecord t : trades) {
            String key = t.instrument() == null ? SINGLE : t.instrument();
            used.putIfAbsent(key, rateFor(t.instrument()));
        }
        return used;
    }
}
