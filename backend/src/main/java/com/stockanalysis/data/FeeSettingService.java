package com.stockanalysis.data;

import com.stockanalysis.domain.FeeSetting;
import com.stockanalysis.domain.FeeSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Reads and updates the single global commission rate. */
@Service
public class FeeSettingService {

    /** Guards against a typo'd rate silently wiping out every result. */
    private static final double MAX_FEE_RATE_PCT = 5.0;

    private final FeeSettingRepository repo;

    public FeeSettingService(FeeSettingRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public FeeSetting get() {
        return repo.findById(FeeSetting.ID).orElseGet(() -> {
            // The V5 migration seeds this row; fall back to a transient default rather than failing
            // a backtest if it is ever missing.
            FeeSetting fallback = new FeeSetting();
            fallback.setFeeRatePct(0.015);
            return fallback;
        });
    }

    /** Default for newly uploaded symbols; per-symbol rates live on {@code dataset.fee_rate_pct}. */
    @Transactional(readOnly = true)
    public double currentRatePct() {
        return get().getFeeRatePct();
    }

    /** Shared by the global default and the per-symbol rates. */
    public static double validateRate(double feeRatePct) {
        if (Double.isNaN(feeRatePct) || feeRatePct < 0) {
            throw new IllegalArgumentException("수수료율은 0 이상이어야 합니다.");
        }
        if (feeRatePct > MAX_FEE_RATE_PCT) {
            throw new IllegalArgumentException("수수료율이 너무 큽니다 (최대 " + MAX_FEE_RATE_PCT + "%).");
        }
        return feeRatePct;
    }

    @Transactional
    public FeeSetting update(double feeRatePct) {
        validateRate(feeRatePct);
        FeeSetting s = repo.findById(FeeSetting.ID).orElseGet(FeeSetting::new);
        s.setId(FeeSetting.ID);
        s.setFeeRatePct(feeRatePct);
        s.setUpdatedAt(LocalDateTime.now());
        return repo.save(s);
    }
}
