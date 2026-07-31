package com.stockanalysis.recommend;

import com.stockanalysis.backtest.spec.StrategySpec;

import java.util.List;

/** Produces backtestable strategy specs for a dataset. */
public interface RecommendationService {

    List<StrategySpec> recommend(long datasetId);
}
