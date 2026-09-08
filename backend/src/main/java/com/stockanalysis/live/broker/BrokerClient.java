package com.stockanalysis.live.broker;

import com.stockanalysis.backtest.Bar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything live trading needs from a broker. Kept narrow on purpose: the trading logic itself
 * lives in the shared {@code StrategySpec} evaluators, and this interface only moves prices in and
 * orders out.
 *
 * <p>Two implementations exist — {@link KisBrokerClient} talks to Korea Investment &amp; Securities,
 * and {@link DryRunBroker} records what would have been ordered without sending anything.
 */
public interface BrokerClient {

    /**
     * A price observation. {@code ts} is KST wall-clock, like every other timestamp here.
     * {@code cumulativeVolume} is the session's running traded volume when the source reports it
     * (null otherwise) — bar volume is the difference between two of these, since no feed gives
     * per-bar volume directly.
     */
    record Quote(String ticker, double price, LocalDateTime ts, boolean estimated,
                 Double cumulativeVolume) {

        public Quote(String ticker, double price, LocalDateTime ts, boolean estimated) {
            this(ticker, price, ts, estimated, null);
        }
    }

    record OrderRequest(String ticker, boolean buy, long quantity, String orderType,
                        Double limitPrice, String clientOrderId) {
    }

    /**
     * What the broker said when the order was accepted. A fill is <em>not</em> implied — that comes
     * from {@link #orderStatus}, because a market order can still be rejected or partially filled.
     */
    record OrderAck(String brokerOrderNo, boolean accepted, String message, String raw) {
    }

    record OrderFill(String brokerOrderNo, long filledQuantity, Double filledPrice,
                     boolean done, boolean rejected, String raw) {
    }

    /** One holding in the account. */
    record Holding(String ticker, long quantity, double avgPrice) {
    }

    record Balance(double cashAvailable, List<Holding> holdings, String raw) {

        public long quantityOf(String ticker) {
            return holdings.stream()
                    .filter(h -> h.ticker().equals(ticker))
                    .mapToLong(Holding::quantity)
                    .sum();
        }
    }

    /** Human-readable name of the account this client trades, for display and logs. */
    String describe();

    /**
     * Current price of a domestic futures contract. During the pre-market call auction this is an
     * indicative (estimated) price rather than a trade — {@link Quote#estimated()} says which,
     * and the pre-market decision depends on getting a moving value here.
     */
    Quote futuresQuote(String ticker);

    /** Current price of a listed stock or ETF. */
    Quote stockQuote(String ticker);

    /**
     * Minute bars for a stock/ETF on a given date, oldest first, with moving averages left as
     * {@code NaN} — the caller computes those so they match the backtest's definition exactly.
     * Used to warm up indicators before the session starts.
     */
    List<Bar> minuteBars(String ticker, LocalDate date);

    OrderAck placeOrder(OrderRequest request);

    OrderFill orderStatus(String brokerOrderNo, String ticker);

    Balance balance();
}
