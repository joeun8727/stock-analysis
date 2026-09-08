package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveEventRepository extends JpaRepository<LiveEvent, Long> {

    List<LiveEvent> findBySessionIdOrderByTsAsc(Long sessionId);
}
