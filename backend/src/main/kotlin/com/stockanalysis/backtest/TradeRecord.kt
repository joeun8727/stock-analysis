package com.stockanalysis.backtest

import java.time.LocalDateTime

/**
 * 완료된 왕복 거래 하나.
 *
 * [instrument]는 ETF 장전 모드에서 어느 쪽을 샀는지(LEVERAGE/INVERSE)이고, 단일 데이터셋
 * 백테스트에서는 null입니다.
 *
 * [quantity]/[profitAmount]/[feeAmount]는 이 거래의 **금액 관점**으로, 엔진의 자본 환산
 * 패스가 나중에 채웁니다. 복리 모드에서는 사이징이 직전까지의 잔고에 달려 있어서, 그 패스가
 * 돌기 전까지는 0으로 남아 있습니다.
 */
data class TradeRecord(
    @get:JvmName("entryTs") val entryTs: LocalDateTime,
    @get:JvmName("exitTs") val exitTs: LocalDateTime,
    @get:JvmName("entryPrice") val entryPrice: Double,
    @get:JvmName("exitPrice") val exitPrice: Double,
    @get:JvmName("returnPct") val returnPct: Double,
    @get:JvmName("exitReason") val exitReason: ExitReason,
    @get:JvmName("success") val success: Boolean,
    @get:JvmName("instrument") val instrument: String?,
    @get:JvmName("quantity") val quantity: Long = 0L,
    @get:JvmName("profitAmount") val profitAmount: Double = 0.0,
    @get:JvmName("feeAmount") val feeAmount: Double = 0.0
) {

    /** 가격만 채운 거래. 금액 필드는 자본 환산 패스가 나중에 채웁니다. */
    constructor(
        entryTs: LocalDateTime,
        exitTs: LocalDateTime,
        entryPrice: Double,
        exitPrice: Double,
        returnPct: Double,
        exitReason: ExitReason,
        success: Boolean,
        instrument: String?
    ) : this(entryTs, exitTs, entryPrice, exitPrice, returnPct, exitReason, success, instrument,
        0L, 0.0, 0.0)

    fun withMoney(quantity: Long, profitAmount: Double, feeAmount: Double): TradeRecord =
        copy(quantity = quantity, profitAmount = profitAmount, feeAmount = feeAmount)

    /** 진입 시각의 '시' — 시간대별 실패 분석에서 묶는 기준입니다. */
    fun entryHour(): Int = entryTs.hour
}
