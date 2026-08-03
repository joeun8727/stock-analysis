package com.stockanalysis.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Bar length inference — what {@code dataset.bar_interval_minutes} is filled from at upload. */
class BarSeriesIntervalTest {

    private static Bar bar(LocalDateTime ts) {
        return new Bar(ts, 100, 100, 100, 100, 100, 100, 100, 100, 1, 1, 1, 1, 1);
    }

    /** {@code days} sessions of {@code perDay} bars spaced {@code stepMinutes} apart from 09:00. */
    private static BarSeries series(int stepMinutes, int perDay, int days) {
        List<Bar> bars = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDateTime open = LocalDateTime.of(2024, 1, 1 + d, 9, 0);
            for (int i = 0; i < perDay; i++) {
                bars.add(bar(open.plusMinutes((long) stepMinutes * i)));
            }
        }
        return new BarSeries(bars);
    }

    @Test
    void infersThreeMinuteBars() {
        assertEquals(3, series(3, 20, 3).inferIntervalMinutes());
    }

    @Test
    void infersOneMinuteBars() {
        assertEquals(1, series(1, 60, 3).inferIntervalMinutes());
    }

    /** Overnight gaps are 15+ hours; they must not be mistaken for the bar length. */
    @Test
    void ignoresOvernightGaps() {
        assertEquals(1, series(1, 2, 30).inferIntervalMinutes());
    }

    /** A session break (or a missing bar) is a minority gap, so the mode still wins. */
    @Test
    void toleratesGapsWithinTheDay() {
        List<Bar> bars = new ArrayList<>();
        LocalDateTime open = LocalDateTime.of(2024, 1, 1, 9, 0);
        for (int i = 0; i < 10; i++) {
            bars.add(bar(open.plusMinutes(i)));
        }
        bars.add(bar(open.plusMinutes(70))); // one hour missing mid-session
        bars.add(bar(open.plusMinutes(71)));
        assertEquals(1, new BarSeries(bars).inferIntervalMinutes());
    }

    /** Nothing measurable (single bar, or one bar per day) falls back to the documented default. */
    @Test
    void fallsBackWhenUnmeasurable() {
        assertEquals(BarSeries.DEFAULT_INTERVAL_MINUTES,
                new BarSeries(List.of(bar(LocalDateTime.of(2024, 1, 1, 9, 0)))).inferIntervalMinutes());
        assertEquals(BarSeries.DEFAULT_INTERVAL_MINUTES, series(3, 1, 5).inferIntervalMinutes());
    }
}
