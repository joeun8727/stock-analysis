package com.stockanalysis.run;

import com.stockanalysis.backtest.BacktestResult;

import java.util.List;
import java.util.Map;

/**
 * 백테스트 결과의 저장 형태({@code backtest_run.summary_json}에 들어갑니다).
 * 모든 타임스탬프는 ISO 문자열이고 enum 키도 문자열입니다 — Hibernate의 Jackson JSON 매퍼가
 * (Java time 모듈이 없어서) 추가 설정 없이 직렬화할 수 있게 하려고입니다.
 */
public record StoredResult(
        BacktestResult.Summary summary,
        BacktestResult.MoneySummary money,
        BacktestResult.Diagnosis diagnosis,
        List<StoredEquityPoint> equityCurve,
        Map<String, BacktestResult.ReasonStat> failureByReason,
        Map<String, BacktestResult.HourStat> failureByHour,
        List<StoredTrade> worstTrades
) {

    public record StoredEquityPoint(String ts, double equity) {
    }

    public record StoredTrade(
            String entryTs,
            String exitTs,
            double entryPrice,
            double exitPrice,
            double returnPct,
            String exitReason,
            boolean success,
            String instrument,
            long quantity,
            double profitAmount,
            double feeAmount
    ) {
    }
}
