package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveConfigRepository extends JpaRepository<LiveConfig, Long> {
}
