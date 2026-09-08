package com.stockanalysis.backtest.spec

/**
 * 전략이 무엇을 대상으로 하는지.
 *
 * - [SINGLE]: 데이터셋 하나(일반 종목이나 단일 ETF)를 매수 조건으로 매매합니다.
 * - [ETF]: 레버리지/인버스 쌍. [PremarketSpec]을 켜면 장전 선물 추세가 그날 어느 쪽을
 *   살지 정하고, **매수 조건은 무시됩니다**.
 */
enum class TargetType {
    SINGLE,
    ETF
}
