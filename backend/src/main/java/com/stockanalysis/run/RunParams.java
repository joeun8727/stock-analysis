package com.stockanalysis.run;

/**
 * 실행을 무엇으로 요청했는지. {@code backtest_run.params}에 저장됩니다.
 * {@code fromDate}/{@code toDate}는 요청한 달력 경계(null이면 데이터셋 전체 구간)이고,
 * {@code barFromTs}/{@code barToTs}는 실제로 쓰인 첫 봉과 마지막 봉 — 그 구간이 해석된 결과입니다.
 *
 * <p>날짜를 ISO 문자열로 두는 이유는 {@link StoredResult}가 {@code java.time}을 피하는 것과
 * 같습니다 — Hibernate의 JSON 매퍼에 Java time 모듈이 없습니다.
 */
public record RunParams(
        String fromDate,
        String toDate,
        String barFromTs,
        String barToTs,
        int barCount
) {
}
