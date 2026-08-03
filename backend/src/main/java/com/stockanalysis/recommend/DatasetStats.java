package com.stockanalysis.recommend;

import com.stockanalysis.domain.Dataset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Computes a compact statistical summary of a dataset's bars to feed the LLM prompt. */
@Component
public class DatasetStats {

    private final JdbcTemplate jdbc;

    public DatasetStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stats(
            String symbol,
            String market,
            String kind,
            int barIntervalMinutes,
            long barCount,
            String fromTs,
            String toTs,
            long tradingDays,
            double minClose,
            double maxClose,
            double avgClose,
            double avgRangePct,
            double avgVolume
    ) {
    }

    public Stats compute(Dataset ds) {
        long id = ds.getId();
        return jdbc.queryForObject("""
                SELECT
                    COUNT(*)                                             AS bar_count,
                    MIN(ts)                                              AS from_ts,
                    MAX(ts)                                              AS to_ts,
                    COUNT(DISTINCT DATE(ts))                             AS trading_days,
                    MIN(close)                                          AS min_close,
                    MAX(close)                                          AS max_close,
                    AVG(close)                                          AS avg_close,
                    AVG((high - low) / NULLIF(close, 0)) * 100          AS avg_range_pct,
                    AVG(volume)                                         AS avg_volume
                FROM price_bar WHERE dataset_id = ?
                """,
                (rs, n) -> new Stats(
                        ds.getSymbol(), ds.getMarket().name(), ds.getKind().name(),
                        ds.getBarIntervalMinutes(),
                        rs.getLong("bar_count"),
                        String.valueOf(rs.getObject("from_ts")),
                        String.valueOf(rs.getObject("to_ts")),
                        rs.getLong("trading_days"),
                        rs.getDouble("min_close"), rs.getDouble("max_close"), rs.getDouble("avg_close"),
                        rs.getDouble("avg_range_pct"), rs.getDouble("avg_volume")),
                id);
    }
}
