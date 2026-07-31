package com.stockanalysis.backtest;

import com.stockanalysis.backtest.spec.Condition;
import com.stockanalysis.backtest.spec.ConditionGroup;
import com.stockanalysis.backtest.spec.ExitSpec;
import com.stockanalysis.backtest.spec.Logic;
import com.stockanalysis.backtest.spec.Operand;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.backtest.spec.TimeBand;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic unit tests for entry cross-detection and each exit reason. */
class BacktestEngineTest {

    private final BacktestEngine engine = new BacktestEngine();

    private static Bar bar(LocalDateTime ts, double o, double h, double l, double c, double ma5, double ma20) {
        return new Bar(ts, o, h, l, c, ma5, Double.NaN, ma20, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    /** Entry only fires on a genuine cross (prev below, current above), not when already above. */
    private static StrategySpec crossEntryOnly() {
        StrategySpec s = new StrategySpec();
        s.setName("cross");
        s.setEntry(new ConditionGroup(Logic.AND, List.of(
                new Condition(Operand.of(Indicator.MA5), Operator.CROSS_ABOVE, Operand.of(Indicator.MA20)))));
        return s;
    }

    @Test
    void takeProfitExit() {
        LocalDateTime d = LocalDateTime.of(2026, 1, 5, 9, 0);
        List<Bar> bars = new ArrayList<>();
        bars.add(bar(d, 100, 100, 100, 100, 1, 2));                 // below
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2));  // cross above -> entry @100
        bars.add(bar(d.plusMinutes(6), 100, 102, 99.5, 101, 3, 2)); // high 102 hits TP 101.5

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setTakeProfitPct(1.5);
        exit.setStopLossPct(1.0);
        exit.setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, new BarSeries(bars));
        assertEquals(1, r.summary().totalTrades());
        TradeRecord t = r.trades().get(0);
        assertEquals(ExitReason.TAKE_PROFIT, t.exitReason());
        assertEquals(101.5, t.exitPrice(), 1e-9);
        assertEquals(1.5, t.returnPct(), 1e-9);
        assertTrue(t.success());
    }

    @Test
    void stopLossTakesPriorityOverTakeProfit() {
        LocalDateTime d = LocalDateTime.of(2026, 1, 5, 9, 0);
        List<Bar> bars = new ArrayList<>();
        bars.add(bar(d, 100, 100, 100, 100, 1, 2));
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2));  // entry @100
        bars.add(bar(d.plusMinutes(6), 100, 102, 98, 100, 3, 2));   // low 98 hits SL 99, high 102 hits TP -> SL wins

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setTakeProfitPct(1.5);
        exit.setStopLossPct(1.0);
        exit.setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, new BarSeries(bars));
        TradeRecord t = r.trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.exitReason());
        assertEquals(99.0, t.exitPrice(), 1e-9);
        assertEquals(-1.0, t.returnPct(), 1e-9);
        assertTrue(!t.success());
    }

    @Test
    void dayEndCloseWhenNextBarIsNewDay() {
        LocalDateTime d1 = LocalDateTime.of(2026, 1, 5, 9, 0);
        LocalDateTime d2 = LocalDateTime.of(2026, 1, 6, 9, 0);
        List<Bar> bars = new ArrayList<>();
        bars.add(bar(d1, 100, 100, 100, 100, 1, 2));
        bars.add(bar(d1.plusMinutes(3), 100, 100, 100, 100, 3, 2)); // entry @100
        bars.add(bar(d1.plusMinutes(6), 100, 100.5, 99.8, 101, 3, 2)); // last bar of day1 -> DAY_END @101
        bars.add(bar(d2, 100, 100, 100, 100, 3, 2));                // new day

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setCloseAtDayEnd(true); // no TP/SL/signal

        BacktestResult r = engine.run(s, new BarSeries(bars));
        TradeRecord t = r.trades().get(0);
        assertEquals(ExitReason.DAY_END, t.exitReason());
        assertEquals(101.0, t.exitPrice(), 1e-9);
    }

    @Test
    void endOfDataClosesOpenPosition() {
        LocalDateTime d = LocalDateTime.of(2026, 1, 5, 9, 0);
        List<Bar> bars = new ArrayList<>();
        bars.add(bar(d, 100, 100, 100, 100, 1, 2));
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2)); // entry @100
        bars.add(bar(d.plusMinutes(6), 100, 100.2, 99.9, 100.2, 3, 2)); // last overall -> END_OF_DATA

        StrategySpec s = crossEntryOnly();
        s.getExit().setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, new BarSeries(bars));
        TradeRecord t = r.trades().get(0);
        assertEquals(ExitReason.END_OF_DATA, t.exitReason());
    }

    // ------------------------------------------------------- time-of-day take-profit / stop-loss

    /** Bars every 3 minutes from 09:00, flat at 100 unless overridden. */
    private static List<Bar> flatDay(int count) {
        LocalDateTime d = LocalDateTime.of(2026, 1, 5, 9, 0);
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bars.add(bar(d.plusMinutes(3L * i), 100, 100, 100, 100, i == 0 ? 1 : 3, 2));
        }
        return bars;
    }

    /**
     * The band is matched against the bar being checked, not the entry bar: a position opened
     * under the wide 09:00 band is stopped out by the tighter 10:00 band once the clock passes it.
     */
    @Test
    void stopLossFollowsTheCurrentBarsTimeBand() {
        List<Bar> bars = flatDay(24);                          // 09:00 .. 10:09, entry @100 on 09:03
        // 09:57 dips to 99.0 — inside the 09:00 band (stop 98.5), so it survives.
        bars.set(19, bar(LocalDateTime.of(2026, 1, 5, 9, 57), 100, 100, 99.0, 100, 3, 2));
        // 10:03 dips to the same 99.0 — the 10:00 band's stop is 99.5, so this one exits.
        bars.set(21, bar(LocalDateTime.of(2026, 1, 5, 10, 3), 100, 100, 99.0, 100, 3, 2));

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setStopLossPct(5.0);                              // base, deliberately far away
        exit.setCloseAtDayEnd(false);
        exit.setBands(List.of(
                new TimeBand("09:00", "10:00", null, 1.5),     // stop 98.5
                new TimeBand("10:00", "15:20", null, 0.5)));   // stop 99.5

        BacktestResult r = engine.run(s, new BarSeries(bars));
        assertEquals(1, r.summary().totalTrades());
        TradeRecord t = r.trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.exitReason());
        assertEquals(LocalDateTime.of(2026, 1, 5, 10, 3), t.exitTs());
        assertEquals(99.5, t.exitPrice(), 1e-9);
    }

    /** A band that only sets one side leaves the other on the ExitSpec base value. */
    @Test
    void bandFallsBackToBaseForTheSideItDoesNotSet() {
        List<Bar> bars = flatDay(6);
        bars.set(4, bar(LocalDateTime.of(2026, 1, 5, 9, 12), 100, 101.2, 100, 100, 3, 2)); // high hits TP 101

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setTakeProfitPct(1.0);                            // base TP survives
        exit.setStopLossPct(1.0);
        exit.setCloseAtDayEnd(false);
        exit.setBands(List.of(new TimeBand("09:00", "10:00", null, 3.0)));  // overrides SL only

        TradeRecord t = engine.run(s, new BarSeries(bars)).trades().get(0);
        assertEquals(ExitReason.TAKE_PROFIT, t.exitReason());
        assertEquals(101.0, t.exitPrice(), 1e-9);
    }

    /** Outside every band the base values apply, so uncovered hours behave as before. */
    @Test
    void uncoveredTimeUsesBaseValues() {
        List<Bar> bars = flatDay(6);
        bars.set(4, bar(LocalDateTime.of(2026, 1, 5, 9, 12), 100, 100, 98.9, 100, 3, 2)); // low hits base stop 99

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setStopLossPct(1.0);
        exit.setCloseAtDayEnd(false);
        exit.setBands(List.of(new TimeBand("13:00", "15:20", null, 0.1)));  // never covers these bars

        TradeRecord t = engine.run(s, new BarSeries(bars)).trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.exitReason());
        assertEquals(99.0, t.exitPrice(), 1e-9);
    }

    /** [start, end): the end minute belongs to the next band, not this one. */
    @Test
    void bandEndIsExclusive() {
        ExitSpec exit = new ExitSpec();
        exit.setStopLossPct(9.0);
        exit.setBands(List.of(
                new TimeBand("09:00", "10:00", null, 1.0),
                new TimeBand("10:00", "15:20", null, 2.0)));
        assertEquals(1.0, exit.stopLossPctAt(LocalTime.of(9, 59)), 1e-9);
        assertEquals(2.0, exit.stopLossPctAt(LocalTime.of(10, 0)), 1e-9);
        assertEquals(9.0, exit.stopLossPctAt(LocalTime.of(15, 20)), 1e-9);  // past every band -> base
    }

    /** Overlaps are legal and resolved by list order, so a specific window can sit on top. */
    @Test
    void firstMatchingBandWinsAnOverlap() {
        ExitSpec exit = new ExitSpec();
        exit.setBands(List.of(
                new TimeBand("09:00", "09:30", 0.5, 0.3),      // narrow, listed first
                new TimeBand("09:00", "15:20", 2.0, 1.5)));
        assertEquals(0.5, exit.takeProfitPctAt(LocalTime.of(9, 15)), 1e-9);
        assertEquals(2.0, exit.takeProfitPctAt(LocalTime.of(9, 45)), 1e-9);
    }

    @Test
    void seriesIsSortedAscendingRegardlessOfInputOrder() {
        LocalDateTime d = LocalDateTime.of(2026, 1, 5, 9, 0);
        List<Bar> bars = new ArrayList<>();
        // insert out of order (newest first, like the source excel)
        bars.add(bar(d.plusMinutes(6), 100, 100, 100, 100, 3, 2));
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2));
        bars.add(bar(d, 100, 100, 100, 100, 1, 2));
        BarSeries series = new BarSeries(bars);
        assertEquals(d, series.bars().get(0).ts());
        assertEquals(d.plusMinutes(6), series.bars().get(2).ts());
    }
}
