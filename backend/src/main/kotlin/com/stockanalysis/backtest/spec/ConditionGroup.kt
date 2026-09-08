package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.stockanalysis.backtest.Bar

/**
 * 조건 여러 개를 AND/OR로 묶은 것.
 *
 * **빈 그룹은 절대 매치되지 않습니다.** 조건을 하나도 안 걸어놓고 "아무 때나 사라"가 되면
 * 사고이므로, 비어 있으면 아무 일도 일어나지 않는 쪽을 택했습니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class ConditionGroup {

    @JsonSetter(nulls = Nulls.SKIP)
    var logic: Logic = Logic.AND

    @JsonSetter(nulls = Nulls.AS_EMPTY)
    var conditions: MutableList<Condition> = mutableListOf()

    constructor()

    constructor(logic: Logic?, conditions: MutableList<Condition>?) {
        this.logic = logic ?: Logic.AND
        this.conditions = conditions ?: mutableListOf()
    }

    fun matches(cur: Bar?, prev: Bar?, curFut: Bar?, prevFut: Bar?): Boolean {
        if (conditions.isEmpty()) {
            return false
        }
        return if (logic == Logic.OR) {
            conditions.any { it.matches(cur, prev, curFut, prevFut) }
        } else {
            conditions.all { it.matches(cur, prev, curFut, prevFut) }
        }
    }

    /** JSON에는 나가지 않습니다 — 저장된 스펙의 모양을 바꾸면 안 되기 때문입니다. */
    @JsonIgnore
    fun isEmpty(): Boolean = conditions.isEmpty()
}
