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
 * 데이터셋 업로드를 처리합니다: 원본 xlsx를 {@code data/} 아래에 저장하고, 스트리밍으로 봉을
 * 읽어 {@code price_bar}에 배치 적재한 뒤 데이터셋 메타데이터를 기록합니다.
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
     * @param etfGroupId 이 데이터셋을 넣을 그룹. 기존 그룹 중에서 고르며, null이면 그룹 없이
     *                   둡니다(지정하기 전까지 장전 모드를 쓸 수 없습니다).
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

        BarSeries series = new BarSeries(bars); // 오름차순
        Dataset ds = new Dataset();
        ds.setSymbol(symbol.trim());
        ds.setMarket(market);
        ds.setKind(effectiveKind);
        ds.setEtfGroupId(etfGroupId);
        // 새 종목은 전역 기본값에서 시작합니다. 종목별 요율은 수수료 화면에서 편집합니다.
        ds.setFeeRatePct(feeSettings.currentRatePct());
        ds.setOriginalFilename(filename);
        ds.setBarCount(series.size());
        // 파일 자체에서 측정합니다: 같은 종목을 1분봉으로도 올릴 수 있습니다.
        ds.setBarIntervalMinutes(series.inferIntervalMinutes());
        ds.setFromTs(series.bars().get(0).ts());
        ds.setToTs(series.bars().get(series.size() - 1).ts());
        Dataset saved = repo.save(ds);

        priceBarWriter.insertBars(saved.getId(), series.bars());
        return saved;
    }

    /** 이 종목의 수수료를 설정합니다. 이 종목을 매매하는 모든 백테스트가 새 요율을 씁니다. */
    @Transactional
    public Dataset setFeeRate(long id, double feeRatePct) {
        Dataset d = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + id));
        d.setFeeRatePct(FeeSettingService.validateRate(feeRatePct));
        return d;
    }

    /**
     * 실주문에 쓰는 종목코드를 설정합니다. 백테스트는 읽지 않으므로 선택 사항이지만, 실투자는
     * 이 값이 없는 데이터셋을 거부합니다 — {@code symbol}은 표시명일 뿐이기 때문입니다.
     */
    @Transactional
    public Dataset setTicker(long id, String ticker) {
        Dataset d = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + id));
        d.setTicker(validateTicker(ticker));
        return d;
    }

    /** 국내 상장 코드는 6자리이고, 선물 코드는 "101W03" 같은 짧은 영숫자입니다. */
    private static String validateTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null; // 비우는 것은 허용합니다
        }
        String trimmed = ticker.trim().toUpperCase();
        if (!trimmed.matches("[A-Z0-9]{4,12}")) {
            throw new IllegalArgumentException("종목코드 형식이 올바르지 않습니다: " + ticker);
        }
        return trimmed;
    }

    @Transactional
    public void delete(long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + id);
        }
        // price_bar 행은 ON DELETE CASCADE 외래키가 지웁니다.
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
