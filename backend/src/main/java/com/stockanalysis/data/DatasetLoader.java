package com.stockanalysis.data;

import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.BarSeries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Loads a dataset's bars from {@code price_bar} in ascending time order into a {@link BarSeries}. */
@Component
public class DatasetLoader {

    private static final String SELECT = """
            SELECT ts, open, high, low, close,
                   ma5, ma10, ma20, ma60, volume, vol_ma5, vol_ma20, vol_ma60, vol_ma120
            FROM price_bar
            WHERE dataset_id = ?
            """;

    private static final RowMapper<Bar> BAR_MAPPER = (rs, rowNum) -> new Bar(
            rs.getObject("ts", java.time.LocalDateTime.class),
            rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"),
            nan(rs, "ma5"), nan(rs, "ma10"), nan(rs, "ma20"), nan(rs, "ma60"),
            nan(rs, "volume"), nan(rs, "vol_ma5"), nan(rs, "vol_ma20"), nan(rs, "vol_ma60"), nan(rs, "vol_ma120")
    );

    private final JdbcTemplate jdbc;

    public DatasetLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BarSeries load(long datasetId) {
        return load(datasetId, null, null);
    }

    /**
     * Loads bars within an inclusive calendar range; either bound may be null for "open ended".
     * Filtering happens in SQL so a one-month backtest doesn't pull 150k rows into memory.
     *
     * <p>{@code to} is inclusive of the whole day, so the predicate is {@code ts < to + 1 day}.
     * Bounds are bound as {@code LocalDateTime} (never {@code Timestamp}) to match the KST
     * wall-clock convention {@code price_bar.ts} is written with.
     */
    public BarSeries load(long datasetId, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>();
        args.add(datasetId);
        if (from != null) {
            sql.append(" AND ts >= ?");
            args.add(from.atStartOfDay());
        }
        if (to != null) {
            sql.append(" AND ts < ?");
            args.add(to.plusDays(1).atStartOfDay());
        }
        sql.append(" ORDER BY ts ASC");
        List<Bar> bars = jdbc.query(sql.toString(), BAR_MAPPER, args.toArray());
        return new BarSeries(bars);
    }

    private static double nan(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? Double.NaN : v;
    }
}
