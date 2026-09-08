package com.stockanalysis.domain

/** 데이터셋이 속한 시장. 업로드 시 이 값에 따라 `data/` 아래 저장 폴더가 갈립니다. */
enum class Market {
    FUTURES,
    ETF,
    NORMAL
}
