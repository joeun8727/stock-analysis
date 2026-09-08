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
 * ETF 그룹을 관리합니다. 그룹은 레버리지 ETF 하나, 인버스 ETF 하나, 선물 계열 하나를 묶습니다
 * (슬롯당 데이터셋 하나). 그룹은 전용 화면에서 만들고 편집합니다 — 이름을 자유롭게 붙이고,
 * 바꾸고, 지우고, 이미 업로드된 데이터셋으로 슬롯을 채웁니다 — 그래서 전에 없던 ETF 쌍을
 * 추가하는 데 스키마나 코드 변경이 필요 없습니다.
 */
@Service
public class EtfGroupService {

    private final EtfGroupRepository groupRepo;
    private final DatasetRepository datasetRepo;

    public EtfGroupService(EtfGroupRepository groupRepo, DatasetRepository datasetRepo) {
        this.groupRepo = groupRepo;
        this.datasetRepo = datasetRepo;
    }

    /** 그룹을 세 슬롯과 준비 상태와 함께 보여주는 UI용 뷰. */
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

    /** 장전 백테스트를 돌리는 데 필요한 데이터셋 셋. */
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

    /** 그룹을 지웁니다. 데이터셋은 그대로 남고 연결만 끊깁니다(봉 데이터는 건드리지 않습니다). */
    @Transactional
    public void delete(long id) {
        require(id);
        List<Dataset> members = datasetRepo.findByEtfGroupId(id);
        members.forEach(d -> d.setEtfGroupId(null));
        datasetRepo.saveAll(members);
        datasetRepo.flush(); // 그룹 행이 사라지기 전에 연결을 끊습니다. 아니면 FK가 삭제를 거부합니다
        groupRepo.deleteById(id);
    }

    /**
     * 데이터셋을 그룹으로 옮깁니다. {@code groupId}가 null이면 어느 그룹에도 속하지 않게 합니다.
     * 그룹 화면이 엑셀을 다시 올리지 않고도 슬롯을 채우고 비울 수 있게 해줍니다.
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
            return d; // 이미 이 그룹에 속함. 다시 검사하면 자기 자신과 충돌합니다
        }
        validateSlot(d.getMarket(), d.getKind(), groupId);
        d.setEtfGroupId(groupId);
        return d;
    }

    /** 한 그룹에는 레버리지 ETF, 인버스 ETF, 선물 데이터셋이 각각 최대 하나씩입니다. */
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
