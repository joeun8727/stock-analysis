package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EtfGroupRepository extends JpaRepository<EtfGroup, Long> {

    Optional<EtfGroup> findByName(String name);

    boolean existsByName(String name);

    List<EtfGroup> findAllByOrderByNameAsc();
}
