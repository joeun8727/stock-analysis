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

/** 진입 돌파 판정과 청산 사유별 동작을 확인하는 결정론적 단위 테스트. */
class BacktestEngineTest {

    private final BacktestEngine engine = new BacktestEngine();

    private static Bar bar(LocalDateTime ts, double o, double h, double l, double c, double ma5, double ma20) {
        return new Bar(ts, o, h, l, c, ma5, Double.NaN, ma20, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    /** 진입은 진짜 돌파(직전은 아래, 현재는 위)에서만 걸리고, 이미 위에 있으면 걸리지 않습니다. */
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
        bars.add(bar(d, 100, 100, 100, 100, 1, 2));                 // 아래
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2));  // 상향돌파 -> 100에 진입
        bars.add(bar(d.plusMinutes(6), 100, 102, 99.5, 101, 3, 2)); // 고가 102가 익절 101.5에 닿음

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
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2));  // 100에 진입
        bars.add(bar(d.plusMinutes(6), 100, 102, 98, 100, 3, 2));   // 저가 98이 손절 99에, 고가 102가 익절에 닿음 -> 손절 우선

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
        bars.add(bar(d1.plusMinutes(3), 100, 100, 100, 100, 3, 2)); // 100에 진입
        bars.add(bar(d1.plusMinutes(6), 100, 100.5, 99.8, 101, 3, 2)); // 1일차 마지막 봉 -> 101에 DAY_END
        bars.add(bar(d2, 100, 100, 100, 100, 3, 2));                // 다음 날

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setCloseAtDayEnd(true); // 익절/손절/시그널 없음

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
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2)); // 100에 진입
        bars.add(bar(d.plusMinutes(6), 100, 100.2, 99.9, 100.2, 3, 2)); // 전체 마지막 -> END_OF_DATA

        StrategySpec s = crossEntryOnly();
        s.getExit().setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, new BarSeries(bars));
        TradeRecord t = r.trades().get(0);
        assertEquals(ExitReason.END_OF_DATA, t.exitReason());
    }

    // ------------------------------------------------------- 시간대별 익절 / 손절

    /** 09:00부터 3분 간격 봉. 따로 지정하지 않으면 전부 100으로 평평합니다. */
    private static List<Bar> flatDay(int count) {
        LocalDateTime d = LocalDateTime.of(2026, 1, 5, 9, 0);
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bars.add(bar(d.plusMinutes(3L * i), 100, 100, 100, 100, i == 0 ? 1 : 3, 2));
        }
        return bars;
    }

    /**
     * 밴드는 진입 봉이 아니라 지금 확인하는 봉에 맞춰집니다: 넓은 09:00 밴드에서 잡은 포지션도
     * 시계가 넘어가면 더 좁은 10:00 밴드의 손절선에 걸립니다.
     */
    @Test
    void stopLossFollowsTheCurrentBarsTimeBand() {
        List<Bar> bars = flatDay(24);                          // 09:00 .. 10:09, 09:03에 100으로 진입
        // 09:57에 99.0까지 눌림 — 09:00 밴드 안(손절 98.5)이라 살아남습니다.
        bars.set(19, bar(LocalDateTime.of(2026, 1, 5, 9, 57), 100, 100, 99.0, 100, 3, 2));
        // 10:03에 똑같이 99.0까지 눌림 — 10:00 밴드의 손절은 99.5라 이번엔 청산됩니다.
        bars.set(21, bar(LocalDateTime.of(2026, 1, 5, 10, 3), 100, 100, 99.0, 100, 3, 2));

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setStopLossPct(5.0);                              // 기본값. 일부러 멀리 둠
        exit.setCloseAtDayEnd(false);
        exit.setBands(List.of(
                new TimeBand("09:00", "10:00", null, 1.5),     // 손절 98.5
                new TimeBand("10:00", "15:20", null, 0.5)));   // 손절 99.5

        BacktestResult r = engine.run(s, new BarSeries(bars));
        assertEquals(1, r.summary().totalTrades());
        TradeRecord t = r.trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.exitReason());
        assertEquals(LocalDateTime.of(2026, 1, 5, 10, 3), t.exitTs());
        assertEquals(99.5, t.exitPrice(), 1e-9);
    }

    /** 한쪽만 지정한 밴드는 나머지 한쪽을 ExitSpec 기본값으로 둡니다. */
    @Test
    void bandFallsBackToBaseForTheSideItDoesNotSet() {
        List<Bar> bars = flatDay(6);
        bars.set(4, bar(LocalDateTime.of(2026, 1, 5, 9, 12), 100, 101.2, 100, 100, 3, 2)); // 고가가 익절 101에 닿음

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setTakeProfitPct(1.0);                            // 기본 익절은 그대로 유지됨
        exit.setStopLossPct(1.0);
        exit.setCloseAtDayEnd(false);
        exit.setBands(List.of(new TimeBand("09:00", "10:00", null, 3.0)));  // 손절만 덮어씀

        TradeRecord t = engine.run(s, new BarSeries(bars)).trades().get(0);
        assertEquals(ExitReason.TAKE_PROFIT, t.exitReason());
        assertEquals(101.0, t.exitPrice(), 1e-9);
    }

    /** 어느 밴드에도 안 걸리는 시각은 기본값을 쓰므로, 지정하지 않은 시간대는 예전 그대로 동작합니다. */
    @Test
    void uncoveredTimeUsesBaseValues() {
        List<Bar> bars = flatDay(6);
        bars.set(4, bar(LocalDateTime.of(2026, 1, 5, 9, 12), 100, 100, 98.9, 100, 3, 2)); // 저가가 기본 손절 99에 닿음

        StrategySpec s = crossEntryOnly();
        ExitSpec exit = s.getExit();
        exit.setStopLossPct(1.0);
        exit.setCloseAtDayEnd(false);
        exit.setBands(List.of(new TimeBand("13:00", "15:20", null, 0.1)));  // 이 봉들을 전혀 덮지 않음

        TradeRecord t = engine.run(s, new BarSeries(bars)).trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.exitReason());
        assertEquals(99.0, t.exitPrice(), 1e-9);
    }

    /** [시작, 종료): 종료 분은 이 밴드가 아니라 다음 밴드에 속합니다. */
    @Test
    void bandEndIsExclusive() {
        ExitSpec exit = new ExitSpec();
        exit.setStopLossPct(9.0);
        exit.setBands(List.of(
                new TimeBand("09:00", "10:00", null, 1.0),
                new TimeBand("10:00", "15:20", null, 2.0)));
        assertEquals(1.0, exit.stopLossPctAt(LocalTime.of(9, 59)), 1e-9);
        assertEquals(2.0, exit.stopLossPctAt(LocalTime.of(10, 0)), 1e-9);
        assertEquals(9.0, exit.stopLossPctAt(LocalTime.of(15, 20)), 1e-9);  // 모든 밴드를 지남 -> 기본값
    }

    /** 겹침은 허용되고 목록 순서로 결정되므로, 좁은 구간을 위에 올려둘 수 있습니다. */
    @Test
    void firstMatchingBandWinsAnOverlap() {
        ExitSpec exit = new ExitSpec();
        exit.setBands(List.of(
                new TimeBand("09:00", "09:30", 0.5, 0.3),      // 좁은 구간을 앞에 둠
                new TimeBand("09:00", "15:20", 2.0, 1.5)));
        assertEquals(0.5, exit.takeProfitPctAt(LocalTime.of(9, 15)), 1e-9);
        assertEquals(2.0, exit.takeProfitPctAt(LocalTime.of(9, 45)), 1e-9);
    }

    @Test
    void seriesIsSortedAscendingRegardlessOfInputOrder() {
        LocalDateTime d = LocalDateTime.of(2026, 1, 5, 9, 0);
        List<Bar> bars = new ArrayList<>();
        // 원본 엑셀처럼 최신순으로, 순서를 흐트러뜨려 넣습니다
        bars.add(bar(d.plusMinutes(6), 100, 100, 100, 100, 3, 2));
        bars.add(bar(d.plusMinutes(3), 100, 100, 100, 100, 3, 2));
        bars.add(bar(d, 100, 100, 100, 100, 1, 2));
        BarSeries series = new BarSeries(bars);
        assertEquals(d, series.bars().get(0).ts());
        assertEquals(d.plusMinutes(6), series.bars().get(2).ts());
    }
}
