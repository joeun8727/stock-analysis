package com.stockanalysis.backtest

import com.stockanalysis.backtest.spec.PremarketSpec
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

/**
 * 장전 선물 움직임으로 ETF 쌍 중 어느 쪽을 살지 정합니다.
 *
 * `BacktestEngine`에서 꺼내온 이유는 [ExitEvaluator]와 같습니다 — **백테스트와 실투자가 방향을
 * 같은 구현으로 정해야** 하기 때문입니다.
 *
 * 입력을 일부러 가장 작은 모양(시각+가격 쌍의 시간순 목록)으로 뒀습니다. 그래야 각자 가진 걸
 * 그대로 넘길 수 있습니다:
 * - **백테스트**: `price_bar`에서 읽은 그날 선물 봉들의 종가
 * - **실투자**: 증권사 선물 API에서 폴링한 시세. 봉으로 만들 필요도, 엑셀도 필요 없습니다.
 *
 * 규칙 자체는 단순합니다: `[startTime, endTime)` 안의 **첫 값과 마지막 값**으로 변화율을 재서,
 * [PremarketSpec.thresholdPct] 이상 올랐으면 레버리지, 그만큼 내렸으면 인버스, 어중간하면 스킵.
 */
object PremarketDecider {

    /** 선물 가격 관측 하나. */
    data class PricePoint(
        @get:JvmName("ts") val ts: LocalDateTime,
        @get:JvmName("price") val price: Double
    )

    /** 장전 움직임이 가리키는 방향. */
    enum class Side {
        LEVERAGE,
        INVERSE,

        /** 쓸 만한 움직임이 없음 — 그날은 쉽니다. */
        SKIP
    }

    /**
     * 판단 결과. **왜 그렇게 정했는지** 화면에 보여줄 수 있도록 실제 변화율도 함께 담습니다.
     * [trendPct]가 null인 경우는 표본이 부족해 아예 잴 수 없었던 때뿐입니다.
     */
    data class Decision(
        @get:JvmName("side") val side: Side,
        @get:JvmName("trendPct") val trendPct: Double?,
        @get:JvmName("sampleCount") val sampleCount: Int
    ) {
        fun shouldTrade(): Boolean = side != Side.SKIP

        /** [TradeRecord.instrument]와 같은 표기. 스킵이면 null. */
        fun instrument(): String? = if (side == Side.SKIP) null else side.name
    }

    @JvmStatic
    fun decide(samples: List<PricePoint>, pm: PremarketSpec): Decision {
        val start = LocalTime.parse(pm.startTime)
        val end = LocalTime.parse(pm.endTime)
        val window = inWindow(samples, start, end)

        val trend = trendPct(window)
            ?: return Decision(Side.SKIP, null, window.size)

        // 임계치는 크기만 봅니다: -0.3% 도 +0.3% 만큼이나 0.1% 기준을 넘습니다.
        if (abs(trend) < abs(pm.thresholdPct)) {
            return Decision(Side.SKIP, trend, window.size)
        }
        return Decision(if (trend > 0) Side.LEVERAGE else Side.INVERSE, trend, window.size)
    }

    /** `[start, end)`에 들어오는 표본만, 들어온 순서 그대로. */
    @JvmStatic
    fun inWindow(samples: List<PricePoint>, start: LocalTime, end: LocalTime): List<PricePoint> =
        samples.filter {
            val t = it.ts.toLocalTime()
            !t.isBefore(start) && t.isBefore(end)
        }

    /**
     * 첫 표본 대비 마지막 표본의 변화율(%).
     *
     * 잴 게 없으면 null입니다 — 표본이 2개 미만이거나, 첫 가격이 0이라 나눌 수 없는 경우.
     */
    @JvmStatic
    fun trendPct(window: List<PricePoint>): Double? {
        if (window.size < 2) {
            return null
        }
        val first = window.first().price
        val last = window.last().price
        if (first == 0.0) {
            return null
        }
        return (last - first) / first * 100.0
    }

    /** 봉의 종가를 관측값으로 바꿉니다 — 백테스트가 이 함수로 입력을 만듭니다. */
    @JvmStatic
    fun fromBars(bars: List<Bar>): List<PricePoint> = bars.map { PricePoint(it.ts, it.close) }
}
