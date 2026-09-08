package com.stockanalysis.backtest

/**
 * 종목별 수수료율(편도 %).
 *
 * 전략이 아니라 **종목**에 붙는 이유: 레버리지 ETF와 인버스가 서로 다른 요율일 수 있고,
 * 장전 모드는 하루에 둘 중 하나를 고르므로 실행당 요율 하나로는 반드시 틀립니다.
 *
 * 키는 [TradeRecord.instrument]와 같습니다 — 단일 데이터셋 실행은 `null`, 그 외에는
 * `LEVERAGE`/`INVERSE`. 선물 시리즈는 추세를 읽을 뿐 매매하지 않으므로 요율이 없습니다.
 */
data class FeeSchedule(
    @get:JvmName("singlePct") val singlePct: Double,
    @get:JvmName("leveragePct") val leveragePct: Double,
    @get:JvmName("inversePct") val inversePct: Double
) {

    /** 거래의 instrument에 맞는 요율. 모르는 값이면 단일 데이터셋 요율로 떨어집니다. */
    fun rateFor(instrument: String?): Double = when (instrument) {
        null -> singlePct
        LEVERAGE -> leveragePct
        INVERSE -> inversePct
        else -> singlePct
    }

    /** 실제로 적용된 요율을 instrument별로 모읍니다 — 결과 화면에 보여주기 위한 것입니다. */
    fun ratesFor(trades: Iterable<TradeRecord>): Map<String, Double> {
        val used = LinkedHashMap<String, Double>()
        for (t in trades) {
            val key = t.instrument ?: SINGLE
            used.putIfAbsent(key, rateFor(t.instrument))
        }
        return used
    }

    companion object {
        const val LEVERAGE = "LEVERAGE"
        const val INVERSE = "INVERSE"

        /** 단일 데이터셋 실행에서 instrument가 null일 때 보고용으로 쓰는 키. */
        const val SINGLE = "SINGLE"

        /** 어디나 같은 요율 — 단일 데이터셋 실행과 테스트용. */
        @JvmStatic
        fun flat(pct: Double): FeeSchedule = FeeSchedule(pct, pct, pct)

        /** 수수료 없음. 가격만 보는 기준선입니다. */
        @JvmStatic
        fun free(): FeeSchedule = flat(0.0)

        @JvmStatic
        fun ofEtf(leveragePct: Double, inversePct: Double): FeeSchedule =
            FeeSchedule(0.0, leveragePct, inversePct)
    }
}
