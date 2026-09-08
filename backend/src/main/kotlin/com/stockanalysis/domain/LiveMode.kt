package com.stockanalysis.domain

/**
 * 실투자가 어디까지 나가도 되는지.
 *
 * `KIS_MODE` 환경변수로만 바뀝니다 — API로 바꿀 수 있게 두지 않은 건, 실계좌 전환이
 * 클릭 한 번이 아니라 **의도적인 재기동**을 거치게 하기 위해서입니다.
 */
enum class LiveMode {
    /** 판단과 기록만 하고 주문은 보내지 않습니다. 시세는 실제 시세를 씁니다. */
    DRY_RUN,

    /** 모의투자 서버에 실제 주문. */
    PAPER,

    /** 실계좌에 진짜 주문. 진짜 돈입니다. */
    REAL;

    /** 주문이 프로세스 밖으로 실제로 나가는지. */
    fun placesOrders(): Boolean = this != DRY_RUN

    fun isReal(): Boolean = this == REAL
}
