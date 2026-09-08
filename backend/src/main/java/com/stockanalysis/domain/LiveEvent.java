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
 * 감사 기록 한 줄: 상태 변화, 주문 요청이나 응답, 거절, 오류.
 *
 * <p>{@code sessionId}가 nullable인 이유는 세션이 생기기 전의 일 — 연결 점검, 활성화 거절 —
 * 도 기록되게 하려고입니다. 실투자에서 뭔가 잘못됐을 때, 시스템이 무엇을 보고 무엇을 결정했는지
 * 말해주는 건 이 테이블뿐입니다.
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

    /** 자유 형식 JSON 텍스트(브로커 페이로드, 판단 입력값). JSON 컬럼이 아니라 텍스트입니다. */
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
