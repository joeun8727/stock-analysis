package com.stockanalysis.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** 금액 환산: 정수 주식 수, 매수·매도 양쪽 수수료, 고정 vs 복리 투입. */
class CapitalSizingTest {

    private final BacktestEngine engine = new BacktestEngine();

    private static Bar bar(LocalDateTime ts, double o, double h, double l, double c, double ma5, double ma20) {
        return new Bar(ts, o, h, l, c, ma5, Double.NaN, ma20, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    /** 서로 다른 날에 일어나는, 1,000에 진입하는 +1% 익절 거래 두 개. */
    private static BarSeries twoWinningTrades() {
        List<Bar> bars = new ArrayList<>();
        for (int day = 5; day <= 6; day++) {
            LocalDateTime d = LocalDateTime.of(2026, 1, day, 9, 0);
            bars.add(bar(d, 1000, 1000, 1000, 1000, 1, 2));                    // 아래
            bars.add(bar(d.plusMinutes(3), 1000, 1000, 1000, 1000, 3, 2));     // 돌파 -> 1000에 진입
            bars.add(bar(d.plusMinutes(6), 1000, 1020, 999, 1010, 3, 2));      // 고가 1020이 익절 1010에 닿음
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
        // 1,000,000 / 1,000 = 정확히 1,000주.
        assertEquals(1000L, t.quantity());
        // 매수 1,000,000 + 매도 1,010,000 -> 각 구간 0.015% 수수료 = 150 + 151.5
        assertEquals(301.5, t.feeAmount(), 1e-6);
        assertEquals(10_000 - 301.5, t.profitAmount(), 1e-6);

        // 고정 모드: 두 번째 거래도 같은 금액으로 크기를 잡으므로 둘이 동일합니다.
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

        // 재투자 효과만 떼어 보려고 수수료를 0으로 둡니다.
        BacktestResult r = engine.run(s, twoWinningTrades(), FeeSchedule.free());

        TradeRecord first = r.trades().get(0);
        TradeRecord second = r.trades().get(1);
        assertEquals(1000L, first.quantity());
        assertEquals(10_000, first.profitAmount(), 1e-6);
        // 잔고가 1,010,000이 되었으므로 floor(1,010,000 / 1,000) = 1,010주.
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
        s.getCapital().setAmount(500); // 1주 값이 1,000
        s.getCapital().setMode(CapitalMode.FIXED);

        BacktestResult r = engine.run(s, twoWinningTrades());

        assertEquals(2, r.summary().totalTrades());
        assertTrue(r.trades().stream().allMatch(t -> t.quantity() == 0L));
        assertEquals(0.0, r.money().totalProfitAmount(), 1e-9);
        assertEquals(2, r.money().unaffordableTrades());
        // 가격 기준 지표는 포지션 크기에 영향받지 않습니다.
        assertEquals(1.0, r.trades().get(0).returnPct(), 1e-9);
    }

    @Test
    void defaultsApplyWhenTheSpecCarriesNoCapitalBlock() throws Exception {
        // capital 블록이 생기기 전에 쓰인 spec_json: 키가 아예 없습니다.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.valueToTree(tpStrategy());
        json.remove("capital");

        StrategySpec s = mapper.treeToValue(json, StrategySpec.class);
        BacktestResult r = engine.run(s, twoWinningTrades());

        assertEquals("FIXED", r.money().mode());
        assertEquals(10_000_000, r.money().investAmount(), 1e-9);
        assertTrue(r.trades().get(0).quantity() > 0);
    }

    /** 어떤 저장 행은 키를 빼는 대신 명시적 null을 담고 있습니다. 결과는 같아야 합니다. */
    @Test
    void anExplicitNullCapitalBlockAlsoFallsBackToTheDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.valueToTree(tpStrategy());
        json.putNull("capital");
        json.putNull("premarket");

        StrategySpec s = mapper.treeToValue(json, StrategySpec.class);
        BacktestResult r = engine.run(s, twoWinningTrades());

        assertEquals("FIXED", r.money().mode());
        assertEquals(10_000_000, r.money().investAmount(), 1e-9);
    }
}
