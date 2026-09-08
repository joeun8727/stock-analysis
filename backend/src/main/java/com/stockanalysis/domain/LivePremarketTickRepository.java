package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LivePremarketTickRepository extends JpaRepository<LivePremarketTick, Long> {

    List<LivePremarketTick> findBySessionIdOrderByTsAsc(Long sessionId);
}
