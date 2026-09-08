package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The single live-trading setup row (always {@link #ID}), following {@link FeeSetting}'s pattern.
 *
 * <p>Everything here is <em>execution environment</em> — which strategy, which account, how much,
 * what limits. Trading rules are deliberately absent: they live in {@code strategy.spec_json} so
 * that the backtest and the live run cannot disagree. If you find yourself wanting to add a
 * take-profit field here, add it to {@code ExitSpec} instead.
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

    /** The backtest that justified trading this logic. Null means "never verified". */
    @Column(name = "verified_run_id")
    private Long verifiedRunId;

    /**
     * Hash of the spec as it was when {@link #verifiedRunId} scored it. If the saved strategy no
     * longer hashes to this, the verification is stale and trading is blocked.
     */
    @Column(name = "verified_spec_hash", length = 64)
    private String verifiedSpecHash;

    @Column(name = "min_verified_trades", nullable = false)
    private int minVerifiedTrades = 20;

    @Column(name = "min_verified_days", nullable = false)
    private int minVerifiedDays = 60;

    /** Front-month futures code. Rolls every quarter, so the UI warns as expiry approaches. */
    @Column(name = "futures_ticker", length = 20)
    private String futuresTicker;

    /**
     * The day the user switched trading on. The scheduler compares this to today, so an armed
     * switch expires by itself at midnight instead of quietly trading every day thereafter.
     */
    @Column(name = "armed_date")
    private LocalDate armedDate;

    @Column(name = "max_order_amount", nullable = false)
    private double maxOrderAmount = 1_000_000;

    @Column(name = "max_daily_loss", nullable = false)
    private double maxDailyLoss = 200_000;

    @Column(name = "poll_interval_sec", nullable = false)
    private int pollIntervalSec = 10;

    /** Wall-clock time to force-close, kept before the closing auction. */
    @Column(name = "day_end_exit_time", nullable = false, length = 5)
    private String dayEndExitTime = "15:15";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** True when the user turned trading on for today specifically. */
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
