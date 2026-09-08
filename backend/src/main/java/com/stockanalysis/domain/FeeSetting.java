package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 전역 수수료율 하나(편도 %). 항상 {@link #ID} 행입니다 — 수수료는 계좌 차원의 사실이라,
 * 모든 백테스트가 자기 것을 들고 다니는 대신 이 값 하나를 읽습니다.
 */
@Entity
@Table(name = "fee_setting")
public class FeeSetting {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Column(name = "fee_rate_pct", nullable = false)
    private double feeRatePct;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public double getFeeRatePct() {
        return feeRatePct;
    }

    public void setFeeRatePct(double feeRatePct) {
        this.feeRatePct = feeRatePct;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
