package com.stockanalysis.tools;

import com.stockanalysis.backtest.BacktestEngine;
import com.stockanalysis.backtest.BacktestResult;
import com.stockanalysis.backtest.BarSeries;
import com.stockanalysis.backtest.SampleStrategies;
import com.stockanalysis.data.ExcelParser;

import java.nio.file.Path;

/**
 * 독립 실행 러너(Spring도 DB도 없음): 엑셀 파일을 파싱해 골든크로스 샘플 전략의 백테스트
 * 지표를 출력합니다. 사용법: {@code BacktestCliRunner <xlsx 경로>}.
 */
public final class BacktestCliRunner {

    public static void main(String[] args) {
        Path file = Path.of(args.length > 0 ? args[0] : "../data/stock/etf/KODEX레버리지.xlsx");
        System.out.println("Parsing " + file.toAbsolutePath() + " ...");

        long t0 = System.nanoTime();
        BarSeries series = new BarSeries(new ExcelParser().parse(file));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("Parsed %,d bars in %,d ms%n", series.size(), ms);

        BacktestResult result = new BacktestEngine().run(SampleStrategies.goldenCross(), series);
        BacktestResult.Summary s = result.summary();

        System.out.println("=== 골든크로스 단타 ===");
        System.out.printf("총 거래: %d | 성공: %d | 실패: %d%n", s.totalTrades(), s.wins(), s.losses());
        System.out.printf("성공률: %.2f%% | 실패율: %.2f%%%n", s.winRate(), s.lossRate());
        System.out.printf("누적수익(복리): %.2f%% | 평균이익: %.2f%% | 평균손실: %.2f%%%n",
                s.compoundedReturnPct(), s.avgWinPct(), s.avgLossPct());
        System.out.printf("최대낙폭(MDD): %.2f%% | 최대연속손실: %d%n",
                s.maxDrawdownPct(), s.maxConsecutiveLosses());
        System.out.println("실패 원인별: " + result.failureAnalysis().byExitReason());
    }
}
