package com.stockanalysis.live.broker;

import com.stockanalysis.domain.LiveMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kis.*}에서 바인딩되는 한국투자증권 접속 설정.
 *
 * <p>자격증명은 환경변수에서만 옵니다({@code .env.example} 참고). 여기 있는 어떤 것도 DB에
 * 저장되거나 커밋되지 않습니다. {@link #mode} 역시 API 필드가 아니라 환경변수라, 모의에서
 * 실계좌로 옮기려면 의도적인 재기동이 필요합니다.
 *
 * <p>{@code quote*} 덮어쓰기가 있는 이유는 모의투자 서버가 국내선물 시세를 주지 않을 수 있기
 * 때문입니다. 실제로 그런 경우, 시세만 실전 앱키로 받고 주문은 계속 모의 계좌로 보냅니다.
 */
@ConfigurationProperties(prefix = "kis")
public class KisProperties {

    public static final String REAL_REST = "https://openapi.koreainvestment.com:9443";
    public static final String PAPER_REST = "https://openapivts.koreainvestment.com:29443";
    public static final String REAL_WS = "ws://ops.koreainvestment.com:21000";
    public static final String PAPER_WS = "ws://ops.koreainvestment.com:31000";

    private LiveMode mode = LiveMode.DRY_RUN;
    private String appKey = "";
    private String appSecret = "";
    /** CANO — 상품코드를 뺀 계좌번호 8자리. */
    private String accountNo = "";
    /** ACNT_PRDT_CD — 국내주식 계좌는 보통 "01". */
    private String accountProductCode = "01";

    private String restBase;
    private String wsBase;

    /** 시세 전용 앱키. 비어 있으면 "매매용 자격증명을 그대로 쓴다"는 뜻입니다. */
    private String quoteAppKey = "";
    private String quoteAppSecret = "";
    private String quoteRestBase;

    /**
     * 브로커가 허용하는 초당 요청 수. KIS 문서 기준 실전 20/초, 모의 2/초입니다. 넘기면 요청이
     * 거절되므로 클라이언트가 스스로 속도를 조절합니다.
     */
    private int rateLimitPerSecond = 0;

    /** WebSocket이 이만큼 조용하면 피드가 REST 폴링으로 넘어갑니다. */
    private int wsStaleSeconds = 30;

    private final TrIds trIds = new TrIds();

    /**
     * 엔드포인트와 계좌 종류마다 하나씩인 거래 ID. KIS API에서 가장 낡기 쉬운 부분이고 —
     * 현금주문 ID는 실제로 번호가 바뀐 적이 있습니다 — 그래서 전부 설정으로 덮어쓸 수 있게 했습니다.
     * 실전 매매 전에 공식 문서와 대조하세요.
     */
    public static class TrIds {
        private String orderBuy = "TTTC0802U";
        private String orderSell = "TTTC0801U";
        private String paperOrderBuy = "VTTC0802U";
        private String paperOrderSell = "VTTC0801U";
        private String balance = "TTTC8434R";
        private String paperBalance = "VTTC8434R";
        private String dailyExecution = "TTTC8001R";
        private String paperDailyExecution = "VTTC8001R";
        private String stockQuote = "FHKST01010100";
        private String futuresQuote = "FHMIF10000000";
        private String minuteChart = "FHKST03010200";
        private String dailyMinuteChart = "FHKST03010230";

        public String getOrderBuy() {
            return orderBuy;
        }

        public void setOrderBuy(String orderBuy) {
            this.orderBuy = orderBuy;
        }

        public String getOrderSell() {
            return orderSell;
        }

        public void setOrderSell(String orderSell) {
            this.orderSell = orderSell;
        }

        public String getPaperOrderBuy() {
            return paperOrderBuy;
        }

        public void setPaperOrderBuy(String paperOrderBuy) {
            this.paperOrderBuy = paperOrderBuy;
        }

        public String getPaperOrderSell() {
            return paperOrderSell;
        }

        public void setPaperOrderSell(String paperOrderSell) {
            this.paperOrderSell = paperOrderSell;
        }

        public String getBalance() {
            return balance;
        }

        public void setBalance(String balance) {
            this.balance = balance;
        }

        public String getPaperBalance() {
            return paperBalance;
        }

        public void setPaperBalance(String paperBalance) {
            this.paperBalance = paperBalance;
        }

        public String getDailyExecution() {
            return dailyExecution;
        }

        public void setDailyExecution(String dailyExecution) {
            this.dailyExecution = dailyExecution;
        }

        public String getPaperDailyExecution() {
            return paperDailyExecution;
        }

        public void setPaperDailyExecution(String paperDailyExecution) {
            this.paperDailyExecution = paperDailyExecution;
        }

        public String getStockQuote() {
            return stockQuote;
        }

        public void setStockQuote(String stockQuote) {
            this.stockQuote = stockQuote;
        }

        public String getFuturesQuote() {
            return futuresQuote;
        }

        public void setFuturesQuote(String futuresQuote) {
            this.futuresQuote = futuresQuote;
        }

        public String getMinuteChart() {
            return minuteChart;
        }

        public void setMinuteChart(String minuteChart) {
            this.minuteChart = minuteChart;
        }

        public String getDailyMinuteChart() {
            return dailyMinuteChart;
        }

        public void setDailyMinuteChart(String dailyMinuteChart) {
            this.dailyMinuteChart = dailyMinuteChart;
        }
    }

    /** KIS와 통신하는 데 최소한 필요한 자격증명이 갖춰졌는지. */
    public boolean hasCredentials() {
        return !appKey.isBlank() && !appSecret.isBlank();
    }

    public boolean hasAccount() {
        return !accountNo.isBlank();
    }

    /**
     * {@link LiveMode#PAPER}에서는 주문이 모의투자 서버로 갑니다. {@link LiveMode#DRY_RUN}에서는
     * 주문 자체가 없지만 시세는 어딘가에서 받아야 하고, 읽기용으로는 모의 쪽이 더 안전합니다.
     */
    public boolean usesPaperServer() {
        return mode != LiveMode.REAL;
    }

    public String resolvedRestBase() {
        if (restBase != null && !restBase.isBlank()) {
            return restBase;
        }
        return usesPaperServer() ? PAPER_REST : REAL_REST;
    }

    public String resolvedWsBase() {
        if (wsBase != null && !wsBase.isBlank()) {
            return wsBase;
        }
        return usesPaperServer() ? PAPER_WS : REAL_WS;
    }

    /** 시세가 매매용이 아니라 자체 자격증명과 서버를 쓰는지. */
    public boolean hasSeparateQuoteCredentials() {
        return !quoteAppKey.isBlank() && !quoteAppSecret.isBlank();
    }

    public String resolvedQuoteRestBase() {
        if (quoteRestBase != null && !quoteRestBase.isBlank()) {
            return quoteRestBase;
        }
        // 시세용 자격증명을 따로 두는 건 실전 시세 서버를 상대로만 의미가 있습니다 — 애초에
        // 그러려고 분리한 것입니다.
        return hasSeparateQuoteCredentials() ? REAL_REST : resolvedRestBase();
    }

    public int resolvedRateLimitPerSecond() {
        if (rateLimitPerSecond > 0) {
            return rateLimitPerSecond;
        }
        return usesPaperServer() ? 2 : 20;
    }

    /** 지금 쓰는 계좌 종류에 맞는 현금주문 tr_id. */
    public String orderTrId(boolean buy) {
        if (usesPaperServer()) {
            return buy ? trIds.getPaperOrderBuy() : trIds.getPaperOrderSell();
        }
        return buy ? trIds.getOrderBuy() : trIds.getOrderSell();
    }

    public String balanceTrId() {
        return usesPaperServer() ? trIds.getPaperBalance() : trIds.getBalance();
    }

    public String dailyExecutionTrId() {
        return usesPaperServer() ? trIds.getPaperDailyExecution() : trIds.getDailyExecution();
    }

    public LiveMode getMode() {
        return mode;
    }

    public void setMode(LiveMode mode) {
        this.mode = mode == null ? LiveMode.DRY_RUN : mode;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey == null ? "" : appKey.trim();
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret == null ? "" : appSecret.trim();
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo == null ? "" : accountNo.trim();
    }

    public String getAccountProductCode() {
        return accountProductCode;
    }

    public void setAccountProductCode(String accountProductCode) {
        this.accountProductCode = accountProductCode == null ? "01" : accountProductCode.trim();
    }

    public String getRestBase() {
        return restBase;
    }

    public void setRestBase(String restBase) {
        this.restBase = restBase;
    }

    public String getWsBase() {
        return wsBase;
    }

    public void setWsBase(String wsBase) {
        this.wsBase = wsBase;
    }

    public String getQuoteAppKey() {
        return quoteAppKey;
    }

    public void setQuoteAppKey(String quoteAppKey) {
        this.quoteAppKey = quoteAppKey == null ? "" : quoteAppKey.trim();
    }

    public String getQuoteAppSecret() {
        return quoteAppSecret;
    }

    public void setQuoteAppSecret(String quoteAppSecret) {
        this.quoteAppSecret = quoteAppSecret == null ? "" : quoteAppSecret.trim();
    }

    public String getQuoteRestBase() {
        return quoteRestBase;
    }

    public void setQuoteRestBase(String quoteRestBase) {
        this.quoteRestBase = quoteRestBase;
    }

    public int getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public void setRateLimitPerSecond(int rateLimitPerSecond) {
        this.rateLimitPerSecond = rateLimitPerSecond;
    }

    public int getWsStaleSeconds() {
        return wsStaleSeconds;
    }

    public void setWsStaleSeconds(int wsStaleSeconds) {
        this.wsStaleSeconds = wsStaleSeconds;
    }

    public TrIds getTrIds() {
        return trIds;
    }
}
