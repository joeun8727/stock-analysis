package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * 시간대별 익절·손절 덮어쓰기. [ExitSpec]이 들고 있습니다.
 *
 * 구간은 KST 벽시계 기준 `[startTime, endTime)`이고, **진입 봉이 아니라 지금 보고 있는 봉의
 * 시각**과 맞춰봅니다 — 그래서 09:20에 잡은 포지션도 10시를 넘기면 10시 밴드의 선으로 바뀝니다.
 *
 * 퍼센트가 null이면 "이 밴드는 그 값을 안 건드림"이라는 뜻이고 [ExitSpec]의 기본값을 씁니다.
 *
 * 시각 파싱 결과를 캐시합니다 — 16만 봉짜리 시리즈에서 **봉마다** 호출되기 때문입니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class TimeBand {

    var startTime: String? = null
        set(value) {
            field = value
            parsed = false
        }

    var endTime: String? = null
        set(value) {
            field = value
            parsed = false
        }

    var takeProfitPct: Double? = null
    var stopLossPct: Double? = null

    @JsonIgnore
    private var startCache: LocalTime? = null

    @JsonIgnore
    private var endCache: LocalTime? = null

    @JsonIgnore
    private var parsed = false

    constructor()

    constructor(startTime: String?, endTime: String?, takeProfitPct: Double?, stopLossPct: Double?) {
        this.startTime = startTime
        this.endTime = endTime
        this.takeProfitPct = takeProfitPct
        this.stopLossPct = stopLossPct
    }

    /** `[start, end)`에 들어오면 true. 형식이 깨졌거나 뒤집힌 구간은 **절대 매치되지 않습니다**. */
    fun covers(t: LocalTime): Boolean {
        parse()
        val start = startCache ?: return false
        val end = endCache ?: return false
        if (!start.isBefore(end)) {
            return false
        }
        return !t.isBefore(start) && t.isBefore(end)
    }

    private fun parse() {
        if (parsed) {
            return
        }
        startCache = parseOrNull(startTime)
        endCache = parseOrNull(endTime)
        parsed = true
    }

    companion object {
        /** HH:mm이 아니면 예외 대신 null — 검증은 저장 시점에 합니다(`StrategyService.validateBands`). */
        @JvmStatic
        fun parseOrNull(s: String?): LocalTime? {
            if (s.isNullOrBlank()) {
                return null
            }
            return try {
                LocalTime.parse(s.trim())
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}
