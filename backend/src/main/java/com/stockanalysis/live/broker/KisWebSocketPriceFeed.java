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
 * 구독한 종목의 체결가를 KIS 실시간 WebSocket으로 흘려받습니다.
 *
 * <p>순전히 반응 속도를 위한 것입니다: 몇 초마다 폴링해서 확인하는 손절은 몇 초 늦게 나가고,
 * 레버리지 ETF에서 그 간격은 곧 돈입니다. 정확성이 여기에 의존하지는 않습니다 —
 * {@link LivePriceFeed}가 이쪽이 조용해지면 REST로 넘어가므로, 소켓이 끊기면 체결가가 나빠질
 * 뿐 판단이 달라지지는 않습니다.
 *
 * <p>프레임은 JSON이 아니라 브로커 자체 형식입니다: {@code 0|H0STCNT0|001|005930^093045^71900^...}
 * 이고 필드 0이 종목코드, 1이 HHmmss 체결시각, 2가 가격입니다. JSON 프레임은 제어 메시지 —
 * 구독 응답과 PINGPONG 킵얼라이브이고, 후자는 받은 그대로 되돌려줘야 합니다.
 */
public class KisWebSocketPriceFeed {

    private static final Logger log = LoggerFactory.getLogger(KisWebSocketPriceFeed.class);

    /** 국내 주식/ETF 실시간 체결 피드. */
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

    /** 흘러들어온 가격 하나와 우리가 받은 시각. */
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
     * {@code ticker}에 연결·구독된 상태를 보장합니다. 반복 호출해도 안전합니다 — 재연결 후
     * 세션이 다시 구독하는 방식이 바로 이것입니다. 실패는 로그만 남기고 절대 던지지 않습니다:
     * REST 경로가 항상 있기 때문입니다.
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
            return false; // 브로커를 두드리지 않습니다. 그동안은 REST가 받쳐줍니다
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
            // 소켓이 새것이므로, 보고 있던 종목은 전부 다시 구독해야 합니다.
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
                return; // 암호화 페이로드: 주문 통보만 쓰는데 여기서는 그걸 읽지 않습니다
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
                    // 서버는 자기 프레임을 그대로 돌려받기를 기대합니다. 아니면 연결을 끊습니다.
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

        /** 한 프레임에 레코드 여러 개가 같은 '^' 구분자로 이어져 올 수 있습니다. */
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
