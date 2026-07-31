package com.stockanalysis.web;

import com.stockanalysis.run.BacktestDtos.BacktestListItem;
import com.stockanalysis.run.BacktestDtos.BacktestResponse;
import com.stockanalysis.run.BacktestDtos.RunRequest;
import com.stockanalysis.run.BacktestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backtests")
public class BacktestController {

    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    @PostMapping
    public BacktestResponse run(@RequestBody RunRequest req) {
        if (req.strategyId() == null) {
            throw new IllegalArgumentException("전략을 선택해주세요.");
        }
        return service.run(req.strategyId(), req.datasetId(), req.groupId(), req.fromDate(), req.toDate());
    }

    @GetMapping
    public List<BacktestListItem> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public BacktestResponse get(@PathVariable long id) {
        return service.get(id);
    }
}
