package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.stockanalysis.backtest.Bar
import com.stockanalysis.backtest.Operator

/** 두 피연산자를 연산자 하나로 비교하는 최소 단위. */
@JsonIgnoreProperties(ignoreUnknown = true)
class Condition {

    var left: Operand? = null
    var op: Operator? = null
    var right: Operand? = null

    constructor()

    constructor(left: Operand?, op: Operator?, right: Operand?) {
        this.left = left
        this.op = op
        this.right = right
    }

    /**
     * 현재 봉 기준으로 조건이 맞는지 봅니다.
     *
     * `CROSS_*`는 직전 봉까지 함께 보므로 **시리즈 첫 봉에서는 판정할 수 없어** false입니다.
     * `NaN`이 낀 비교도 전부 false입니다(Kotlin의 `>`/`<`도 Java와 같게 동작합니다).
     */
    fun matches(cur: Bar?, prev: Bar?, curFut: Bar?, prevFut: Bar?): Boolean {
        val l = left ?: return false
        val r = right ?: return false
        val operator = op ?: return false

        val lv = l.resolve(cur, curFut)
        val rv = r.resolve(cur, curFut)
        return when (operator) {
            Operator.GT -> lv > rv
            Operator.GTE -> lv >= rv
            Operator.LT -> lv < rv
            Operator.LTE -> lv <= rv
            Operator.EQ -> lv == rv
            Operator.CROSS_ABOVE -> {
                if (prev == null) false
                else l.resolve(prev, prevFut) <= r.resolve(prev, prevFut) && lv > rv
            }
            Operator.CROSS_BELOW -> {
                if (prev == null) false
                else l.resolve(prev, prevFut) >= r.resolve(prev, prevFut) && lv < rv
            }
        }
    }
}
