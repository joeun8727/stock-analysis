package com.stockanalysis.data;

import com.stockanalysis.domain.FeeSetting;
import com.stockanalysis.domain.FeeSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 전역 수수료율 단일 행을 읽고 갱신합니다. */
@Service
public class FeeSettingService {

    /** 잘못 입력한 요율이 모든 결과를 조용히 지워버리는 것을 막습니다. */
    private static final double MAX_FEE_RATE_PCT = 5.0;

    private final FeeSettingRepository repo;

    public FeeSettingService(FeeSettingRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public FeeSetting get() {
        return repo.findById(FeeSetting.ID).orElseGet(() -> {
            // V5 마이그레이션이 이 행을 심어둡니다. 혹시라도 없으면 백테스트를 실패시키는 대신
            // 영속화되지 않은 기본값으로 폴백합니다.
            FeeSetting fallback = new FeeSetting();
            fallback.setFeeRatePct(0.015);
            return fallback;
        });
    }

    /** 새로 업로드하는 종목의 기본값. 종목별 요율은 {@code dataset.fee_rate_pct}에 있습니다. */
    @Transactional(readOnly = true)
    public double currentRatePct() {
        return get().getFeeRatePct();
    }

    /** 전역 기본값과 종목별 요율이 함께 씁니다. */
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
