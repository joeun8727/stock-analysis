package com.stockanalysis.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.spec.CapitalMode;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.backtest.spec.TargetType;
import com.stockanalysis.data.EtfGroupService;
import com.stockanalysis.domain.Dataset;
import com.stockanalysis.domain.Kind;
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
import com.stockanalysis.domain.Market;
import com.stockanalysis.domain.Strategy;
import com.stockanalysis.domain.StrategyRepository;
import com.stockanalysis.live.broker.BrokerClient;
import com.stockanalysis.live.broker.KisProperties;
import com.stockanalysis.live.broker.LivePriceFeed;
import com.stockanalysis.live.session.LiveConfigService;
import com.stockanalysis.live.session.LiveTradingService;
import com.stockanalysis.live.session.VerificationGate;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 하루치 매매 전체를 수 밀리초에 재생합니다.
 *
 * <p>{@code tick}이 시각을 인자로 받으므로, 여기서는 장전 구간부터 장 마감까지 시계를 걸어가며
 * 상태 기계가 전략이 말한 대로 움직였는지 확인합니다 — 검증 게이트가 거부한 전략은 매매하지
 * 않는다는 것까지 포함해서. 그게 "먼저 백테스트"를 실제로 지켜주는 규칙입니다.
 */
class LiveSessionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 24);
    private static final long STRATEGY_ID = 7L;
    private static final long GROUP_ID = 3L;
    private static final String LEV = "122630";
    private static final String INV = "252670";
    private static final String FUT = "101W12";

    private LiveTradingService service;
    private FakeBroker broker;
    private LivePriceFeed feed;
    private LiveConfig config;
    private VerificationGate gate;
    private final Map<Long, LiveSession> savedSessions = new HashMap<>();
    private final List<LiveOrder> savedOrders = new ArrayList<>();
    private final List<LiveEvent> savedEvents = new ArrayList<>();

    private double futuresPrice = 100.0;
    private double etfPrice = 10_000.0;

    /** 주문을 기록하고, 피드가 보여주는 가격에 체결시킵니다. */
    private static final class FakeBroker implements BrokerClient {
        private final AtomicLong seq = new AtomicLong(1);
        private final Map<String, Long> filledQty = new HashMap<>();
        private final Map<String, Double> filledPrice = new HashMap<>();
        final List<OrderRequest> requests = new ArrayList<>();
        double fillPrice = 10_000.0;
        boolean rejectNext;

        @Override
        public String describe() {
            return "테스트 계좌";
        }

        @Override
        public Quote futuresQuote(String ticker) {
            return new Quote(ticker, 0, LocalDateTime.now(), false);
        }

        @Override
        public Quote stockQuote(String ticker) {
            return new Quote(ticker, fillPrice, LocalDateTime.now(), false);
        }

        @Override
        public List<Bar> minuteBars(String ticker, LocalDate date) {
            return List.of();
        }

        @Override
        public OrderAck placeOrder(OrderRequest request) {
            requests.add(request);
            if (rejectNext) {
                return new OrderAck(null, false, "거부됨", "{}");
            }
            String no = "T" + seq.getAndIncrement();
            filledQty.put(no, request.quantity());
            filledPrice.put(no, fillPrice);
            return new OrderAck(no, true, "접수", "{}");
        }

        @Override
        public OrderFill orderStatus(String brokerOrderNo, String ticker) {
            Long qty = filledQty.get(brokerOrderNo);
            if (qty == null) {
                return new OrderFill(brokerOrderNo, 0, null, false, false, "{}");
            }
            return new OrderFill(brokerOrderNo, qty, filledPrice.get(brokerOrderNo), true, false, "{}");
        }

        @Override
        public Balance balance() {
            return new Balance(100_000_000, List.of(), "{}");
        }
    }

    private static Dataset dataset(String symbol, String ticker, Market market, Kind kind) {
        Dataset d = new Dataset();
        d.setSymbol(symbol);
        d.setTicker(ticker);
        d.setMarket(market);
        d.setKind(kind);
        d.setFeeRatePct(0.015);
        d.setBarIntervalMinutes(3);
        return d;
    }

    private StrategySpec spec() {
        StrategySpec spec = new StrategySpec();
        spec.setName("장전추세");
        spec.setTargetType(TargetType.ETF);
        spec.getPremarket().setEnabled(true);
        spec.getPremarket().setStartTime("08:45");
        spec.getPremarket().setEndTime("09:00");
        spec.getPremarket().setThresholdPct(0.1);
        spec.getExit().setStopLossPct(1.0);
        spec.getExit().setTakeProfitPct(2.0);
        spec.getExit().setCloseAtDayEnd(true);
        spec.getCapital().setAmount(10_000_000);
        spec.getCapital().setMode(CapitalMode.FIXED);
        return spec;
    }

    @BeforeEach
    void setUp() {
        config = new LiveConfig();
        config.setId(LiveConfig.ID);
        config.setStrategyId(STRATEGY_ID);
        config.setEtfGroupId(GROUP_ID);
        config.setFuturesTicker(FUT);
        config.setArmedDate(DAY);
        config.setMaxOrderAmount(10_000_000);
        config.setMaxDailyLoss(1_000_000);
        config.setPollIntervalSec(10);
        config.setDayEndExitTime("15:15");

        LiveConfigService configService = mock(LiveConfigService.class);
        when(configService.get()).thenReturn(config);

        LiveSessionRepository sessions = mock(LiveSessionRepository.class);
        AtomicLong sessionSeq = new AtomicLong(1);
        when(sessions.findByTradeDate(any())).thenAnswer(inv ->
                savedSessions.values().stream()
                        .filter(s -> s.getTradeDate().equals(inv.getArgument(0)))
                        .findFirst());
        when(sessions.save(any(LiveSession.class))).thenAnswer(inv -> {
            LiveSession s = inv.getArgument(0);
            if (s.getId() == null) {
                // 세션마다 client order id가 안정적이도록 identity 컬럼을 흉내 냅니다.
                setId(s, sessionSeq.getAndIncrement());
            }
            savedSessions.put(s.getId(), s);
            return s;
        });

        LiveOrderRepository orders = mock(LiveOrderRepository.class);
        AtomicLong orderSeq = new AtomicLong(1);
        when(orders.save(any(LiveOrder.class))).thenAnswer(inv -> {
            LiveOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                setId(o, orderSeq.getAndIncrement());
                savedOrders.add(o);
            }
            return o;
        });
        when(orders.findByClientOrderId(anyString())).thenAnswer(inv ->
                savedOrders.stream()
                        .filter(o -> o.getClientOrderId().equals(inv.getArgument(0)))
                        .findFirst());
        when(orders.findBySessionIdAndSideOrderByRequestedAtAsc(anyLong(), anyString()))
                .thenAnswer(inv -> savedOrders.stream()
                        .filter(o -> o.getSessionId().equals(inv.getArgument(0))
                                && o.getSide().equals(inv.getArgument(1)))
                        .toList());

        LivePremarketTickRepository ticks = mock(LivePremarketTickRepository.class);
        when(ticks.save(any(LivePremarketTick.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticks.findBySessionIdOrderByTsAsc(anyLong())).thenReturn(List.of());

        LiveEventRepository events = mock(LiveEventRepository.class);
        when(events.save(any(LiveEvent.class))).thenAnswer(inv -> {
            savedEvents.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        Strategy strategy = new Strategy();
        strategy.setName("장전추세");
        strategy.setSpec(spec());
        StrategyRepository strategies = mock(StrategyRepository.class);
        when(strategies.findById(STRATEGY_ID)).thenReturn(Optional.of(strategy));

        EtfGroupService etfGroups = mock(EtfGroupService.class);
        when(etfGroups.resolve(GROUP_ID)).thenReturn(new EtfGroupService.Resolved(
                dataset("KODEX레버리지", LEV, Market.ETF, Kind.LEVERAGE),
                dataset("KODEX인버스", INV, Market.ETF, Kind.INVERSE),
                dataset("KOSPI200선물", FUT, Market.FUTURES, Kind.SINGLE)));

        gate = mock(VerificationGate.class);
        when(gate.evaluate(any(), any())).thenReturn(new VerificationGate.Status(
                true, "검증됨", 42L, null, 120, 61.0, 1.4, 12.0, 3_000_000.0,
                "2026-01-02T09:00", "2026-06-30T15:30", 180));

        broker = new FakeBroker();
        feed = mock(LivePriceFeed.class);
        when(feed.futuresPrice(anyString())).thenAnswer(inv ->
                new BrokerClient.Quote(FUT, futuresPrice, LocalDateTime.now(), true));
        when(feed.stockPrice(anyString())).thenAnswer(inv ->
                new BrokerClient.Quote(LEV, etfPrice, LocalDateTime.now(), false, null));
        when(feed.sourceLabel()).thenReturn("REST");

        KisProperties props = new KisProperties();
        props.setMode(LiveMode.PAPER);

        service = new LiveTradingService(configService, sessions, orders, ticks, events,
                strategies, etfGroups, gate, broker, feed, props, new ObjectMapper(),
                mock(EntityManager.class));
    }

    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void tick(int hour, int minute) {
        service.tick(LocalDateTime.of(DAY, LocalTime.of(hour, minute)));
    }

    private LiveSession session() {
        return savedSessions.values().iterator().next();
    }

    /** 장전 선물이 오르는 구간을 걸어가며 레버리지 ETF에 진입합니다. */
    private void runUpToEntry() {
        tick(8, 44);                       // 세션 생성, ARMED
        tick(8, 45);                       // 관측 구간 시작
        for (int m = 46; m <= 58; m++) {   // 시세가 100 -> 약 100.4로 상승
            futuresPrice = 100.0 + (m - 45) * 0.03;
            tick(8, m);
        }
        tick(9, 0);                        // 판단 + 매수
        tick(9, 1);                        // 체결
    }

    @Test
    void aRisingPremarketBuysLeverageAndTheOrderIsSizedToTheBudget() {
        runUpToEntry();

        LiveSession s = session();
        assertEquals(LiveState.HOLDING, s.getState());
        assertEquals("LEVERAGE", s.getChosenInstrument());
        assertEquals(LEV, s.getChosenTicker());
        assertTrue(s.getTrendPct() > 0.1, "장전 변화율이 임계치를 넘어야 진입합니다");

        // 예산 10,000,000 / 주당 10,000 = 정확히 1,000주.
        assertEquals(1000L, s.getQuantity());
        assertEquals(10_000.0, s.getEntryPrice(), 1e-9);
        assertEquals(1, broker.requests.size());
        assertTrue(broker.requests.get(0).buy());
    }

    @Test
    void aFlatPremarketSkipsTheDayWithoutOrdering() {
        tick(8, 44);
        tick(8, 45);
        for (int m = 46; m <= 58; m++) {
            futuresPrice = 100.0 + (m - 45) * 0.001; // 구간이 끝날 때 약 0.013%
            tick(8, m);
        }
        tick(9, 0);

        LiveSession s = session();
        assertEquals(LiveState.SKIPPED, s.getState());
        assertNull(s.getChosenInstrument());
        assertTrue(broker.requests.isEmpty(), "임계치 미달이면 주문이 나가면 안 됩니다");
    }

    /** 장전이 하락이면 인버스를 삽니다 — 레버리지 경우의 거울상입니다. */
    @Test
    void aFallingPremarketBuysInverse() {
        tick(8, 44);
        tick(8, 45);
        for (int m = 46; m <= 58; m++) {
            futuresPrice = 100.0 - (m - 45) * 0.03;
            tick(8, m);
        }
        tick(9, 0);
        tick(9, 1);

        LiveSession s = session();
        assertEquals("INVERSE", s.getChosenInstrument());
        assertEquals(INV, s.getChosenTicker());
        assertTrue(s.getTrendPct() < -0.1);
    }

    @Test
    void priceFallingThroughTheStopSellsAndClosesTheSession() {
        runUpToEntry();

        etfPrice = 9_900.0;            // 정확히 1% 손절선
        broker.fillPrice = 9_900.0;
        tick(9, 5);                    // 청산 시그널 + 매도 주문
        assertEquals(LiveState.EXIT_PENDING, session().getState());

        tick(9, 6);                    // 체결
        LiveSession s = session();
        assertEquals(LiveState.CLOSED, s.getState());
        assertEquals("STOP_LOSS", s.getExitReason());
        assertEquals(2, broker.requests.size());
        assertTrue(!broker.requests.get(1).buy());

        // 1,000주가 10,000에서 9,900으로, 양쪽 수수료를 빼기 전 -100,000입니다.
        double expectedFees = (1000 * 10_000 + 1000 * 9_900) * 0.00015;
        assertEquals(expectedFees, s.getFeeAmount(), 1e-6);
        assertEquals(-100_000 - expectedFees, s.getProfitAmount(), 1e-6);
    }

    @Test
    void priceReachingTheTargetTakesProfit() {
        runUpToEntry();

        etfPrice = 10_200.0;           // 2% 목표가
        broker.fillPrice = 10_200.0;
        tick(9, 30);
        tick(9, 31);

        LiveSession s = session();
        assertEquals(LiveState.CLOSED, s.getState());
        assertEquals("TAKE_PROFIT", s.getExitReason());
        assertTrue(s.getProfitAmount() > 0);
    }

    /**
     * 실전은 "데이터의 마지막 봉"이 아니라 시계로 장마감 청산을 하므로, 주문이 종가 단일가에
     * 걸리는 대신 그 전에 나갑니다.
     */
    @Test
    void theDayEndTimeForcesAnExitEvenWithoutAPriceTrigger() {
        runUpToEntry();

        etfPrice = 10_050.0;           // 손절과 목표 사이: 가격 기준 청산은 없음
        broker.fillPrice = 10_050.0;
        tick(14, 0);
        assertEquals(LiveState.HOLDING, session().getState(), "장중에는 계속 보유해야 합니다");

        tick(15, 15);
        assertEquals(LiveState.EXIT_PENDING, session().getState());
        tick(15, 16);

        LiveSession s = session();
        assertEquals(LiveState.CLOSED, s.getState());
        assertEquals("DAY_END", s.getExitReason());
    }

    @Test
    void breachingTheDailyLossLimitLiquidatesBeforeTheStopWouldFire() {
        config.setMaxDailyLoss(50_000);  // 1% 손절의 100,000보다 빡빡하게
        runUpToEntry();

        etfPrice = 9_940.0;              // 평가손 -60,000, 하지만 -0.6%에 불과
        broker.fillPrice = 9_940.0;
        tick(9, 10);

        assertEquals(LiveState.EXIT_PENDING, session().getState());
        assertTrue(savedEvents.stream().anyMatch(e -> "LIMIT".equals(e.getType())),
                "손실 한도 초과가 기록으로 남아야 합니다");
    }

    /** "먼저 백테스트"를 관례 이상으로 만드는 규칙. */
    @Test
    void anUnverifiedStrategyNeverStartsASession() {
        when(gate.evaluate(any(), any())).thenReturn(
                VerificationGate.Status.blocked("이 전략은 아직 백테스트를 한 적이 없습니다."));

        tick(8, 44);
        tick(8, 45);
        tick(9, 0);

        assertTrue(savedSessions.isEmpty(), "검증 안 된 전략은 세션 자체가 만들어지면 안 됩니다");
        assertTrue(broker.requests.isEmpty());
        assertTrue(savedEvents.stream().anyMatch(e -> "REFUSED".equals(e.getType())));
    }

    @Test
    void aDayThatWasNeverArmedDoesNothingAtAll() {
        config.setArmedDate(null);

        tick(8, 44);
        tick(9, 0);
        tick(10, 0);

        assertTrue(savedSessions.isEmpty());
        assertTrue(broker.requests.isEmpty());
        assertTrue(savedEvents.isEmpty());
    }

    /** 관측 구간이 지난 뒤에 도착했다면 그 움직임을 본 적이 없다는 뜻입니다. 추측하지 않습니다. */
    @Test
    void startingAfterTheWindowHasClosedRefusesToTrade() {
        tick(9, 30);

        assertTrue(savedSessions.isEmpty());
        assertTrue(broker.requests.isEmpty());
    }

    @Test
    void aRejectedEntryHaltsInsteadOfRetrying() {
        broker.rejectNext = true;

        tick(8, 44);
        tick(8, 45);
        for (int m = 46; m <= 58; m++) {
            futuresPrice = 100.0 + (m - 45) * 0.03;
            tick(8, m);
        }
        tick(9, 0);

        LiveSession s = session();
        assertEquals(LiveState.HALTED, s.getState());
        assertNotNull(s.getHaltedReason());
        assertEquals(1, broker.requests.size(), "거부된 주문을 다시 내면 안 됩니다");
    }

    /** 멱등 키는 같은 상태에서 tick이 반복돼도 그대로여야 합니다. */
    @Test
    void repeatedTicksNeverSendASecondEntryOrder() {
        runUpToEntry();

        etfPrice = 10_050.0;
        tick(9, 2);
        tick(9, 3);
        tick(9, 4);

        assertEquals(1, broker.requests.size());
        assertEquals(LiveState.HOLDING, session().getState());
    }

    @Test
    void aFinishedDayIsNotRestarted() {
        runUpToEntry();
        etfPrice = 10_200.0;
        broker.fillPrice = 10_200.0;
        tick(9, 30);
        tick(9, 31);
        assertEquals(LiveState.CLOSED, session().getState());

        int ordersSoFar = broker.requests.size();
        tick(10, 0);
        tick(11, 0);

        assertEquals(ordersSoFar, broker.requests.size());
        assertEquals(LiveState.CLOSED, session().getState());
    }
}
