package com.stockanalysis.data;

import com.stockanalysis.domain.Dataset;
import com.stockanalysis.domain.DatasetRepository;
import com.stockanalysis.domain.EtfGroup;
import com.stockanalysis.domain.EtfGroupRepository;
import com.stockanalysis.domain.Kind;
import com.stockanalysis.domain.Market;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages ETF groups. A group pairs one leverage ETF, one inverse ETF, and one futures series
 * (one dataset per slot). Groups are created and edited on their own screen — named freely, renamed,
 * deleted, and their slots filled from already-uploaded datasets — so adding an ETF pair that didn't
 * exist before never needs a schema or code change.
 */
@Service
public class EtfGroupService {

    private final EtfGroupRepository groupRepo;
    private final DatasetRepository datasetRepo;

    public EtfGroupService(EtfGroupRepository groupRepo, DatasetRepository datasetRepo) {
        this.groupRepo = groupRepo;
        this.datasetRepo = datasetRepo;
    }

    /** UI-facing view of a group with its three slots and readiness. */
    public record EtfGroupView(
            Long id,
            String name,
            Slot leverage,
            Slot inverse,
            Slot futures,
            boolean ready
    ) {
    }

    public record Slot(Long datasetId, String symbol) {
    }

    /** The three datasets needed to run a pre-market backtest. */
    public record Resolved(Dataset leverage, Dataset inverse, Dataset futures) {
    }

    @Transactional
    public EtfGroup createGroup(String name) {
        String trimmed = requireName(name);
        if (groupRepo.existsByName(trimmed)) {
            throw new IllegalArgumentException("이미 존재하는 그룹 이름입니다: " + trimmed);
        }
        return groupRepo.save(new EtfGroup(trimmed));
    }

    @Transactional
    public EtfGroup rename(long id, String name) {
        String trimmed = requireName(name);
        EtfGroup g = require(id);
        if (!g.getName().equals(trimmed) && groupRepo.existsByName(trimmed)) {
            throw new IllegalArgumentException("이미 존재하는 그룹 이름입니다: " + trimmed);
        }
        g.setName(trimmed);
        return g;
    }

    /** Deletes a group; its datasets survive and are simply unlinked (bar data is untouched). */
    @Transactional
    public void delete(long id) {
        require(id);
        List<Dataset> members = datasetRepo.findByEtfGroupId(id);
        members.forEach(d -> d.setEtfGroupId(null));
        datasetRepo.saveAll(members);
        datasetRepo.flush(); // unlink before the group row goes away, or the FK rejects the delete
        groupRepo.deleteById(id);
    }

    /**
     * Moves a dataset into a group, or out of any group when {@code groupId} is null. Lets the group
     * screen fill and clear slots without re-uploading the excel.
     */
    @Transactional
    public Dataset assignDataset(long datasetId, Long groupId) {
        Dataset d = datasetRepo.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("데이터셋을 찾을 수 없습니다: " + datasetId));
        if (groupId == null) {
            d.setEtfGroupId(null);
            return d;
        }
        if (groupId.equals(d.getEtfGroupId())) {
            return d; // already in this group; re-validating would collide with itself
        }
        validateSlot(d.getMarket(), d.getKind(), groupId);
        d.setEtfGroupId(groupId);
        return d;
    }

    /** A group holds at most one leverage ETF, one inverse ETF, and one futures dataset. */
    public void validateSlot(Market market, Kind kind, Long groupId) {
        if (groupId == null) {
            return;
        }
        if (market == Market.NORMAL) {
            throw new IllegalArgumentException("일반종목은 ETF 그룹에 넣을 수 없습니다.");
        }
        if (!groupRepo.existsById(groupId)) {
            throw new IllegalArgumentException("존재하지 않는 ETF 그룹입니다: " + groupId);
        }
        if (market == Market.FUTURES) {
            if (datasetRepo.existsByEtfGroupIdAndMarket(groupId, Market.FUTURES)) {
                throw new IllegalArgumentException("이 그룹에는 이미 선물 데이터가 있습니다.");
            }
        } else if (datasetRepo.existsByEtfGroupIdAndMarketAndKind(groupId, Market.ETF, kind)) {
            String label = kind == Kind.LEVERAGE ? "레버리지" : "인버스";
            throw new IllegalArgumentException("이 그룹에는 이미 " + label + " ETF가 있습니다.");
        }
    }

    @Transactional(readOnly = true)
    public List<EtfGroupView> listAll() {
        List<EtfGroupView> views = new ArrayList<>();
        for (EtfGroup g : groupRepo.findAllByOrderByNameAsc()) {
            views.add(toView(g));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public EtfGroupView view(long id) {
        return toView(require(id));
    }

    @Transactional(readOnly = true)
    public Resolved resolve(long groupId) {
        List<Dataset> members = datasetRepo.findByEtfGroupId(groupId);
        Dataset lev = pick(members, Market.ETF, Kind.LEVERAGE);
        Dataset inv = pick(members, Market.ETF, Kind.INVERSE);
        Dataset fut = pickFutures(members);
        if (lev == null || inv == null || fut == null) {
            throw new IllegalArgumentException(
                    "ETF 그룹에 레버리지·인버스·선물이 모두 있어야 합니다. "
                            + "(레버리지=" + (lev != null) + ", 인버스=" + (inv != null) + ", 선물=" + (fut != null) + ")");
        }
        return new Resolved(lev, inv, fut);
    }

    private EtfGroupView toView(EtfGroup g) {
        List<Dataset> members = datasetRepo.findByEtfGroupId(g.getId());
        Slot lev = slot(members, Market.ETF, Kind.LEVERAGE);
        Slot inv = slot(members, Market.ETF, Kind.INVERSE);
        Slot fut = slotFutures(members);
        boolean ready = lev != null && inv != null && fut != null;
        return new EtfGroupView(g.getId(), g.getName(), lev, inv, fut, ready);
    }

    private EtfGroup require(long id) {
        return groupRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 ETF 그룹입니다: " + id));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("그룹 이름을 입력해주세요.");
        }
        return name.trim();
    }

    private static Slot slot(List<Dataset> members, Market market, Kind kind) {
        Dataset d = pick(members, market, kind);
        return d == null ? null : new Slot(d.getId(), d.getSymbol());
    }

    private static Slot slotFutures(List<Dataset> members) {
        Dataset d = pickFutures(members);
        return d == null ? null : new Slot(d.getId(), d.getSymbol());
    }

    private static Dataset pick(List<Dataset> members, Market market, Kind kind) {
        return members.stream()
                .filter(d -> d.getMarket() == market && d.getKind() == kind)
                .findFirst().orElse(null);
    }

    private static Dataset pickFutures(List<Dataset> members) {
        return members.stream()
                .filter(d -> d.getMarket() == Market.FUTURES)
                .findFirst().orElse(null);
    }
}
