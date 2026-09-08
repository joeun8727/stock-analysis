package com.stockanalysis.run;

import com.stockanalysis.backtest.BacktestResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 백테스트 API 응답 형태들. */
public final class BacktestDtos {

    private BacktestDtos() {
    }

    /** POST /api/backtests와 GET /api/backtests/{id}가 돌려주는 전체 결과. */
    public record BacktestResponse(
            Long runId,
            Long strategyId,
            String strategyName,
            Long datasetId,
            String datasetSymbol,
            String createdAt,
            RunParams params,
            BacktestResult.Summary summary,
            BacktestResult.MoneySummary money,
            BacktestResult.Diagnosis diagnosis,
            List<StoredResult.StoredEquityPoint> equityCurve,
            Map<String, BacktestResult.ReasonStat> failureByReason,
            Map<String, BacktestResult.HourStat> failureByHour,
            List<StoredResult.StoredTrade> worstTrades,
            List<StoredResult.StoredTrade> trades
    ) {
    }

    /** 이력 목록용 간략 행. */
    public record BacktestListItem(
            Long runId,
            Long strategyId,
            String strategyName,
            Long datasetId,
            String datasetSymbol,
            String createdAt,
            RunParams params,
            BacktestResult.Summary summary,
            BacktestResult.MoneySummary money
    ) {
    }

    /**
     * POST /api/backtests의 요청 본문. 단일 종목이면 datasetId, ETF 장전이면 groupId를 씁니다.
     * {@code fromDate}/{@code toDate}는 양끝을 포함하는 ISO 날짜(yyyy-MM-dd)이며, 둘 중 하나를
     * 빼면 데이터셋의 전체 구간이 됩니다.
     */
    public record RunRequest(Long strategyId, Long datasetId, Long groupId,
                             LocalDate fromDate, LocalDate toDate) {
    }
}
