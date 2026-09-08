package com.stockanalysis.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 봉 길이 추론 — 업로드 시 {@code dataset.bar_interval_minutes}를 채우는 값. */
class BarSeriesIntervalTest {

    private static Bar bar(LocalDateTime ts) {
        return new Bar(ts, 100, 100, 100, 100, 100, 100, 100, 100, 1, 1, 1, 1, 1);
    }

    /** 09:00부터 {@code stepMinutes} 간격으로 {@code perDay}개씩, {@code days}일치 세션. */
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

    /** 밤을 넘기는 간격은 15시간 이상입니다. 이걸 봉 길이로 착각하면 안 됩니다. */
    @Test
    void ignoresOvernightGaps() {
        assertEquals(1, series(1, 2, 30).inferIntervalMinutes());
    }

    /** 장중 휴식(또는 결측 봉)은 소수 쪽 간격이라 최빈값이 그대로 이깁니다. */
    @Test
    void toleratesGapsWithinTheDay() {
        List<Bar> bars = new ArrayList<>();
        LocalDateTime open = LocalDateTime.of(2024, 1, 1, 9, 0);
        for (int i = 0; i < 10; i++) {
            bars.add(bar(open.plusMinutes(i)));
        }
        bars.add(bar(open.plusMinutes(70))); // 장중에 한 시간이 비어 있음
        bars.add(bar(open.plusMinutes(71)));
        assertEquals(1, new BarSeries(bars).inferIntervalMinutes());
    }

    /** 잴 수 없는 경우(봉이 하나, 또는 하루에 한 봉)는 문서에 적힌 기본값으로 폴백합니다. */
    @Test
    void fallsBackWhenUnmeasurable() {
        assertEquals(BarSeries.DEFAULT_INTERVAL_MINUTES,
                new BarSeries(List.of(bar(LocalDateTime.of(2024, 1, 1, 9, 0)))).inferIntervalMinutes());
        assertEquals(BarSeries.DEFAULT_INTERVAL_MINUTES, series(3, 1, 5).inferIntervalMinutes());
    }
}
