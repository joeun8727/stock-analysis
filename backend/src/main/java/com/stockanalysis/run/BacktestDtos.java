package com.stockanalysis.run;

import com.stockanalysis.backtest.BacktestResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** API response shapes for backtests. */
public final class BacktestDtos {

    private BacktestDtos() {
    }

    /** Full result returned by POST /api/backtests and GET /api/backtests/{id}. */
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

    /** Compact row for the history list. */
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
     * Request body for POST /api/backtests. Use datasetId for single, groupId for ETF pre-market.
     * {@code fromDate}/{@code toDate} are inclusive ISO dates (yyyy-MM-dd); omit either for the
     * dataset's whole span.
     */
    public record RunRequest(Long strategyId, Long datasetId, Long groupId,
                             LocalDate fromDate, LocalDate toDate) {
    }
}
