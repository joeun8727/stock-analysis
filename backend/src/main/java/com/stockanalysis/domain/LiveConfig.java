package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 실투자 설정 단일 행(항상 {@link #ID}). {@link FeeSetting}과 같은 방식입니다.
 *
 * <p>여기 있는 건 전부 <em>실행 환경</em>입니다 — 어느 전략, 어느 계좌, 얼마, 어떤 한도.
 * 매매 규칙은 의도적으로 없습니다: 규칙은 {@code strategy.spec_json}에 있어야 백테스트와
 * 실전이 어긋날 수 없습니다. 여기에 익절 필드를 넣고 싶어진다면, 대신 {@code ExitSpec}에
 * 넣으세요.
 */
@Entity
@Table(name = "live_config")
public class LiveConfig {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(name = "etf_group_id")
    private Long etfGroupId;

    /** 이 로직으로 매매해도 된다는 근거가 된 백테스트. null이면 "검증된 적 없음"입니다. */
    @Column(name = "verified_run_id")
    private Long verifiedRunId;

    /**
     * {@link #verifiedRunId}가 점수를 매길 당시 스펙의 해시. 저장된 전략이 더는 이 값으로
     * 해시되지 않으면 검증이 낡은 것이고 매매가 막힙니다.
     */
    @Column(name = "verified_spec_hash", length = 64)
    private String verifiedSpecHash;

    @Column(name = "min_verified_trades", nullable = false)
    private int minVerifiedTrades = 20;

    @Column(name = "min_verified_days", nullable = false)
    private int minVerifiedDays = 60;

    /** 최근월물 선물 코드. 분기마다 롤오버되므로 만기가 다가오면 UI가 알려줍니다. */
    @Column(name = "futures_ticker", length = 20)
    private String futuresTicker;

    /**
     * 사용자가 매매를 켠 날짜. 스케줄러가 오늘과 비교하므로, 켜둔 스위치는 자정에 스스로
     * 만료됩니다 — 그 뒤로 매일 조용히 매매하는 대신에.
     */
    @Column(name = "armed_date")
    private LocalDate armedDate;

    @Column(name = "max_order_amount", nullable = false)
    private double maxOrderAmount = 1_000_000;

    @Column(name = "max_daily_loss", nullable = false)
    private double maxDailyLoss = 200_000;

    @Column(name = "poll_interval_sec", nullable = false)
    private int pollIntervalSec = 10;

    /** 강제 청산할 벽시계 시각. 종가 단일가보다 앞에 둡니다. */
    @Column(name = "day_end_exit_time", nullable = false, length = 5)
    private String dayEndExitTime = "15:15";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 사용자가 다른 날이 아니라 바로 오늘에 대해 매매를 켰는지. */
    public boolean isArmedFor(LocalDate date) {
        return armedDate != null && armedDate.equals(date);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

    public Long getEtfGroupId() {
        return etfGroupId;
    }

    public void setEtfGroupId(Long etfGroupId) {
        this.etfGroupId = etfGroupId;
    }

    public Long getVerifiedRunId() {
        return verifiedRunId;
    }

    public void setVerifiedRunId(Long verifiedRunId) {
        this.verifiedRunId = verifiedRunId;
    }

    public String getVerifiedSpecHash() {
        return verifiedSpecHash;
    }

    public void setVerifiedSpecHash(String verifiedSpecHash) {
        this.verifiedSpecHash = verifiedSpecHash;
    }

    public int getMinVerifiedTrades() {
        return minVerifiedTrades;
    }

    public void setMinVerifiedTrades(int minVerifiedTrades) {
        this.minVerifiedTrades = minVerifiedTrades;
    }

    public int getMinVerifiedDays() {
        return minVerifiedDays;
    }

    public void setMinVerifiedDays(int minVerifiedDays) {
        this.minVerifiedDays = minVerifiedDays;
    }

    public String getFuturesTicker() {
        return futuresTicker;
    }

    public void setFuturesTicker(String futuresTicker) {
        this.futuresTicker = (futuresTicker == null || futuresTicker.isBlank()) ? null : futuresTicker.trim();
    }

    public LocalDate getArmedDate() {
        return armedDate;
    }

    public void setArmedDate(LocalDate armedDate) {
        this.armedDate = armedDate;
    }

    public double getMaxOrderAmount() {
        return maxOrderAmount;
    }

    public void setMaxOrderAmount(double maxOrderAmount) {
        this.maxOrderAmount = maxOrderAmount;
    }

    public double getMaxDailyLoss() {
        return maxDailyLoss;
    }

    public void setMaxDailyLoss(double maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss;
    }

    public int getPollIntervalSec() {
        return pollIntervalSec;
    }

    public void setPollIntervalSec(int pollIntervalSec) {
        this.pollIntervalSec = pollIntervalSec;
    }

    public String getDayEndExitTime() {
        return dayEndExitTime;
    }

    public void setDayEndExitTime(String dayEndExitTime) {
        this.dayEndExitTime = dayEndExitTime;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
