package com.stockanalysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One futures quote seen during the pre-market window. Stored so the 09:00 direction call can be
 * re-derived afterwards from exactly the numbers it was made on — with real money involved,
 * "why did it buy the inverse?" has to be answerable.
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
