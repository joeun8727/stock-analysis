package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LiveOrderRepository extends JpaRepository<LiveOrder, Long> {

    List<LiveOrder> findBySessionIdOrderByRequestedAtAsc(Long sessionId);

    Optional<LiveOrder> findByClientOrderId(String clientOrderId);

    List<LiveOrder> findBySessionIdAndSideOrderByRequestedAtAsc(Long sessionId, String side);
}
