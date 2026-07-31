package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyRepository extends JpaRepository<Strategy, Long> {

    List<Strategy> findAllByOrderByUpdatedAtDesc();
}
