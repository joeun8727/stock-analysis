package com.stockanalysis.backtest;

import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.backtest.spec.TargetType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pre-market futures trend must pick leverage on up-days and inverse on down-days. */
class EtfPremarketTest {

    private final BacktestEngine engine = new BacktestEngine();

    private static Bar bar(LocalDate d, LocalTime t, double close) {
        return new Bar(LocalDateTime.of(d, t), close, close, close, close,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    @Test
    void picksLeverageOnUpDayAndInverseOnDownDay() {
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 1, 6);

        // Futures: d1 pre-market rises (100 -> 101 = +1%), d2 falls (100 -> 99 = -1%).
        BarSeries futures = new BarSeries(List.of(
                bar(d1, LocalTime.of(8, 45), 100), bar(d1, LocalTime.of(8, 57), 101),
                bar(d2, LocalTime.of(8, 45), 100), bar(d2, LocalTime.of(8, 57), 99)
        ));
        // Leverage has a d1 session; inverse has a d2 session.
        BarSeries leverage = new BarSeries(List.of(
                bar(d1, LocalTime.of(9, 0), 200), bar(d1, LocalTime.of(9, 3), 202)
        ));
        BarSeries inverse = new BarSeries(List.of(
                bar(d2, LocalTime.of(9, 0), 50), bar(d2, LocalTime.of(9, 3), 49)
        ));

        StrategySpec spec = new StrategySpec();
        spec.setName("ETF 장전추세");
        spec.setTargetType(TargetType.ETF);
        spec.getPremarket().setEnabled(true);
        spec.getExit().setCloseAtDayEnd(true); // no TP/SL -> day-end close

        BacktestResult r = engine.runEtfPremarket(spec, leverage, inverse, futures);

        assertEquals(2, r.summary().totalTrades());
        TradeRecord up = r.trades().get(0);
        assertEquals("LEVERAGE", up.instrument());
        assertEquals(202.0, up.exitPrice(), 1e-9);
        assertEquals(1.0, up.returnPct(), 1e-9);

        TradeRecord down = r.trades().get(1);
        assertEquals("INVERSE", down.instrument());
        assertEquals(49.0, down.exitPrice(), 1e-9);
        assertEquals(-2.0, down.returnPct(), 1e-9);
    }

    /** Leverage and inverse can be on different commission rates; each trade must use its own. */
    @Test
    void eachSideIsChargedItsOwnCommissionRate() {
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 1, 6);

        BarSeries futures = new BarSeries(List.of(
                bar(d1, LocalTime.of(8, 45), 100), bar(d1, LocalTime.of(8, 57), 101),
                bar(d2, LocalTime.of(8, 45), 100), bar(d2, LocalTime.of(8, 57), 99)
        ));
        BarSeries leverage = new BarSeries(List.of(
                bar(d1, LocalTime.of(9, 0), 200), bar(d1, LocalTime.of(9, 3), 202)
        ));
        BarSeries inverse = new BarSeries(List.of(
                bar(d2, LocalTime.of(9, 0), 50), bar(d2, LocalTime.of(9, 3), 49)
        ));

        StrategySpec spec = new StrategySpec();
        spec.setTargetType(TargetType.ETF);
        spec.getPremarket().setEnabled(true);
        spec.getExit().setCloseAtDayEnd(true);
        spec.getCapital().setAmount(1_000_000);

        // Leverage 0.01% per side, inverse 0.1% per side — a 10x difference.
        BacktestResult r = engine.runEtfPremarket(spec, leverage, inverse, futures,
                FeeSchedule.ofEtf(0.01, 0.1));

        TradeRecord up = r.trades().get(0);
        assertEquals("LEVERAGE", up.instrument());
        // 1,000,000 / 200 = 5,000 shares; buy 1,000,000 + sell 1,010,000 at 0.01% = 100 + 101
        assertEquals(5000L, up.quantity());
        assertEquals(201.0, up.feeAmount(), 1e-6);

        TradeRecord down = r.trades().get(1);
        assertEquals("INVERSE", down.instrument());
        // 1,000,000 / 50 = 20,000 shares; buy 1,000,000 + sell 980,000 at 0.1% = 1,000 + 980
        assertEquals(20000L, down.quantity());
        assertEquals(1980.0, down.feeAmount(), 1e-6);

        assertEquals(Map.of("LEVERAGE", 0.01, "INVERSE", 0.1), r.money().feeRatesPct());
        assertEquals(201.0 + 1980.0, r.money().totalFeeAmount(), 1e-6);
    }

    @Test
    void skipsDayWhenTrendBelowThreshold() {
        LocalDate d = LocalDate.of(2026, 1, 5);
        // Flat pre-market (100 -> 100.02 = +0.02%) is below the default 0.1% threshold.
        BarSeries futures = new BarSeries(List.of(
                bar(d, LocalTime.of(8, 45), 100), bar(d, LocalTime.of(8, 57), 100.02)));
        BarSeries leverage = new BarSeries(List.of(
                bar(d, LocalTime.of(9, 0), 200), bar(d, LocalTime.of(9, 3), 202)));
        BarSeries inverse = new BarSeries(List.of(
                bar(d, LocalTime.of(9, 0), 50), bar(d, LocalTime.of(9, 3), 49)));

        StrategySpec spec = new StrategySpec();
        spec.setTargetType(TargetType.ETF);
        spec.getPremarket().setEnabled(true);

        BacktestResult r = engine.runEtfPremarket(spec, leverage, inverse, futures);
        assertEquals(0, r.summary().totalTrades());
    }
}
