package com.stockanalysis.data;

import com.stockanalysis.backtest.Bar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** 파싱한 봉을 {@code price_bar}에 청크 단위로 배치 insert합니다. */
@Component
public class PriceBarWriter {

    /**
     * {@code executeBatch} 한 번에 넣는 행 수. 반드시 제한이 있어야 합니다: JDBC url이
     * {@code rewriteBatchedStatements=true}라서 Connector/J가 배치 하나를 multi-values INSERT
     * 하나로 접고, 패킷을 만드는 동안 바인딩 값을 전부 메모리에 들고 있습니다. 시리즈 전체를
     * 넘기는 방식은 3분봉 크기(약 15만 행)에서는 동작했지만 1분봉 크기(약 45만 행)에서 힙이
     * 터졌습니다 — {@code executeBatchWithMultiValuesClause} 안에서 {@code OutOfMemoryError}.
     * 청킹하면 파일 크기와 무관하게 최대 사용량이 평평해지고, 청크 하나가 여전히 왕복 한 번이라
     * rewrite의 이득도 그대로 남습니다.
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
            // LocalDateTime을 직접 바인딩해서 Connector/J가 벽시계 값을 그대로 저장하게 합니다
            // (서버 타임존 변환 없음). JPA가 DATETIME을 매핑하는 방식과 맞춥니다.
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
