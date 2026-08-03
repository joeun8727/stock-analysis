package com.stockanalysis.backtest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A primary bar series in ascending time order, with an optional aligned futures series
 * (keyed by timestamp) so strategies may reference futures indicators.
 */
public final class BarSeries {

    /** Assumed bar length when a series is too short to measure one (all seed data is 3-minute). */
    public static final int DEFAULT_INTERVAL_MINUTES = 3;

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

    /**
     * Bar length in minutes, measured as the most common gap between consecutive bars of the same
     * day (3 for 3-minute data, 1 for 1-minute). The mode — not the minimum — so a session break
     * or a missing bar can't stretch the answer, and cross-day gaps are skipped entirely. Ties go
     * to the shorter gap. Returns {@link #DEFAULT_INTERVAL_MINUTES} when nothing is measurable.
     *
     * <p>The engine never consults this: it counts bars, not minutes. It exists so the metadata,
     * the UI copy and the LLM prompt can say how long one 봉 actually is for this dataset.
     */
    public int inferIntervalMinutes() {
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 1; i < bars.size(); i++) {
            Bar prev = bars.get(i - 1);
            Bar cur = bars.get(i);
            if (!prev.date().equals(cur.date())) {
                continue; // overnight gap says nothing about bar length
            }
            long minutes = Duration.between(prev.ts(), cur.ts()).toMinutes();
            if (minutes > 0) {
                counts.merge(minutes, 1, Integer::sum);
            }
        }
        long best = 0;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() < best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best == 0 ? DEFAULT_INTERVAL_MINUTES : (int) Math.min(best, Integer.MAX_VALUE);
    }
}
