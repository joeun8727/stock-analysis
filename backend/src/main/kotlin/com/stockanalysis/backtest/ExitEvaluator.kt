package com.stockanalysis.backtest

import com.stockanalysis.backtest.spec.ConditionGroup
import com.stockanalysis.backtest.spec.ExitSpec
import java.time.LocalTime

/**
 * 보유 중인 포지션을 언제 닫을지 정합니다.
 *
 * `BacktestEngine`에서 꺼내온 이유는 하나입니다: **백테스트와 실투자가 같은 규칙으로 돌아야**
 * 하기 때문입니다. 과거 데이터로 검증한 전략이 실계좌에서 다르게 움직이면 검증이 의미가 없고,
 * 그건 구현이 하나일 때만 보장됩니다.
 *
 * 봉마다 검사 순서: 손절 → 익절 → 시그널 → 시간 → 장마감 → 데이터끝.
 * 손절·익절은 봉의 고가/저가로 **장중**에 판정하고, 한 봉에서 둘 다 닿으면 **손절이 이깁니다**
 * (봉 안에서 어느 쪽이 먼저였는지 알 수 없으니 보수적으로).
 *
 * 퍼센트는 진입 시각이 아니라 **지금 보고 있는 봉의 시각**으로 매번 다시 구합니다 — 그래서
 * [ExitSpec]의 시간대 밴드가 포지션을 들고 있는 중에도 선을 움직입니다.
 *
 * `frontend/src/app/guide`는 이 순서를 손으로 옮겨 적은 문서입니다. 여기를 고치면 거기도 고치세요.
 */
object ExitEvaluator {

    /** 발동한 청산: 어느 가격에 닫는지와 그 이유. */
    data class Decision(
        @get:JvmName("price") val price: Double,
        @get:JvmName("reason") val reason: ExitReason
    )

    /**
     * 봉 단위 판정. 백테스트 엔진과, 실투자에서 봉이 마감될 때 씁니다.
     * 아직 들고 있어야 하면 `null`입니다.
     */
    @JvmStatic
    fun decide(
        cur: Bar,
        prev: Bar?,
        lastOverall: Boolean,
        lastBarOfDay: Boolean,
        barsHeld: Int,
        exit: ExitSpec,
        exitGroup: ConditionGroup,
        entryPrice: Double,
        curFut: Bar?,
        prevFut: Bar?
    ): Decision? {
        // 거래별이 아니라 봉별로 다시 구합니다 — 시간대 밴드가 보유 중에 선을 옮길 수 있습니다.
        val at = cur.ts.toLocalTime()
        val stopPrice = stopPriceOf(exit, entryPrice, at)
        val tpPrice = tpPriceOf(exit, entryPrice, at)

        if (!stopPrice.isNaN() && cur.low <= stopPrice) {
            return Decision(stopPrice, ExitReason.STOP_LOSS)
        }
        if (!tpPrice.isNaN() && cur.high >= tpPrice) {
            return Decision(tpPrice, ExitReason.TAKE_PROFIT)
        }
        if (!exitGroup.isEmpty() && exitGroup.matches(cur, prev, curFut, prevFut)) {
            return Decision(cur.close, ExitReason.SIGNAL)
        }
        exit.maxHoldBars?.let { maxBars ->
            if (barsHeld >= maxBars) {
                return Decision(cur.close, ExitReason.TIME)
            }
        }
        if (exit.closeAtDayEnd && lastBarOfDay && !lastOverall) {
            return Decision(cur.close, ExitReason.DAY_END)
        }
        if (lastOverall) {
            return Decision(cur.close, ExitReason.END_OF_DATA)
        }
        return null
    }

    /**
     * 실투자용 틱 단위 손절·익절 검사. 완성된 봉이 아니라 가격이 하나씩 들어오는 상황입니다.
     *
     * [decide]가 봉의 고가/저가로 한 번에 보는 것과 **의미가 같습니다** — 백테스트는 봉의
     * 양 극단을 한꺼번에 보고, 실전은 그 가격들이 지나가는 걸 순서대로 봅니다. 동점일 때
     * 손절이 이기는 것도 그대로입니다.
     *
     * 돌려주는 가격은 **트리거 선**이라 백테스트의 이상적 체결가와 같습니다. 실전 호출부는
     * 이 값을 증권사의 실제 체결가로 덮어써야 합니다 — 그 차이가 슬리피지이고, 실전 성적이
     * 백테스트보다 나쁘게 나오는 이유입니다.
     *
     * 시그널·시간·장마감 청산은 여기서 안 봅니다. 완성된 봉이나 봉 수가 필요해서, 봉이 마감될 때
     * [decide]로 판정합니다.
     */
    @JvmStatic
    fun checkLivePrice(exit: ExitSpec, entryPrice: Double, price: Double, at: LocalTime): Decision? {
        val stopPrice = stopPriceOf(exit, entryPrice, at)
        if (!stopPrice.isNaN() && price <= stopPrice) {
            return Decision(stopPrice, ExitReason.STOP_LOSS)
        }
        val tpPrice = tpPriceOf(exit, entryPrice, at)
        if (!tpPrice.isNaN() && price >= tpPrice) {
            return Decision(tpPrice, ExitReason.TAKE_PROFIT)
        }
        return null
    }

    /**
     * [entryPrice]에 잡은 포지션의 [at] 시점 손절가. [at]은 지금 검사 중인 봉의 시각이라,
     * 보유 중에 시작되는 밴드가 선을 옮깁니다. 그 시각에 손절이 없으면 `NaN`입니다.
     */
    @JvmStatic
    fun stopPriceOf(exit: ExitSpec, entryPrice: Double, at: LocalTime): Double {
        val pct = exit.stopLossPctAt(at) ?: return Double.NaN
        return entryPrice * (1.0 - pct / 100.0)
    }

    /** [at] 시점의 익절가. 없으면 `NaN`. [stopPriceOf] 참고. */
    @JvmStatic
    fun tpPriceOf(exit: ExitSpec, entryPrice: Double, at: LocalTime): Double {
        val pct = exit.takeProfitPctAt(at) ?: return Double.NaN
        return entryPrice * (1.0 + pct / 100.0)
    }
}
