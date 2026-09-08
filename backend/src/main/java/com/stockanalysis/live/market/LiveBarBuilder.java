package com.stockanalysis.live.market;

import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.Indicator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 실시간 시세로부터, 백테스트가 엑셀에서 읽는 것과 똑같은 {@link Bar}를 다시 만듭니다 —
 * 그래야 저장된 전략의 지표 조건이 실계좌에서도 같은 것을 뜻합니다.
 *
 * <p>원본 데이터의 성질 두 가지를 여기서 재현합니다. 둘 다 시드 파일로 실측 확인했고,
 * 둘 다 추측으로 정하면 조용히 틀립니다:
 *
 * <ul>
 *   <li><b>봉의 타임스탬프는 봉의 시작 시각</b>이고, 구간은 하루 중 분에 정렬된
 *       {@code [t, t + interval)}입니다. 3분봉 선물 파일에서 종가 단일가 구간의 15:36 / 15:39 /
 *       15:42 봉은 거래량이 0인데, 라벨이 끝 시각이라면 15:36 봉이 (아직 연속거래 중인)
 *       15:34를 포함하게 되어 0일 수 없습니다.</li>
 *   <li><b>MA<i>n</i>은 <i>n</i>봉 평균이지 n분 평균이 아닙니다.</b> 3분봉 파일의 MA5는 직전
 *       5봉 종가의 평균과 정확히 일치합니다. 따라서 같은 스펙이 봉 길이에 따라 다른 것을 뜻하며,
 *       실시간 봉은 그 전략을 백테스트한 것과 같은 간격으로 조립해야 합니다.</li>
 * </ul>
 *
 * <p>이력이 모자란 지표는 {@link Double#NaN}으로 남습니다. 의도한 것입니다: 엔진은 NaN이 낀
 * 비교를 전부 false로 보므로, 워밍업이 안 된 MA60은 지어낸 숫자로 발동하는 대신 그냥
 * 발동하지 않습니다.
 */
public class LiveBarBuilder {

    /** 어떤 지표든 필요로 하는 가장 긴 룩백(volMA120)에 여유를 더한 값. */
    private static final int MAX_HISTORY = 400;

    private final int intervalMinutes;
    private final Deque<Bar> history = new ArrayDeque<>();

    private LocalDateTime bucketStart;
    private double open;
    private double high;
    private double low;
    private double close;
    private Double bucketStartCumulativeVolume;
    private Double latestCumulativeVolume;

    public LiveBarBuilder(int intervalMinutes) {
        if (intervalMinutes <= 0) {
            throw new IllegalArgumentException("봉 길이는 1분 이상이어야 합니다: " + intervalMinutes);
        }
        this.intervalMinutes = intervalMinutes;
    }

    public int intervalMinutes() {
        return intervalMinutes;
    }

    /**
     * 브로커의 1분봉을 이 빌더의 간격으로 합쳐 이력을 채웁니다. 전일부터 먼저 넣고 그다음 오늘을
     * 넣으세요 — 3분봉의 volMA120은 6시간치 이력이 필요한데, 그건 거래일의 대부분입니다.
     *
     * <p>설계상 최선 노력입니다: 워밍업이 짧으면 실패하는 대신 긴 지표를 NaN으로 둡니다.
     */
    public void warmUp(List<Bar> oneMinuteBars) {
        for (Bar aggregated : aggregate(oneMinuteBars, intervalMinutes)) {
            // 지표는 봉이 들어올 때마다 다시 계산합니다. 받은 그대로 두지 않는 이유는, 엔진이
            // CROSS 연산자에서 *직전* 봉을 읽기 때문입니다 — 지표가 NaN인 봉으로 이력을 채우면
            // 세션 초반의 모든 돌파 규칙이 조용히 죽습니다.
            push(withIndicators(aggregated.ts(), aggregated.open(), aggregated.high(),
                    aggregated.low(), aggregated.close(), aggregated.volume()));
        }
    }

    /**
     * 실시간 시세 하나를 넣습니다. 이 시세가 새 구간에 속하면, 방금 마감된 봉을 돌려줍니다 —
     * 호출자는 그 완성된 봉으로 시그널/시간/장마감 청산을 평가합니다. 백테스트와 똑같습니다.
     * 현재 봉이 아직 만들어지는 중이면 빈 값을 돌려줍니다.
     *
     * @param cumulativeVolume 세션 누적 거래량. 피드가 주지 않으면 null
     */
    public java.util.Optional<Bar> accept(LocalDateTime ts, double price, Double cumulativeVolume) {
        LocalDateTime bucket = bucketStartOf(ts, intervalMinutes);

        if (bucketStart == null) {
            latestCumulativeVolume = cumulativeVolume;
            startBucket(bucket, price, cumulativeVolume);
            return java.util.Optional.empty();
        }
        if (bucket.equals(bucketStart)) {
            if (cumulativeVolume != null) {
                latestCumulativeVolume = cumulativeVolume;
            }
            high = Math.max(high, price);
            low = Math.min(low, price);
            close = price;
            return java.util.Optional.empty();
        }

        // 시세가 뒤쪽 구간에 떨어졌으므로 직전 봉은 확정입니다. 이 시세의 누적 거래량을 반영하기
        // **전에** 봉을 마감해야, 새 봉의 거래가 옛 봉에 섞이지 않습니다.
        Bar completed = sealCurrent();
        push(completed);
        if (cumulativeVolume != null) {
            latestCumulativeVolume = cumulativeVolume;
        }
        startBucket(bucket, price, cumulativeVolume);
        return java.util.Optional.of(completed);
    }

    /**
     * 지금 만들어지는 중인 봉. 지표는 방금 마감한 것처럼 계산해서 담습니다. 장중 청산 판정과
     * 화면 표시에 쓰며, 마감되기 전까지는 이력에 넣지 않습니다.
     */
    public java.util.Optional<Bar> formingBar() {
        if (bucketStart == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(sealCurrent());
    }

    /** 완성된 봉들. 오래된 것부터. */
    public List<Bar> completedBars() {
        return new ArrayList<>(history);
    }

    public java.util.Optional<Bar> lastCompletedBar() {
        return history.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(history.peekLast());
    }

    public int completedBarCount() {
        return history.size();
    }

    /**
     * 어떤 지표가 실제 숫자를 담을 만큼 이력을 갖췄는지. 화면이 이걸 보여줘서, 사용자가 예컨대
     * MA60이 아직 준비 안 됐다는 걸 알 수 있게 합니다 — 규칙이 왜 안 걸리는지 헤매지 않도록.
     */
    public Map<Indicator, Boolean> readiness() {
        Map<Indicator, Boolean> out = new EnumMap<>(Indicator.class);
        // 만들어지는 중인 봉도 셉니다: n봉 평균은 현재 봉 하나에 이력 n-1개를 더한 것이고,
        // 엑셀의 MA5가 라벨이 붙은 봉과 그 앞 네 개를 덮는 방식이 바로 이것입니다.
        int n = history.size() + (bucketStart == null ? 0 : 1);
        for (Indicator indicator : Indicator.values()) {
            int need = lookbackOf(indicator);
            out.put(indicator, n >= need);
        }
        return out;
    }

    /** 어떤 지표가 NaN을 벗어나기까지 필요한 봉 개수. */
    public static int lookbackOf(Indicator indicator) {
        return switch (indicator) {
            case OPEN, HIGH, LOW, CLOSE, VOLUME -> 1;
            case MA5, VOL_MA5 -> 5;
            case MA10 -> 10;
            case MA20, VOL_MA20 -> 20;
            case MA60, VOL_MA60 -> 60;
            case VOL_MA120 -> 120;
        };
    }

    // ------------------------------------------------------------------ 내부

    private void startBucket(LocalDateTime bucket, double price, Double cumulativeVolume) {
        bucketStart = bucket;
        open = price;
        high = price;
        low = price;
        close = price;
        bucketStartCumulativeVolume = cumulativeVolume;
    }

    /** 현재 구간을 Bar로 만듭니다. 지표는 완성된 이력에서 가져옵니다. */
    private Bar sealCurrent() {
        double volume = Double.NaN;
        if (bucketStartCumulativeVolume != null && latestCumulativeVolume != null) {
            double delta = latestCumulativeVolume - bucketStartCumulativeVolume;
            volume = delta >= 0 ? delta : Double.NaN; // 리셋(새 세션)이면 증분이 의미가 없습니다
        }
        return withIndicators(bucketStart, open, high, low, close, volume);
    }

    /**
     * 지금 마감되는 봉의 이동평균을 계산합니다. 평균에는 그 봉 자신이 포함됩니다 — 엑셀의 MA5가
     * 라벨이 붙은 봉에 그 앞 네 개를 더해 덮는 것과 같은 방식입니다.
     */
    private Bar withIndicators(LocalDateTime ts, double o, double h, double l, double c, double volume) {
        return new Bar(ts, o, h, l, c,
                closeMa(5, c), closeMa(10, c), closeMa(20, c), closeMa(60, c),
                volume,
                volumeMa(5, volume), volumeMa(20, volume), volumeMa(60, volume), volumeMa(120, volume));
    }

    private double closeMa(int n, double currentClose) {
        double sum = currentClose;
        int taken = 1;
        var it = history.descendingIterator();
        while (taken < n && it.hasNext()) {
            sum += it.next().close();
            taken++;
        }
        return taken == n ? sum / n : Double.NaN;
    }

    private double volumeMa(int n, double currentVolume) {
        if (Double.isNaN(currentVolume)) {
            return Double.NaN;
        }
        double sum = currentVolume;
        int taken = 1;
        var it = history.descendingIterator();
        while (taken < n && it.hasNext()) {
            double v = it.next().volume();
            if (Double.isNaN(v)) {
                return Double.NaN; // 거래량에 구멍이 있으면 그 평균은 거짓말이 됩니다
            }
            sum += v;
            taken++;
        }
        return taken == n ? sum / n : Double.NaN;
    }

    private void push(Bar bar) {
        history.addLast(bar);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    /**
     * 타임스탬프가 속한 구간의 시작. 하루 중 분을 간격 단위로 내림합니다. 3분봉이면 원본 파일의
     * 08:45 / 08:48 / … / 15:45 격자를 그대로 재현합니다.
     */
    public static LocalDateTime bucketStartOf(LocalDateTime ts, int intervalMinutes) {
        int minuteOfDay = ts.getHour() * 60 + ts.getMinute();
        int floored = (minuteOfDay / intervalMinutes) * intervalMinutes;
        return LocalDateTime.of(ts.toLocalDate(), LocalTime.of(floored / 60, floored % 60));
    }

    /**
     * 1분봉을 {@code intervalMinutes} 봉으로 접습니다. 여기서 지표는 NaN으로 두고, 합쳐진 봉이
     * 하나씩 들어갈 때 다시 계산합니다 — 워밍업 봉과 실시간 봉이 같은 방식으로 만들어지도록.
     */
    public static List<Bar> aggregate(List<Bar> oneMinuteBars, int intervalMinutes) {
        List<Bar> out = new ArrayList<>();
        if (oneMinuteBars.isEmpty()) {
            return out;
        }
        List<Bar> sorted = new ArrayList<>(oneMinuteBars);
        sorted.sort((a, b) -> a.ts().compareTo(b.ts()));

        LocalDateTime bucket = null;
        double o = 0;
        double h = 0;
        double l = 0;
        double c = 0;
        double v = 0;
        boolean volumeKnown = true;

        for (Bar b : sorted) {
            LocalDateTime bs = bucketStartOf(b.ts(), intervalMinutes);
            if (bucket == null || !bucket.equals(bs)) {
                if (bucket != null) {
                    out.add(plain(bucket, o, h, l, c, volumeKnown ? v : Double.NaN));
                }
                bucket = bs;
                o = b.open();
                h = b.high();
                l = b.low();
                v = 0;
                volumeKnown = true;
            }
            h = Math.max(h, b.high());
            l = Math.min(l, b.low());
            c = b.close();
            if (Double.isNaN(b.volume())) {
                volumeKnown = false;
            } else {
                v += b.volume();
            }
        }
        out.add(plain(bucket, o, h, l, c, volumeKnown ? v : Double.NaN));
        return out;
    }

    private static Bar plain(LocalDateTime ts, double o, double h, double l, double c, double volume) {
        double nan = Double.NaN;
        return new Bar(ts, o, h, l, c, nan, nan, nan, nan, volume, nan, nan, nan, nan);
    }

    /** 타임스탬프 대신 날짜를 들고 있는 호출자를 위한 편의 메서드. */
    public static LocalDateTime firstBucketOf(LocalDate date, LocalTime sessionStart, int intervalMinutes) {
        return bucketStartOf(LocalDateTime.of(date, sessionStart), intervalMinutes);
    }
}
