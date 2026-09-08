package com.stockanalysis.backtest

import com.stockanalysis.backtest.spec.Condition
import com.stockanalysis.backtest.spec.ConditionGroup
import com.stockanalysis.backtest.spec.ExitSpec
import com.stockanalysis.backtest.spec.Logic
import com.stockanalysis.backtest.spec.Operand
import com.stockanalysis.backtest.spec.StrategySpec

/**
 * 기본 예제 전략들.
 *
 * 테스트와 CLI 러너가 쓰고, 사용자가 처음 들어와서 **당장 백테스트해볼 것**이 있도록 하는
 * 용도이기도 합니다.
 */
object SampleStrategies {

    /** 골든크로스: MA5가 MA20을 위로 뚫으면 매수. 익절 1.5% / 손절 1.0%, 데드크로스에 매도. */
    @JvmStatic
    fun goldenCross(): StrategySpec = StrategySpec().apply {
        name = "골든크로스 단타"
        source = "USER"
        entry = ConditionGroup(
            Logic.AND,
            mutableListOf(
                Condition(Operand.of(Indicator.MA5), Operator.CROSS_ABOVE, Operand.of(Indicator.MA20))
            )
        )
        exit = ExitSpec().apply {
            takeProfitPct = 1.5
            stopLossPct = 1.0
            closeAtDayEnd = true
            logic = Logic.OR
            conditions = mutableListOf(
                Condition(Operand.of(Indicator.MA5), Operator.CROSS_BELOW, Operand.of(Indicator.MA20))
            )
        }
    }

    /** 거래량 돌파: 종가가 MA20 위이고 거래량이 20봉 평균을 넘으면 매수. 익절 0.8% / 손절 0.6%. */
    @JvmStatic
    fun volumeBreakout(): StrategySpec = StrategySpec().apply {
        name = "거래량 돌파 단타"
        source = "USER"
        entry = ConditionGroup(
            Logic.AND,
            mutableListOf(
                Condition(Operand.of(Indicator.CLOSE), Operator.GT, Operand.of(Indicator.MA20)),
                Condition(Operand.of(Indicator.VOLUME), Operator.GT, Operand.of(Indicator.VOL_MA20))
            )
        )
        exit = ExitSpec().apply {
            takeProfitPct = 0.8
            stopLossPct = 0.6
            maxHoldBars = 10
            closeAtDayEnd = true
        }
    }
}
