package com.stockanalysis.web;

import com.stockanalysis.domain.LiveConfig;
import com.stockanalysis.live.session.LiveConfigService;
import com.stockanalysis.live.session.LiveQueryService;
import com.stockanalysis.live.session.LiveTradingService;
import com.stockanalysis.live.session.VerificationGate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Live trading API.
 *
 * <p>Note what is missing: there is no endpoint that changes a trading rule, and none that switches
 * to a real account. Rules are edited on the strategy screen so that what trades is exactly what
 * was backtested, and the account mode comes from an environment variable so that going live takes
 * a restart rather than a click.
 */
@RestController
@RequestMapping("/api/live")
public class LiveController {

    private final LiveConfigService configService;
    private final LiveQueryService queryService;
    private final LiveTradingService trading;
    private final VerificationGate gate;

    public LiveController(LiveConfigService configService, LiveQueryService queryService,
                          LiveTradingService trading, VerificationGate gate) {
        this.configService = configService;
        this.queryService = queryService;
        this.trading = trading;
        this.gate = gate;
    }

    public record ConfigDto(Long strategyId, Long etfGroupId, String futuresTicker,
                            Long verifiedRunId, boolean specVerified,
                            double maxOrderAmount, double maxDailyLoss,
                            int pollIntervalSec, String dayEndExitTime,
                            int minVerifiedTrades, int minVerifiedDays,
                            String armedDate, String updatedAt) {

        static ConfigDto from(LiveConfig c) {
            return new ConfigDto(c.getStrategyId(), c.getEtfGroupId(), c.getFuturesTicker(),
                    c.getVerifiedRunId(), c.getVerifiedSpecHash() != null,
                    c.getMaxOrderAmount(), c.getMaxDailyLoss(),
                    c.getPollIntervalSec(), c.getDayEndExitTime(),
                    c.getMinVerifiedTrades(), c.getMinVerifiedDays(),
                    c.getArmedDate() == null ? null : c.getArmedDate().toString(),
                    c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        }
    }

    @GetMapping("/config")
    public ConfigDto config() {
        return ConfigDto.from(configService.get());
    }

    @PutMapping("/config")
    public ConfigDto updateConfig(@RequestBody LiveConfigService.Update req) {
        return ConfigDto.from(configService.update(req));
    }

    /** Strategies that could be traded, each with its verification evidence or the reason it can't. */
    @GetMapping("/candidates")
    public List<VerificationGate.Candidate> candidates() {
        return gate.candidates(configService.get());
    }

    public record ArmRequest(String date) {
    }

    /** Switches trading on for one day. Refuses unless the strategy passes the verification gate. */
    @PostMapping("/arm")
    public ConfigDto arm(@RequestBody(required = false) ArmRequest req) {
        LocalDate date = (req == null || req.date() == null || req.date().isBlank())
                ? LocalDate.now() : LocalDate.parse(req.date());
        return ConfigDto.from(configService.arm(date));
    }

    @PostMapping("/disarm")
    public ConfigDto disarm() {
        return ConfigDto.from(configService.disarm());
    }

    @GetMapping("/today")
    public LiveQueryService.TodayView today() {
        return queryService.today();
    }

    @GetMapping("/sessions")
    public List<LiveQueryService.SessionListItem> sessions() {
        return queryService.history();
    }

    public record HaltRequest(String reason, Boolean closePosition) {
    }

    /**
     * Kill switch. Always stops new orders; {@code closePosition} additionally liquidates at market.
     * Disarms as well, so a halt does not quietly re-arm on the next tick.
     */
    @PostMapping("/halt")
    public LiveQueryService.TodayView halt(@RequestBody(required = false) HaltRequest req) {
        String reason = (req == null || req.reason() == null || req.reason().isBlank())
                ? "사용자가 긴급 정지했습니다." : req.reason();
        boolean close = req != null && Boolean.TRUE.equals(req.closePosition());
        configService.disarm();
        trading.haltToday(reason, close);
        return queryService.today();
    }

    /** Connection and setup check. Reads only — never places an order. */
    @PostMapping("/check")
    public LiveQueryService.CheckResult check() {
        return queryService.check();
    }
}
