package com.stockanalysis.live.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.live.broker.BrokerClient.Quote;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 실투자가 "지금 이게 얼마인가"를 묻는 단 한 곳.
 *
 * <p>설계상 하이브리드입니다: 포지션이 열려 있는 동안은 WebSocket이 틱 속도의 반응을 주고,
 * 나머지는 REST가 맡습니다 — 장전 선물 구간, 그리고 소켓이 죽었거나 조용한 모든 순간.
 * 폴백은 자동이고 호출자에게는 보이지 않아서 연결이 끊겨도 세션이 멈추지 않습니다.
 * {@link #sourceLabel()}이 있는 건 그래도 화면이 어느 경로로 받고 있는지 말할 수 있게 하려고입니다.
 *
 * <p>선물은 항상 REST입니다: 장전 동시호가는 체결이 아니라 예상체결가를 내보내고, 체결 스트림은
 * 장이 실제로 열리기 전까지 할 말이 없습니다.
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

    /** 종목 스트리밍을 시작합니다. 최선 노력 — 소켓이 안 열리면 REST가 세션을 끌고 갑니다. */
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
     * 주식/ETF의 최신 가격: 스트리밍 틱이 신선하면 그것을, 아니면 REST 시세를 씁니다.
     * "신선함"의 기준은 {@code kis.ws-stale-seconds}입니다 — 거래가 얇은 ETF에서 조용한 1분을
     * 넘길 만큼은 길고, 낡은 가격으로 행동하지 않을 만큼은 짧게.
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

    /** 선물 가격. 항상 REST입니다 — 장전 동시호가에 대한 클래스 설명 참고. */
    public Quote futuresPrice(String ticker) {
        lastRestQuoteAt = Instant.now();
        return broker.futuresQuote(ticker);
    }

    /** 지금 실제로 가격을 주고 있는 경로. 화면 표시용. */
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
