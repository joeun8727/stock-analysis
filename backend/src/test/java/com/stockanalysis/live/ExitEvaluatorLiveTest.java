package com.stockanalysis.live;

import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.ExitEvaluator;
import com.stockanalysis.backtest.ExitReason;
import com.stockanalysis.backtest.spec.ExitSpec;
import com.stockanalysis.backtest.spec.TimeBand;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The live tick check and the backtest's intrabar check are two views of one rule: the backtest
 * sees a bar's high and low at once, live sees the same prices one at a time. These assert they
 * agree, including the conservative tie-break and the time bands.
 */
class ExitEvaluatorLiveTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 24);

    private static ExitSpec exitSpec(Double takeProfit, Double stopLoss) {
        ExitSpec spec = new ExitSpec();
        spec.setTakeProfitPct(takeProfit);
        spec.setStopLossPct(stopLoss);
        spec.setCloseAtDayEnd(false);
        return spec;
    }

    private static Bar bar(LocalTime t, double open, double high, double low, double close) {
        double nan = Double.NaN;
        return new Bar(LocalDateTime.of(DAY, t), open, high, low, close,
                nan, nan, nan, nan, nan, nan, nan, nan, nan);
    }

    @Test
    void aTickAtTheStopLevelExitsAtTheSamePriceTheBacktestWouldUse() {
        ExitSpec spec = exitSpec(2.0, 1.0);

        ExitEvaluator.Decision live =
                ExitEvaluator.checkLivePrice(spec, 1000, 990, LocalTime.of(10, 0));
        assertNotNull(live);
        assertEquals(ExitReason.STOP_LOSS, live.reason());
        assertEquals(990.0, live.price(), 1e-9); // 1000 × (1 − 1%)

        // Same entry, a bar whose low reaches the stop: the backtest exits at the same level.
        ExitEvaluator.Decision backtest = ExitEvaluator.decide(
                bar(LocalTime.of(10, 0), 1000, 1005, 985, 1000), null, false, false, 1,
                spec, spec.asGroup(), 1000, null, null);
        assertNotNull(backtest);
        assertEquals(ExitReason.STOP_LOSS, backtest.reason());
        assertEquals(live.price(), backtest.price(), 1e-9);
    }

    @Test
    void aPriceBetweenTheLevelsDoesNotExit() {
        ExitSpec spec = exitSpec(2.0, 1.0);
        assertNull(ExitEvaluator.checkLivePrice(spec, 1000, 1005, LocalTime.of(10, 0)));
    }

    /** Stop-loss wins a tie in the backtest; the live check must be just as conservative. */
    @Test
    void stopLossWinsWhenBothLevelsAreReachable() {
        ExitSpec spec = exitSpec(1.0, 1.0);
        // A price that satisfies neither on its own can't test the tie; use the level itself.
        ExitEvaluator.Decision live =
                ExitEvaluator.checkLivePrice(spec, 1000, 990, LocalTime.of(10, 0));
        assertEquals(ExitReason.STOP_LOSS, live.reason());

        ExitEvaluator.Decision backtest = ExitEvaluator.decide(
                bar(LocalTime.of(10, 0), 1000, 1010, 990, 1000), null, false, false, 1,
                spec, spec.asGroup(), 1000, null, null);
        assertEquals(ExitReason.STOP_LOSS, backtest.reason());
    }

    /** A band that starts mid-position moves the levels — live resolves it per tick, as the engine does per bar. */
    @Test
    void timeBandsMoveTheLiveLevelsAsTheClockPasses() {
        ExitSpec spec = exitSpec(3.0, 3.0);
        spec.setBands(List.of(new TimeBand("10:00", "11:00", 1.0, 1.0)));

        // 09:30 falls outside the band: the base 3% stop is 970, so 985 is still safe.
        assertNull(ExitEvaluator.checkLivePrice(spec, 1000, 985, LocalTime.of(9, 30)));

        // The same price at 10:30 is past the band's 1% stop at 990.
        ExitEvaluator.Decision inBand =
                ExitEvaluator.checkLivePrice(spec, 1000, 985, LocalTime.of(10, 30));
        assertNotNull(inBand);
        assertEquals(ExitReason.STOP_LOSS, inBand.reason());
        assertEquals(990.0, inBand.price(), 1e-9);
    }

    @Test
    void noLevelsConfiguredMeansNoPriceExit() {
        ExitSpec spec = exitSpec(null, null);
        assertNull(ExitEvaluator.checkLivePrice(spec, 1000, 1, LocalTime.of(10, 0)));
        assertNull(ExitEvaluator.checkLivePrice(spec, 1000, 100000, LocalTime.of(10, 0)));
    }

    @Test
    void maxHoldBarsStillFiresOnCompletedBars() {
        ExitSpec spec = exitSpec(null, null);
        spec.setMaxHoldBars(3);

        assertNull(ExitEvaluator.decide(bar(LocalTime.of(10, 0), 100, 100, 100, 100), null,
                false, false, 2, spec, spec.asGroup(), 100, null, null));

        ExitEvaluator.Decision timed = ExitEvaluator.decide(bar(LocalTime.of(10, 3), 100, 100, 100, 100),
                null, false, false, 3, spec, spec.asGroup(), 100, null, null);
        assertNotNull(timed);
        assertEquals(ExitReason.TIME, timed.reason());
    }
}
