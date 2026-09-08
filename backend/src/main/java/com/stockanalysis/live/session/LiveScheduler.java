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
 * {@link LiveTradingService#tick}을 타이머로 돌립니다.
 *
 * <p>판단은 전부 서비스에 있고 그쪽이 시각을 인자로 받습니다. 여기서는 <em>언제</em> 부를지만
 * 정합니다. 이 분리 덕분에 하루를 실제로 기다리지 않고 하루치를 테스트할 수 있고, 스케줄러는
 * 봐서 맞다는 게 명백할 만큼 작게 유지됩니다.
 *
 * <p>tick은 평일 장 시간대로 한정합니다. 밖에서는 어차피 서비스가 즉시 돌아오지만, 아예 부르지
 * 않으면 놀고 있는 기계가 브로커에 요청을 하나도 보내지 않습니다.
 */
@Component
public class LiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(LiveScheduler.class);

    /** 가장 이른 장전 구간보다 조금 앞. 세션 준비가 제때 끝나도록. */
    private static final LocalTime WINDOW_START = LocalTime.of(8, 30);
    /** 종가 단일가 이후. 늦게 오는 체결도 받을 수 있도록. */
    private static final LocalTime WINDOW_END = LocalTime.of(15, 50);

    private final LiveTradingService trading;

    public LiveScheduler(LiveTradingService trading) {
        this.trading = trading;
    }

    /**
     * 장 중 2초마다. 장전 폴링은 설정된 주기에 맞춰 스스로 속도를 조절하고, 이 간격을 정하는 건
     * 청산 확인 쪽입니다 — 레버리지 ETF에서 몇 초의 지연은 곧 몇 초어치 슬리피지입니다.
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
            // 서비스는 자기 오류에 대해 이미 스스로 정지합니다. 이건 마지막 그물로, tick 하나가
            // 잘못됐다고 스케줄러 스레드가 죽어 열린 포지션이 조용히 방치되는 일을 막습니다.
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
