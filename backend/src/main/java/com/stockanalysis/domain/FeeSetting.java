package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The single global commission rate, in percent per side. Always row {@link #ID} — commission is an
 * account-level fact, so every backtest reads this one value instead of carrying its own.
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
