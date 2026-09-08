package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.stockanalysis.backtest.Bar
import com.stockanalysis.backtest.Indicator

/**
 * 비교의 한쪽 항. 지표를 읽거나, 고정 숫자이거나 둘 중 하나입니다.
 *
 * JSON 형태: `{"indicator":"MA5"}`, `{"indicator":"CLOSE","source":"FUTURES"}`, `{"const":1000}`.
 *
 * `const`는 Kotlin 예약어라 프로퍼티 이름은 [constant]로 두고 [JsonProperty]로 JSON 이름만
 * 맞췄습니다 — **이름이 바뀌면 저장된 전략이 전부 깨집니다.**
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class Operand {

    var indicator: Indicator? = null

    @JsonSetter(nulls = Nulls.SKIP)
    var source: Source = Source.PRIMARY

    @get:JsonProperty("const")
    @set:JsonProperty("const")
    var constant: Double? = null

    /**
     * 주어진 봉에서 이 항의 값을 구합니다.
     *
     * 상수가 있으면 그걸 쓰고, 아니면 지표를 읽습니다. 읽을 봉이 없으면(선물 짝이 없는 등)
     * `NaN`입니다 — 엔진 규약상 `NaN`이 낀 비교는 항상 false라 조건이 조용히 꺼집니다.
     */
    fun resolve(primary: Bar?, futures: Bar?): Double {
        constant?.let { return it }
        val ind = indicator ?: return Double.NaN
        val bar = if (source == Source.FUTURES) futures else primary
        return bar?.value(ind) ?: Double.NaN
    }

    companion object {
        @JvmStatic
        fun of(indicator: Indicator): Operand = Operand().apply { this.indicator = indicator }

        @JvmStatic
        fun of(indicator: Indicator, source: Source): Operand =
            of(indicator).apply { this.source = source }

        @JvmStatic
        fun constant(value: Double): Operand = Operand().apply { constant = value }
    }
}
