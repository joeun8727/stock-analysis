package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Metadata for one uploaded symbol series. Bar data itself lives in {@code price_bar}. */
@Entity
@Table(name = "dataset")
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Market market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "etf_group_id")
    private Long etfGroupId;

    /** One-way commission for this symbol, in percent; applied to both legs of every trade. */
    @Column(name = "fee_rate_pct", nullable = false)
    private double feeRatePct = 0.015;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "bar_count", nullable = false)
    private int barCount;

    @Column(name = "from_ts")
    private LocalDateTime fromTs;

    @Column(name = "to_ts")
    private LocalDateTime toTs;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Market getMarket() {
        return market;
    }

    public void setMarket(Market market) {
        this.market = market;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public Long getEtfGroupId() {
        return etfGroupId;
    }

    public void setEtfGroupId(Long etfGroupId) {
        this.etfGroupId = etfGroupId;
    }

    public double getFeeRatePct() {
        return feeRatePct;
    }

    public void setFeeRatePct(double feeRatePct) {
        this.feeRatePct = feeRatePct;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public int getBarCount() {
        return barCount;
    }

    public void setBarCount(int barCount) {
        this.barCount = barCount;
    }

    public LocalDateTime getFromTs() {
        return fromTs;
    }

    public void setFromTs(LocalDateTime fromTs) {
        this.fromTs = fromTs;
    }

    public LocalDateTime getToTs() {
        return toTs;
    }

    public void setToTs(LocalDateTime toTs) {
        this.toTs = toTs;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
