package com.stockanalysis.domain

/**
 * 하루치 실투자 세션이 어디까지 왔는지.
 *
 * 앞으로만 갑니다. [HALTED]는 그날의 종착점이고 **사람이 확인해야 풀립니다** — 여기 오는 두
 * 경우(킬 스위치, 기록과 실제 잔고 불일치)는 둘 다 시스템이 추측해서 사고파는 것보다
 * 멈추는 쪽이 나은 상황이기 때문입니다.
 */
enum class LiveState {
    /** 준비 완료, 장전 관측 시작을 기다리는 중. */
    ARMED,

    /** 장전 구간에서 선물 시세를 모으는 중. */
    WATCHING,

    /** 장전 변화율이 임계치에 못 미쳐 오늘은 거래 없음. */
    SKIPPED,

    /** 매수 주문을 냈고 체결을 기다리는 중. */
    ENTRY_PENDING,

    /** ETF 보유 중. 청산 조건을 감시합니다. */
    HOLDING,

    /** 매도 주문을 냈고 체결을 기다리는 중. */
    EXIT_PENDING,

    /** 청산 완료, 손익 정산됨. */
    CLOSED,

    /** 중단됨. 오늘은 더 이상 주문이 나가지 않습니다. */
    HALTED;

    /** 어느 쪽으로든 하루가 끝났는지. */
    fun isFinished(): Boolean = this == SKIPPED || this == CLOSED || this == HALTED

    /** 돈이 걸려 있는 상태인지 — 포지션이 열려 있거나 주문이 떠 있음. */
    fun holdsExposure(): Boolean = this == ENTRY_PENDING || this == HOLDING || this == EXIT_PENDING
}
