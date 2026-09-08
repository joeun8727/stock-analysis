package com.stockanalysis.web;

import com.stockanalysis.data.DatasetService;
import com.stockanalysis.data.EtfGroupService;
import com.stockanalysis.domain.Dataset;
import com.stockanalysis.domain.EtfGroupRepository;
import com.stockanalysis.domain.Kind;
import com.stockanalysis.domain.Market;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetService service;
    private final EtfGroupService etfGroups;
    private final EtfGroupRepository groupRepo;

    public DatasetController(DatasetService service, EtfGroupService etfGroups, EtfGroupRepository groupRepo) {
        this.service = service;
        this.etfGroups = etfGroups;
        this.groupRepo = groupRepo;
    }

    public record DatasetDto(Long id, String symbol, String ticker, Market market, Kind kind,
                             Long etfGroupId, String groupName, double feeRatePct,
                             String originalFilename, int barCount, int barIntervalMinutes,
                             LocalDateTime fromTs, LocalDateTime toTs, LocalDateTime uploadedAt) {
        static DatasetDto from(Dataset d, String groupName) {
            return new DatasetDto(d.getId(), d.getSymbol(), d.getTicker(), d.getMarket(), d.getKind(),
                    d.getEtfGroupId(), groupName, d.getFeeRatePct(),
                    d.getOriginalFilename(), d.getBarCount(), d.getBarIntervalMinutes(),
                    d.getFromTs(), d.getToTs(), d.getUploadedAt());
        }
    }

    @GetMapping
    public List<DatasetDto> list() {
        Map<Long, String> names = groupRepo.findAll().stream()
                .collect(Collectors.toMap(g -> g.getId(), g -> g.getName()));
        return service.list().stream()
                .map(d -> DatasetDto.from(d, d.getEtfGroupId() == null ? null : names.get(d.getEtfGroupId())))
                .toList();
    }

    @PostMapping
    public DatasetDto upload(@RequestParam("file") MultipartFile file,
                             @RequestParam("symbol") String symbol,
                             @RequestParam("market") Market market,
                             @RequestParam(value = "kind", required = false) Kind kind,
                             @RequestParam(value = "etfGroupId", required = false) Long etfGroupId) {
        return withGroupName(service.upload(file, symbol, market, kind, etfGroupId));
    }

    public record GroupRequest(Long etfGroupId) {
    }

    /** Moves a dataset to another group, or clears its group when {@code etfGroupId} is null. */
    @PatchMapping("/{id}/group")
    public DatasetDto setGroup(@PathVariable long id, @RequestBody GroupRequest req) {
        return withGroupName(etfGroups.assignDataset(id, req.etfGroupId()));
    }

    public record TickerRequest(String ticker) {
    }

    /** Exchange code for live orders (e.g. 122630). Send null or "" to clear it. */
    @PatchMapping("/{id}/ticker")
    public DatasetDto setTicker(@PathVariable long id, @RequestBody TickerRequest req) {
        return withGroupName(service.setTicker(id, req.ticker()));
    }

    public record FeeRequest(double feeRatePct) {
    }

    /** Per-symbol commission, in percent per side. */
    @PatchMapping("/{id}/fee")
    public DatasetDto setFee(@PathVariable long id, @RequestBody FeeRequest req) {
        return withGroupName(service.setFeeRate(id, req.feeRatePct()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    private DatasetDto withGroupName(Dataset d) {
        String groupName = d.getEtfGroupId() == null ? null
                : groupRepo.findById(d.getEtfGroupId()).map(g -> g.getName()).orElse(null);
        return DatasetDto.from(d, groupName);
    }
}
