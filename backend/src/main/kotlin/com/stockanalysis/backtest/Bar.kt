package com.stockanalysis.backtest

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 분봉 하나(데이터셋에 따라 3분봉 또는 1분봉)와 미리 계산된 이동평균들.
 *
 * **[ts]는 봉의 시작 시각**이고 구간은 `[ts, ts + 봉길이)`입니다. 3분봉이면 08:45/08:48/…/15:45
 * 격자가 됩니다. 실측 근거: 종가 단일가 구간(15:35~15:45)에 걸치는 15:36·15:39·15:42 봉의
 * 거래량이 0인데, 끝 시각 라벨이라면 15:36 봉이 연속거래 중이던 15:34를 포함하므로 0일 수 없습니다.
 *
 * 값이 없는 지표는 [Double.NaN]입니다 — `NaN`이 낀 비교는 항상 false라, 데이터가 아직 없는
 * 지표는 조건을 발동시키지 않고 조용히 넘어갑니다.
 *
 * 접근자에 `@get:JvmName`을 붙여 Java record와 같은 `bar.ts()` 형태를 유지합니다. Kotlin에서는
 * 그냥 `bar.ts`로 씁니다.
 */
data class Bar(
    @get:JvmName("ts") val ts: LocalDateTime,
    @get:JvmName("open") val open: Double,
    @get:JvmName("high") val high: Double,
    @get:JvmName("low") val low: Double,
    @get:JvmName("close") val close: Double,
    @get:JvmName("ma5") val ma5: Double,
    @get:JvmName("ma10") val ma10: Double,
    @get:JvmName("ma20") val ma20: Double,
    @get:JvmName("ma60") val ma60: Double,
    @get:JvmName("volume") val volume: Double,
    @get:JvmName("volMa5") val volMa5: Double,
    @get:JvmName("volMa20") val volMa20: Double,
    @get:JvmName("volMa60") val volMa60: Double,
    @get:JvmName("volMa120") val volMa120: Double
) {

    fun date(): LocalDate = ts.toLocalDate()

    /** 이 봉에서 지표 하나를 읽습니다. */
    fun value(indicator: Indicator): Double = when (indicator) {
        Indicator.OPEN -> open
        Indicator.HIGH -> high
        Indicator.LOW -> low
        Indicator.CLOSE -> close
        Indicator.MA5 -> ma5
        Indicator.MA10 -> ma10
        Indicator.MA20 -> ma20
        Indicator.MA60 -> ma60
        Indicator.VOLUME -> volume
        Indicator.VOL_MA5 -> volMa5
        Indicator.VOL_MA20 -> volMa20
        Indicator.VOL_MA60 -> volMa60
        Indicator.VOL_MA120 -> volMa120
    }
}
