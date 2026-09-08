package com.stockanalysis.backtest;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 자동 진단: 고장 난 것이 확실한 전략에 대해 원인을 제대로 짚는가? */
class FailureDiagnosisTest {

    private final BacktestEngine engine = new BacktestEngine();

    private static Bar bar(LocalDateTime ts, double o, double h, double l, double c, double ma5, double ma20) {
        return new Bar(ts, o, h, l, c, ma5, Double.NaN, ma20, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static StrategySpec crossStrategy() {
        StrategySpec s = new StrategySpec();
        s.setName("diag");
        s.setEntry(new ConditionGroup(Logic.AND, List.of(
                new Condition(Operand.of(Indicator.MA5), Operator.CROSS_ABOVE, Operand.of(Indicator.MA20)))));
        return s;
    }

    private static boolean hasTitleContaining(BacktestResult r, String needle) {
        return r.diagnosis().findings().stream().anyMatch(f -> f.title().contains(needle));
    }

    /** {@code hour}시에 진입해 손절로 끝나는 손실 거래일들을 덧붙입니다. */
    private static void appendLosingDays(List<Bar> bars, int startDayOffset, int count, int hour) {
        for (int i = 0; i < count; i++) {
            LocalDateTime d = LocalDateTime.of(2026, 1, 5, hour, 0).plusDays(startDayOffset + i);
            bars.add(bar(d, 1000, 1000, 1000, 1000, 1, 2));                 // 아래
            bars.add(bar(d.plusMinutes(3), 1000, 1000, 1000, 1000, 3, 2));  // 돌파 -> 1000에 진입
            bars.add(bar(d.plusMinutes(6), 1000, 1001, 980, 985, 3, 2));    // 저가 980이 손절 990에 닿음
        }
    }

    private static BarSeries losingDays(int days) {
        List<Bar> bars = new ArrayList<>();
        appendLosingDays(bars, 0, days, 9);
        return new BarSeries(bars);
    }

    /** 9시대에 손실 15건, 13시에 3건 — 한 시간대가 다른 쪽을 확실히 압도합니다. */
    private static BarSeries losingDaysAcrossTwoHours() {
        List<Bar> bars = new ArrayList<>();
        appendLosingDays(bars, 0, 15, 9);
        appendLosingDays(bars, 15, 3, 13);
        return new BarSeries(bars);
    }

    @Test
    void noTradesIsCalledOutExplicitly() {
        List<Bar> flat = List.of(
                bar(LocalDateTime.of(2026, 1, 5, 9, 0), 100, 100, 100, 100, 1, 2),
                bar(LocalDateTime.of(2026, 1, 5, 9, 3), 100, 100, 100, 100, 1, 2));

        BacktestResult r = engine.run(crossStrategy(), new BarSeries(flat));

        assertEquals(0, r.summary().totalTrades());
        assertTrue(r.diagnosis().headline().contains("거래가 한 건도"));
        assertEquals(1, r.diagnosis().findings().size());
        assertEquals(BacktestResult.Severity.HIGH, r.diagnosis().findings().get(0).severity());
    }

    /** 진입 시간대가 하나뿐이면 견줄 대상이 없으므로 어느 시간대도 탓하지 않습니다. */
    @Test
    void doesNotBlameAnHourWhenEveryTradeSharesTheSameHour() {
        StrategySpec s = crossStrategy();
        s.getExit().setStopLossPct(1.0);
        s.getExit().setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, losingDays(15));

        assertFalse(hasTitleContaining(r, "시대"), titles(r));
    }

    @Test
    void namesStopLossAsTheDominantExitAndTheWorstHour() {
        StrategySpec s = crossStrategy();
        s.getExit().setStopLossPct(1.0);
        s.getExit().setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, losingDaysAcrossTwoHours());

        assertEquals(18, r.summary().losses());
        assertTrue(hasTitleContaining(r, "손절"), "손절 원인 지목 없음: " + titles(r));
        assertTrue(hasTitleContaining(r, "9시대"), "시간대 지목 없음: " + titles(r));
        // 손절 관련 진단은 설정된 퍼센트를 제안 문구에 그대로 담아야 합니다.
        String fix = r.diagnosis().findings().stream()
                .filter(f -> f.title().contains("손절")).findFirst().orElseThrow().suggestion();
        assertTrue(fix.contains("1.00%"), fix);
    }

    @Test
    void flagsFeesEatingAProfitableEdge() {
        StrategySpec s = crossStrategy();
        s.getExit().setTakeProfitPct(0.2);
        s.getExit().setCloseAtDayEnd(false);
        s.getCapital().setAmount(1_000_000);

        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDateTime d = LocalDateTime.of(2026, 2, 2, 10, 0).plusDays(i);
            bars.add(bar(d, 1000, 1000, 1000, 1000, 1, 2));
            bars.add(bar(d.plusMinutes(3), 1000, 1000, 1000, 1000, 3, 2));
            bars.add(bar(d.plusMinutes(6), 1000, 1010, 999, 1005, 3, 2));   // 익절 1002에 닿음
        }

        // 편도 0.5%는 일부러 말도 안 되게 잡은 값입니다: 0.2% 목표에 왕복 1% 비용.
        BacktestResult r = engine.run(s, new BarSeries(bars), FeeSchedule.flat(0.5));

        assertEquals(12, r.summary().wins()); // 가격 기준으로는 전부 이김
        assertTrue(r.money().totalProfitAmount() < 0); // 하지만 수수료를 빼면 손실
        assertTrue(hasTitleContaining(r, "수수료"), titles(r));
        assertEquals(BacktestResult.Severity.HIGH, r.diagnosis().findings().get(0).severity());
    }

    @Test
    void headlineCarriesTradeCountWinRateAndTheTopProblem() {
        StrategySpec s = crossStrategy();
        s.getExit().setStopLossPct(1.0);
        s.getExit().setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, losingDays(15));

        String h = r.diagnosis().headline();
        assertTrue(h.contains("15건"), h);
        assertTrue(h.contains("승률 0.00%"), h);
        assertTrue(h.contains("가장 큰 문제는"), h);
        assertFalse(r.diagnosis().findings().isEmpty());
        // 최악부터 정렬됩니다.
        assertEquals(BacktestResult.Severity.HIGH, r.diagnosis().findings().get(0).severity());
    }

    @Test
    void aCleanWinningRunProducesNoHighSeverityFindings() {
        StrategySpec s = crossStrategy();
        s.getExit().setTakeProfitPct(1.0);
        s.getExit().setStopLossPct(1.0);
        s.getExit().setCloseAtDayEnd(false);

        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDateTime d = LocalDateTime.of(2026, 3, 2, 11, 0).plusDays(i);
            bars.add(bar(d, 1000, 1000, 1000, 1000, 1, 2));
            bars.add(bar(d.plusMinutes(3), 1000, 1000, 1000, 1000, 3, 2));
            bars.add(bar(d.plusMinutes(6), 1000, 1020, 999, 1015, 3, 2));
        }

        BacktestResult r = engine.run(s, new BarSeries(bars));

        assertNotNull(r.diagnosis());
        assertEquals(100.0, r.summary().winRate(), 1e-9);
        assertTrue(r.diagnosis().findings().stream()
                .noneMatch(f -> f.severity() == BacktestResult.Severity.HIGH), titles(r));
    }

    /** 모음이나 받침 ㄹ 뒤에는 '로', 그 밖에는 '으로' — 청산 사유 라벨이 두 갈래를 모두 씁니다. */
    @Test
    void exitReasonReadsWithTheCorrectKoreanParticle() {
        StrategySpec s = crossStrategy();
        s.getExit().setStopLossPct(1.0);
        s.getExit().setCloseAtDayEnd(false);

        BacktestResult r = engine.run(s, losingDaysAcrossTwoHours());

        String detail = r.diagnosis().findings().stream()
                .filter(f -> f.title().contains("손절")).findFirst().orElseThrow().detail();
        assertTrue(detail.contains("'손절'로"), detail);   // 절 ends in ㄹ
        assertFalse(detail.contains("손절'으로"), detail);
    }

    private static String titles(BacktestResult r) {
        return r.diagnosis().findings().stream().map(BacktestResult.Finding::title).toList().toString();
    }
}
