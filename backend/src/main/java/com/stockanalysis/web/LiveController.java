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
 * 실투자 API.
 *
 * <p>없는 것에 주목하세요: 매매 규칙을 바꾸는 엔드포인트가 없고, 실계좌로 전환하는 것도
 * 없습니다. 규칙은 전략 화면에서 편집해야 매매되는 것이 백테스트한 것과 정확히 같고, 계좌
 * 모드는 환경변수에서 와야 실전 전환이 클릭이 아니라 재기동을 요구합니다.
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

    /** 매매 후보가 될 수 있는 전략들. 각각 검증 근거 또는 안 되는 이유를 함께 담습니다. */
    @GetMapping("/candidates")
    public List<VerificationGate.Candidate> candidates() {
        return gate.candidates(configService.get());
    }

    public record ArmRequest(String date) {
    }

    /** 하루 동안 매매를 켭니다. 전략이 검증 게이트를 통과하지 못하면 거부합니다. */
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
     * 킬 스위치. 새 주문은 항상 막고, {@code closePosition}이면 시장가로 청산까지 합니다.
     * 활성화도 함께 풀어서, 정지한 것이 다음 tick에 조용히 다시 켜지지 않게 합니다.
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

    /** 연결과 설정 점검. 읽기만 합니다 — 주문은 절대 내지 않습니다. */
    @PostMapping("/check")
    public LiveQueryService.CheckResult check() {
        return queryService.check();
    }
}
