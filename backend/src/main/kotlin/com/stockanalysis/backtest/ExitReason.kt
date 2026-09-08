package com.stockanalysis.backtest

/**
 * 포지션이 닫힌 이유. **선언 순서가 곧 엔진의 검사 우선순위**입니다.
 *
 * 한 봉에서 손절과 익절이 모두 닿을 수 있는데, 봉 안에서 어느 쪽이 먼저였는지는 알 수 없으므로
 * 위에 있는 손절이 이깁니다(보수적).
 */
enum class ExitReason {
    /** 손절선 도달. */
    STOP_LOSS,

    /** 익절선 도달. */
    TAKE_PROFIT,

    /** 매도 조건(지표 등) 성립. */
    SIGNAL,

    /** 최대 보유 봉 수 초과. */
    TIME,

    /** 장 마감 청산. */
    DAY_END,

    /** 더 볼 봉이 없어서 강제 종료 — 백테스트에만 나옵니다. */
    END_OF_DATA
}
