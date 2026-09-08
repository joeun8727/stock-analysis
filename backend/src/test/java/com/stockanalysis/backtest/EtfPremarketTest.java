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

/** 장전 선물 추세는 오르는 날 레버리지를, 내리는 날 인버스를 골라야 합니다. */
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

        // 선물: 1일차 장전은 상승(100 -> 101 = +1%), 2일차는 하락(100 -> 99 = -1%).
        BarSeries futures = new BarSeries(List.of(
                bar(d1, LocalTime.of(8, 45), 100), bar(d1, LocalTime.of(8, 57), 101),
                bar(d2, LocalTime.of(8, 45), 100), bar(d2, LocalTime.of(8, 57), 99)
        ));
        // 레버리지는 1일차 세션을, 인버스는 2일차 세션을 갖습니다.
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
        spec.getExit().setCloseAtDayEnd(true); // 익절/손절 없음 -> 장마감 청산

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

    /** 레버리지와 인버스는 수수료율이 다를 수 있습니다. 거래마다 자기 요율을 써야 합니다. */
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

        // 레버리지 편도 0.01%, 인버스 편도 0.1% — 10배 차이.
        BacktestResult r = engine.runEtfPremarket(spec, leverage, inverse, futures,
                FeeSchedule.ofEtf(0.01, 0.1));

        TradeRecord up = r.trades().get(0);
        assertEquals("LEVERAGE", up.instrument());
        // 1,000,000 / 200 = 5,000주. 매수 1,000,000 + 매도 1,010,000에 0.01% = 100 + 101
        assertEquals(5000L, up.quantity());
        assertEquals(201.0, up.feeAmount(), 1e-6);

        TradeRecord down = r.trades().get(1);
        assertEquals("INVERSE", down.instrument());
        // 1,000,000 / 50 = 20,000주. 매수 1,000,000 + 매도 980,000에 0.1% = 1,000 + 980
        assertEquals(20000L, down.quantity());
        assertEquals(1980.0, down.feeAmount(), 1e-6);

        assertEquals(Map.of("LEVERAGE", 0.01, "INVERSE", 0.1), r.money().feeRatesPct());
        assertEquals(201.0 + 1980.0, r.money().totalFeeAmount(), 1e-6);
    }

    @Test
    void skipsDayWhenTrendBelowThreshold() {
        LocalDate d = LocalDate.of(2026, 1, 5);
        // 장전이 평평하면(100 -> 100.02 = +0.02%) 기본 임계치 0.1%에 못 미칩니다.
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
