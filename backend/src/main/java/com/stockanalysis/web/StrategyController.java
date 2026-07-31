package com.stockanalysis.web;

import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.strategy.StrategyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyService service;

    public StrategyController(StrategyService service) {
        this.service = service;
    }

    public record StrategyRequest(String name, String source, StrategySpec spec) {
    }

    public record StrategyDto(Long id, String name, String source, StrategySpec spec,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
        static StrategyDto from(Strategy s) {
            return new StrategyDto(s.getId(), s.getName(), s.getSource(), s.getSpec(),
                    s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    @GetMapping
    public List<StrategyDto> list() {
        return service.list().stream().map(StrategyDto::from).toList();
    }

    @GetMapping("/{id}")
    public StrategyDto get(@PathVariable long id) {
        return StrategyDto.from(service.get(id));
    }

    @PostMapping
    public StrategyDto create(@RequestBody StrategyRequest req) {
        return StrategyDto.from(service.create(req.name(), req.source(), req.spec()));
    }

    @PutMapping("/{id}")
    public StrategyDto update(@PathVariable long id, @RequestBody StrategyRequest req) {
        return StrategyDto.from(service.update(id, req.name(), req.source(), req.spec()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
