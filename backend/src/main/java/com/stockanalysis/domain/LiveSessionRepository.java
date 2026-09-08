package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LiveSessionRepository extends JpaRepository<LiveSession, Long> {

    /** 하루에 세션은 최대 하나입니다 — 테이블이 강제합니다. */
    Optional<LiveSession> findByTradeDate(LocalDate tradeDate);

    /** 이력. 최근 날짜부터. */
    List<LiveSession> findAllByOrderByTradeDateDesc();

    /** 돈이 걸린 채로 끝난 세션 — 재기동 시 대조에 씁니다. */
    List<LiveSession> findByStateIn(List<LiveState> states);
}
