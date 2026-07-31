package com.stockanalysis.web;

import com.stockanalysis.backtest.Indicator;
import com.stockanalysis.backtest.Operator;
import com.stockanalysis.backtest.spec.CapitalMode;
import com.stockanalysis.backtest.spec.Logic;
import com.stockanalysis.backtest.spec.Source;
import com.stockanalysis.domain.Kind;
import com.stockanalysis.domain.Market;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Enumerations the rule-builder UI needs to populate dropdowns. */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @GetMapping
    public Map<String, List<String>> meta() {
        return Map.of(
                "indicators", names(Indicator.values()),
                "operators", names(Operator.values()),
                "logics", names(Logic.values()),
                "sources", names(Source.values()),
                "markets", names(Market.values()),
                "kinds", names(Kind.values()),
                "capitalModes", names(CapitalMode.values())
        );
    }

    private static <E extends Enum<E>> List<String> names(E[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
