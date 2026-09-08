package com.stockanalysis.backtest.spec

/** 여러 조건을 묶는 방법. AND는 전부 맞아야, OR는 하나만 맞으면 됩니다. */
enum class Logic {
    AND,
    OR
}
