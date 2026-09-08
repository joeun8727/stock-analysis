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

/** 업로드한 종목 계열 하나의 메타데이터. 봉 데이터 자체는 {@code price_bar}에 있습니다. */
@Entity
@Table(name = "dataset")
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    /**
     * 실주문에 쓰는 종목코드(예: "122630"). {@code symbol}은 표시명이라 그걸로는 주문할 수
     * 없어서, 실투자는 ticker가 비어 있는 데이터셋을 거부합니다. 백테스트는 이 값을 읽지 않습니다.
     */
    @Column(name = "ticker", length = 20)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Market market;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "etf_group_id")
    private Long etfGroupId;

    /** 이 종목의 편도 수수료율(%). 모든 거래의 매수·매도 양쪽에 적용됩니다. */
    @Column(name = "fee_rate_pct", nullable = false)
    private double feeRatePct = 0.015;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "bar_count", nullable = false)
    private int barCount;

    /**
     * 이 파일의 봉 길이(분). 업로드 시 파싱한 타임스탬프에서 추론합니다. 엔진은 이 값을 보지
     * 않지만(봉을 셀 뿐), "10봉 보유"가 3분봉에서는 30분이고 1분봉에서는 10분이라 UI와 LLM
     * 프롬프트는 같은 스펙을 데이터셋에 맞게 설명하려면 이 값이 필요합니다.
     */
    @Column(name = "bar_interval_minutes", nullable = false)
    private int barIntervalMinutes = 3;

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

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = (ticker == null || ticker.isBlank()) ? null : ticker.trim();
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

    public int getBarIntervalMinutes() {
        return barIntervalMinutes;
    }

    public void setBarIntervalMinutes(int barIntervalMinutes) {
        this.barIntervalMinutes = barIntervalMinutes;
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
