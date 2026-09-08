package com.stockanalysis.live;

import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.Indicator;
import com.stockanalysis.live.market.LiveBarBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live bars have to come out identical to the ones the backtest reads from the excel, or a verified
 * strategy quietly means something different on a real account. These pin the two properties that
 * were measured from the seed files rather than assumed.
 */
class LiveBarBuilderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 24);

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DAY, LocalTime.of(hour, minute));
    }

    private static Bar minuteBar(int hour, int minute, double close, double volume) {
        double nan = Double.NaN;
        return new Bar(at(hour, minute), close, close, close, close,
                nan, nan, nan, nan, volume, nan, nan, nan, nan);
    }

    /**
     * The 3-minute grid in the source files runs 08:45 / 08:48 / … / 15:45 — a bar is labelled by
     * its start, and buckets are floored on minute-of-day.
     */
    @Test
    void bucketsAreLabelledByTheirStartOnTheSameGridAsTheExcel() {
        assertEquals(at(8, 45), LiveBarBuilder.bucketStartOf(at(8, 45), 3));
        assertEquals(at(8, 45), LiveBarBuilder.bucketStartOf(at(8, 47), 3));
        assertEquals(at(8, 48), LiveBarBuilder.bucketStartOf(at(8, 48), 3));
        assertEquals(at(9, 0), LiveBarBuilder.bucketStartOf(at(9, 2), 3));
        assertEquals(at(15, 45), LiveBarBuilder.bucketStartOf(at(15, 47), 3));
        // 1-minute data is the degenerate case: every minute is its own bucket.
        assertEquals(at(9, 7), LiveBarBuilder.bucketStartOf(at(9, 7), 1));
    }

    @Test
    void aggregatesOneMinuteBarsIntoThreeMinuteBucketsWithSummedVolume() {
        List<Bar> minutes = List.of(
                minuteBar(9, 0, 100, 10),
                minuteBar(9, 1, 104, 20),
                minuteBar(9, 2, 102, 30),
                minuteBar(9, 3, 105, 40));

        List<Bar> bars = LiveBarBuilder.aggregate(minutes, 3);

        assertEquals(2, bars.size());
        Bar first = bars.get(0);
        assertEquals(at(9, 0), first.ts());
        assertEquals(100, first.open(), 1e-9);
        assertEquals(104, first.high(), 1e-9);
        assertEquals(100, first.low(), 1e-9);
        assertEquals(102, first.close(), 1e-9);
        assertEquals(60, first.volume(), 1e-9);
        assertEquals(at(9, 3), bars.get(1).ts());
    }

    /**
     * MA5 in the excel is the mean of the labelled bar and the four before it — bars, not minutes.
     * Verified against KOSPI200 3-minute data, where the 15:45 bar's ma5 matches exactly.
     */
    @Test
    void movingAverageIsOverNBarsIncludingTheCurrentOne() {
        LiveBarBuilder builder = new LiveBarBuilder(1);
        List<Bar> warm = new ArrayList<>();
        double[] closes = {10, 20, 30, 40};
        for (int i = 0; i < closes.length; i++) {
            warm.add(minuteBar(9, i, closes[i], 100));
        }
        builder.warmUp(warm);

        // Fifth bar closes at 50: MA5 = (10+20+30+40+50)/5 = 30.
        builder.accept(at(9, 4), 50, 500.0);
        Bar forming = builder.formingBar().orElseThrow();
        assertEquals(30.0, forming.ma5(), 1e-9);

        // MA10 has only 5 bars to work with, so it stays NaN rather than averaging what exists.
        assertTrue(Double.isNaN(forming.ma10()));
    }

    @Test
    void unwarmedIndicatorsStayNaNSoTheirRulesNeverFire() {
        LiveBarBuilder builder = new LiveBarBuilder(3);
        builder.accept(at(9, 0), 100, null);
        Bar bar = builder.formingBar().orElseThrow();

        assertTrue(Double.isNaN(bar.ma5()));
        assertTrue(Double.isNaN(bar.ma60()));
        // A NaN comparison is false in the engine, so an unwarmed rule is simply inert.
        assertFalse(bar.ma5() > 0);
        assertFalse(bar.ma5() < 0);

        assertFalse(builder.readiness().get(Indicator.MA5));
        assertTrue(builder.readiness().get(Indicator.CLOSE));
    }

    @Test
    void aBarIsReturnedOnlyWhenTheNextBucketStarts() {
        LiveBarBuilder builder = new LiveBarBuilder(3);

        assertTrue(builder.accept(at(9, 0), 100, 0.0).isEmpty());
        assertTrue(builder.accept(at(9, 1), 110, 50.0).isEmpty());
        assertTrue(builder.accept(at(9, 2), 105, 80.0).isEmpty());

        Optional<Bar> sealed = builder.accept(at(9, 3), 106, 90.0);
        assertTrue(sealed.isPresent());
        Bar bar = sealed.get();
        assertEquals(at(9, 0), bar.ts());
        assertEquals(100, bar.open(), 1e-9);
        assertEquals(110, bar.high(), 1e-9);
        assertEquals(100, bar.low(), 1e-9);
        assertEquals(105, bar.close(), 1e-9);
        // Bar volume is the difference of session-cumulative volume across the bucket.
        assertEquals(80, bar.volume(), 1e-9);
        assertEquals(1, builder.completedBarCount());
    }

    @Test
    void volumeStaysNaNWhenTheFeedDoesNotReportIt() {
        LiveBarBuilder builder = new LiveBarBuilder(3);
        builder.accept(at(9, 0), 100, null);
        builder.accept(at(9, 3), 101, null);

        Bar bar = builder.completedBars().get(0);
        assertTrue(Double.isNaN(bar.volume()));
        assertTrue(Double.isNaN(bar.volMa5()));
    }
}
