package com.stockanalysis.backtest.spec

/**
 * 피연산자가 어느 시리즈에서 값을 읽는지.
 *
 * [FUTURES]는 **타임스탬프가 정확히 일치하는** 선물 봉을 찾습니다. 그래서 ETF와 선물의 봉
 * 길이가 다르면(1분봉 ETF + 3분봉 선물) 세 봉 중 둘은 짝을 못 찾아 조용히 `NaN`이 됩니다.
 */
enum class Source {
    PRIMARY,
    FUTURES
}
