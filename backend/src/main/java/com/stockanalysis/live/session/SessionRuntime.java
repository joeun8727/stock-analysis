package com.stockanalysis.live.session;

import com.stockanalysis.backtest.PremarketDecider.PricePoint;
import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.live.market.LiveBarBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 거래일 중 메모리에서만 의미가 있는 것들: 지금 돌리는 전략, 지금까지 모은 시세, 조립 중인
 * 봉 시리즈.
 *
 * <p>재기동을 견뎌야 하는 것은 전부 {@code live_session}과 그 하위 테이블에 있습니다 — 이
 * 객체는 기동 시 거기서 재구성됩니다. 여기 있는 어떤 것도 원본이 아닙니다.
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

    /** 별도 스케줄러 없이 폴링을 설정된 주기로 제한합니다. */
    boolean shouldPoll(LocalDateTime now, int intervalSeconds) {
        if (lastPollAt == null || !now.isBefore(lastPollAt.plusSeconds(intervalSeconds))) {
            lastPollAt = now;
            return true;
        }
        return false;
    }
}
