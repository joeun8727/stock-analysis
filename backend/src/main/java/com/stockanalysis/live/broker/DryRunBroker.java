package com.stockanalysis.live.broker;

import com.stockanalysis.backtest.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 주문을 한 건도 보내지 않고 하루치 매매를 전부 돌립니다.
 *
 * <p>시세는 진짜입니다 — 감싸고 있는 클라이언트에 위임하므로, 여기서 내리는 판단은 실전이
 * 내릴 판단 그대로입니다. 시뮬레이션되는 건 주문 부분뿐입니다: 방금 본 시세에 즉시 "체결"됩니다.
 * 낙관적이지만(슬리피지도, 대기열도, 거부도 없음) 그건 백테스트가 가진 낙관과 정확히 같은
 * 것이라서, 드라이런이 백테스트와 어긋난다면 잡음이 아니라 진짜로 뭔가 잘못된 것입니다.
 *
 * <p>모의투자 앞 단계입니다: 계좌에 아무 일도 일어날 수 없는 상태로 일정과 판단과 배관을
 * 검증합니다.
 */
public class DryRunBroker implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(DryRunBroker.class);

    private final BrokerClient quotes;
    private final AtomicLong orderSeq = new AtomicLong(1);
    private final Map<String, SimulatedOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, Long> positions = new ConcurrentHashMap<>();

    private record SimulatedOrder(String ticker, boolean buy, long quantity, double price) {
    }

    public DryRunBroker(BrokerClient quotes) {
        this.quotes = quotes;
    }

    @Override
    public String describe() {
        return "드라이런 (주문 미전송, 시세는 실제 " + quotes.describe() + ")";
    }

    @Override
    public Quote futuresQuote(String ticker) {
        return quotes.futuresQuote(ticker);
    }

    @Override
    public Quote stockQuote(String ticker) {
        return quotes.stockQuote(ticker);
    }

    @Override
    public List<Bar> minuteBars(String ticker, LocalDate date) {
        return quotes.minuteBars(ticker, date);
    }

    @Override
    public OrderAck placeOrder(OrderRequest request) {
        double price = quotes.stockQuote(request.ticker()).price();
        String orderNo = "DRY-" + orderSeq.getAndIncrement();
        orders.put(orderNo, new SimulatedOrder(request.ticker(), request.buy(), request.quantity(), price));
        positions.merge(request.ticker(),
                request.buy() ? request.quantity() : -request.quantity(), Long::sum);
        log.info("[드라이런] {} {} {}주 @ {} (실제 주문 아님)",
                request.buy() ? "매수" : "매도", request.ticker(), request.quantity(), price);
        return new OrderAck(orderNo, true, "드라이런: 주문을 보내지 않았습니다.",
                "{\"dryRun\":true,\"price\":" + price + "}");
    }

    @Override
    public OrderFill orderStatus(String brokerOrderNo, String ticker) {
        SimulatedOrder o = orders.get(brokerOrderNo);
        if (o == null) {
            return new OrderFill(brokerOrderNo, 0, null, false, true, "{\"dryRun\":true,\"found\":false}");
        }
        return new OrderFill(brokerOrderNo, o.quantity(), o.price(), true, false,
                "{\"dryRun\":true,\"filled\":" + o.quantity() + "}");
    }

    @Override
    public Balance balance() {
        List<Holding> holdings = positions.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new Holding(e.getKey(), e.getValue(), 0.0))
                .toList();
        // 현금은 사실상 무제한으로 보고합니다: 예산 한도는 live_config에서 오고, 드라이런이
        // 실제로 쓰지도 않는 잔고 때문에 막히면 안 됩니다.
        return new Balance(Double.MAX_VALUE, holdings, "{\"dryRun\":true}");
    }

    /** 시뮬레이션된 체결 시각. 호출자가 말이 되는 값을 기록할 수 있도록. */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
