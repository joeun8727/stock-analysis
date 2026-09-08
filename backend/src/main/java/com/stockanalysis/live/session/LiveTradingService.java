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
 * 백테스트를 마친 전략으로 하루치 매매를 처음부터 끝까지 실행합니다.
 *
 * <p>전체가 시계로 굴러가는 상태 기계입니다: {@link #tick(LocalDateTime)}이 반복 호출되고,
 * 그때의 상태와 시각이 요구하는 일을 합니다. 시각을 안에서 읽지 않고 인자로 받기 때문에
 * 하루치를 테스트에서 수 밀리초에 재생할 수 있습니다.
 *
 * <p>판단은 전부 백테스트가 쓰는 그 코드에 위임합니다 — 방향은 {@link PremarketDecider},
 * 청산은 {@link ExitEvaluator}. 이 클래스는 매매에 대해 <em>아무것도</em> 결정하지 않습니다.
 * 시세를 넣고, 주문을 내보내고, 일어난 일을 기록할 뿐입니다. 백테스트가 의미를 갖는 건
 * 바로 이 성질 때문입니다.
 *
 * <p>백테스트와 의도적으로 다른 지점과 그 이유:
 * <ul>
 *   <li>백테스트는 그날 마지막 봉에 청산하지만, 실전은 종가 단일가 전의 설정된 시각에 냅니다.
 *       단일가에 걸린 주문은 원하는 때 체결되지 않기 때문입니다.</li>
 *   <li>백테스트는 손절가에 정확히 체결되지만 실전은 시장이 있는 자리에서 체결됩니다.
 *       그 차이가 슬리피지이고, 항상 우리에게 불리한 방향입니다.</li>
 * </ul>
 */
@Service
public class LiveTradingService {

    private static final Logger log = LoggerFactory.getLogger(LiveTradingService.class);

    /** 체결되지 않은 주문을 이만큼 지나면 포기합니다 — 매달아둔 채로 두지 않기 위해서. */
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

    // ------------------------------------------------------------------ 시계

    /**
     * 거래일을 한 칸 진행시킵니다. 스케줄러와 수동 API 호출이 동시에 여기로 들어올 수 있어
     * synchronized입니다 — 두 스레드가 진입 주문을 내는 것이 돈이 두 번 나가는 유일한 버그입니다.
     */
    public synchronized void tick(LocalDateTime now) {
        LiveConfig config = configService.get();
        LocalDate today = now.toLocalDate();

        if (!config.isArmedFor(today)) {
            return; // 그날을 켠 적이 없습니다. 스케줄러는 아무 일도 하지 않습니다.
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
                    // SKIPPED / CLOSED / HALTED은 종착 상태라 더 진행할 것이 없습니다.
                }
            }
        } catch (RuntimeException e) {
            // 돈이 걸린 상태에서 처리되지 않은 실패는 재시도가 아니라 정지입니다: 이해하지 못하는
            // 시스템 위에서 계속 움직이느니 멈춰서 사람이 보게 하는 편이 낫습니다.
            log.error("실투자 세션 처리 중 오류", e);
            record(session, "ERROR", e.getMessage(), null);
            if (session.getState().holdsExposure()) {
                halt(session, "처리 중 오류가 발생해 중단했습니다: " + e.getMessage(), false);
            }
        }
    }

    // ------------------------------------------------------------------ 세션 시작

    private LiveSession tryStartSession(LiveConfig config, LocalDateTime now) {
        StrategySpec spec = loadSpec(config.getStrategyId());
        if (spec == null) {
            refuseOnce(now.toLocalDate(), "전략을 찾을 수 없습니다.");
            return null;
        }
        LocalTime windowEnd = LocalTime.parse(spec.getPremarket().getEndTime());
        if (!now.toLocalTime().isBefore(windowEnd)) {
            // 장전 관측 구간이 이미 지났습니다. 지금 시작하면 반쪽짜리 측정으로 매매하게 되는데,
            // 그건 아예 매매하지 않는 것보다 나쁩니다.
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

    /** 거절 사유를 매 tick마다가 아니라 하루 한 번만 남깁니다. */
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
        // 봉 길이는 그 전략이 검증받은 데이터의 성질입니다. 실시간 봉도 같은 간격으로 조립해야
        // MA20이 백테스트에서 뜻하던 것을 계속 뜻합니다.
        int interval = group.leverage().getBarIntervalMinutes();

        runtime = new SessionRuntime(session.getId(), session.getTradeDate(), spec, interval,
                group.leverage().getTicker(), group.inverse().getTicker(),
                group.leverage().getFeeRatePct(), group.inverse().getFeeRatePct());

        // 재기동 후 재구성: 오늘 이미 모아둔 시세는 DB에 있습니다.
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
        // BacktestService와 같은 함정: 관리 상태의 Strategy는 spec_json이 더티 체킹되어
        // 조용히 덮어써집니다. 스펙을 읽기 전에 detach합니다.
        entityManager.detach(strategy);
        return strategy.getSpec();
    }

    // ------------------------------------------------------------------ 장전

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
                // 한 번 실패한 폴링은 치명적이지 않습니다 — 판단은 모은 것의 첫값과 끝값만 씁니다.
                log.warn("장전 선물 시세 조회 실패: {}", e.toString());
            }
            return;
        }
        decideDirection(session, rt, config, now);
    }

    /**
     * 09:00의 판단. 규칙은 여기 있지 않습니다 — {@link PremarketDecider}이고, 백테스트가 과거 봉에
     * 돌렸던 바로 그 함수에 방금 폴링한 시세를 그대로 넣습니다.
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
     * 거래당 예산. 설정한 한도로 잘립니다. {@code FIXED}는 매일 전략에 적힌 금액을 쓰고,
     * {@code COMPOUND}는 계좌가 실제로 들고 있는 금액을 씁니다 — "수익을 재투자한다"를
     * 실전에서 정직하게 읽으면 후자입니다.
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
        // 설정한 상한이 언제나 이깁니다. 전략 금액을 잘못 입력했을 때 계좌와의 사이에 남는
        // 마지막 한 줄입니다.
        return Math.min(budget, config.getMaxOrderAmount());
    }

    // ------------------------------------------------------------------ 주문

    private void placeOrder(LiveSession session, SessionRuntime rt, String ticker,
                            boolean buy, long quantity, LocalDateTime now) {
        String clientOrderId = clientOrderId(session, buy);
        if (orders.findByClientOrderId(clientOrderId).isPresent()) {
            // 이미 보냈습니다. 재시도 방지 장치가 제 일을 한 경우입니다.
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
        // 보내기 **전에** 저장합니다: 브로커가 받은 뒤에 전송이 예외로 끝나더라도 행은 남아 있고,
        // UNIQUE 키가 두 번째 시도를 막습니다.
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

    /** (세션, 방향)마다 고정: 같은 날에 진입 둘 또는 청산 둘이 나올 수 없습니다. */
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

    // ------------------------------------------------------------------ 보유 중

    private void monitorPosition(LiveSession session, SessionRuntime rt, LiveConfig config, LocalDateTime now) {
        String ticker = session.getChosenTicker();
        BrokerClient.Quote quote;
        try {
            quote = feed.stockPrice(ticker);
        } catch (RuntimeException e) {
            log.warn("보유 종목 시세 조회 실패: {}", e.toString());
            return; // 시세를 한 번 놓친 것은 파는 이유가 되지 않습니다. 다음 tick에서 다시 시도합니다.
        }

        double entryPrice = session.getEntryPrice();

        // 봉 조립기에 시세를 넣어야 지표 기반 매도 규칙이 백테스트와 똑같이 동작합니다.
        // 봉 하나가 완성되는 순간이 그 규칙을 평가하는 시점입니다.
        Optional<Bar> completed = rt.bars().accept(now, quote.price(), quote.cumulativeVolume());
        if (completed.isPresent()) {
            rt.countBar();
            Bar bar = completed.get();
            Bar prev = previousBar(rt);
            // lastOverall/lastBarOfDay를 false로 두는 건 의도적입니다: 엔진의 장마감 분기는
            // "데이터의 마지막 봉"을 기준으로 하는데 실전은 그걸 알 수 없습니다. 장마감은 아래에서
            // 시계로 처리해, 주문이 종가 단일가에 걸리는 대신 그 전에 나가게 합니다.
            ExitEvaluator.Decision decision = ExitEvaluator.decide(
                    bar, prev, false, false, rt.barsHeld(),
                    rt.spec().getExit(), rt.spec().getExit().asGroup(), entryPrice, null, null);
            if (decision != null) {
                exitNow(session, rt, decision.reason(), now,
                        "봉 마감 판정 (" + bar.ts().toLocalTime() + ")");
                return;
            }
        }

        // 봉과 봉 사이에는 가격선 청산만 발동할 수 있습니다 — 백테스트가 봉의 고가·저가로 하는
        // 장중 판정과 같은 것이, 틱 단위로 하나씩 도착하는 것뿐입니다.
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
        // completedBars()에는 방금 마감한 봉이 이미 들어 있어서, "직전"은 그것의 한 칸 앞입니다.
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

    // ------------------------------------------------------------------ 지표

    /**
     * 브로커의 분봉으로 봉 버퍼를 채워, 보유 첫 봉부터 지표 규칙이 동작하게 합니다. 전략에 지표
     * 기반 매도 규칙이 없으면 통째로 건너뜁니다 — 장전 전략 대부분은 가격과 시간만으로 청산하고,
     * 이 작업은 API 호출을 한 번에 몰아 쓰기 때문입니다.
     */
    private void warmUpIndicators(LiveSession session, SessionRuntime rt, LocalDateTime now) {
        if (rt.isWarmedUp()) {
            return;
        }
        rt.markWarmedUp();
        if (rt.spec().getExit().asGroup().isEmpty()) {
            return; // 지표를 읽는 규칙이 없으니 워밍업할 것도 없습니다.
        }
        String ticker = session.getChosenTicker();
        try {
            // 긴 이동평균은 오늘 이전까지 거슬러 올라갑니다: 3분봉의 volMA120은 6시간치입니다.
            List<Bar> previous = broker.minuteBars(ticker, now.toLocalDate().minusDays(1));
            List<Bar> today = broker.minuteBars(ticker, now.toLocalDate());
            rt.bars().warmUp(previous);
            rt.bars().warmUp(today);
            record(session, "WARMUP",
                    "지표 워밍업 완료: 전일 " + previous.size() + "분봉 + 당일 " + today.size()
                            + "분봉 → " + rt.bars().completedBarCount() + "봉", null);
        } catch (RuntimeException e) {
            // 치명적이지 않습니다: 워밍업이 안 된 지표는 NaN으로 남고, NaN 비교는 false이므로
            // 그 규칙은 그냥 발동하지 않습니다. 지어낸 숫자로 발동하는 것보다 낫습니다.
            record(session, "WARMUP_FAILED",
                    "지표 워밍업에 실패했습니다. 지표 기반 매도 조건은 데이터가 쌓일 때까지 발동하지 않습니다: "
                            + e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------ 정지와 복구

    /** 그날을 멈춥니다. 필요하면 먼저 청산합니다 — 그 결정은 호출자가 하며 이 메서드가 하지 않습니다. */
    public synchronized LiveSession halt(LiveSession session, String reason, boolean closePosition) {
        if (closePosition && session.getState() == LiveState.HOLDING && session.getQuantity() != null) {
            try {
                SessionRuntime rt = ensureRuntime(session, configService.get());
                session.setExitReason(ExitReason.SIGNAL.name());
                placeOrder(session, rt, session.getChosenTicker(), false, session.getQuantity(),
                        LocalDateTime.now());
                record(session, "HALT_LIQUIDATE", "긴급 정지: 보유분 시장가 청산 주문을 냈습니다.", null);
                // 체결은 계속 받아야 하므로 상태를 EXIT_PENDING에 둔 채로 정지합니다.
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

    /** 오늘 세션이 있다면, 그 세션에 대한 킬 스위치. */
    public synchronized Optional<LiveSession> haltToday(String reason, boolean closePosition) {
        return sessions.findByTradeDate(LocalDate.now())
                .map(s -> halt(s, reason, closePosition));
    }

    /**
     * 재기동 안전장치. 아직 보유 중인 세션이 있으면 기록된 포지션과 브로커가 실제로 알려주는 것을
     * 대조합니다. 어긋나면 추측하지 않고 정지합니다 — 이해하지 못한 불일치를 "고치려고" 사거나
     * 파는 것이 작은 문제를 큰 문제로 만드는 경로입니다.
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
            // 드라이런은 실제로 들고 있는 것이 없습니다. 메모리상의 포지션은 그냥 사라집니다.
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

    // ------------------------------------------------------------------ 보조

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
