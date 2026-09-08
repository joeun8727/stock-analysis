package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 시스템이 보낸(드라이런이면 보냈을) 주문 하나.
 *
 * <p>{@code clientOrderId}는 (세션, 방향)에서 만든 우리 자체의 멱등 키입니다. DB에서 UNIQUE라,
 * 전송이 타임아웃되어 재시도하면 두 번째 insert가 실패합니다 — 두 번째 포지션이 열리는 대신에.
 * 진짜 돈이 두 번 나가는 유일한 실패 방식이기 때문입니다.
 */
@Entity
@Table(name = "live_order")
public class LiveOrder {

    public static final String BUY = "BUY";
    public static final String SELL = "SELL";

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FILLED = "FILLED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TYPE_MARKET = "MARKET";
    public static final String TYPE_LIMIT = "LIMIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "side", nullable = false, length = 4)
    private String side;

    @Column(name = "ticker", nullable = false, length = 20)
    private String ticker;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "order_type", nullable = false, length = 10)
    private String orderType = TYPE_MARKET;

    @Column(name = "limit_price")
    private Double limitPrice;

    @Column(name = "client_order_id", nullable = false, length = 64)
    private String clientOrderId;

    @Column(name = "broker_order_no", length = 40)
    private String brokerOrderNo;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_SENT;

    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;

    @Column(name = "filled_price")
    private Double filledPrice;

    @Column(name = "fee_amount")
    private Double feeAmount;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    /** 브로커 응답 원문. 텍스트입니다 — JSON 컬럼이 아닌 이유는 마이그레이션 파일 참고. */
    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @PrePersist
    void onCreate() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }

    public boolean isFilled() {
        return STATUS_FILLED.equals(status);
    }

    public boolean isDead() {
        return STATUS_REJECTED.equals(status) || STATUS_FAILED.equals(status);
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public Double getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(Double limitPrice) {
        this.limitPrice = limitPrice;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }

    public String getBrokerOrderNo() {
        return brokerOrderNo;
    }

    public void setBrokerOrderNo(String brokerOrderNo) {
        this.brokerOrderNo = brokerOrderNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public void setFilledQuantity(long filledQuantity) {
        this.filledQuantity = filledQuantity;
    }

    public Double getFilledPrice() {
        return filledPrice;
    }

    public void setFilledPrice(Double filledPrice) {
        this.filledPrice = filledPrice;
    }

    public Double getFeeAmount() {
        return feeAmount;
    }

    public void setFeeAmount(Double feeAmount) {
        this.feeAmount = feeAmount;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getFilledAt() {
        return filledAt;
    }

    public void setFilledAt(LocalDateTime filledAt) {
        this.filledAt = filledAt;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }
}
