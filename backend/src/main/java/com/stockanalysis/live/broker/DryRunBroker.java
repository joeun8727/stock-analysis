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
 * Runs a full trading day without sending a single order.
 *
 * <p>Quotes are real — they are delegated to the wrapped client, so the decisions this makes are the
 * decisions a live run would make. Only the order leg is simulated: a "fill" happens immediately at
 * the quote we just saw. That is optimistic (no slippage, no queue, no rejection), which is exactly
 * the same optimism the backtest has, so a dry run that disagrees with the backtest means something
 * is genuinely wrong rather than merely noisy.
 *
 * <p>This is the step before paper trading: it proves the schedule, the decision and the plumbing
 * while nothing can go wrong with an account.
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
        // Cash is reported as unlimited-ish: budget limits come from live_config, and a dry run
        // should never be blocked by a balance it isn't really spending.
        return new Balance(Double.MAX_VALUE, holdings, "{\"dryRun\":true}");
    }

    /** Simulated fill timestamp, so callers can record something sensible. */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
