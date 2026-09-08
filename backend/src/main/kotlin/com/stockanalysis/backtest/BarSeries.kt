package com.stockanalysis.backtest

import java.time.Duration
import java.time.LocalDateTime

/**
 * 시간 오름차순으로 정렬된 봉 시리즈. 선택적으로 **타임스탬프가 일치하는** 선물 시리즈를
 * 함께 들고 있어서 전략이 선물 지표를 참조할 수 있습니다.
 *
 * 원본 엑셀은 최신순이라 생성자에서 **항상 오름차순으로 다시 정렬**합니다.
 */
class BarSeries @JvmOverloads constructor(
    bars: List<Bar>,
    futuresByTs: Map<LocalDateTime, Bar> = emptyMap()
) {

    private val barList: List<Bar> = bars.sortedBy { it.ts }
    private val futures: Map<LocalDateTime, Bar> = futuresByTs.toMap()

    fun bars(): List<Bar> = barList

    fun size(): Int = barList.size

    /** 해당 시각의 선물 봉. 짝이 없으면 null이고, 그 조건은 `NaN`이 되어 발동하지 않습니다. */
    fun futuresAt(ts: LocalDateTime): Bar? = futures[ts]

    fun hasFutures(): Boolean = futures.isNotEmpty()

    /**
     * 봉 길이(분). 같은 날 안에서 연속한 봉 간격의 **최빈값**입니다.
     *
     * 최솟값이 아니라 최빈값을 쓰는 이유: 장중 결측이나 휴식 구간 하나가 답을 늘려버리지
     * 않게 하려고입니다. 날짜가 바뀌는 간격은 봉 길이와 무관하므로 건너뜁니다. 동률이면
     * 짧은 쪽, 아예 잴 수 없으면 [DEFAULT_INTERVAL_MINUTES].
     *
     * **엔진은 이 값을 보지 않습니다** — 엔진은 분이 아니라 봉을 셉니다. 메타데이터와 UI 문구,
     * LLM 프롬프트가 "1봉 = 몇 분"을 설명하는 데 씁니다.
     */
    fun inferIntervalMinutes(): Int {
        val counts = HashMap<Long, Int>()
        for (i in 1 until barList.size) {
            val prev = barList[i - 1]
            val cur = barList[i]
            if (prev.date() != cur.date()) {
                continue // 밤을 넘긴 간격은 봉 길이에 대해 아무것도 말해주지 않습니다
            }
            val minutes = Duration.between(prev.ts, cur.ts).toMinutes()
            if (minutes > 0) {
                counts.merge(minutes, 1, Int::plus)
            }
        }
        var best = 0L
        var bestCount = 0
        for ((minutes, count) in counts) {
            if (count > bestCount || (count == bestCount && minutes < best)) {
                best = minutes
                bestCount = count
            }
        }
        return if (best == 0L) DEFAULT_INTERVAL_MINUTES else minOf(best, Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        /** 시리즈가 너무 짧아 잴 수 없을 때 가정하는 봉 길이(시드 데이터가 전부 3분봉입니다). */
        const val DEFAULT_INTERVAL_MINUTES = 3
    }
}
