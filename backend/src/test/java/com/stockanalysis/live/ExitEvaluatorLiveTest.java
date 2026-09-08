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
 * 실시간 틱 확인과 백테스트의 장중 확인은 한 규칙의 두 얼굴입니다: 백테스트는 봉의 고가와
 * 저가를 한 번에 보고, 실전은 같은 가격을 하나씩 봅니다. 여기서는 둘이 일치하는지를 —
 * 보수적인 타이브레이크와 시간대 밴드까지 포함해 — 확인합니다.
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

        // 같은 진입, 저가가 손절선에 닿는 봉: 백테스트도 같은 선에서 청산합니다.
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

    /** 백테스트에서는 동시에 닿으면 손절이 이깁니다. 실시간 확인도 똑같이 보수적이어야 합니다. */
    @Test
    void stopLossWinsWhenBothLevelsAreReachable() {
        ExitSpec spec = exitSpec(1.0, 1.0);
        // 어느 쪽도 단독으로 만족시키지 못하는 가격으로는 타이를 시험할 수 없으니, 그 선 자체를 씁니다.
        ExitEvaluator.Decision live =
                ExitEvaluator.checkLivePrice(spec, 1000, 990, LocalTime.of(10, 0));
        assertEquals(ExitReason.STOP_LOSS, live.reason());

        ExitEvaluator.Decision backtest = ExitEvaluator.decide(
                bar(LocalTime.of(10, 0), 1000, 1010, 990, 1000), null, false, false, 1,
                spec, spec.asGroup(), 1000, null, null);
        assertEquals(ExitReason.STOP_LOSS, backtest.reason());
    }

    /** 보유 도중에 시작되는 밴드는 선을 옮깁니다 — 실전은 틱마다, 엔진은 봉마다 해석합니다. */
    @Test
    void timeBandsMoveTheLiveLevelsAsTheClockPasses() {
        ExitSpec spec = exitSpec(3.0, 3.0);
        spec.setBands(List.of(new TimeBand("10:00", "11:00", 1.0, 1.0)));

        // 09:30은 밴드 밖입니다: 기본 3% 손절이 970이라 985는 아직 안전합니다.
        assertNull(ExitEvaluator.checkLivePrice(spec, 1000, 985, LocalTime.of(9, 30)));

        // 같은 가격이라도 10:30이면 밴드의 1% 손절선 990을 지납니다.
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
