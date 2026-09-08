package com.stockanalysis.live.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.live.broker.BrokerClient.Quote;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The one place live trading asks "what is this worth right now".
 *
 * <p>Hybrid by design: the WebSocket gives tick-speed reaction while a position is open, and REST
 * covers everything else — the pre-market futures window, and any moment the socket is down or
 * quiet. The fallback is automatic and silent to callers, so a dropped connection never stops the
 * session; {@link #sourceLabel()} exists so the screen can still say which path is live.
 *
 * <p>Futures are always REST: the pre-market call auction publishes an indicative price rather than
 * trades, and the trade stream has nothing to say until the market actually opens.
 */
@Component
public class LivePriceFeed {

    private final BrokerClient broker;
    private final KisProperties props;
    private final KisWebSocketPriceFeed stream;

    private volatile Instant lastRestQuoteAt = Instant.EPOCH;

    public LivePriceFeed(BrokerClient broker, KisProperties props, KisTokenStore tokens,
                         ObjectMapper mapper) {
        this.broker = broker;
        this.props = props;
        this.stream = new KisWebSocketPriceFeed(props, tokens, mapper);
    }

    /** Start streaming a ticker. Best-effort — if the socket won't open, REST carries the session. */
    public void watch(String ticker) {
        stream.watch(ticker);
    }

    public void unwatch(String ticker) {
        stream.unwatch(ticker);
    }

    public void close() {
        stream.close();
    }

    /**
     * Latest price for a stock/ETF: the streamed tick when it is fresh, otherwise a REST quote.
     * "Fresh" is {@code kis.ws-stale-seconds} — long enough to ride out a quiet minute in a thin
     * ETF, short enough that we never act on a stale price.
     */
    public Quote stockPrice(String ticker) {
        Optional<KisWebSocketPriceFeed.Tick> tick = stream.latest(ticker);
        if (tick.isPresent() && isFresh(tick.get().receivedAt())) {
            KisWebSocketPriceFeed.Tick t = tick.get();
            return new Quote(t.ticker(), t.price(), t.ts(), false);
        }
        lastRestQuoteAt = Instant.now();
        return broker.stockQuote(ticker);
    }

    /** Futures price. Always REST — see the class note on the pre-market auction. */
    public Quote futuresPrice(String ticker) {
        lastRestQuoteAt = Instant.now();
        return broker.futuresQuote(ticker);
    }

    /** Which path is actually serving prices, for display. */
    public String sourceLabel() {
        return isStreaming() ? "WEBSOCKET" : "REST";
    }

    public boolean isStreaming() {
        return stream.isConnected();
    }

    public Instant lastRestQuoteAt() {
        return lastRestQuoteAt;
    }

    private boolean isFresh(Instant receivedAt) {
        return Duration.between(receivedAt, Instant.now()).getSeconds() < props.getWsStaleSeconds();
    }
}
