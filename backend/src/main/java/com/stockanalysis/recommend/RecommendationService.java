package com.stockanalysis.recommend;

import com.stockanalysis.backtest.spec.StrategySpec;

import java.util.List;

/** 데이터셋에 대해 백테스트 가능한 전략 스펙을 만들어냅니다. */
public interface RecommendationService {

    List<StrategySpec> recommend(long datasetId);
}
