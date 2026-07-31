package com.stockanalysis.strategy;

import com.stockanalysis.backtest.spec.ExitSpec;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.backtest.spec.TimeBand;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.domain.StrategyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Service
public class StrategyService {

    private final StrategyRepository repo;

    public StrategyService(StrategyRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<Strategy> list() {
        return repo.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Strategy get(long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + id));
    }

    @Transactional
    public Strategy create(String name, String source, StrategySpec spec) {
        validate(name, spec);
        Strategy s = new Strategy();
        s.setName(name.trim());
        s.setSource(source == null || source.isBlank() ? "USER" : source);
        s.setSpec(spec);
        return repo.save(s);
    }

    @Transactional
    public Strategy update(long id, String name, String source, StrategySpec spec) {
        validate(name, spec);
        Strategy s = get(id);
        s.setName(name.trim());
        if (source != null && !source.isBlank()) {
            s.setSource(source);
        }
        s.setSpec(spec);
        return repo.save(s);
    }

    @Transactional
    public void delete(long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("Strategy not found: " + id);
        }
        repo.deleteById(id);
    }

    private void validate(String name, StrategySpec spec) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("전략 이름을 입력해주세요.");
        }
        if (spec == null) {
            throw new IllegalArgumentException("전략 내용이 비어 있습니다.");
        }
        // ETF pre-market strategies enter via the futures trend gate, so entry conditions are optional.
        if (!spec.usesPremarket() && (spec.getEntry() == null || spec.getEntry().isEmpty())) {
            throw new IllegalArgumentException("매수 조건을 최소 1개 이상 추가해주세요.");
        }
        validateBands(spec);
    }

    /**
     * Time bands are matched in list order, so overlaps are allowed (first match wins) — but a
     * window the engine could never match is always a mistake, and silently ignoring it would look
     * like the setting did nothing.
     */
    private void validateBands(StrategySpec spec) {
        ExitSpec exit = spec.getExit();
        if (exit == null || exit.getBands() == null) {
            return;
        }
        List<TimeBand> bands = exit.getBands();
        for (int i = 0; i < bands.size(); i++) {
            TimeBand b = bands.get(i);
            String where = (i + 1) + "번째 시간대: ";
            if (b == null) {
                throw new IllegalArgumentException(where + "내용이 비어 있습니다.");
            }
            LocalTime start = TimeBand.parseOrNull(b.getStartTime());
            LocalTime end = TimeBand.parseOrNull(b.getEndTime());
            if (start == null || end == null) {
                throw new IllegalArgumentException(where + "시작·종료 시각을 09:00 형식으로 입력해주세요.");
            }
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException(
                        where + "종료 시각(" + b.getEndTime() + ")이 시작 시각(" + b.getStartTime() + ")보다 뒤여야 합니다.");
            }
            if (b.getTakeProfitPct() == null && b.getStopLossPct() == null) {
                throw new IllegalArgumentException(where + "익절 % 또는 손절 % 중 최소 하나는 입력해주세요.");
            }
        }
    }
}
