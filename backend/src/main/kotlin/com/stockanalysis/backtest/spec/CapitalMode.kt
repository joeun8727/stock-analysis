package com.stockanalysis.backtest.spec

/** 투자금을 거래마다 어떻게 굴릴지. */
enum class CapitalMode {
    /** 매번 같은 금액으로 삽니다. 수익은 따로 빼두고 재투자하지 않습니다. */
    FIXED,

    /** 수익을 다시 넣습니다. 매 진입에 현재 잔고 전부를 씁니다. */
    COMPOUND
}
