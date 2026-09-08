package com.stockanalysis.backtest.spec

/**
 * 매매 방향. 지금은 롱만 있습니다.
 *
 * 공매도를 안 만든 게 아니라 필요가 없어서입니다 — 인버스 ETF 자체가 하락에 베팅하는
 * 상품이라, 인버스를 **사는 것**이 이미 하락 뷰를 표현합니다.
 */
enum class Position {
    LONG
}
