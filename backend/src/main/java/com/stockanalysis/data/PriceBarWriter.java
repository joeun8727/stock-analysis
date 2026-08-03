package com.stockanalysis.data;

import com.stockanalysis.backtest.Bar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** Batch-inserts parsed bars into {@code price_bar}, in chunks. */
@Component
public class PriceBarWriter {

    /**
     * Rows per {@code executeBatch}. This must stay bounded: the JDBC url sets
     * {@code rewriteBatchedStatements=true}, so Connector/J folds a whole batch into one
     * multi-values INSERT and holds every bound parameter of it in memory while building the
     * packet. Handing it the full series worked at 3-minute sizes (~150k rows) and blew the heap
     * at 1-minute sizes (~450k rows) — {@code OutOfMemoryError} inside
     * {@code executeBatchWithMultiValuesClause}. Chunking keeps the peak flat regardless of file
     * size while keeping the rewrite benefit, since each chunk is still one round trip.
     */
    private static final int CHUNK_SIZE = 2_000;

    private static final String INSERT = """
            INSERT INTO price_bar
                (dataset_id, ts, open, high, low, close,
                 ma5, ma10, ma20, ma60, volume, vol_ma5, vol_ma20, vol_ma60, vol_ma120)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public PriceBarWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBars(long datasetId, List<Bar> bars) {
        jdbc.batchUpdate(INSERT, bars, CHUNK_SIZE, (ps, b) -> {
            ps.setLong(1, datasetId);
            // Bind LocalDateTime directly so Connector/J stores the wall-clock value
            // as-is (no server-timezone conversion), matching how JPA maps DATETIME.
            ps.setObject(2, b.ts());
            ps.setDouble(3, b.open());
            ps.setDouble(4, b.high());
            ps.setDouble(5, b.low());
            ps.setDouble(6, b.close());
            setNullable(ps, 7, b.ma5());
            setNullable(ps, 8, b.ma10());
            setNullable(ps, 9, b.ma20());
            setNullable(ps, 10, b.ma60());
            setNullable(ps, 11, b.volume());
            setNullable(ps, 12, b.volMa5());
            setNullable(ps, 13, b.volMa20());
            setNullable(ps, 14, b.volMa60());
            setNullable(ps, 15, b.volMa120());
        });
    }

    private static void setNullable(PreparedStatement ps, int idx, double v) throws SQLException {
        if (Double.isNaN(v)) {
            ps.setNull(idx, Types.DOUBLE);
        } else {
            ps.setDouble(idx, v);
        }
    }
}
