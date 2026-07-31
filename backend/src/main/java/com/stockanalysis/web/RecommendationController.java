package com.stockanalysis.web;

import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.recommend.RecommendationService;
import com.stockanalysis.strategy.StrategyService;
import com.stockanalysis.web.StrategyController.StrategyDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Generates LLM-recommended strategies (via the Claude CLI) and saves them as source=LLM. */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final StrategyService strategyService;

    public RecommendationController(RecommendationService recommendationService,
                                    StrategyService strategyService) {
        this.recommendationService = recommendationService;
        this.strategyService = strategyService;
    }

    public record RecommendRequest(Long datasetId) {
    }

    @PostMapping
    public List<StrategyDto> recommend(@RequestBody RecommendRequest req) {
        if (req.datasetId() == null) {
            throw new IllegalArgumentException("데이터셋을 선택해주세요.");
        }
        List<StrategySpec> specs = recommendationService.recommend(req.datasetId());
        return specs.stream()
                .map(spec -> StrategyDto.from(strategyService.create(spec.getName(), "LLM", spec)))
                .toList();
    }
}
