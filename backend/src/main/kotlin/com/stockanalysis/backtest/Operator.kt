package com.stockanalysis.backtest

/**
 * 두 피연산자를 비교하는 방법.
 *
 * `CROSS_*`는 직전 봉까지 함께 봅니다(예: 어제까지는 아래에 있었는데 지금 위로 올라섰는가).
 * 그래서 시리즈의 **첫 봉에서는 돌파를 판정할 수 없고** 항상 false입니다.
 */
enum class Operator {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    CROSS_ABOVE,
    CROSS_BELOW
}
