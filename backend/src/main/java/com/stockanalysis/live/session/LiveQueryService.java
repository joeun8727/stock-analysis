package com.stockanalysis.live.session;

import com.stockanalysis.backtest.ExitEvaluator;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.data.EtfGroupService;
import com.stockanalysis.domain.LiveConfig;
import com.stockanalysis.domain.LiveEvent;
import com.stockanalysis.domain.LiveEventRepository;
import com.stockanalysis.domain.LiveMode;
import com.stockanalysis.domain.LiveOrder;
import com.stockanalysis.domain.LiveOrderRepository;
import com.stockanalysis.domain.LivePremarketTickRepository;
import com.stockanalysis.domain.LiveSession;
import com.stockanalysis.domain.LiveSessionRepository;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.domain.StrategyRepository;
import com.stockanalysis.live.broker.BrokerClient;
import com.stockanalysis.live.broker.KisProperties;
import com.stockanalysis.live.broker.LivePriceFeed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-side of live trading: what the screen needs to show, assembled from the session tables plus
 * a current quote. Kept apart from {@link LiveTradingService} so that looking at the page can never
 * advance the state machine.
 */
@Service
public class LiveQueryService {

    private final LiveSessionRepository sessions;
    private final LiveOrderRepository orders;
    private final LivePremarketTickRepository ticks;
    private final LiveEventRepository events;
    private final StrategyRepository strategies;
    private final EtfGroupService etfGroups;
    private final LiveConfigService configService;
    private final VerificationGate gate;
    private final LivePriceFeed feed;
    private final BrokerClient broker;
    private final KisProperties props;

    public LiveQueryService(LiveSessionRepository sessions, LiveOrderRepository orders,
                            LivePremarketTickRepository ticks, LiveEventRepository events,
                            StrategyRepository strategies, EtfGroupService etfGroups,
                            LiveConfigService configService, VerificationGate gate,
                            LivePriceFeed feed, BrokerClient broker, KisProperties props) {
        this.sessions = sessions;
        this.orders = orders;
        this.ticks = ticks;
        this.events = events;
        this.strategies = strategies;
        this.etfGroups = etfGroups;
        this.configService = configService;
        this.gate = gate;
        this.feed = feed;
        this.broker = broker;
        this.props = props;
    }

    public record TickPoint(String ts, double price) {
    }

    public record OrderView(String side, String ticker, long quantity, String status,
                            long filledQuantity, Double filledPrice, String requestedAt,
                            String filledAt, String brokerOrderNo) {
    }

    public record EventView(String ts, String type, String message) {
    }

    /** Everything the "today" panel shows. Nulls mean "not applicable yet", not "unknown". */
    public record TodayView(
            String mode,
            boolean armed,
            String armedDate,
            String state,
            String tradeDate,
            Long sessionId,
            String strategyName,
            Long verifiedRunId,
            String futuresTicker,
            Double trendPct,
            Double thresholdPct,
            String premarketStart,
            String premarketEnd,
            int premarketSamples,
            List<TickPoint> premarketTicks,
            String chosenInstrument,
            String chosenTicker,
            Double entryPrice,
            Long quantity,
            Double currentPrice,
            Double unrealisedProfit,
            Double stopPrice,
            Double takeProfitPrice,
            Double exitPrice,
            String exitReason,
            Double profitAmount,
            Double feeAmount,
            String haltedReason,
            String priceSource,
            String dayEndExitTime,
            List<OrderView> orders,
            List<EventView> events,
            VerificationGate.Status verification
    ) {
    }

    @Transactional(readOnly = true)
    public TodayView today() {
        LiveConfig config = configService.get();
        LocalDate today = LocalDate.now();
        VerificationGate.Status verification = gate.evaluate(config.getStrategyId(), config);
        Optional<LiveSession> found = sessions.findByTradeDate(today);

        if (found.isEmpty()) {
            return idleView(config, today, verification);
        }
        LiveSession s = found.get();
        StrategySpec spec = specOf(s.getStrategyId());

        Double currentPrice = null;
        Double unrealised = null;
        Double stop = null;
        Double take = null;
        if (s.getState() == com.stockanalysis.domain.LiveState.HOLDING && s.getChosenTicker() != null) {
            try {
                currentPrice = feed.stockPrice(s.getChosenTicker()).price();
                if (s.getEntryPrice() != null && s.getQuantity() != null) {
                    unrealised = (currentPrice - s.getEntryPrice()) * s.getQuantity();
                }
            } catch (RuntimeException e) {
                currentPrice = null; // showing nothing beats showing a stale number on a live position
            }
            if (spec != null && s.getEntryPrice() != null) {
                // Resolved at the current time, so the displayed levels follow the time bands the
                // same way the engine does while holding.
                LocalTime now = LocalTime.now();
                stop = nanToNull(ExitEvaluator.stopPriceOf(spec.getExit(), s.getEntryPrice(), now));
                take = nanToNull(ExitEvaluator.tpPriceOf(spec.getExit(), s.getEntryPrice(), now));
            }
        }

        return new TodayView(
                props.getMode().name(),
                config.isArmedFor(today),
                config.getArmedDate() == null ? null : config.getArmedDate().toString(),
                s.getState().name(),
                s.getTradeDate().toString(),
                s.getId(),
                strategyName(s.getStrategyId()),
                s.getVerifiedRunId(),
                s.getFuturesTicker(),
                s.getTrendPct(),
                spec == null ? null : spec.getPremarket().getThresholdPct(),
                spec == null ? null : spec.getPremarket().getStartTime(),
                spec == null ? null : spec.getPremarket().getEndTime(),
                ticks.findBySessionIdOrderByTsAsc(s.getId()).size(),
                ticks.findBySessionIdOrderByTsAsc(s.getId()).stream()
                        .map(t -> new TickPoint(t.getTs().toString(), t.getPrice())).toList(),
                s.getChosenInstrument(),
                s.getChosenTicker(),
                s.getEntryPrice(),
                s.getQuantity(),
                currentPrice,
                unrealised,
                stop,
                take,
                s.getExitPrice(),
                s.getExitReason(),
                s.getProfitAmount(),
                s.getFeeAmount(),
                s.getHaltedReason(),
                feed.sourceLabel(),
                config.getDayEndExitTime(),
                orders.findBySessionIdOrderByRequestedAtAsc(s.getId()).stream()
                        .map(LiveQueryService::toOrderView).toList(),
                events.findBySessionIdOrderByTsAsc(s.getId()).stream()
                        .map(LiveQueryService::toEventView).toList(),
                verification);
    }

    private TodayView idleView(LiveConfig config, LocalDate today, VerificationGate.Status verification) {
        StrategySpec spec = specOf(config.getStrategyId());
        return new TodayView(
                props.getMode().name(),
                config.isArmedFor(today),
                config.getArmedDate() == null ? null : config.getArmedDate().toString(),
                "IDLE",
                today.toString(),
                null,
                strategyName(config.getStrategyId()),
                config.getVerifiedRunId(),
                config.getFuturesTicker(),
                null,
                spec == null ? null : spec.getPremarket().getThresholdPct(),
                spec == null ? null : spec.getPremarket().getStartTime(),
                spec == null ? null : spec.getPremarket().getEndTime(),
                0, List.of(), null, null, null, null, null, null, null, null, null, null, null, null,
                null,
                feed.sourceLabel(),
                config.getDayEndExitTime(),
                List.of(), List.of(), verification);
    }

    /** History rows, newest day first. */
    @Transactional(readOnly = true)
    public List<SessionListItem> history() {
        return sessions.findAllByOrderByTradeDateDesc().stream()
                .map(s -> new SessionListItem(
                        s.getId(), s.getTradeDate().toString(), s.getMode().name(), s.getState().name(),
                        strategyName(s.getStrategyId()), s.getVerifiedRunId(),
                        s.getTrendPct(), s.getChosenInstrument(), s.getChosenTicker(),
                        s.getEntryPrice(), s.getExitPrice(), s.getQuantity(),
                        s.getExitReason(), s.getProfitAmount(), s.getFeeAmount(), s.getHaltedReason()))
                .toList();
    }

    public record SessionListItem(Long sessionId, String tradeDate, String mode, String state,
                                  String strategyName, Long verifiedRunId, Double trendPct,
                                  String chosenInstrument, String chosenTicker,
                                  Double entryPrice, Double exitPrice, Long quantity,
                                  String exitReason, Double profitAmount, Double feeAmount,
                                  String haltedReason) {
    }

    /** Connection check: proves credentials, account access and ticker setup without ordering. */
    public record CheckResult(String mode, String account, boolean tokenOk, boolean balanceOk,
                              Double cashAvailable, String futuresTicker, Double futuresPrice,
                              boolean futuresEstimated, List<String> problems) {
    }

    @Transactional(readOnly = true)
    public CheckResult check() {
        LiveConfig config = configService.get();
        List<String> problems = new java.util.ArrayList<>();
        boolean tokenOk = false;
        boolean balanceOk = false;
        Double cash = null;

        String account;
        try {
            account = broker.describe();
            tokenOk = true;
        } catch (RuntimeException e) {
            account = "(확인 실패)";
            problems.add("인증 실패: " + e.getMessage());
        }

        try {
            BrokerClient.Balance balance = broker.balance();
            cash = balance.cashAvailable();
            balanceOk = true;
        } catch (RuntimeException e) {
            problems.add("잔고 조회 실패: " + e.getMessage());
        }

        Double futuresPrice = null;
        boolean estimated = false;
        if (config.getFuturesTicker() == null) {
            problems.add("선물 종목코드가 설정되지 않았습니다.");
        } else {
            try {
                BrokerClient.Quote q = broker.futuresQuote(config.getFuturesTicker());
                futuresPrice = q.price();
                estimated = q.estimated();
            } catch (RuntimeException e) {
                problems.add("선물 시세 조회 실패: " + e.getMessage());
            }
        }

        if (config.getEtfGroupId() == null) {
            problems.add("ETF 그룹이 선택되지 않았습니다.");
        } else {
            try {
                EtfGroupService.Resolved g = etfGroups.resolve(config.getEtfGroupId());
                if (g.leverage().getTicker() == null) {
                    problems.add("레버리지 데이터셋 '" + g.leverage().getSymbol() + "'에 종목코드가 없습니다.");
                }
                if (g.inverse().getTicker() == null) {
                    problems.add("인버스 데이터셋 '" + g.inverse().getSymbol() + "'에 종목코드가 없습니다.");
                }
            } catch (RuntimeException e) {
                problems.add(e.getMessage());
            }
        }

        VerificationGate.Status status = gate.evaluate(config.getStrategyId(), config);
        if (!status.verified()) {
            problems.add(status.reason());
        }

        if (props.getMode() == LiveMode.DRY_RUN) {
            problems.add("현재 DRY_RUN 모드입니다 — 판단과 기록만 하고 주문은 나가지 않습니다.");
        }

        return new CheckResult(props.getMode().name(), account, tokenOk, balanceOk, cash,
                config.getFuturesTicker(), futuresPrice, estimated, problems);
    }

    // ------------------------------------------------------------------ helpers

    private StrategySpec specOf(Long strategyId) {
        if (strategyId == null) {
            return null;
        }
        return strategies.findById(strategyId).map(Strategy::getSpec).orElse(null);
    }

    private String strategyName(Long strategyId) {
        if (strategyId == null) {
            return null;
        }
        return strategies.findById(strategyId).map(Strategy::getName).orElse("(삭제됨)");
    }

    private static Double nanToNull(double value) {
        return Double.isNaN(value) ? null : value;
    }

    private static OrderView toOrderView(LiveOrder o) {
        return new OrderView(o.getSide(), o.getTicker(), o.getQuantity(), o.getStatus(),
                o.getFilledQuantity(), o.getFilledPrice(),
                o.getRequestedAt() == null ? null : o.getRequestedAt().toString(),
                o.getFilledAt() == null ? null : o.getFilledAt().toString(),
                o.getBrokerOrderNo());
    }

    private static EventView toEventView(LiveEvent e) {
        return new EventView(e.getTs() == null ? null : e.getTs().toString(), e.getType(), e.getMessage());
    }
}
