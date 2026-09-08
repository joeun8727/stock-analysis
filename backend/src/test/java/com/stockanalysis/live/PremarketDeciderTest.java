package com.stockanalysis.live;

import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.BacktestEngine;
import com.stockanalysis.backtest.BacktestResult;
import com.stockanalysis.backtest.BarSeries;
import com.stockanalysis.backtest.PremarketDecider;
import com.stockanalysis.backtest.PremarketDecider.PricePoint;
import com.stockanalysis.backtest.PremarketDecider.Side;
import com.stockanalysis.backtest.spec.PremarketSpec;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.backtest.spec.TargetType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-market direction call is the one decision that starts a real trade, and the backtest and
 * the live session must reach it the same way. They share {@link PremarketDecider}; these tests pin
 * the rule itself and then check that the engine's answer matches the decider's on the same data —
 * the equivalence that makes a backtest worth anything.
 */
class PremarketDeciderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 5);

    private static PricePoint at(int hour, int minute, double price) {
        return new PricePoint(LocalDateTime.of(DAY, LocalTime.of(hour, minute)), price);
    }

    private static PremarketSpec spec(double thresholdPct) {
        PremarketSpec pm = new PremarketSpec();
        pm.setEnabled(true);
        pm.setThresholdPct(thresholdPct);
        return pm;
    }

    @Test
    void aRiseBeyondTheThresholdBuysLeverage() {
        PremarketDecider.Decision d = PremarketDecider.decide(
                List.of(at(8, 45, 100), at(8, 50, 100.5), at(8, 57, 101)), spec(0.1));

        assertEquals(Side.LEVERAGE, d.side());
        assertEquals(1.0, d.trendPct(), 1e-9);
        assertEquals("LEVERAGE", d.instrument());
        assertTrue(d.shouldTrade());
    }

    @Test
    void aFallBeyondTheThresholdBuysInverse() {
        PremarketDecider.Decision d = PremarketDecider.decide(
                List.of(at(8, 45, 100), at(8, 57, 99)), spec(0.1));

        assertEquals(Side.INVERSE, d.side());
        assertEquals(-1.0, d.trendPct(), 1e-9);
    }

    @Test
    void aMoveInsideTheThresholdSkipsTheDayButStillReportsWhatItSaw() {
        PremarketDecider.Decision d = PremarketDecider.decide(
                List.of(at(8, 45, 100), at(8, 57, 100.02)), spec(0.1));

        assertEquals(Side.SKIP, d.side());
        assertEquals(0.02, d.trendPct(), 1e-9); // the number is kept so the screen can explain the skip
        assertNull(d.instrument());
    }

    /** Only samples inside [startTime, endTime) count — a 09:05 quote must not move the decision. */
    @Test
    void samplesOutsideTheWindowAreIgnored() {
        PremarketDecider.Decision d = PremarketDecider.decide(
                List.of(at(8, 30, 90), at(8, 45, 100), at(8, 57, 100.05), at(9, 5, 130)), spec(0.1));

        assertEquals(Side.SKIP, d.side());
        assertEquals(0.05, d.trendPct(), 1e-9);
    }

    @Test
    void tooFewSamplesMeansNoMeasurementAtAll() {
        PremarketDecider.Decision only = PremarketDecider.decide(List.of(at(8, 50, 100)), spec(0.1));
        assertEquals(Side.SKIP, only.side());
        assertNull(only.trendPct());

        PremarketDecider.Decision none = PremarketDecider.decide(List.of(), spec(0.1));
        assertEquals(Side.SKIP, none.side());
        assertNull(none.trendPct());
    }

    /**
     * Equivalence: feed the backtest engine a day of futures bars and feed the decider the same
     * closes as if they had been polled live. Both must pick the same side.
     */
    @Test
    void theEngineAndTheLiveDeciderAgreeOnTheSameData() {
        LocalDate up = LocalDate.of(2026, 1, 5);
        LocalDate down = LocalDate.of(2026, 1, 6);

        List<Bar> futuresBars = new ArrayList<>();
        futuresBars.add(bar(up, LocalTime.of(8, 45), 100));
        futuresBars.add(bar(up, LocalTime.of(8, 57), 101));
        futuresBars.add(bar(down, LocalTime.of(8, 45), 100));
        futuresBars.add(bar(down, LocalTime.of(8, 57), 99));

        StrategySpec strategy = new StrategySpec();
        strategy.setTargetType(TargetType.ETF);
        strategy.getPremarket().setEnabled(true);
        strategy.getExit().setCloseAtDayEnd(true);

        BacktestResult result = new BacktestEngine().runEtfPremarket(
                strategy,
                new BarSeries(List.of(bar(up, LocalTime.of(9, 0), 200), bar(up, LocalTime.of(9, 3), 202))),
                new BarSeries(List.of(bar(down, LocalTime.of(9, 0), 50), bar(down, LocalTime.of(9, 3), 49))),
                new BarSeries(futuresBars));

        // What the engine did, day by day.
        assertEquals("LEVERAGE", result.trades().get(0).instrument());
        assertEquals("INVERSE", result.trades().get(1).instrument());

        // What a live session would do from the same prices, arriving as polled quotes.
        List<PricePoint> upSamples = List.of(
                new PricePoint(LocalDateTime.of(up, LocalTime.of(8, 45)), 100),
                new PricePoint(LocalDateTime.of(up, LocalTime.of(8, 57)), 101));
        List<PricePoint> downSamples = List.of(
                new PricePoint(LocalDateTime.of(down, LocalTime.of(8, 45)), 100),
                new PricePoint(LocalDateTime.of(down, LocalTime.of(8, 57)), 99));

        assertEquals(result.trades().get(0).instrument(),
                PremarketDecider.decide(upSamples, strategy.getPremarket()).instrument());
        assertEquals(result.trades().get(1).instrument(),
                PremarketDecider.decide(downSamples, strategy.getPremarket()).instrument());
    }

    private static Bar bar(LocalDate d, LocalTime t, double close) {
        double nan = Double.NaN;
        return new Bar(LocalDateTime.of(d, t), close, close, close, close,
                nan, nan, nan, nan, nan, nan, nan, nan, nan);
    }
}
