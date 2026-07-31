package com.stockanalysis.backtest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A primary bar series in ascending time order, with an optional aligned futures series
 * (keyed by timestamp) so strategies may reference futures indicators.
 */
public final class BarSeries {

    private final List<Bar> bars;
    private final Map<LocalDateTime, Bar> futuresByTs;

    public BarSeries(List<Bar> bars) {
        this(bars, Map.of());
    }

    public BarSeries(List<Bar> bars, Map<LocalDateTime, Bar> futuresByTs) {
        List<Bar> copy = new ArrayList<>(bars);
        copy.sort((a, b) -> a.ts().compareTo(b.ts()));
        this.bars = List.copyOf(copy);
        this.futuresByTs = futuresByTs == null ? Map.of() : Map.copyOf(futuresByTs);
    }

    public List<Bar> bars() {
        return bars;
    }

    public int size() {
        return bars.size();
    }

    /** Aligned futures bar at the given timestamp, or {@code null} if none. */
    public Bar futuresAt(LocalDateTime ts) {
        return futuresByTs.get(ts);
    }

    public boolean hasFutures() {
        return !futuresByTs.isEmpty();
    }
}
