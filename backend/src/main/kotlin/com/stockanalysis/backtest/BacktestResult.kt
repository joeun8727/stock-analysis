package com.stockanalysis.backtest

import java.time.LocalDateTime

/**
 * 백테스트 결과 전체: 대표 지표, 거래별 기록, 자본 곡선, 그리고 **손실이 어디서 났는지**
 * (청산 사유별·시간대별) 분석.
 */
data class BacktestResult(
    @get:JvmName("summary") val summary: Summary,
    @get:JvmName("money") val money: MoneySummary,
    @get:JvmName("diagnosis") val diagnosis: Diagnosis,
    @get:JvmName("trades") val trades: List<TradeRecord>,
    @get:JvmName("equityCurve") val equityCurve: List<EquityPoint>,
    @get:JvmName("failureAnalysis") val failureAnalysis: FailureAnalysis
) {

    /** 무엇이 잘못됐는지 평이한 한국어로 읽어준 것. 모델 호출 없이 결정론적 규칙만 씁니다. */
    data class Diagnosis(
        @get:JvmName("headline") val headline: String,
        @get:JvmName("findings") val findings: List<Finding>
    )

    data class Finding(
        @get:JvmName("severity") val severity: Severity,
        @get:JvmName("title") val title: String,
        @get:JvmName("detail") val detail: String,
        @get:JvmName("suggestion") val suggestion: String
    )

    /** 심각한 순. UI가 이 값으로 정렬하고 색을 입힙니다. */
    enum class Severity {
        HIGH,
        MEDIUM,
        INFO
    }

    /**
     * 같은 실행을 원화로 본 것. 전략의 `CapitalSpec` 기준이고 금액은 **수수료를 뺀 뒤**입니다.
     *
     * [unaffordableTrades]는 예산으로 1주도 못 산 시그널의 수입니다 — 복리 모드에서 이 값이
     * 늘어나면 잔고가 1주 값 아래로 깎여 내려갔다는 뜻입니다.
     */
    data class MoneySummary(
        @get:JvmName("mode") val mode: String,
        @get:JvmName("investAmount") val investAmount: Double,
        @get:JvmName("feeRatesPct") val feeRatesPct: Map<String, Double>,
        @get:JvmName("totalProfitAmount") val totalProfitAmount: Double,
        @get:JvmName("totalFeeAmount") val totalFeeAmount: Double,
        @get:JvmName("avgProfitPerTrade") val avgProfitPerTrade: Double,
        @get:JvmName("finalBalance") val finalBalance: Double,
        @get:JvmName("returnOnCapitalPct") val returnOnCapitalPct: Double,
        @get:JvmName("bestTradeAmount") val bestTradeAmount: Double,
        @get:JvmName("worstTradeAmount") val worstTradeAmount: Double,
        @get:JvmName("unaffordableTrades") val unaffordableTrades: Int
    )

    /**
     * 대표 지표. 수익률은 전부 퍼센트입니다.
     *
     * [winRate]는 **수수료를 빼지 않은 가격 기준**입니다 — 요율이 높으면 "이긴" 거래가
     * 금액으로는 손실일 수 있어서, 판단은 [MoneySummary] 쪽을 봐야 합니다.
     */
    data class Summary(
        @get:JvmName("totalTrades") val totalTrades: Int,
        @get:JvmName("wins") val wins: Int,
        @get:JvmName("losses") val losses: Int,
        @get:JvmName("winRate") val winRate: Double,
        @get:JvmName("lossRate") val lossRate: Double,
        @get:JvmName("totalReturnPct") val totalReturnPct: Double,
        @get:JvmName("compoundedReturnPct") val compoundedReturnPct: Double,
        @get:JvmName("avgWinPct") val avgWinPct: Double,
        @get:JvmName("avgLossPct") val avgLossPct: Double,
        @get:JvmName("maxDrawdownPct") val maxDrawdownPct: Double,
        @get:JvmName("maxConsecutiveLosses") val maxConsecutiveLosses: Int
    )

    data class EquityPoint(
        @get:JvmName("ts") val ts: LocalDateTime,
        @get:JvmName("equity") val equity: Double
    )

    /** 실패가 어디에 몰려 있는지. */
    data class FailureAnalysis(
        @get:JvmName("byExitReason") val byExitReason: Map<ExitReason, ReasonStat>,
        @get:JvmName("byHour") val byHour: Map<Int, HourStat>,
        @get:JvmName("worstTrades") val worstTrades: List<TradeRecord>
    )

    data class ReasonStat(
        @get:JvmName("count") val count: Int,
        @get:JvmName("avgReturnPct") val avgReturnPct: Double,
        @get:JvmName("totalReturnPct") val totalReturnPct: Double
    )

    data class HourStat(
        @get:JvmName("lossCount") val lossCount: Int,
        @get:JvmName("avgLossPct") val avgLossPct: Double
    )
}
