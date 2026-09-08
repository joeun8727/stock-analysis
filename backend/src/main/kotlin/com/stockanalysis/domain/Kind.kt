package com.stockanalysis.domain

/**
 * 데이터셋의 역할. ETF는 레버리지/인버스 쌍으로 다니고, 나머지는 SINGLE입니다.
 *
 * ETF 그룹에서 슬롯을 가르는 기준이라, 한 그룹에 같은 종류가 둘 있을 수 없습니다.
 */
enum class Kind {
    LEVERAGE,
    INVERSE,
    SINGLE
}
