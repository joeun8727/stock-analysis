package com.stockanalysis.backtest.spec

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * 매매 로직 전체. **이 프로젝트에서 규칙의 유일한 원본**입니다.
 *
 * `strategy.spec_json`에 그대로 저장되고, LLM 추천도 같은 포맷을 만들며, 무엇보다
 * **백테스트·모의투자·실전투자가 전부 이 하나를 읽습니다**. 실투자 쪽에 익절·손절 같은 필드를
 * 복제해두면 두 벌이 되는 순간 어긋나고, 그러면 백테스트가 의미를 잃습니다.
 *
 * 기본값이 있는 필드를 새로 추가할 때 주의: 예전에 저장된 행에는 그 키가 아예 없어서
 * Hibernate가 재직렬화 결과를 다르게 보고 **조용한 UPDATE**를 날립니다. 쓰기 트랜잭션에서
 * 전략을 읽는 곳은 반드시 `entityManager.detach`를 거쳐야 합니다(`BacktestService` 참고).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class StrategySpec {

    var name: String? = null

    /** USER 또는 LLM. 누가 만든 규칙인지 표시용입니다. */
    var source: String? = "USER"

    // 아래 필드들은 전부 `nulls = SKIP`입니다. 기본값이 있는 필드를 나중에 추가했기 때문에
    // 예전에 저장된 spec_json에는 키가 없거나 null인 행이 있고, Kotlin 비-널 타입은 null을
    // 거부하므로 Jackson 단계에서 흘려보내 기본값을 살립니다.

    @JsonSetter(nulls = Nulls.SKIP)
    var position: Position = Position.LONG

    @JsonSetter(nulls = Nulls.SKIP)
    var targetType: TargetType = TargetType.SINGLE

    @JsonSetter(nulls = Nulls.SKIP)
    var entry: ConditionGroup = ConditionGroup()

    @JsonSetter(nulls = Nulls.SKIP)
    var exit: ExitSpec = ExitSpec()

    @JsonSetter(nulls = Nulls.SKIP)
    var premarket: PremarketSpec = PremarketSpec()

    @JsonSetter(nulls = Nulls.SKIP)
    var capital: CapitalSpec = CapitalSpec()

    /**
     * ETF 장전추세 모드로 돌려야 하는 전략인지.
     *
     * 이 모드에서는 [entry] 조건이 **무시됩니다** — 그날 무엇을 살지는 장전 선물 추세가 정하고,
     * 매수 시점도 장 시작으로 고정됩니다.
     */
    fun usesPremarket(): Boolean = targetType == TargetType.ETF && premarket.enabled
}
