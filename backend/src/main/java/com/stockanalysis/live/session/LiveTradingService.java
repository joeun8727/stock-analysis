package com.stockanalysis.live.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.ExitEvaluator;
import com.stockanalysis.backtest.ExitReason;
import com.stockanalysis.backtest.PremarketDecider;
import com.stockanalysis.backtest.spec.CapitalMode;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.data.EtfGroupService;
import com.stockanalysis.domain.Dataset;
import com.stockanalysis.domain.LiveConfig;
import com.stockanalysis.domain.LiveEvent;
import com.stockanalysis.domain.LiveEventRepository;
import com.stockanalysis.domain.LiveMode;
import com.stockanalysis.domain.LiveOrder;
import com.stockanalysis.domain.LiveOrderRepository;
import com.stockanalysis.domain.LivePremarketTick;
import com.stockanalysis.domain.LivePremarketTickRepository;
import com.stockanalysis.domain.LiveSession;
import com.stockanalysis.domain.LiveSessionRepository;
import com.stockanalysis.domain.LiveState;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.domain.StrategyRepository;
import com.stockanalysis.live.broker.BrokerClient;
import com.stockanalysis.live.broker.KisProperties;
import com.stockanalysis.live.broker.LivePriceFeed;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Runs one trading day, end to end, from a strategy that was already backtested.
 *
 * <p>The whole thing is a clock-driven state machine: {@link #tick(LocalDateTime)} is called
 * repeatedly and does whatever the current state and time call for. Time is a parameter rather than
 * being read inside, so a whole day can be replayed in a test in milliseconds.
 *
 * <p>Every judgement call is delegated to the same code the backtest uses —
 * {@link PremarketDecider} for the direction and {@link ExitEvaluator} for the exit. This class
 * decides <em>nothing</em> about trading; it moves quotes in, orders out, and records what happened.
 * That is the property that makes a backtest mean something.
 *
 * <p>Where it deliberately differs from the backtest, and why:
 * <ul>
 *   <li>The backtest closes on the last bar of the day; live closes at a configured time before the
 *       closing auction, because an order placed into the auction does not fill when we want.</li>
 *   <li>The backtest fills exactly at the stop price; live fills wherever the market is. The
 *       difference is slippage, and it always goes against us.</li>
 * </ul>
 */
@Service
public class LiveTradingService {

    private static final Logger log = LoggerFactory.getLogger(LiveTradingService.class);

    /** Give up on an unfilled order after this long rather than leaving it dangling. */
    private static final int ORDER_TIMEOUT_MINUTES = 5;

    private final LiveConfigService configService;
    private final LiveSessionRepository sessions;
    private final LiveOrderRepository orders;
    private final LivePremarketTickRepository ticks;
    private final LiveEventRepository events;
    private final StrategyRepository strategies;
    private final EtfGroupService etfGroups;
    private final VerificationGate gate;
    private final BrokerClient broker;
    private final LivePriceFeed feed;
    private final KisProperties props;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    private SessionRuntime runtime;
    private LocalDate refusalLoggedFor;

    public LiveTradingService(LiveConfigService configService,
                              LiveSessionRepository sessions,
                              LiveOrderRepository orders,
                              LivePremarketTickRepository ticks,
                              LiveEventRepository events,
                              StrategyRepository strategies,
                              EtfGroupService etfGroups,
                              VerificationGate gate,
                              BrokerClient broker,
                              LivePriceFeed feed,
                              KisProperties props,
                              ObjectMapper mapper,
                              EntityManager entityManager) {
        this.configService = configService;
        this.sessions = sessions;
        this.orders = orders;
        this.ticks = ticks;
        this.events = events;
        this.strategies = strategies;
        this.etfGroups = etfGroups;
        this.gate = gate;
        this.broker = broker;
        this.feed = feed;
        this.props = props;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ the clock

    /**
     * Advances the trading day. Synchronized because the scheduler and a manual API call can both
     * land here, and two threads placing an entry order is the one bug that costs money twice.
     */
    public synchronized void tick(LocalDateTime now) {
        LiveConfig config = configService.get();
        LocalDate today = now.toLocalDate();

        if (!config.isArmedFor(today)) {
            return; // the day was never switched on; the scheduler does nothing at all
        }

        LiveSession session = sessions.findByTradeDate(today).orElse(null);
        if (session == null) {
            session = tryStartSession(config, now);
            if (session == null) {
                return;
            }
        }
        if (session.getState().isFinished()) {
            return;
        }

        try {
            SessionRuntime rt = ensureRuntime(session, config);
            switch (session.getState()) {
                case ARMED -> maybeOpenWindow(session, rt, now);
                case WATCHING -> watchPremarket(session, rt, config, now);
                case ENTRY_PENDING -> pollEntry(session, rt, now);
                case HOLDING -> monitorPosition(session, rt, config, now);
                case EXIT_PENDING -> pollExit(session, rt, now);
                default -> {
                    // SKIPPED / CLOSED / HALTED are terminal; nothing to advance.
                }
            }
        } catch (RuntimeException e) {
            // Any unhandled failure while money is exposed is a halt, not a retry: we would rather
            // stop and be looked at than keep acting on a system we no longer understand.
            log.error("실투자 세션 처리 중 오류", e);
            record(session, "ERROR", e.getMessage(), null);
            if (session.getState().holdsExposure()) {
                halt(session, "처리 중 오류가 발생해 중단했습니다: " + e.getMessage(), false);
            }
        }
    }

    // ------------------------------------------------------------------ session start

    private LiveSession tryStartSession(LiveConfig config, LocalDateTime now) {
        StrategySpec spec = loadSpec(config.getStrategyId());
        if (spec == null) {
            refuseOnce(now.toLocalDate(), "전략을 찾을 수 없습니다.");
            return null;
        }
        LocalTime windowEnd = LocalTime.parse(spec.getPremarket().getEndTime());
        if (!now.toLocalTime().isBefore(windowEnd)) {
            // The pre-market window has already passed; starting now would trade on a partial
            // measurement, which is worse than not trading.
            refuseOnce(now.toLocalDate(), "장전 관측 시간(" + windowEnd + ")이 지나 오늘은 시작하지 않습니다.");
            return null;
        }

        VerificationGate.Status status = gate.evaluate(config.getStrategyId(), config);
        if (!status.verified()) {
            refuseOnce(now.toLocalDate(), status.reason());
            return null;
        }

        EtfGroupService.Resolved group;
        try {
            group = etfGroups.resolve(config.getEtfGroupId());
        } catch (RuntimeException e) {
            refuseOnce(now.toLocalDate(), e.getMessage());
            return null;
        }
        String levTicker = requireTicker(group.leverage(), now);
        String invTicker = requireTicker(group.inverse(), now);
        if (levTicker == null || invTicker == null) {
            return null;
        }
        if (config.getFuturesTicker() == null) {
            refuseOnce(now.toLocalDate(), "선물 종목코드가 설정되지 않았습니다.");
            return null;
        }

        LiveSession session = new LiveSession();
        session.setTradeDate(now.toLocalDate());
        session.setMode(props.getMode());
        session.setState(LiveState.ARMED);
        session.setStrategyId(config.getStrategyId());
        session.setEtfGroupId(config.getEtfGroupId());
        session.setVerifiedRunId(status.runId());
        session.setFuturesTicker(config.getFuturesTicker());
        LiveSession saved = sessions.save(session);

        record(saved, "SESSION_START",
                "실투자 세션 시작 (" + props.getMode() + ") — 근거 백테스트 #" + status.runId(),
                json(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "mode", String.valueOf(props.getMode()),
                        "leverage", levTicker,
                        "inverse", invTicker,
                        "futures", config.getFuturesTicker()))));
        log.info("실투자 세션 시작: {} 모드={} 전략={} 근거실행={}",
                saved.getTradeDate(), props.getMode(), config.getStrategyId(), status.runId());
        return saved;
    }

    private String requireTicker(Dataset dataset, LocalDateTime now) {
        if (dataset.getTicker() == null) {
            refuseOnce(now.toLocalDate(),
                    "데이터셋 '" + dataset.getSymbol() + "'에 종목코드가 없습니다. 데이터 화면에서 6자리 코드를 입력하세요.");
            return null;
        }
        return dataset.getTicker();
    }

    /** Logs a refusal once a day instead of on every tick. */
    private void refuseOnce(LocalDate date, String reason) {
        if (date.equals(refusalLoggedFor)) {
            return;
        }
        refusalLoggedFor = date;
        events.save(new LiveEvent(null, "REFUSED", reason, null));
        log.warn("실투자를 시작하지 않았습니다: {}", reason);
    }

    private SessionRuntime ensureRuntime(LiveSession session, LiveConfig config) {
        if (runtime != null && session.getId().equals(runtime.sessionId())) {
            return runtime;
        }
        StrategySpec spec = loadSpec(session.getStrategyId());
        EtfGroupService.Resolved group = etfGroups.resolve(session.getEtfGroupId());
        // The bar length is a property of the data the strategy was validated on; live bars must be
        // built at the same interval or MA20 stops meaning what it meant in the backtest.
        int interval = group.leverage().getBarIntervalMinutes();

        runtime = new SessionRuntime(session.getId(), session.getTradeDate(), spec, interval,
                group.leverage().getTicker(), group.inverse().getTicker(),
                group.leverage().getFeeRatePct(), group.inverse().getFeeRatePct());

        // Rebuilt after a restart: the quotes already collected today are in the database.
        List<PremarketDecider.PricePoint> stored = ticks.findBySessionIdOrderByTsAsc(session.getId())
                .stream()
                .map(t -> new PremarketDecider.PricePoint(t.getTs(), t.getPrice()))
                .toList();
        runtime.replaceSamples(stored);
        return runtime;
    }

    private StrategySpec loadSpec(Long strategyId) {
        if (strategyId == null) {
            return null;
        }
        Strategy strategy = strategies.findById(strategyId).orElse(null);
        if (strategy == null) {
            return null;
        }
        // Same trap as BacktestService: a managed Strategy gets dirty-checked on spec_json and
        // silently rewritten. Detach before reading the spec.
        entityManager.detach(strategy);
        return strategy.getSpec();
    }

    // ------------------------------------------------------------------ pre-market

    private void maybeOpenWindow(LiveSession session, SessionRuntime rt, LocalDateTime now) {
        LocalTime start = LocalTime.parse(rt.spec().getPremarket().getStartTime());
        if (now.toLocalTime().isBefore(start)) {
            return;
        }
        session.setState(LiveState.WATCHING);
        sessions.save(session);
        record(session, "WATCH_START", "장전 선물 관측 시작 (" + start + ")", null);
    }

    private void watchPremarket(LiveSession session, SessionRuntime rt, LiveConfig config, LocalDateTime now) {
        LocalTime end = LocalTime.parse(rt.spec().getPremarket().getEndTime());

        if (now.toLocalTime().isBefore(end)) {
            if (!rt.shouldPoll(now, config.getPollIntervalSec())) {
                return;
            }
            try {
                BrokerClient.Quote quote = feed.futuresPrice(session.getFuturesTicker());
                rt.addSample(now, quote.price());
                ticks.save(new LivePremarketTick(session.getId(), now, quote.price()));
            } catch (RuntimeException e) {
                // One failed poll is not fatal — the decision uses first and last of whatever we got.
                log.warn("장전 선물 시세 조회 실패: {}", e.toString());
            }
            return;
        }
        decideDirection(session, rt, config, now);
    }

    /**
     * The 09:00 call. The rule is not implemented here — it is {@link PremarketDecider}, the same
     * function the backtest ran over historical bars, fed with the quotes we just polled.
     */
    private void decideDirection(LiveSession session, SessionRuntime rt, LiveConfig config, LocalDateTime now) {
        PremarketDecider.Decision decision =
                PremarketDecider.decide(rt.premarketSamples(), rt.spec().getPremarket());
        session.setTrendPct(decision.trendPct());

        if (!decision.shouldTrade()) {
            session.setState(LiveState.SKIPPED);
            sessions.save(session);
            String detail = decision.trendPct() == null
                    ? "관측 표본이 부족했습니다 (" + decision.sampleCount() + "건)."
                    : String.format("장전 변화율 %.3f%%가 임계치 %.3f%%에 못 미쳐 오늘은 거래하지 않습니다.",
                    decision.trendPct(), Math.abs(rt.spec().getPremarket().getThresholdPct()));
            record(session, "SKIP", detail, null);
            log.info("오늘 거래 없음: {}", detail);
            return;
        }

        String instrument = decision.instrument();
        String ticker = rt.tickerFor(instrument);
        session.setChosenInstrument(instrument);
        session.setChosenTicker(ticker);

        double budget = budgetFor(rt.spec(), config);
        BrokerClient.Quote quote = feed.stockPrice(ticker);
        long quantity = (long) Math.floor(budget / quote.price());

        session.setBudgetAmount(budget);
        if (quantity <= 0) {
            session.setState(LiveState.SKIPPED);
            sessions.save(session);
            record(session, "SKIP",
                    String.format("예산 %,.0f원으로는 %s 1주(%,.0f원)를 살 수 없습니다.",
                            budget, ticker, quote.price()), null);
            return;
        }

        record(session, "DECISION",
                String.format("장전 변화율 %.3f%% → %s 매수 (%s, %d주 @ 약 %,.0f원)",
                        decision.trendPct(), instrument, ticker, quantity, quote.price()),
                json(java.util.Map.of(
                        "trendPct", decision.trendPct(),
                        "samples", decision.sampleCount(),
                        "thresholdPct", rt.spec().getPremarket().getThresholdPct())));

        sessions.save(session);
        placeOrder(session, rt, ticker, true, quantity, now);
    }

    /**
     * Per-trade budget, capped by the configured limit. {@code FIXED} spends the strategy's amount
     * every day; {@code COMPOUND} spends what the account actually holds, which is the honest live
     * reading of "reinvest the profits".
     */
    private double budgetFor(StrategySpec spec, LiveConfig config) {
        double fromSpec = spec.getCapital().getAmount();
        double budget = fromSpec;
        if (spec.getCapital().getMode() == CapitalMode.COMPOUND) {
            try {
                double cash = broker.balance().cashAvailable();
                budget = Math.min(cash, Double.MAX_VALUE);
            } catch (RuntimeException e) {
                log.warn("잔고 조회 실패, 전략의 고정 금액으로 진행합니다: {}", e.toString());
                budget = fromSpec;
            }
        }
        // The configured cap always wins. It is the last line between a mis-typed strategy amount
        // and the account.
        return Math.min(budget, config.getMaxOrderAmount());
    }

    // ------------------------------------------------------------------ orders

    private void placeOrder(LiveSession session, SessionRuntime rt, String ticker,
                            boolean buy, long quantity, LocalDateTime now) {
        String clientOrderId = clientOrderId(session, buy);
        if (orders.findByClientOrderId(clientOrderId).isPresent()) {
            // Already sent. This is the retry guard doing its job.
            log.warn("중복 주문 시도를 막았습니다: {}", clientOrderId);
            return;
        }

        LiveOrder order = new LiveOrder();
        order.setSessionId(session.getId());
        order.setSide(buy ? LiveOrder.BUY : LiveOrder.SELL);
        order.setTicker(ticker);
        order.setQuantity(quantity);
        order.setOrderType(LiveOrder.TYPE_MARKET);
        order.setClientOrderId(clientOrderId);
        order.setRequestedAt(now);
        // Persisted *before* sending: if the send throws after the broker accepted it, the row is
        // still here and the unique key stops a second attempt.
        order = orders.save(order);

        BrokerClient.OrderAck ack;
        try {
            ack = broker.placeOrder(new BrokerClient.OrderRequest(
                    ticker, buy, quantity, LiveOrder.TYPE_MARKET, null, clientOrderId));
        } catch (RuntimeException e) {
            order.setStatus(LiveOrder.STATUS_FAILED);
            order.setRawResponse(e.toString());
            orders.save(order);
            halt(session, (buy ? "매수" : "매도") + " 주문 전송에 실패했습니다: " + e.getMessage(), false);
            return;
        }

        order.setBrokerOrderNo(ack.brokerOrderNo());
        order.setRawResponse(ack.raw());
        order.setStatus(ack.accepted() ? LiveOrder.STATUS_SENT : LiveOrder.STATUS_REJECTED);
        orders.save(order);

        record(session, buy ? "ORDER_BUY" : "ORDER_SELL",
                (buy ? "매수" : "매도") + " 주문 " + (ack.accepted() ? "접수" : "거부")
                        + ": " + ticker + " " + quantity + "주 — " + ack.message(),
                ack.raw());

        if (!ack.accepted()) {
            halt(session, (buy ? "매수" : "매도") + " 주문이 거부되었습니다: " + ack.message(), false);
            return;
        }
        session.setState(buy ? LiveState.ENTRY_PENDING : LiveState.EXIT_PENDING);
        sessions.save(session);
    }

    /** Stable per (session, side): the same day cannot produce two entries or two exits. */
    private static String clientOrderId(LiveSession session, boolean buy) {
        return "S" + session.getId() + "-" + (buy ? "BUY" : "SELL");
    }

    private void pollEntry(LiveSession session, SessionRuntime rt, LocalDateTime now) {
        LiveOrder order = latestOrder(session, LiveOrder.BUY);
        if (order == null) {
            halt(session, "매수 주문 기록을 찾을 수 없습니다.", false);
            return;
        }
        BrokerClient.OrderFill fill = broker.orderStatus(order.getBrokerOrderNo(), order.getTicker());
        if (fill.rejected()) {
            applyFill(order, fill, LiveOrder.STATUS_REJECTED, now);
            halt(session, "매수 주문이 체결되지 않고 거부되었습니다.", false);
            return;
        }
        if (fill.filledQuantity() <= 0) {
            if (order.getRequestedAt().plusMinutes(ORDER_TIMEOUT_MINUTES).isBefore(now)) {
                halt(session, ORDER_TIMEOUT_MINUTES + "분 안에 매수가 체결되지 않아 중단했습니다. 증권사 화면에서 미체결 주문을 확인하세요.", false);
            }
            return;
        }

        applyFill(order, fill, fill.done() ? LiveOrder.STATUS_FILLED : LiveOrder.STATUS_PARTIAL, now);
        double price = fill.filledPrice() == null ? feed.stockPrice(order.getTicker()).price() : fill.filledPrice();

        session.setEntryTs(now);
        session.setEntryPrice(price);
        session.setQuantity(fill.filledQuantity());
        session.setState(LiveState.HOLDING);
        sessions.save(session);

        rt.setEntryBucket(com.stockanalysis.live.market.LiveBarBuilder
                .bucketStartOf(now, rt.barIntervalMinutes()));
        rt.setBarsHeld(0);
        warmUpIndicators(session, rt, now);
        feed.watch(order.getTicker());

        record(session, "ENTRY",
                String.format("매수 체결: %s %d주 @ %,.0f원", order.getTicker(), fill.filledQuantity(), price),
                fill.raw());
        log.info("진입: {} {}주 @ {}", order.getTicker(), fill.filledQuantity(), price);
    }

    private void pollExit(LiveSession session, SessionRuntime rt, LocalDateTime now) {
        LiveOrder order = latestOrder(session, LiveOrder.SELL);
        if (order == null) {
            halt(session, "매도 주문 기록을 찾을 수 없습니다.", false);
            return;
        }
        BrokerClient.OrderFill fill = broker.orderStatus(order.getBrokerOrderNo(), order.getTicker());
        if (fill.rejected()) {
            applyFill(order, fill, LiveOrder.STATUS_REJECTED, now);
            halt(session, "매도 주문이 거부되었습니다. 보유분이 남아 있으니 직접 확인하세요.", false);
            return;
        }
        if (fill.filledQuantity() <= 0) {
            if (order.getRequestedAt().plusMinutes(ORDER_TIMEOUT_MINUTES).isBefore(now)) {
                halt(session, ORDER_TIMEOUT_MINUTES + "분 안에 매도가 체결되지 않았습니다. 보유분을 직접 확인하세요.", false);
            }
            return;
        }

        applyFill(order, fill, LiveOrder.STATUS_FILLED, now);
        double exitPrice = fill.filledPrice() == null
                ? feed.stockPrice(order.getTicker()).price() : fill.filledPrice();
        closeSession(session, rt, exitPrice, now, fill.raw());
    }

    private void applyFill(LiveOrder order, BrokerClient.OrderFill fill, String status, LocalDateTime now) {
        order.setStatus(status);
        order.setFilledQuantity(fill.filledQuantity());
        order.setFilledPrice(fill.filledPrice());
        order.setFilledAt(now);
        order.setRawResponse(fill.raw());
        orders.save(order);
    }

    private LiveOrder latestOrder(LiveSession session, String side) {
        List<LiveOrder> found = orders.findBySessionIdAndSideOrderByRequestedAtAsc(session.getId(), side);
        return found.isEmpty() ? null : found.get(found.size() - 1);
    }

    // ------------------------------------------------------------------ holding

    private void monitorPosition(LiveSession session, SessionRuntime rt, LiveConfig config, LocalDateTime now) {
        String ticker = session.getChosenTicker();
        BrokerClient.Quote quote;
        try {
            quote = feed.stockPrice(ticker);
        } catch (RuntimeException e) {
            log.warn("보유 종목 시세 조회 실패: {}", e.toString());
            return; // a missed quote is not a reason to sell; the next tick tries again
        }

        double entryPrice = session.getEntryPrice();

        // Feeding the bar builder is what lets indicator-based sell rules work live exactly as they
        // did in the backtest. A completed bar is the moment those rules get evaluated.
        Optional<Bar> completed = rt.bars().accept(now, quote.price(), quote.cumulativeVolume());
        if (completed.isPresent()) {
            rt.countBar();
            Bar bar = completed.get();
            Bar prev = previousBar(rt);
            // lastOverall/lastBarOfDay are false on purpose: the engine's day-end branch keys off
            // "the last bar in the data", which live cannot know. Day-end is handled below, on the
            // clock, so the order goes in before the closing auction rather than into it.
            ExitEvaluator.Decision decision = ExitEvaluator.decide(
                    bar, prev, false, false, rt.barsHeld(),
                    rt.spec().getExit(), rt.spec().getExit().asGroup(), entryPrice, null, null);
            if (decision != null) {
                exitNow(session, rt, decision.reason(), now,
                        "봉 마감 판정 (" + bar.ts().toLocalTime() + ")");
                return;
            }
        }

        // Between bars, only the price-level exits can fire — the same intrabar check the backtest
        // makes with a bar's high and low, just arriving one tick at a time.
        ExitEvaluator.Decision live = ExitEvaluator.checkLivePrice(
                rt.spec().getExit(), entryPrice, quote.price(), now.toLocalTime());
        if (live != null) {
            exitNow(session, rt, live.reason(), now,
                    String.format("실시간 가격 %,.0f원이 %s선에 도달", quote.price(),
                            live.reason() == ExitReason.STOP_LOSS ? "손절" : "익절"));
            return;
        }

        if (breachesDailyLoss(session, config, quote.price())) {
            record(session, "LIMIT", "일일 손실 한도를 넘어 청산합니다.", null);
            exitNow(session, rt, ExitReason.SIGNAL, now, "일일 손실 한도 초과");
            return;
        }

        if (rt.spec().getExit().getCloseAtDayEnd() && !now.toLocalTime().isBefore(dayEnd(config))) {
            exitNow(session, rt, ExitReason.DAY_END, now,
                    "장 마감 청산 시각(" + config.getDayEndExitTime() + ") 도달");
        }
    }

    private boolean breachesDailyLoss(LiveSession session, LiveConfig config, double price) {
        if (session.getEntryPrice() == null || session.getQuantity() == null) {
            return false;
        }
        double unrealised = (price - session.getEntryPrice()) * session.getQuantity();
        return unrealised <= -Math.abs(config.getMaxDailyLoss());
    }

    private static LocalTime dayEnd(LiveConfig config) {
        return LocalTime.parse(config.getDayEndExitTime());
    }

    private Bar previousBar(SessionRuntime rt) {
        List<Bar> bars = rt.bars().completedBars();
        // completedBars() already includes the bar we just sealed, so "previous" is one before it.
        return bars.size() >= 2 ? bars.get(bars.size() - 2) : null;
    }

    private void exitNow(LiveSession session, SessionRuntime rt, ExitReason reason,
                         LocalDateTime now, String why) {
        session.setExitReason(reason.name());
        sessions.save(session);
        record(session, "EXIT_SIGNAL", reason + " — " + why, null);
        placeOrder(session, rt, session.getChosenTicker(), false, session.getQuantity(), now);
    }

    private void closeSession(LiveSession session, SessionRuntime rt, double exitPrice,
                              LocalDateTime now, String raw) {
        long qty = session.getQuantity() == null ? 0 : session.getQuantity();
        double entryPrice = session.getEntryPrice() == null ? 0 : session.getEntryPrice();
        double feeRate = rt.feePctFor(session.getChosenInstrument()) / 100.0;
        double buyValue = qty * entryPrice;
        double sellValue = qty * exitPrice;
        double fees = (buyValue + sellValue) * feeRate;

        session.setExitTs(now);
        session.setExitPrice(exitPrice);
        session.setFeeAmount(fees);
        session.setProfitAmount(sellValue - buyValue - fees);
        session.setState(LiveState.CLOSED);
        sessions.save(session);

        feed.unwatch(session.getChosenTicker());
        record(session, "CLOSED",
                String.format("청산 완료: %s @ %,.0f원, 손익 %,.0f원 (수수료 %,.0f원)",
                        session.getExitReason(), exitPrice, session.getProfitAmount(), fees),
                raw);
        log.info("청산: {} {}주 @ {} 손익 {}", session.getChosenTicker(), qty, exitPrice,
                session.getProfitAmount());
    }

    // ------------------------------------------------------------------ indicators

    /**
     * Fills the bar buffer from the broker's minute bars so indicator rules work from the first bar
     * held. Skipped entirely when the strategy has no indicator-based sell rules — most pre-market
     * strategies exit on price and time alone, and this costs a burst of API calls.
     */
    private void warmUpIndicators(LiveSession session, SessionRuntime rt, LocalDateTime now) {
        if (rt.isWarmedUp()) {
            return;
        }
        rt.markWarmedUp();
        if (rt.spec().getExit().asGroup().isEmpty()) {
            return; // no rule reads an indicator, so there is nothing to warm up
        }
        String ticker = session.getChosenTicker();
        try {
            // Long averages reach back past today: volMA120 on 3-minute bars is six hours of data.
            List<Bar> previous = broker.minuteBars(ticker, now.toLocalDate().minusDays(1));
            List<Bar> today = broker.minuteBars(ticker, now.toLocalDate());
            rt.bars().warmUp(previous);
            rt.bars().warmUp(today);
            record(session, "WARMUP",
                    "지표 워밍업 완료: 전일 " + previous.size() + "분봉 + 당일 " + today.size()
                            + "분봉 → " + rt.bars().completedBarCount() + "봉", null);
        } catch (RuntimeException e) {
            // Not fatal: unwarmed indicators stay NaN, and a NaN comparison is false, so the rule
            // simply does not fire. Better than firing on invented numbers.
            record(session, "WARMUP_FAILED",
                    "지표 워밍업에 실패했습니다. 지표 기반 매도 조건은 데이터가 쌓일 때까지 발동하지 않습니다: "
                            + e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------ halt & recovery

    /** Stops the day. Optionally liquidates first — the caller decides, never this method. */
    public synchronized LiveSession halt(LiveSession session, String reason, boolean closePosition) {
        if (closePosition && session.getState() == LiveState.HOLDING && session.getQuantity() != null) {
            try {
                SessionRuntime rt = ensureRuntime(session, configService.get());
                session.setExitReason(ExitReason.SIGNAL.name());
                placeOrder(session, rt, session.getChosenTicker(), false, session.getQuantity(),
                        LocalDateTime.now());
                record(session, "HALT_LIQUIDATE", "긴급 정지: 보유분 시장가 청산 주문을 냈습니다.", null);
                // Leave the state at EXIT_PENDING so the fill is still collected, then halt.
                return sessions.save(session);
            } catch (RuntimeException e) {
                record(session, "HALT_LIQUIDATE_FAILED", "긴급 청산 주문에 실패했습니다: " + e.getMessage(), null);
            }
        }
        session.setState(LiveState.HALTED);
        session.setHaltedReason(reason);
        LiveSession saved = sessions.save(session);
        record(saved, "HALTED", reason, null);
        log.error("실투자 중단: {}", reason);
        return saved;
    }

    /** Kill switch for today's session, if there is one. */
    public synchronized Optional<LiveSession> haltToday(String reason, boolean closePosition) {
        return sessions.findByTradeDate(LocalDate.now())
                .map(s -> halt(s, reason, closePosition));
    }

    /**
     * Restart safety. If a session is still holding, the position on record is compared with what
     * the broker actually reports. A mismatch halts rather than guesses — buying or selling to
     * "fix" a discrepancy we do not understand is how a small problem becomes a large one.
     */
    public synchronized void reconcileOnStartup() {
        LocalDate today = LocalDate.now();
        Optional<LiveSession> found = sessions.findByTradeDate(today);
        if (found.isEmpty()) {
            return;
        }
        LiveSession session = found.get();
        if (!session.getState().holdsExposure()) {
            return;
        }
        if (props.getMode() == LiveMode.DRY_RUN) {
            // A dry run holds nothing real; its in-memory position is simply gone.
            halt(session, "드라이런 중 재기동되어 세션을 종료했습니다.", false);
            return;
        }
        try {
            long held = broker.balance().quantityOf(session.getChosenTicker());
            long expected = session.getQuantity() == null ? 0 : session.getQuantity();
            if (session.getState() == LiveState.HOLDING && held != expected) {
                halt(session, "재기동 후 보유수량이 기록과 다릅니다 (기록 " + expected + "주 / 실제 " + held
                        + "주). 자동 매매를 멈췄으니 증권사 화면에서 직접 확인하세요.", false);
                return;
            }
            record(session, "RECONCILED",
                    "재기동 확인: 보유수량 " + held + "주가 기록과 일치합니다. 감시를 재개합니다.", null);
            if (session.getChosenTicker() != null) {
                feed.watch(session.getChosenTicker());
            }
        } catch (RuntimeException e) {
            halt(session, "재기동 후 잔고를 확인하지 못해 중단했습니다: " + e.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void record(LiveSession session, String type, String message, String detail) {
        events.save(new LiveEvent(session == null ? null : session.getId(), type, message, detail));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
