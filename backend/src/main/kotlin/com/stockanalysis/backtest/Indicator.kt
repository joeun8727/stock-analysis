package com.stockanalysis.backtest

/**
 * 봉 하나에서 읽을 수 있는 지표들.
 *
 * 엑셀 15개 열에 **위치로** 대응합니다 — 이동평균 열의 헤더가 `5`, `10`, `20` 같은 맨숫자라
 * 이름으로는 구분이 안 되기 때문입니다.
 *
 * 뒤의 숫자는 **분이 아니라 봉 개수**입니다. MA20은 직전 20**봉** 종가 평균이라, 3분봉이면
 * 60분치, 1분봉이면 20분치를 담습니다. 같은 전략도 봉 길이가 다르면 다른 전략이 됩니다.
 */
enum class Indicator {
    OPEN,
    HIGH,
    LOW,
    CLOSE,
    MA5,
    MA10,
    MA20,
    MA60,
    VOLUME,
    VOL_MA5,
    VOL_MA20,
    VOL_MA60,
    VOL_MA120
}
