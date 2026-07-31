package com.stockanalysis.run;

import com.stockanalysis.backtest.TradeRecord;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** Batch-writes and reads {@code trade} rows (JdbcTemplate, since IDENTITY ids block JPA batching). */
@Component
public class TradeJdbc {

    private static final String INSERT = """
            INSERT INTO trade
                (backtest_run_id, entry_ts, exit_ts, entry_price, exit_price, return_pct, exit_reason, instrument,
                 success, quantity, profit_amount, fee_amount)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT = """
            SELECT entry_ts, exit_ts, entry_price, exit_price, return_pct, exit_reason, instrument, success,
                   quantity, profit_amount, fee_amount
            FROM trade WHERE backtest_run_id = ? ORDER BY entry_ts ASC
            """;

    private static final RowMapper<StoredResult.StoredTrade> MAPPER = (rs, n) -> new StoredResult.StoredTrade(
            rs.getObject("entry_ts", LocalDateTime.class).toString(),
            rs.getObject("exit_ts", LocalDateTime.class).toString(),
            rs.getDouble("entry_price"),
            rs.getDouble("exit_price"),
            rs.getDouble("return_pct"),
            rs.getString("exit_reason"),
            rs.getBoolean("success"),
            rs.getString("instrument"),
            rs.getLong("quantity"),
            rs.getDouble("profit_amount"),
            rs.getDouble("fee_amount")
    );

    private final JdbcTemplate jdbc;

    public TradeJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(long runId, List<TradeRecord> trades) {
        jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TradeRecord t = trades.get(i);
                ps.setLong(1, runId);
                ps.setObject(2, t.entryTs());
                ps.setObject(3, t.exitTs());
                ps.setDouble(4, t.entryPrice());
                ps.setDouble(5, t.exitPrice());
                ps.setDouble(6, t.returnPct());
                ps.setString(7, t.exitReason().name());
                ps.setString(8, t.instrument());
                ps.setBoolean(9, t.success());
                ps.setLong(10, t.quantity());
                ps.setDouble(11, t.profitAmount());
                ps.setDouble(12, t.feeAmount());
            }

            @Override
            public int getBatchSize() {
                return trades.size();
            }
        });
    }

    public List<StoredResult.StoredTrade> findByRun(long runId) {
        return jdbc.query(SELECT, MAPPER, runId);
    }
}
