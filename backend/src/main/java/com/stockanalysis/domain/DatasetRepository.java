package com.stockanalysis.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetRepository extends JpaRepository<Dataset, Long> {

    List<Dataset> findAllByOrderByUploadedAtDesc();

    boolean existsBySymbolAndKind(String symbol, Kind kind);

    List<Dataset> findByEtfGroupId(Long etfGroupId);

    List<Dataset> findByEtfGroupIdIsNotNull();

    boolean existsByEtfGroupIdAndMarketAndKind(Long etfGroupId, Market market, Kind kind);

    boolean existsByEtfGroupIdAndMarket(Long etfGroupId, Market market);
}
