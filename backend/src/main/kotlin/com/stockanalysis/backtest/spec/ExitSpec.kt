package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.time.LocalTime

/**
 * 매도(청산) 규칙.
 *
 * 익절·손절은 **진입가 대비 퍼센트**이고, [maxHoldBars]는 N봉이 지나면 시간 청산,
 * [closeAtDayEnd]는 장 마지막 봉에서 강제 청산입니다(단타 기본값). 지표 조건([conditions])도
 * 매도 트리거가 됩니다.
 *
 * [bands]는 시간대별로 익절·손절을 덮어씁니다. **보유 중인 매 봉마다 그 봉의 시각으로 다시**
 * 판정하므로 시계가 다음 밴드로 넘어가면 선도 따라 움직입니다. 먼저 맞는 밴드가 이기니까
 * 겹쳐도 되고, 좁은 구간을 앞에 두면 됩니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class ExitSpec {

    var takeProfitPct: Double? = null
    var stopLossPct: Double? = null
    var maxHoldBars: Int? = null

    @JsonSetter(nulls = Nulls.SKIP)
    var closeAtDayEnd: Boolean = true

    @JsonSetter(nulls = Nulls.SKIP)
    var logic: Logic = Logic.OR

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    var conditions: MutableList<Condition> = mutableListOf()

    /** 시간대 밴드는 나중에 추가된 필드라 예전 행에는 없습니다 — null이면 빈 목록으로 읽습니다. */
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    var bands: MutableList<TimeBand> = mutableListOf()

    /** 매도 조건들을 그룹으로 감싼 것. 엔진이 매수 조건과 똑같은 방식으로 평가합니다. */
    fun asGroup(): ConditionGroup = ConditionGroup(logic, conditions)

    /** `t` 시점에 유효한 익절 % — 먼저 맞는 밴드의 값, 없으면 기본값. */
    fun takeProfitPctAt(t: LocalTime?): Double? {
        val band = bandAt(t)
        return band?.takeProfitPct ?: takeProfitPct
    }

    /** `t` 시점에 유효한 손절 % — 먼저 맞는 밴드의 값, 없으면 기본값. */
    fun stopLossPctAt(t: LocalTime?): Double? {
        val band = bandAt(t)
        return band?.stopLossPct ?: stopLossPct
    }

    /** 목록 순서대로 찾으므로 구간이 겹치면 앞에 있는 것이 이깁니다. */
    fun bandAt(t: LocalTime?): TimeBand? {
        if (bands.isEmpty() || t == null) {
            return null
        }
        return bands.firstOrNull { it.covers(t) }
    }
}
