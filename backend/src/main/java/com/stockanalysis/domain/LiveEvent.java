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
 * Audit trail entry: a state change, an order request or response, a refusal, an error.
 *
 * <p>{@code sessionId} is nullable so things that happen before a session exists — a connection
 * check, a refusal to arm — are still recorded. When something goes wrong in a live run this table
 * is the only account of what the system saw and decided.
 */
@Entity
@Table(name = "live_event")
public class LiveEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "ts", nullable = false)
    private LocalDateTime ts;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "message", length = 1000)
    private String message;

    /** Free-form JSON text (broker payloads, decision inputs). Text, not a JSON column. */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    public LiveEvent() {
    }

    public LiveEvent(Long sessionId, String type, String message, String detail) {
        this.sessionId = sessionId;
        this.type = type;
        this.message = message;
        this.detail = detail;
    }

    @PrePersist
    void onCreate() {
        if (ts == null) {
            ts = LocalDateTime.now();
        }
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
