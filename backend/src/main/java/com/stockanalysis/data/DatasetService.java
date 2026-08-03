package com.stockanalysis.data;

import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.BarSeries;
import com.stockanalysis.domain.Dataset;
import com.stockanalysis.domain.DatasetRepository;
import com.stockanalysis.domain.Kind;
import com.stockanalysis.domain.Market;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles dataset uploads: stores the original xlsx under {@code data/}, streams it into bars,
 * batch-loads them into {@code price_bar}, and records dataset metadata.
 */
@Service
public class DatasetService {

    private final DatasetRepository repo;
    private final EtfGroupService etfGroups;
    private final FeeSettingService feeSettings;
    private final PriceBarWriter priceBarWriter;
    private final ExcelParser parser = new ExcelParser();
    private final Path dataDir;

    public DatasetService(DatasetRepository repo,
                          EtfGroupService etfGroups,
                          FeeSettingService feeSettings,
                          PriceBarWriter priceBarWriter,
                          @Value("${app.data-dir}") String dataDir) {
        this.repo = repo;
        this.etfGroups = etfGroups;
        this.feeSettings = feeSettings;
        this.priceBarWriter = priceBarWriter;
        this.dataDir = Path.of(dataDir);
    }

    @Transactional(readOnly = true)
    public List<Dataset> list() {
        return repo.findAllByOrderByUploadedAtDesc();
    }

    /**
     * @param etfGroupId group to file this dataset under, picked from the existing groups; null
     *                   leaves it ungrouped (pre-market mode unavailable until it's assigned).
     */
    @Transactional
    public Dataset upload(MultipartFile file, String symbol, Market market, Kind kind, Long etfGroupId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("엑셀 파일을 선택해주세요.");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목명을 입력해주세요.");
        }
        if (market == Market.ETF && kind != Kind.LEVERAGE && kind != Kind.INVERSE) {
            throw new IllegalArgumentException("ETF는 레버리지 또는 인버스를 선택해야 합니다.");
        }
        Kind effectiveKind = market == Market.ETF ? kind : Kind.SINGLE;
        if (repo.existsBySymbolAndKind(symbol.trim(), effectiveKind)) {
            throw new IllegalArgumentException("이미 존재하는 종목+종류입니다: " + symbol + " / " + effectiveKind);
        }
        etfGroups.validateSlot(market, effectiveKind, etfGroupId);

        String filename = sanitize(file.getOriginalFilename(), symbol);
        Path target = storagePath(market).resolve(filename);
        List<Bar> bars;
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toAbsolutePath());
            bars = parser.parse(target);
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장/파싱 실패: " + e.getMessage(), e);
        }
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("봉 데이터를 찾을 수 없습니다. 파일 형식을 확인해주세요.");
        }

        BarSeries series = new BarSeries(bars); // ascending
        Dataset ds = new Dataset();
        ds.setSymbol(symbol.trim());
        ds.setMarket(market);
        ds.setKind(effectiveKind);
        ds.setEtfGroupId(etfGroupId);
        // New symbols start at the global default; per-symbol rates are edited on the fee screen.
        ds.setFeeRatePct(feeSettings.currentRatePct());
        ds.setOriginalFilename(filename);
        ds.setBarCount(series.size());
        // Measured from the file itself: the same symbol can also be uploaded as 1-minute bars.
        ds.setBarIntervalMinutes(series.inferIntervalMinutes());
        ds.setFromTs(series.bars().get(0).ts());
        ds.setToTs(series.bars().get(series.size() - 1).ts());
        Dataset saved = repo.save(ds);

        priceBarWriter.insertBars(saved.getId(), series.bars());
        return saved;
    }

    /** Sets this symbol's commission. Every backtest that trades it picks the new rate up. */
    @Transactional
    public Dataset setFeeRate(long id, double feeRatePct) {
        Dataset d = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + id));
        d.setFeeRatePct(FeeSettingService.validateRate(feeRatePct));
        return d;
    }

    @Transactional
    public void delete(long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + id);
        }
        // price_bar rows are removed by the ON DELETE CASCADE foreign key.
        repo.deleteById(id);
    }

    private Path storagePath(Market market) {
        return switch (market) {
            case FUTURES -> dataDir.resolve("futures");
            case ETF -> dataDir.resolve("stock").resolve("etf");
            case NORMAL -> dataDir.resolve("stock").resolve("normal");
        };
    }

    private static String sanitize(String original, String symbol) {
        if (original == null || original.isBlank()) {
            return symbol + ".xlsx";
        }
        String base = Path.of(original).getFileName().toString();
        return base.replaceAll("[/\\\\]", "_");
    }
}
