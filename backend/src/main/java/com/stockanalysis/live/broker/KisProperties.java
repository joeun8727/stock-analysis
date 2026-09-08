package com.stockanalysis.live.broker;

import com.stockanalysis.domain.LiveMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Korea Investment &amp; Securities connection settings, bound from {@code kis.*}.
 *
 * <p>Credentials come from the environment only (see {@code .env.example}); nothing here is stored
 * in the database or committed. {@link #mode} is likewise an environment variable rather than an
 * API field, so moving from paper to a real account takes a deliberate restart.
 *
 * <p>{@code quote*} overrides exist because the paper-trading server may not serve domestic futures
 * quotes. When that turns out to be the case, point quotes at the live app key while orders keep
 * going to the paper account.
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
    /** CANO — the 8-digit account number without the product code. */
    private String accountNo = "";
    /** ACNT_PRDT_CD — usually "01" for a domestic stock account. */
    private String accountProductCode = "01";

    private String restBase;
    private String wsBase;

    /** Separate app key for quotes; blank means "use the trading credentials". */
    private String quoteAppKey = "";
    private String quoteAppSecret = "";
    private String quoteRestBase;

    /**
     * Requests per second the broker allows. KIS documents 20/s on the live server and 2/s on
     * paper; exceeding it gets requests rejected, so the client paces itself.
     */
    private int rateLimitPerSecond = 0;

    /** How long a WebSocket may go silent before the feed falls back to REST polling. */
    private int wsStaleSeconds = 30;

    private final TrIds trIds = new TrIds();

    /**
     * Transaction IDs, one per endpoint and account type. These are the part of the KIS API most
     * likely to be out of date — the cash-order IDs have been renumbered before — so every one is
     * overridable from configuration. Verify against the official docs before trading for real.
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

    /** True when the credentials needed to talk to KIS at all are present. */
    public boolean hasCredentials() {
        return !appKey.isBlank() && !appSecret.isBlank();
    }

    public boolean hasAccount() {
        return !accountNo.isBlank();
    }

    /**
     * Orders go to the paper server in {@link LiveMode#PAPER}. In {@link LiveMode#DRY_RUN} nothing
     * is ordered, but quotes still need a server, and paper is the safer one to read from.
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

    /** True when quotes use their own credentials and server rather than the trading ones. */
    public boolean hasSeparateQuoteCredentials() {
        return !quoteAppKey.isBlank() && !quoteAppSecret.isBlank();
    }

    public String resolvedQuoteRestBase() {
        if (quoteRestBase != null && !quoteRestBase.isBlank()) {
            return quoteRestBase;
        }
        // Separate quote credentials only make sense against the live quote server — that is the
        // whole reason for splitting them.
        return hasSeparateQuoteCredentials() ? REAL_REST : resolvedRestBase();
    }

    public int resolvedRateLimitPerSecond() {
        if (rateLimitPerSecond > 0) {
            return rateLimitPerSecond;
        }
        return usesPaperServer() ? 2 : 20;
    }

    /** The tr_id for a cash order on whichever account type is in use. */
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
