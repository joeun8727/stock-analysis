package com.stockanalysis.run;

import com.stockanalysis.backtest.BacktestEngine;
import com.stockanalysis.backtest.BacktestResult;
import com.stockanalysis.backtest.BarSeries;
import com.stockanalysis.backtest.FeeSchedule;
import com.stockanalysis.backtest.TradeRecord;
import com.stockanalysis.data.DatasetLoader;
import com.stockanalysis.data.EtfGroupService;
import com.stockanalysis.domain.BacktestRun;
import com.stockanalysis.domain.BacktestRunRepository;
import com.stockanalysis.domain.Dataset;
import com.stockanalysis.domain.DatasetRepository;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.domain.StrategyRepository;
import com.stockanalysis.run.BacktestDtos.BacktestListItem;
import com.stockanalysis.run.BacktestDtos.BacktestResponse;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BacktestService {

    private final StrategyRepository strategyRepo;
    private final DatasetRepository datasetRepo;
    private final BacktestRunRepository runRepo;
    private final DatasetLoader datasetLoader;
    private final EtfGroupService etfGroupService;
    private final TradeJdbc tradeJdbc;
    private final EntityManager entityManager;
    private final BacktestEngine engine = new BacktestEngine();

    public BacktestService(StrategyRepository strategyRepo,
                           DatasetRepository datasetRepo,
                           BacktestRunRepository runRepo,
                           DatasetLoader datasetLoader,
                           EtfGroupService etfGroupService,
                           TradeJdbc tradeJdbc,
                           EntityManager entityManager) {
        this.strategyRepo = strategyRepo;
        this.datasetRepo = datasetRepo;
        this.runRepo = runRepo;
        this.datasetLoader = datasetLoader;
        this.etfGroupService = etfGroupService;
        this.tradeJdbc = tradeJdbc;
        this.entityManager = entityManager;
    }

    @Transactional
    public BacktestResponse run(long strategyId, Long datasetId, Long groupId, LocalDate from, LocalDate to) {
        Strategy strategy = strategyRepo.findById(strategyId)
                .orElseThrow(() -> new IllegalArgumentException("전략을 찾을 수 없습니다: " + strategyId));
        // Running a backtest must never rewrite the strategy. This method writes (the run + trades),
        // so the loaded Strategy stays managed and Hibernate dirty-checks spec_json by comparing the
        // re-serialized spec against the stored text. Any spec field with a default (capital,
        // premarket, …) is absent from older rows, so the round-trip differs and Hibernate would
        // flush a silent UPDATE — rewriting the user's spec and bumping updated_at on every run.
        entityManager.detach(strategy);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("시작일이 종료일보다 늦습니다: " + from + " ~ " + to);
        }

        BacktestResult result;
        long representativeDatasetId;
        String datasetLabel;
        RunParams params;

        if (strategy.getSpec().usesPremarket()) {
            if (groupId == null) {
                throw new IllegalArgumentException("ETF 장전추세 전략은 ETF 그룹을 선택해야 합니다.");
            }
            EtfGroupService.Resolved g = etfGroupService.resolve(groupId);
            BarSeries lev = datasetLoader.load(g.leverage().getId(), from, to);
            BarSeries inv = datasetLoader.load(g.inverse().getId(), from, to);
            BarSeries fut = datasetLoader.load(g.futures().getId(), from, to);
            if (fut.size() == 0 || lev.size() == 0 || inv.size() == 0) {
                throw new IllegalArgumentException(emptyMessage(from, to,
                        "그룹의 레버리지/인버스/선물 데이터가 비어 있습니다."));
            }
            // Each side carries its own commission, so the schedule is built per symbol.
            FeeSchedule fees = FeeSchedule.ofEtf(
                    g.leverage().getFeeRatePct(), g.inverse().getFeeRatePct());
            result = engine.runEtfPremarket(strategy.getSpec(), lev, inv, fut, fees);
            representativeDatasetId = g.leverage().getId();
            datasetLabel = g.leverage().getSymbol() + " / " + g.inverse().getSymbol() + " (장전추세)";
            params = paramsOf(from, to, lev);
        } else {
            if (datasetId == null) {
                throw new IllegalArgumentException("데이터셋을 선택해주세요.");
            }
            Dataset dataset = datasetRepo.findById(datasetId)
                    .orElseThrow(() -> new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + datasetId));
            BarSeries series = datasetLoader.load(datasetId, from, to);
            if (series.size() == 0) {
                throw new IllegalArgumentException(emptyMessage(from, to, "데이터셋에 봉 데이터가 없습니다."));
            }
            result = engine.run(strategy.getSpec(), series, FeeSchedule.flat(dataset.getFeeRatePct()));
            representativeDatasetId = datasetId;
            datasetLabel = dataset.getSymbol();
            params = paramsOf(from, to, series);
        }

        StoredResult stored = toStored(result);
        BacktestRun run = new BacktestRun();
        run.setStrategyId(strategyId);
        run.setDatasetId(representativeDatasetId);
        run.setParams(params);
        run.setSummary(stored);
        BacktestRun saved = runRepo.save(run);

        if (!result.trades().isEmpty()) {
            tradeJdbc.insertAll(saved.getId(), result.trades());
        }

        List<StoredResult.StoredTrade> trades = result.trades().stream()
                .map(BacktestService::toStoredTrade)
                .toList();

        return new BacktestResponse(
                saved.getId(), strategyId, strategy.getName(), representativeDatasetId, datasetLabel,
                saved.getCreatedAt().toString(), params, result.summary(), result.money(), result.diagnosis(), stored.equityCurve(),
                stored.failureByReason(), stored.failureByHour(), stored.worstTrades(), trades);
    }

    @Transactional(readOnly = true)
    public List<BacktestListItem> list() {
        List<BacktestRun> runs = runRepo.findAllByOrderByCreatedAtDesc();
        Map<Long, String> strategyNames = strategyRepo.findAll().stream()
                .collect(Collectors.toMap(Strategy::getId, Strategy::getName));
        Map<Long, String> datasetSymbols = datasetRepo.findAll().stream()
                .collect(Collectors.toMap(Dataset::getId, Dataset::getSymbol));
        return runs.stream().map(r -> new BacktestListItem(
                r.getId(), r.getStrategyId(), strategyNames.getOrDefault(r.getStrategyId(), "(삭제됨)"),
                r.getDatasetId(), datasetSymbols.getOrDefault(r.getDatasetId(), "(삭제됨)"),
                r.getCreatedAt().toString(), r.getParams(),
                r.getSummary() == null ? null : r.getSummary().summary(),
                r.getSummary() == null ? null : r.getSummary().money())).toList();
    }

    @Transactional(readOnly = true)
    public BacktestResponse get(long runId) {
        BacktestRun run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("백테스트 결과를 찾을 수 없습니다: " + runId));
        StoredResult stored = run.getSummary();
        String strategyName = strategyRepo.findById(run.getStrategyId()).map(Strategy::getName).orElse("(삭제됨)");
        String datasetSymbol = datasetRepo.findById(run.getDatasetId()).map(Dataset::getSymbol).orElse("(삭제됨)");
        List<StoredResult.StoredTrade> trades = tradeJdbc.findByRun(runId);
        return new BacktestResponse(
                run.getId(), run.getStrategyId(), strategyName, run.getDatasetId(), datasetSymbol,
                run.getCreatedAt().toString(), run.getParams(),
                stored == null ? null : stored.summary(),
                stored == null ? null : stored.money(),
                stored == null ? null : stored.diagnosis(),
                stored == null ? List.of() : stored.equityCurve(),
                stored == null ? Map.of() : stored.failureByReason(),
                stored == null ? Map.of() : stored.failureByHour(),
                stored == null ? List.of() : stored.worstTrades(),
                trades);
    }

    /** Records the requested range alongside what it actually resolved to in bars. */
    private static RunParams paramsOf(LocalDate from, LocalDate to, BarSeries series) {
        return new RunParams(
                from == null ? null : from.toString(),
                to == null ? null : to.toString(),
                series.bars().get(0).ts().toString(),
                series.bars().get(series.size() - 1).ts().toString(),
                series.size());
    }

    private static String emptyMessage(LocalDate from, LocalDate to, String base) {
        if (from == null && to == null) {
            return base;
        }
        return "선택한 기간(" + (from == null ? "처음" : from) + " ~ " + (to == null ? "끝" : to)
                + ")에 봉 데이터가 없습니다. 기간을 넓혀보세요.";
    }

    private static StoredResult toStored(BacktestResult r) {
        List<StoredResult.StoredEquityPoint> curve = r.equityCurve().stream()
                .map(p -> new StoredResult.StoredEquityPoint(p.ts().toString(), p.equity()))
                .toList();
        Map<String, BacktestResult.ReasonStat> byReason = remap(
                r.failureAnalysis().byExitReason(), k -> k.name());
        Map<String, BacktestResult.HourStat> byHour = remap(
                r.failureAnalysis().byHour(), k -> String.format("%02d", k));
        List<StoredResult.StoredTrade> worst = r.failureAnalysis().worstTrades().stream()
                .map(BacktestService::toStoredTrade)
                .toList();
        return new StoredResult(r.summary(), r.money(), r.diagnosis(), curve, byReason, byHour, worst);
    }

    private static <K, V> Map<String, V> remap(Map<K, V> src, Function<K, String> keyFn) {
        Map<String, V> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(keyFn.apply(k), v));
        return out;
    }

    private static StoredResult.StoredTrade toStoredTrade(TradeRecord t) {
        return new StoredResult.StoredTrade(
                t.entryTs().toString(), t.exitTs().toString(),
                t.entryPrice(), t.exitPrice(), t.returnPct(),
                t.exitReason().name(), t.success(), t.instrument(),
                t.quantity(), t.profitAmount(), t.feeAmount());
    }
}
