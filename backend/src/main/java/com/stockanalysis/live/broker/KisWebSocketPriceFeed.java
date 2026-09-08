package com.stockanalysis.live.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams executed trade prices for subscribed tickers over the KIS real-time WebSocket.
 *
 * <p>This exists purely for reaction speed: a stop-loss checked every few seconds by polling exits
 * several seconds late, and on a leveraged ETF that gap is money. Correctness never depends on it —
 * {@link LivePriceFeed} falls back to REST whenever this goes quiet, so a dropped socket degrades
 * the fill price, not the decision.
 *
 * <p>Frames are the broker's own format, not JSON: {@code 0|H0STCNT0|001|005930^093045^71900^...}
 * where field 0 is the ticker, 1 the HHmmss trade time and 2 the price. JSON frames are control
 * messages — subscription acks and the PINGPONG keepalive, which must be echoed back verbatim.
 */
public class KisWebSocketPriceFeed {

    private static final Logger log = LoggerFactory.getLogger(KisWebSocketPriceFeed.class);

    /** Real-time domestic stock/ETF trade feed. */
    private static final String TR_TRADE = "H0STCNT0";

    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(60);

    private final KisProperties props;
    private final KisTokenStore tokens;
    private final ObjectMapper mapper;

    private final Map<String, Tick> lastByTicker = new ConcurrentHashMap<>();
    private final Map<String, Boolean> subscribed = new ConcurrentHashMap<>();

    private volatile WebSocket socket;
    private volatile Instant lastConnectAttempt = Instant.EPOCH;
    private volatile boolean connecting;

    /** A streamed price and when we received it. */
    public record Tick(String ticker, double price, LocalDateTime ts, Instant receivedAt) {
    }

    public KisWebSocketPriceFeed(KisProperties props, KisTokenStore tokens, ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.mapper = mapper;
    }

    public Optional<Tick> latest(String ticker) {
        return Optional.ofNullable(lastByTicker.get(ticker));
    }

    public boolean isConnected() {
        WebSocket ws = socket;
        return ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    /**
     * Ensures we are connected and subscribed to {@code ticker}. Safe to call repeatedly — it is
     * how the session re-subscribes after a reconnect. Failures are logged, never thrown: the REST
     * path is always there.
     */
    public synchronized void watch(String ticker) {
        subscribed.put(ticker, Boolean.TRUE);
        if (!ensureConnected()) {
            return;
        }
        send(subscribeFrame(ticker, true));
    }

    public synchronized void unwatch(String ticker) {
        subscribed.remove(ticker);
        if (isConnected()) {
            send(subscribeFrame(ticker, false));
        }
    }

    public synchronized void close() {
        subscribed.clear();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (RuntimeException e) {
                log.debug("WebSocket 종료 중 무시된 오류: {}", e.toString());
            }
        }
    }

    private boolean ensureConnected() {
        if (isConnected()) {
            return true;
        }
        if (connecting || Instant.now().isBefore(lastConnectAttempt.plus(RECONNECT_BACKOFF))) {
            return false; // don't hammer the broker; REST is covering us meanwhile
        }
        lastConnectAttempt = Instant.now();
        connecting = true;
        try {
            String approval = tokens.approvalKey();
            socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(props.resolvedWsBase()), new Listener(approval))
                    .join();
            log.info("KIS 실시간 시세 WebSocket 연결됨 ({})", props.resolvedWsBase());
            return true;
        } catch (RuntimeException e) {
            log.warn("KIS WebSocket 연결 실패 — REST 폴링으로 대체합니다: {}", e.toString());
            socket = null;
            return false;
        } finally {
            connecting = false;
        }
    }

    private String subscribeFrame(String ticker, boolean on) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "header", Map.of(
                            "approval_key", tokens.approvalKey(),
                            "custtype", "P",
                            "tr_type", on ? "1" : "2",
                            "content-type", "utf-8"),
                    "body", Map.of("input", Map.of("tr_id", TR_TRADE, "tr_key", ticker))));
        } catch (Exception e) {
            throw new KisApiException("실시간 구독 요청을 만들지 못했습니다.", e);
        }
    }

    private void send(String text) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        try {
            ws.sendText(text, true);
        } catch (RuntimeException e) {
            log.warn("실시간 구독 전송 실패: {}", e.toString());
        }
    }

    private void resubscribeAll() {
        subscribed.keySet().forEach(t -> send(subscribeFrame(t, true)));
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private final String approvalKey;

        private Listener(String approvalKey) {
            this.approvalKey = approvalKey;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            // The socket is new, so anything we were watching needs subscribing again.
            resubscribeAll();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(webSocket, message);
                } catch (RuntimeException e) {
                    log.warn("실시간 메시지 처리 실패: {}", e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("KIS WebSocket 종료 ({} {}) — 다음 조회는 REST로 처리됩니다.", statusCode, reason);
            socket = null;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("KIS WebSocket 오류 — REST 폴링으로 대체합니다: {}", error.toString());
            socket = null;
        }

        private void handle(WebSocket webSocket, String message) {
            if (message.startsWith("{")) {
                handleControl(webSocket, message);
                return;
            }
            // 0|H0STCNT0|001|005930^093045^71900^...
            String[] parts = message.split("\\|", 4);
            if (parts.length < 4 || !TR_TRADE.equals(parts[1])) {
                return;
            }
            if (!"0".equals(parts[0])) {
                return; // encrypted payload: only order notifications use it, and we don't read those here
            }
            for (String record : splitRecords(parts[3], Integer.parseInt(parts[2].trim()))) {
                String[] f = record.split("\\^");
                if (f.length < 3) {
                    continue;
                }
                double price = parsePrice(f[2]);
                if (price <= 0) {
                    continue;
                }
                lastByTicker.put(f[0], new Tick(f[0], price, parseTime(f[1]), Instant.now()));
            }
        }

        private void handleControl(WebSocket webSocket, String message) {
            try {
                JsonNode node = mapper.readTree(message);
                String trId = node.path("header").path("tr_id").asText("");
                if ("PINGPONG".equals(trId)) {
                    // The server expects its own frame back, unchanged, or it drops us.
                    webSocket.sendText(message, true);
                    return;
                }
                int code = node.path("body").path("rt_cd").asInt(0);
                if (code != 0) {
                    log.warn("실시간 구독 거부: {}", node.path("body").path("msg1").asText());
                }
            } catch (Exception e) {
                log.debug("제어 메시지 무시: {}", e.toString());
            }
        }

        /** A frame can carry several records back to back, separated by the same '^' delimiter. */
        private String[] splitRecords(String payload, int count) {
            if (count <= 1) {
                return new String[]{payload};
            }
            String[] fields = payload.split("\\^");
            int perRecord = fields.length / count;
            if (perRecord <= 0) {
                return new String[]{payload};
            }
            String[] records = new String[count];
            for (int i = 0; i < count; i++) {
                records[i] = String.join("^",
                        java.util.Arrays.copyOfRange(fields, i * perRecord, (i + 1) * perRecord));
            }
            return records;
        }

        private double parsePrice(String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private LocalDateTime parseTime(String hhmmss) {
            try {
                int h = Integer.parseInt(hhmmss.substring(0, 2));
                int m = Integer.parseInt(hhmmss.substring(2, 4));
                int s = Integer.parseInt(hhmmss.substring(4, 6));
                return LocalDateTime.now().withHour(h).withMinute(m).withSecond(s).withNano(0);
            } catch (RuntimeException e) {
                return LocalDateTime.now();
            }
        }
    }
}
