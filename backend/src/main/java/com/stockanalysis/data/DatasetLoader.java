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

/** 데이터셋의 봉을 {@code price_bar}에서 시간 오름차순으로 읽어 {@link BarSeries}로 만듭니다. */
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
     * 양끝을 포함하는 달력 구간 안의 봉을 불러옵니다. 어느 쪽이든 null이면 "열린 구간"입니다.
     * 필터링은 SQL에서 하므로 한 달치 백테스트가 15만 행을 메모리로 끌어오지 않습니다.
     *
     * <p>{@code to}는 그날 하루 전체를 포함하므로 조건이 {@code ts < to + 1 day}입니다.
     * 경계값은 {@code price_bar.ts}를 기록할 때 쓴 KST 벽시계 규약에 맞추기 위해
     * {@code LocalDateTime}으로 바인딩합니다({@code Timestamp}는 절대 쓰지 않습니다).
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
