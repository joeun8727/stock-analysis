package com.stockanalysis.backtest;

import com.stockanalysis.data.ExcelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 KODEX 레버리지 워크북으로 하는 종단 확인: 파싱 -> 엔진 -> 말이 되는 지표인지 검증.
 * DB가 필요 없어서(파서 + 엔진만) {@code ./gradlew test}에서 독립적으로 돕니다.
 */
class BacktestIntegrationTest {

    private static final Path DATA_FILE =
            Path.of("..", "data", "stock", "etf", "KODEX레버리지.xlsx");

    @Test
    void parsesRealFileAndProducesSaneBacktest() {
        assertTrue(Files.exists(DATA_FILE),
                "expected seed data at " + DATA_FILE.toAbsolutePath());

        List<Bar> bars = new ExcelParser().parse(DATA_FILE);
        assertTrue(bars.size() > 10_000, "expected many bars, got " + bars.size());

        // 시리즈로 감싸고 나면 봉이 시간순으로 일관돼야 합니다.
        BarSeries series = new BarSeries(bars);
        for (int i = 1; i < series.size(); i++) {
            assertTrue(!series.bars().get(i).ts().isBefore(series.bars().get(i - 1).ts()),
                    "series not ascending at index " + i);
        }

        BacktestResult result = new BacktestEngine().run(SampleStrategies.goldenCross(), series);
        BacktestResult.Summary s = result.summary();
        assertNotNull(s);

        System.out.printf("[integration] bars=%,d trades=%d winRate=%.2f%% lossRate=%.2f%% "
                        + "compounded=%.2f%% MDD=%.2f%%%n",
                series.size(), s.totalTrades(), s.winRate(), s.lossRate(),
                s.compoundedReturnPct(), s.maxDrawdownPct());
        System.out.println("[integration] failure by reason: " + result.failureAnalysis().byExitReason());

        assertTrue(s.totalTrades() > 0, "golden cross should produce trades over 140k bars");
        assertTrue(s.winRate() >= 0.0 && s.winRate() <= 100.0);
        assertTrue(Math.abs((s.wins() + s.losses()) - s.totalTrades()) == 0);
    }
}
