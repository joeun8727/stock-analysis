package com.stockanalysis.live.session;

import com.stockanalysis.backtest.PremarketDecider.PricePoint;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.live.market.LiveBarBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of a trading day that only make sense in memory: the strategy being run, the quotes
 * collected so far, and the bar series being assembled.
 *
 * <p>Everything that must survive a restart is in {@code live_session} and its child tables — this
 * is rebuilt from those on startup. Nothing here is the source of truth.
 */
class SessionRuntime {

    private final Long sessionId;
    private final LocalDate date;
    private final StrategySpec spec;
    private final int barIntervalMinutes;
    private final String leverageTicker;
    private final String inverseTicker;
    private final double leverageFeePct;
    private final double inverseFeePct;

    private final List<PricePoint> premarketSamples = new ArrayList<>();

    private LiveBarBuilder bars;
    private LocalDateTime entryBucket;
    private int barsHeld;
    private boolean warmedUp;
    private LocalDateTime lastPollAt;

    SessionRuntime(Long sessionId, LocalDate date, StrategySpec spec, int barIntervalMinutes,
                   String leverageTicker, String inverseTicker,
                   double leverageFeePct, double inverseFeePct) {
        this.sessionId = sessionId;
        this.date = date;
        this.spec = spec;
        this.barIntervalMinutes = barIntervalMinutes;
        this.leverageTicker = leverageTicker;
        this.inverseTicker = inverseTicker;
        this.leverageFeePct = leverageFeePct;
        this.inverseFeePct = inverseFeePct;
    }

    Long sessionId() {
        return sessionId;
    }

    LocalDate date() {
        return date;
    }

    StrategySpec spec() {
        return spec;
    }

    int barIntervalMinutes() {
        return barIntervalMinutes;
    }

    String tickerFor(String instrument) {
        return "INVERSE".equals(instrument) ? inverseTicker : leverageTicker;
    }

    double feePctFor(String instrument) {
        return "INVERSE".equals(instrument) ? inverseFeePct : leverageFeePct;
    }

    List<PricePoint> premarketSamples() {
        return premarketSamples;
    }

    void addSample(LocalDateTime ts, double price) {
        premarketSamples.add(new PricePoint(ts, price));
    }

    void replaceSamples(List<PricePoint> samples) {
        premarketSamples.clear();
        premarketSamples.addAll(samples);
    }

    LiveBarBuilder bars() {
        if (bars == null) {
            bars = new LiveBarBuilder(barIntervalMinutes);
        }
        return bars;
    }

    boolean isWarmedUp() {
        return warmedUp;
    }

    void markWarmedUp() {
        warmedUp = true;
    }

    LocalDateTime entryBucket() {
        return entryBucket;
    }

    void setEntryBucket(LocalDateTime entryBucket) {
        this.entryBucket = entryBucket;
    }

    int barsHeld() {
        return barsHeld;
    }

    void countBar() {
        barsHeld++;
    }

    void setBarsHeld(int barsHeld) {
        this.barsHeld = barsHeld;
    }

    /** Rate-limits polling to the configured interval without needing its own scheduler. */
    boolean shouldPoll(LocalDateTime now, int intervalSeconds) {
        if (lastPollAt == null || !now.isBefore(lastPollAt.plusSeconds(intervalSeconds))) {
            lastPollAt = now;
            return true;
        }
        return false;
    }
}
