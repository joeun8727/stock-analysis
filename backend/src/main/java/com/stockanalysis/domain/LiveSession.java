package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One trading day of live execution. {@code trade_date} is unique, which is the duplicate-entry
 * guard: a restart mid-session finds the existing row and resumes it instead of buying again.
 *
 * <p>The pre-market mode holds at most one position per day, so entry/exit live directly on the
 * session rather than in a separate position table.
 */
@Entity
@Table(name = "live_session")
public class LiveSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 10)
    private LiveMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private LiveState state = LiveState.ARMED;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(name = "etf_group_id", nullable = false)
    private Long etfGroupId;

    /** The backtest run this day's trading was justified by, for after-the-fact review. */
    @Column(name = "verified_run_id")
    private Long verifiedRunId;

    @Column(name = "futures_ticker", length = 20)
    private String futuresTicker;

    /** The measured pre-market futures move, kept even when it was too small to trade on. */
    @Column(name = "trend_pct")
    private Double trendPct;

    /** LEVERAGE or INVERSE — same vocabulary as {@code trade.instrument}. */
    @Column(name = "chosen_instrument", length = 20)
    private String chosenInstrument;

    @Column(name = "chosen_ticker", length = 20)
    private String chosenTicker;

    @Column(name = "budget_amount")
    private Double budgetAmount;

    @Column(name = "entry_ts")
    private LocalDateTime entryTs;

    @Column(name = "entry_price")
    private Double entryPrice;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "exit_ts")
    private LocalDateTime exitTs;

    @Column(name = "exit_price")
    private Double exitPrice;

    @Column(name = "exit_reason", length = 20)
    private String exitReason;

    @Column(name = "profit_amount")
    private Double profitAmount;

    @Column(name = "fee_amount")
    private Double feeAmount;

    @Column(name = "halted_reason", length = 500)
    private String haltedReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public LiveMode getMode() {
        return mode;
    }

    public void setMode(LiveMode mode) {
        this.mode = mode;
    }

    public LiveState getState() {
        return state;
    }

    public void setState(LiveState state) {
        this.state = state;
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

    public String getFuturesTicker() {
        return futuresTicker;
    }

    public void setFuturesTicker(String futuresTicker) {
        this.futuresTicker = futuresTicker;
    }

    public Double getTrendPct() {
        return trendPct;
    }

    public void setTrendPct(Double trendPct) {
        this.trendPct = trendPct;
    }

    public String getChosenInstrument() {
        return chosenInstrument;
    }

    public void setChosenInstrument(String chosenInstrument) {
        this.chosenInstrument = chosenInstrument;
    }

    public String getChosenTicker() {
        return chosenTicker;
    }

    public void setChosenTicker(String chosenTicker) {
        this.chosenTicker = chosenTicker;
    }

    public Double getBudgetAmount() {
        return budgetAmount;
    }

    public void setBudgetAmount(Double budgetAmount) {
        this.budgetAmount = budgetAmount;
    }

    public LocalDateTime getEntryTs() {
        return entryTs;
    }

    public void setEntryTs(LocalDateTime entryTs) {
        this.entryTs = entryTs;
    }

    public Double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(Double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public Long getQuantity() {
        return quantity;
    }

    public void setQuantity(Long quantity) {
        this.quantity = quantity;
    }

    public LocalDateTime getExitTs() {
        return exitTs;
    }

    public void setExitTs(LocalDateTime exitTs) {
        this.exitTs = exitTs;
    }

    public Double getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(Double exitPrice) {
        this.exitPrice = exitPrice;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }

    public Double getProfitAmount() {
        return profitAmount;
    }

    public void setProfitAmount(Double profitAmount) {
        this.profitAmount = profitAmount;
    }

    public Double getFeeAmount() {
        return feeAmount;
    }

    public void setFeeAmount(Double feeAmount) {
        this.feeAmount = feeAmount;
    }

    public String getHaltedReason() {
        return haltedReason;
    }

    public void setHaltedReason(String haltedReason) {
        this.haltedReason = haltedReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
