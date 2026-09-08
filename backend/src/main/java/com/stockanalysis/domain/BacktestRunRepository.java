package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRun, Long> {

    /** 저장된 모든 실행. 최신순 — 이력 화면은 테이블을 그대로 보는 뷰이고 개수 제한이 없습니다. */
    List<BacktestRun> findAllByOrderByCreatedAtDesc();
}
