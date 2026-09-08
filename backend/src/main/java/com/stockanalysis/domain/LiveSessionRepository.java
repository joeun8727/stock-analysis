package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LiveSessionRepository extends JpaRepository<LiveSession, Long> {

    /** At most one session exists per day — the table enforces it. */
    Optional<LiveSession> findByTradeDate(LocalDate tradeDate);

    /** History, newest day first. */
    List<LiveSession> findAllByOrderByTradeDateDesc();

    /** Sessions that ended with money still committed — used by restart reconciliation. */
    List<LiveSession> findByStateIn(List<LiveState> states);
}
