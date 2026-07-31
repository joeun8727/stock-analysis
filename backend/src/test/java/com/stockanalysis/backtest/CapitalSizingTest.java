package com.stockanalysis.backtest;

import com.stockanalysis.backtest.spec.CapitalMode;
import com.stockanalysis.backtest.spec.Condition;
import com.stockanalysis.backtest.spec.ConditionGroup;
import com.stockanalysis.backtest.spec.ExitSpec;
import com.stockanalysis.backtest.spec.Logic;
import com.stockanalysis.backtest.spec.Operand;
import com.stockanalysis.backtest.spec.StrategySpec;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Money sizing: whole shares, commission on both legs, fixed vs compound deployment. */
class CapitalSizingTest {

    private final BacktestEngine engine = new BacktestEngine();

    private static Bar bar(LocalDateTime ts, double o, double h, double l, double c, double ma5, double ma20) {
        return new Bar(ts, o, h, l, c, ma5, Double.NaN, ma20, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    /** Two identical +1% take-profit trades on separate days, entry at 1,000. */
    private static BarSeries twoWinningTrades() {
        List<Bar> bars = new ArrayList<>();
        for (int day = 5; day <= 6; day++) {
            LocalDateTime d = LocalDateTime.of(2026, 1, day, 9, 0);
            bars.add(bar(d, 1000, 1000, 1000, 1000, 1, 2));                    // below
            bars.add(bar(d.plusMinutes(3), 1000, 1000, 1000, 1000, 3, 2));     // cross -> entry @1000
            bars.add(bar(d.plusMinutes(6), 1000, 1020, 999, 1010, 3, 2));      // high 1020 hits TP 1010
        }
        return new BarSeries(bars);
    }

    private static StrategySpec tpStrategy() {
        StrategySpec s = new StrategySpec();
        s.setName("capital");
        s.setEntry(new ConditionGroup(Logic.AND, List.of(
                new Condition(Operand.of(Indicator.MA5), Operator.CROSS_ABOVE, Operand.of(Indicator.MA20)))));
        ExitSpec exit = s.getExit();
        exit.setTakeProfitPct(1.0);
        exit.setCloseAtDayEnd(false);
        return s;
    }

    @Test
    void fixedAmountBuysWholeSharesAndNetsFeesOnBothLegs() {
        StrategySpec s = tpStrategy();
        s.getCapital().setAmount(1_000_000);
        s.getCapital().setMode(CapitalMode.FIXED);

        BacktestResult r = engine.run(s, twoWinningTrades(), FeeSchedule.flat(0.015));

        assertEquals(2, r.summary().totalTrades());
        TradeRecord t = r.trades().get(0);
        // 1,000,000 / 1,000 = 1,000 shares exactly.
        assertEquals(1000L, t.quantity());
        // buy 1,000,000 + sell 1,010,000 -> fee 0.015% of each leg = 150 + 151.5
        assertEquals(301.5, t.feeAmount(), 1e-6);
        assertEquals(10_000 - 301.5, t.profitAmount(), 1e-6);

        // Fixed mode: the second trade is sized off the same amount, so both are identical.
        assertEquals(t.quantity(), r.trades().get(1).quantity());
        assertEquals(t.profitAmount(), r.trades().get(1).profitAmount(), 1e-6);

        BacktestResult.MoneySummary m = r.money();
        assertEquals("FIXED", m.mode());
        assertEquals(1_000_000, m.investAmount(), 1e-9);
        assertEquals(Map.of(FeeSchedule.SINGLE, 0.015), m.feeRatesPct());
        assertEquals(2 * (10_000 - 301.5), m.totalProfitAmount(), 1e-6);
        assertEquals(2 * 301.5, m.totalFeeAmount(), 1e-6);
        assertEquals(10_000 - 301.5, m.avgProfitPerTrade(), 1e-6);
        assertEquals(1_000_000 + 2 * (10_000 - 301.5), m.finalBalance(), 1e-6);
        assertEquals(0, m.unaffordableTrades());
    }

    @Test
    void compoundModeReinvestsSoTheSecondTradeIsLarger() {
        StrategySpec s = tpStrategy();
        s.getCapital().setAmount(1_000_000);
        s.getCapital().setMode(CapitalMode.COMPOUND);

        // Commission-free so the reinvestment effect is isolated.
        BacktestResult r = engine.run(s, twoWinningTrades(), FeeSchedule.free());

        TradeRecord first = r.trades().get(0);
        TradeRecord second = r.trades().get(1);
        assertEquals(1000L, first.quantity());
        assertEquals(10_000, first.profitAmount(), 1e-6);
        // Balance is now 1,010,000 -> floor(1,010,000 / 1,000) = 1,010 shares.
        assertEquals(1010L, second.quantity());
        assertEquals(10_100, second.profitAmount(), 1e-6);

        BacktestResult.MoneySummary m = r.money();
        assertEquals("COMPOUND", m.mode());
        assertEquals(1_020_100, m.finalBalance(), 1e-6);
        assertEquals(20_100, m.totalProfitAmount(), 1e-6);
        assertEquals(2.01, m.returnOnCapitalPct(), 1e-9);
    }

    @Test
    void amountBelowOneSharePriceBuysNothingAndIsCounted() {
        StrategySpec s = tpStrategy();
        s.getCapital().setAmount(500); // one share costs 1,000
        s.getCapital().setMode(CapitalMode.FIXED);

        BacktestResult r = engine.run(s, twoWinningTrades());

        assertEquals(2, r.summary().totalTrades());
        assertTrue(r.trades().stream().allMatch(t -> t.quantity() == 0L));
        assertEquals(0.0, r.money().totalProfitAmount(), 1e-9);
        assertEquals(2, r.money().unaffordableTrades());
        // Price-based metrics are unaffected by sizing.
        assertEquals(1.0, r.trades().get(0).returnPct(), 1e-9);
    }

    @Test
    void defaultsApplyWhenTheSpecCarriesNoCapitalBlock() {
        StrategySpec s = tpStrategy();
        s.setCapital(null); // e.g. an older stored spec_json

        BacktestResult r = engine.run(s, twoWinningTrades());

        assertEquals("FIXED", r.money().mode());
        assertEquals(10_000_000, r.money().investAmount(), 1e-9);
        assertTrue(r.trades().get(0).quantity() > 0);
    }
}
