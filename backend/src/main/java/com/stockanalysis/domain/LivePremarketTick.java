package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 장전 구간에 본 선물 시세 하나. 09:00의 방향 판단을 그때 쓴 숫자 그대로 사후에 재현할 수
 * 있도록 저장합니다 — 진짜 돈이 걸린 이상 "왜 인버스를 샀나?"에 답할 수 있어야 합니다.
 */
@Entity
@Table(name = "live_premarket_tick")
public class LivePremarketTick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;

    @Column(name = "price", nullable = false)
    private double price;

    public LivePremarketTick() {
    }

    public LivePremarketTick(Long sessionId, LocalDateTime ts, double price) {
        this.sessionId = sessionId;
        this.ts = ts;
        this.price = price;
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

    public LocalDateTime getTs() {
        return ts;
    }

    public void setTs(LocalDateTime ts) {
        this.ts = ts;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }
}
