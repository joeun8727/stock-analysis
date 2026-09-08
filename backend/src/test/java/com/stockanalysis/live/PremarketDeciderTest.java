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
 * 장전 방향 판단은 실제 거래를 시작시키는 유일한 결정이고, 백테스트와 실투자 세션은 반드시
 * 같은 방식으로 거기에 도달해야 합니다. 둘은 {@link PremarketDecider}를 공유합니다. 이 테스트는
 * 규칙 자체를 못 박은 다음, 같은 데이터에서 엔진의 답과 decider의 답이 일치하는지 확인합니다 —
 * 백테스트를 가치 있게 만드는 바로 그 동등성입니다.
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
        assertEquals(0.02, d.trendPct(), 1e-9); // 화면이 건너뛴 이유를 설명할 수 있도록 수치를 남깁니다
        assertNull(d.instrument());
    }

    /** [startTime, endTime) 안의 표본만 셉니다 — 09:05 시세가 판단을 움직여서는 안 됩니다. */
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
     * 동등성 확인: 백테스트 엔진에는 하루치 선물 봉을, decider에는 그 종가들을 실시간으로
     * 폴링한 것처럼 넣습니다. 둘이 같은 방향을 골라야 합니다.
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

        // 엔진이 날짜별로 무엇을 했는지.
        assertEquals("LEVERAGE", result.trades().get(0).instrument());
        assertEquals("INVERSE", result.trades().get(1).instrument());

        // 같은 가격이 폴링된 시세로 도착했을 때 실투자 세션이 무엇을 할지.
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
