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
 * 한국투자증권 REST 어댑터.
 *
 * <p>여기서 조심할 것이 둘 있습니다. 첫째 <b>tr_id</b>: 엔드포인트가 이 값으로 선택되는데
 * 모의투자 서버와 실전 서버가 다르고, KIS가 번호를 개편한 이력도 있습니다 — 그래서 전부
 * {@link KisProperties.TrIds}에서 오며 코드 수정 없이 바로잡을 수 있습니다. 둘째
 * <b>필드명</b>: 응답이 한글 약어 키의 평평한 맵이라, 각 리더가 한 가지 모양을 가정하는 대신
 * 문서에 적힌 이름과 알려진 대체 이름 몇 개를 차례로 시도합니다.
 *
 * <p>시세는 주문과 다른 앱키를 쓸 수 있습니다. 모의투자 서버가 국내선물 시세를 반드시 주지는
 * 않는데, 장전 선물 움직임이 곧 진입 신호 전부이기 때문입니다.
 */
public class KisBrokerClient implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(KisBrokerClient.class);

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    /** KIS는 한 번에 분봉 30개를 줍니다. 하루치를 채우려면 여러 장이 필요합니다. */
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

    // ------------------------------------------------------------------ 시세

    @Override
    public Quote futuresQuote(String ticker) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("FID_COND_MRKT_DIV_CODE", "F");
        params.add("FID_INPUT_ISCD", ticker);
        JsonNode body = getQuote("/uapi/domestic-futureoption/v1/quotations/inquire-price",
                params, props.getTrIds().getFuturesQuote());
        JsonNode out = firstOutput(body);

        // 09:00 이전 선물시장은 동시호가라 체결이 아직 없고 예상체결가만 있습니다. 있으면 그쪽을
        // 씁니다 — 그게 바로 장전 신호이기 때문입니다.
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
        // 세션 누적 거래량: 봉 거래량은 이걸 차분해서 만들기 때문에, 따로 조회하지 않고 가격과
        // 같이 실려 와야 합니다.
        Double acml = readDouble(out, "acml_vol");
        Double price = readDouble(out, "stck_prpr");
        if (price == null || price == 0.0) {
            // 장 시간 밖에서는 현재가가 없을 수 있습니다. 그래도 예상체결가는 동작합니다.
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
        // 봉은 최신순으로 30개씩 오므로, 장 마감에서부터 거꾸로 페이지를 넘기며 중복을 제거합니다:
        // 다음 페이지가 같은 분에서 시작하면 경계 레코드가 겹칩니다.
        Map<LocalDateTime, Bar> byTs = new LinkedHashMap<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = "153000";

        for (int page = 0; page < MAX_CHART_PAGES; page++) {
            if (!seenCursors.add(cursor)) {
                break; // 커서가 멈췄습니다 — 더 가져올 이력이 없습니다
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
                break; // 세션 시작에 도달
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
            // 지난 날짜는 일자 지정 엔드포인트가 필요합니다. 장중 엔드포인트는 당일만 줍니다.
            path = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
            trId = props.getTrIds().getDailyMinuteChart();
            params.add("FID_INPUT_DATE_1", date.format(YYYYMMDD));
        }

        JsonNode body;
        try {
            body = getQuote(path, params, trId);
        } catch (RuntimeException e) {
            // 워밍업은 최선 노력입니다: 없으면 지표가 NaN으로 남아 그냥 발동하지 않습니다.
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

    /** 이동평균은 여기서 NaN으로 둡니다 — 호출자가 엑셀과 같은 방식으로 계산합니다. */
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

    // ------------------------------------------------------------------ 매매

    @Override
    public OrderAck placeOrder(OrderRequest request) {
        requireAccount();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("CANO", props.getAccountNo());
        payload.put("ACNT_PRDT_CD", props.getAccountProductCode());
        payload.put("PDNO", request.ticker());
        // "01" = 시장가, "00" = 지정가. 시장가가 기본인 이유는, 전략의 청산선이 "규칙이 걸리는
        // 그 순간 빠져나온다"를 전제하기 때문입니다. 지정가가 언젠가 체결되기를 기다리는 게 아니라.
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

    // ------------------------------------------------------------------ 통신

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

    /** 본문 무결성 해시. API에서 선택 사항이라, 여기서 실패해도 주문을 막으면 안 됩니다. */
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

    /** 시세 엔드포인트는 본문을 {@code output}, {@code output1}, {@code output2} 중 하나에 담습니다. */
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
     * 존재하고 파싱되는 첫 필드를 읽습니다. KIS는 숫자를 문자열로 보내고 때로 부호나 콤마가 붙으며,
     * 엔드포인트마다 이름이 다릅니다 — 그래서 대체 이름들을 받습니다.
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
                // 다음 후보 이름을 시도합니다.
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
     * 브로커의 초당 호출 상한(실전 20, 모의 2) 안에 머물도록 호출 속도를 조절합니다. 일부러 가장
     * 단순한 방식을 씁니다: 공유 슬롯 큐 하나. 세션이 분당 몇 번 호출하는 정도지 쏟아붓지 않기
     * 때문입니다.
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
