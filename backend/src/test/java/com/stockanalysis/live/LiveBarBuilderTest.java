package com.stockanalysis.live;

import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.Indicator;
import com.stockanalysis.live.market.LiveBarBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실시간 봉은 백테스트가 엑셀에서 읽는 것과 똑같이 나와야 합니다. 아니면 검증받은 전략이
 * 실계좌에서 조용히 다른 것을 뜻하게 됩니다. 여기서는 추측이 아니라 시드 파일에서 실측한 두
 * 가지 성질을 못 박아 둡니다.
 */
class LiveBarBuilderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 24);

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DAY, LocalTime.of(hour, minute));
    }

    private static Bar minuteBar(int hour, int minute, double close, double volume) {
        double nan = Double.NaN;
        return new Bar(at(hour, minute), close, close, close, close,
                nan, nan, nan, nan, volume, nan, nan, nan, nan);
    }

    /**
     * 원본 파일의 3분 격자는 08:45 / 08:48 / … / 15:45입니다 — 봉은 시작 시각으로 이름 붙고,
     * 구간은 하루 중 분을 기준으로 내림합니다.
     */
    @Test
    void bucketsAreLabelledByTheirStartOnTheSameGridAsTheExcel() {
        assertEquals(at(8, 45), LiveBarBuilder.bucketStartOf(at(8, 45), 3));
        assertEquals(at(8, 45), LiveBarBuilder.bucketStartOf(at(8, 47), 3));
        assertEquals(at(8, 48), LiveBarBuilder.bucketStartOf(at(8, 48), 3));
        assertEquals(at(9, 0), LiveBarBuilder.bucketStartOf(at(9, 2), 3));
        assertEquals(at(15, 45), LiveBarBuilder.bucketStartOf(at(15, 47), 3));
        // 1분 데이터는 자명한 경우입니다: 매 분이 각자 하나의 구간입니다.
        assertEquals(at(9, 7), LiveBarBuilder.bucketStartOf(at(9, 7), 1));
    }

    @Test
    void aggregatesOneMinuteBarsIntoThreeMinuteBucketsWithSummedVolume() {
        List<Bar> minutes = List.of(
                minuteBar(9, 0, 100, 10),
                minuteBar(9, 1, 104, 20),
                minuteBar(9, 2, 102, 30),
                minuteBar(9, 3, 105, 40));

        List<Bar> bars = LiveBarBuilder.aggregate(minutes, 3);

        assertEquals(2, bars.size());
        Bar first = bars.get(0);
        assertEquals(at(9, 0), first.ts());
        assertEquals(100, first.open(), 1e-9);
        assertEquals(104, first.high(), 1e-9);
        assertEquals(100, first.low(), 1e-9);
        assertEquals(102, first.close(), 1e-9);
        assertEquals(60, first.volume(), 1e-9);
        assertEquals(at(9, 3), bars.get(1).ts());
    }

    /**
     * 엑셀의 MA5는 라벨이 붙은 봉과 그 앞 네 개의 평균입니다 — 분이 아니라 봉입니다.
     * KOSPI200 3분 데이터로 확인했고, 15:45 봉의 ma5가 정확히 일치합니다.
     */
    @Test
    void movingAverageIsOverNBarsIncludingTheCurrentOne() {
        LiveBarBuilder builder = new LiveBarBuilder(1);
        List<Bar> warm = new ArrayList<>();
        double[] closes = {10, 20, 30, 40};
        for (int i = 0; i < closes.length; i++) {
            warm.add(minuteBar(9, i, closes[i], 100));
        }
        builder.warmUp(warm);

        // 다섯 번째 봉이 50에 마감: MA5 = (10+20+30+40+50)/5 = 30.
        builder.accept(at(9, 4), 50, 500.0);
        Bar forming = builder.formingBar().orElseThrow();
        assertEquals(30.0, forming.ma5(), 1e-9);

        // MA10은 쓸 수 있는 봉이 5개뿐이라, 있는 것만 평균 내지 않고 NaN으로 남습니다.
        assertTrue(Double.isNaN(forming.ma10()));
    }

    @Test
    void unwarmedIndicatorsStayNaNSoTheirRulesNeverFire() {
        LiveBarBuilder builder = new LiveBarBuilder(3);
        builder.accept(at(9, 0), 100, null);
        Bar bar = builder.formingBar().orElseThrow();

        assertTrue(Double.isNaN(bar.ma5()));
        assertTrue(Double.isNaN(bar.ma60()));
        // 엔진에서 NaN 비교는 false이므로, 워밍업이 안 된 규칙은 그냥 발동하지 않습니다.
        assertFalse(bar.ma5() > 0);
        assertFalse(bar.ma5() < 0);

        assertFalse(builder.readiness().get(Indicator.MA5));
        assertTrue(builder.readiness().get(Indicator.CLOSE));
    }

    @Test
    void aBarIsReturnedOnlyWhenTheNextBucketStarts() {
        LiveBarBuilder builder = new LiveBarBuilder(3);

        assertTrue(builder.accept(at(9, 0), 100, 0.0).isEmpty());
        assertTrue(builder.accept(at(9, 1), 110, 50.0).isEmpty());
        assertTrue(builder.accept(at(9, 2), 105, 80.0).isEmpty());

        Optional<Bar> sealed = builder.accept(at(9, 3), 106, 90.0);
        assertTrue(sealed.isPresent());
        Bar bar = sealed.get();
        assertEquals(at(9, 0), bar.ts());
        assertEquals(100, bar.open(), 1e-9);
        assertEquals(110, bar.high(), 1e-9);
        assertEquals(100, bar.low(), 1e-9);
        assertEquals(105, bar.close(), 1e-9);
        // 봉 거래량은 구간 양끝의 세션 누적 거래량 차이입니다.
        assertEquals(80, bar.volume(), 1e-9);
        assertEquals(1, builder.completedBarCount());
    }

    @Test
    void volumeStaysNaNWhenTheFeedDoesNotReportIt() {
        LiveBarBuilder builder = new LiveBarBuilder(3);
        builder.accept(at(9, 0), 100, null);
        builder.accept(at(9, 3), 101, null);

        Bar bar = builder.completedBars().get(0);
        assertTrue(Double.isNaN(bar.volume()));
        assertTrue(Double.isNaN(bar.volMa5()));
    }
}
