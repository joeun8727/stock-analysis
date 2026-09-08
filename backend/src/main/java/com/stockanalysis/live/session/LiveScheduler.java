package com.stockanalysis.live.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Drives {@link LiveTradingService#tick} on a timer.
 *
 * <p>All the intelligence is in the service, which takes the time as a parameter; this only decides
 * <em>when</em> to call it. That split is what lets a full trading day be tested without waiting for
 * one, and it keeps the scheduler small enough to be obviously correct.
 *
 * <p>Ticks are confined to the trading window on weekdays. Outside it the service would return
 * immediately anyway, but not calling it at all means an idle machine makes no broker requests.
 */
@Component
public class LiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(LiveScheduler.class);

    /** A little before the earliest pre-market window so the session is set up in time. */
    private static final LocalTime WINDOW_START = LocalTime.of(8, 30);
    /** Past the closing auction, so a late fill is still collected. */
    private static final LocalTime WINDOW_END = LocalTime.of(15, 50);

    private final LiveTradingService trading;

    public LiveScheduler(LiveTradingService trading) {
        this.trading = trading;
    }

    /**
     * Every two seconds during market hours. The pre-market poll paces itself to the configured
     * interval; this cadence is set by exit checks, where a few seconds of lag is a few seconds of
     * slippage on a leveraged ETF.
     */
    @Scheduled(fixedDelay = 2000L)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        if (!isTradingWindow(now)) {
            return;
        }
        try {
            trading.tick(now);
        } catch (RuntimeException e) {
            // The service already halts on its own errors; this is the last net so one bad tick
            // cannot kill the scheduler thread and silently strand an open position.
            log.error("실투자 스케줄러 tick 실패", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            trading.reconcileOnStartup();
        } catch (RuntimeException e) {
            log.error("재기동 시 포지션 대조에 실패했습니다", e);
        }
    }

    static boolean isTradingWindow(LocalDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        return !t.isBefore(WINDOW_START) && t.isBefore(WINDOW_END);
    }
}
