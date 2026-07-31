package com.stockanalysis.web;

import com.stockanalysis.data.FeeSettingService;
import com.stockanalysis.domain.FeeSetting;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** App-wide settings. Currently just the commission rate every backtest uses. */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final FeeSettingService service;

    public SettingController(FeeSettingService service) {
        this.service = service;
    }

    public record FeeDto(double feeRatePct, LocalDateTime updatedAt) {
        static FeeDto from(FeeSetting s) {
            return new FeeDto(s.getFeeRatePct(), s.getUpdatedAt());
        }
    }

    public record FeeRequest(double feeRatePct) {
    }

    @GetMapping("/fee")
    public FeeDto fee() {
        return FeeDto.from(service.get());
    }

    @PutMapping("/fee")
    public FeeDto updateFee(@RequestBody FeeRequest req) {
        return FeeDto.from(service.update(req.feeRatePct()));
    }
}
