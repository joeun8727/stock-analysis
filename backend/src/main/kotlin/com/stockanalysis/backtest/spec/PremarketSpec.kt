package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * ETF 전략의 장전 선물추세 게이트.
 *
 * 켜두면 매 거래일 `[startTime, endTime)` 구간의 선물 변화율을 재서, [thresholdPct] 이상
 * 오르면 **레버리지**, 그만큼 내리면 **인버스**를 사고, 어중간하면 그날은 건너뜁니다.
 *
 * 이 판단은 백테스트와 실투자가 같은 코드(`PremarketDecider`)로 합니다 — 백테스트는
 * 엑셀 선물 봉의 종가를, 실투자는 한국투자증권 API에서 폴링한 시세를 넘길 뿐입니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class PremarketSpec {

    var enabled: Boolean = false

    /** 관측 시작 시각. 기본 08:45 — 선물 장전 구간의 시작입니다. */
    var startTime: String = "08:45"

    /** 관측 종료 시각(이 시각은 포함하지 않음). 기본 09:00. */
    var endTime: String = "09:00"

    /** 방향을 정하기에 충분한 변화율. 부호는 무시하고 크기만 봅니다. */
    var thresholdPct: Double = 0.1
}
