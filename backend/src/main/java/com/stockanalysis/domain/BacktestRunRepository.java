package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRun, Long> {

    /** Every stored run, newest first — the history screen is a view of the table, uncapped. */
    List<BacktestRun> findAllByOrderByCreatedAtDesc();
}
