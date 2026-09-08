package com.stockanalysis.web;

import com.stockanalysis.data.FeeSettingService;
import com.stockanalysis.domain.FeeSetting;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** 앱 전역 설정. 지금은 모든 백테스트가 쓰는 수수료율 하나뿐입니다. */
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
