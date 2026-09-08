package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * 전략이 얼마를 굴리는지. 결과를 퍼센트가 아니라 **원화 금액**으로 볼 수 있게 하는 값입니다.
 *
 * [amount]는 [CapitalMode.FIXED]에서는 거래당 예산, [CapitalMode.COMPOUND]에서는 시작 잔고입니다.
 *
 * **수수료는 일부러 여기 없습니다.** 수수료는 전략이 아니라 종목에 붙는 성질이라
 * (레버리지와 인버스가 서로 다른 요율일 수 있고 장전 모드는 둘 다 매매합니다)
 * `dataset.fee_rate_pct`에 있고 `FeeSchedule`로 엔진에 들어옵니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class CapitalSpec {

    var amount: Double = 10_000_000.0

    /**
     * JSON에 `null`이 와도 기본값을 유지합니다([Nulls.SKIP]).
     *
     * 예전에 저장된 `spec_json`에는 이 키가 아예 없거나 null인 경우가 있는데, Kotlin 비-널
     * 타입은 null 대입을 거부하므로 Jackson 단계에서 흘려보냅니다. Java 시절 세터가
     * `mode == null ? FIXED : mode`로 하던 일과 같습니다.
     */
    @JsonSetter(nulls = Nulls.SKIP)
    var mode: CapitalMode = CapitalMode.FIXED
}
