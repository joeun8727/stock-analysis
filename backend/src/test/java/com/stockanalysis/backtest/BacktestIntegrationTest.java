package com.stockanalysis.backtest;

import com.stockanalysis.data.ExcelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end over the real KODEX 레버리지 workbook: parse -> engine -> assert sane metrics.
 * No database involved (parser + engine only), so this runs hermetically in {@code ./gradlew test}.
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

        // Bars must be chronologically consistent once wrapped in a series.
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
