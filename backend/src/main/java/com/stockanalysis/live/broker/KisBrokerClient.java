package com.stockanalysis.live.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.backtest.Bar;
import com.stockanalysis.domain.LiveOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Korea Investment &amp; Securities REST adapter.
 *
 * <p>Two things here deserve care. First, <b>tr_id values</b>: every endpoint is selected by one,
 * they differ between the paper and live servers, and KIS has renumbered them before — they all
 * come from {@link KisProperties.TrIds} so they can be corrected without a code change. Second,
 * <b>field names</b>: responses are flat maps of Korean-abbreviated keys, so each reader tries the
 * documented name and a couple of known alternates rather than assuming one shape.
 *
 * <p>Quotes may use a separate app key from orders, because the paper server does not necessarily
 * serve domestic futures prices — and the pre-market futures move is the entire entry signal.
 */
public class KisBrokerClient implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(KisBrokerClient.class);

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    /** KIS returns 30 minute-bars per call; a full session needs several pages. */
    private static final int MAX_CHART_PAGES = 30;

    private final KisProperties props;
    private final KisTokenStore tokens;
    private final ObjectMapper mapper;
    private final RestClient trading;
    private final RestClient quotes;
    private final RateLimiter limiter;

    public KisBrokerClient(KisProperties props, KisTokenStore tokens, ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.mapper = mapper;
        this.trading = RestClient.builder().baseUrl(props.resolvedRestBase()).build();
        this.quotes = props.resolvedQuoteRestBase().equals(props.resolvedRestBase())
                ? this.trading
                : RestClient.builder().baseUrl(props.resolvedQuoteRestBase()).build();
        this.limiter = new RateLimiter(props.resolvedRateLimitPerSecond());
    }

    @Override
    public String describe() {
        String server = props.usesPaperServer() ? "모의투자" : "실전";
        return server + " " + mask(props.getAccountNo()) + "-" + props.getAccountProductCode();
    }

    // ------------------------------------------------------------------ quotes

    @Override
    public Quote futuresQuote(String ticker) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("FID_COND_MRKT_DIV_CODE", "F");
        params.add("FID_INPUT_ISCD", ticker);
        JsonNode body = getQuote("/uapi/domestic-futureoption/v1/quotations/inquire-price",
                params, props.getTrIds().getFuturesQuote());
        JsonNode out = firstOutput(body);

        // Before 09:00 the futures market is in a call auction: there are no trades yet, only an
        // indicative price. Prefer that when present, since that IS the pre-market signal.
        Double estimated = readDouble(out, "antc_cnpr", "antc_cntg_prpr");
        if (estimated != null && estimated != 0.0) {
            return new Quote(ticker, estimated, LocalDateTime.now(), true);
        }
        Double last = readDouble(out, "futs_prpr", "stck_prpr", "prpr");
        if (last == null || last == 0.0) {
            throw new KisApiException("선물 현재가를 읽지 못했습니다 (종목코드 " + ticker + "). 응답: " + out);
        }
        return new Quote(ticker, last, LocalDateTime.now(), false);
    }

    @Override
    public Quote stockQuote(String ticker) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("FID_COND_MRKT_DIV_CODE", "J");
        params.add("FID_INPUT_ISCD", ticker);
        JsonNode body = getQuote("/uapi/domestic-stock/v1/quotations/inquire-price",
                params, props.getTrIds().getStockQuote());
        JsonNode out = firstOutput(body);
        // Session-cumulative volume: bar volume is derived by differencing it, so it has to ride
        // along with the price rather than be fetched separately.
        Double acml = readDouble(out, "acml_vol");
        Double price = readDouble(out, "stck_prpr");
        if (price == null || price == 0.0) {
            // Outside trading hours the last price can be absent; the estimated one still works.
            Double estimated = readDouble(out, "antc_cnpr");
            if (estimated != null && estimated != 0.0) {
                return new Quote(ticker, estimated, LocalDateTime.now(), true, acml);
            }
            throw new KisApiException("현재가를 읽지 못했습니다 (종목코드 " + ticker + "). 응답: " + out);
        }
        return new Quote(ticker, price, LocalDateTime.now(), false, acml);
    }

    @Override
    public List<Bar> minuteBars(String ticker, LocalDate date) {
        boolean today = date.equals(LocalDate.now());
        // Bars arrive newest-first, 30 at a time, so we page backwards from the session close and
        // de-duplicate: the boundary record repeats when the next page starts at the same minute.
        Map<LocalDateTime, Bar> byTs = new LinkedHashMap<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = "153000";

        for (int page = 0; page < MAX_CHART_PAGES; page++) {
            if (!seenCursors.add(cursor)) {
                break; // the cursor stopped moving — no more history available
            }
            List<Bar> batch = minuteBarPage(ticker, date, cursor, today);
            if (batch.isEmpty()) {
                break;
            }
            LocalDateTime oldest = null;
            for (Bar b : batch) {
                byTs.putIfAbsent(b.ts(), b);
                if (oldest == null || b.ts().isBefore(oldest)) {
                    oldest = b.ts();
                }
            }
            if (oldest == null || !oldest.toLocalTime().isAfter(LocalTime.of(8, 45))) {
                break; // reached the start of the session
            }
            cursor = oldest.toLocalTime().minusMinutes(1).format(HHMMSS);
        }

        List<Bar> bars = new ArrayList<>(byTs.values());
        bars.sort((a, b) -> a.ts().compareTo(b.ts()));
        return bars;
    }

    private List<Bar> minuteBarPage(String ticker, LocalDate date, String endHour, boolean today) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("FID_COND_MRKT_DIV_CODE", "J");
        params.add("FID_INPUT_ISCD", ticker);
        params.add("FID_INPUT_HOUR_1", endHour);
        params.add("FID_PW_DATA_INCU_YN", "Y");
        params.add("FID_ETC_CLS_CODE", "");

        String path;
        String trId;
        if (today) {
            path = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
            trId = props.getTrIds().getMinuteChart();
        } else {
            // Past days need the dated variant; the intraday endpoint only serves the current session.
            path = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
            trId = props.getTrIds().getDailyMinuteChart();
            params.add("FID_INPUT_DATE_1", date.format(YYYYMMDD));
        }

        JsonNode body;
        try {
            body = getQuote(path, params, trId);
        } catch (RuntimeException e) {
            // Warm-up is best-effort: without it, indicators stay NaN and simply do not fire.
            log.warn("분봉 조회 실패 ({} {} {}): {}", ticker, date, endHour, e.toString());
            return List.of();
        }

        JsonNode rows = body.path("output2");
        if (!rows.isArray() || rows.isEmpty()) {
            return List.of();
        }
        List<Bar> out = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            Bar bar = toBar(row, date);
            if (bar != null) {
                out.add(bar);
            }
        }
        return out;
    }

    /** Moving averages are left NaN here — the caller computes them the same way the excel does. */
    private static Bar toBar(JsonNode row, LocalDate fallbackDate) {
        String dateText = row.path("stck_bsop_date").asText("");
        String timeText = row.path("stck_cntg_hour").asText("");
        if (timeText.length() < 4) {
            return null;
        }
        LocalDate d = dateText.length() == 8 ? LocalDate.parse(dateText, YYYYMMDD) : fallbackDate;
        LocalTime t = LocalTime.of(
                Integer.parseInt(timeText.substring(0, 2)),
                Integer.parseInt(timeText.substring(2, 4)));

        Double close = readDouble(row, "stck_prpr");
        if (close == null || close == 0.0) {
            return null;
        }
        double open = orElse(readDouble(row, "stck_oprc"), close);
        double high = orElse(readDouble(row, "stck_hgpr"), close);
        double low = orElse(readDouble(row, "stck_lwpr"), close);
        double volume = orElse(readDouble(row, "cntg_vol", "acml_vol"), Double.NaN);

        double nan = Double.NaN;
        return new Bar(LocalDateTime.of(d, t), open, high, low, close,
                nan, nan, nan, nan, volume, nan, nan, nan, nan);
    }

    // ------------------------------------------------------------------ trading

    @Override
    public OrderAck placeOrder(OrderRequest request) {
        requireAccount();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("CANO", props.getAccountNo());
        payload.put("ACNT_PRDT_CD", props.getAccountProductCode());
        payload.put("PDNO", request.ticker());
        // "01" = market, "00" = limit. A market order is the default because the strategy's exit
        // levels assume we are out at the moment the rule fires, not whenever a limit gets hit.
        payload.put("ORD_DVSN", LiveOrder.TYPE_LIMIT.equals(request.orderType()) ? "00" : "01");
        payload.put("ORD_QTY", Long.toString(request.quantity()));
        payload.put("ORD_UNPR", request.limitPrice() == null
                ? "0" : Long.toString(Math.round(request.limitPrice())));

        String trId = props.orderTrId(request.buy());
        String raw = postTrading("/uapi/domestic-stock/v1/trading/order-cash", payload, trId);
        JsonNode body = parse(raw);

        boolean ok = "0".equals(body.path("rt_cd").asText());
        String message = body.path("msg1").asText("");
        String orderNo = body.path("output").path("ODNO").asText(null);
        if (!ok) {
            return new OrderAck(orderNo, false, message.isBlank() ? "주문이 거부되었습니다." : message, raw);
        }
        return new OrderAck(orderNo, true, message, raw);
    }

    @Override
    public OrderFill orderStatus(String brokerOrderNo, String ticker) {
        requireAccount();
        LocalDate today = LocalDate.now();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", props.getAccountNo());
        params.add("ACNT_PRDT_CD", props.getAccountProductCode());
        params.add("INQR_STRT_DT", today.format(YYYYMMDD));
        params.add("INQR_END_DT", today.format(YYYYMMDD));
        params.add("SLL_BUY_DVSN_CD", "00");
        params.add("INQR_DVSN", "00");
        params.add("PDNO", ticker == null ? "" : ticker);
        params.add("CCLD_DVSN", "00");
        params.add("ORD_GNO_BRNO", "");
        params.add("ODNO", brokerOrderNo == null ? "" : brokerOrderNo);
        params.add("INQR_DVSN_3", "00");
        params.add("INQR_DVSN_1", "");
        params.add("CTX_AREA_FK100", "");
        params.add("CTX_AREA_NK100", "");

        String raw = getTradingRaw("/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
                params, props.dailyExecutionTrId());
        JsonNode body = parse(raw);
        JsonNode rows = body.path("output1");
        if (!rows.isArray()) {
            return new OrderFill(brokerOrderNo, 0, null, false, false, raw);
        }
        for (JsonNode row : rows) {
            if (brokerOrderNo != null && !brokerOrderNo.equals(row.path("odno").asText())) {
                continue;
            }
            long ordered = (long) orElse(readDouble(row, "ord_qty"), 0.0);
            long filled = (long) orElse(readDouble(row, "tot_ccld_qty"), 0.0);
            Double avg = readDouble(row, "avg_prvs", "ccld_prvs");
            boolean rejected = filled == 0 && !row.path("rjct_rson").asText("").isBlank();
            boolean done = filled > 0 && filled >= ordered;
            return new OrderFill(brokerOrderNo, filled, avg, done, rejected, raw);
        }
        return new OrderFill(brokerOrderNo, 0, null, false, false, raw);
    }

    @Override
    public Balance balance() {
        requireAccount();
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", props.getAccountNo());
        params.add("ACNT_PRDT_CD", props.getAccountProductCode());
        params.add("AFHR_FLPR_YN", "N");
        params.add("OFL_YN", "");
        params.add("INQR_DVSN", "02");
        params.add("UNPR_DVSN", "01");
        params.add("FUND_STTL_ICLD_YN", "N");
        params.add("FNCG_AMT_AUTO_RDPT_YN", "N");
        params.add("PRCS_DVSN", "00");
        params.add("CTX_AREA_FK100", "");
        params.add("CTX_AREA_NK100", "");

        String raw = getTradingRaw("/uapi/domestic-stock/v1/trading/inquire-balance",
                params, props.balanceTrId());
        JsonNode body = parse(raw);
        if (!"0".equals(body.path("rt_cd").asText("0"))) {
            throw new KisApiException("잔고 조회에 실패했습니다: " + body.path("msg1").asText());
        }

        List<Holding> holdings = new ArrayList<>();
        for (JsonNode row : body.path("output1")) {
            String code = row.path("pdno").asText("");
            long qty = (long) orElse(readDouble(row, "hldg_qty"), 0.0);
            if (code.isBlank() || qty <= 0) {
                continue;
            }
            holdings.add(new Holding(code, qty, orElse(readDouble(row, "pchs_avg_pric"), 0.0)));
        }

        JsonNode summary = body.path("output2");
        JsonNode first = summary.isArray() && !summary.isEmpty() ? summary.get(0) : summary;
        double cash = orElse(readDouble(first, "dnca_tot_amt", "prvs_rcdl_excc_amt"), 0.0);
        return new Balance(cash, holdings, raw);
    }

    // ------------------------------------------------------------------ transport

    private JsonNode getQuote(String path, MultiValueMap<String, String> params, String trId) {
        limiter.acquire();
        boolean separate = props.hasSeparateQuoteCredentials();
        String key = separate ? props.getQuoteAppKey() : props.getAppKey();
        String secret = separate ? props.getQuoteAppSecret() : props.getAppSecret();
        String raw = quotes.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                .header("authorization", "Bearer " + tokens.accessToken())
                .header("appkey", key)
                .header("appsecret", secret)
                .header("tr_id", trId)
                .header("custtype", "P")
                .retrieve()
                .body(String.class);
        return parse(raw);
    }

    private String getTradingRaw(String path, MultiValueMap<String, String> params, String trId) {
        limiter.acquire();
        return trading.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                .header("authorization", "Bearer " + tokens.accessToken())
                .header("appkey", props.getAppKey())
                .header("appsecret", props.getAppSecret())
                .header("tr_id", trId)
                .header("custtype", "P")
                .retrieve()
                .body(String.class);
    }

    private String postTrading(String path, Map<String, String> payload, String trId) {
        limiter.acquire();
        String hash = hashkey(payload);
        return trading.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("authorization", "Bearer " + tokens.accessToken())
                .header("appkey", props.getAppKey())
                .header("appsecret", props.getAppSecret())
                .header("tr_id", trId)
                .header("custtype", "P")
                .header("hashkey", hash == null ? "" : hash)
                .body(payload)
                .retrieve()
                .body(String.class);
    }

    /** Body integrity hash. Optional in the API, so a failure here must not block the order. */
    private String hashkey(Map<String, String> payload) {
        try {
            limiter.acquire();
            JsonNode body = trading.post()
                    .uri("/uapi/hashkey")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("appkey", props.getAppKey())
                    .header("appsecret", props.getAppSecret())
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            return body == null ? null : body.path("HASH").asText(null);
        } catch (RuntimeException e) {
            log.warn("hashkey 발급 실패, 해시 없이 주문합니다: {}", e.toString());
            return null;
        }
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            throw new KisApiException("KIS 응답을 해석하지 못했습니다: " + raw, e);
        }
    }

    private void requireAccount() {
        if (!props.hasAccount()) {
            throw new KisApiException("계좌번호가 설정되지 않았습니다. .env에 KIS_ACCOUNT_NO를 넣어주세요.");
        }
    }

    /** Quote endpoints put the payload in {@code output}, {@code output1} or {@code output2}. */
    private static JsonNode firstOutput(JsonNode body) {
        for (String key : List.of("output", "output1", "output2")) {
            JsonNode node = body.path(key);
            if (node.isObject()) {
                return node;
            }
            if (node.isArray() && !node.isEmpty()) {
                return node.get(0);
            }
        }
        return body;
    }

    /**
     * Reads the first present, parseable field. KIS sends numbers as strings, sometimes with signs
     * or commas, and names them differently across endpoints — hence the alternates.
     */
    private static Double readDouble(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String text = value.asText("").trim().replace(",", "");
            if (text.isEmpty()) {
                continue;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                // Try the next candidate name.
            }
        }
        return null;
    }

    private static double orElse(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static String mask(String accountNo) {
        if (accountNo == null || accountNo.length() < 4) {
            return "****";
        }
        return "****" + accountNo.substring(accountNo.length() - 4);
    }

    /**
     * Paces calls to stay inside the broker's per-second cap (20 live, 2 on paper). Deliberately
     * the simplest thing that works: one shared slot queue, since the session makes a handful of
     * calls a minute, not a flood.
     */
    static final class RateLimiter {

        private final long minIntervalNanos;
        private long nextAllowedAt;

        RateLimiter(int perSecond) {
            this.minIntervalNanos = perSecond <= 0 ? 0 : 1_000_000_000L / perSecond;
        }

        synchronized void acquire() {
            if (minIntervalNanos == 0) {
                return;
            }
            long now = System.nanoTime();
            if (now < nextAllowedAt) {
                long waitNanos = nextAllowedAt - now;
                try {
                    Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new KisApiException("요청 대기 중 중단되었습니다.", e);
                }
                now = System.nanoTime();
            }
            nextAllowedAt = now + minIntervalNanos;
        }
    }
}
