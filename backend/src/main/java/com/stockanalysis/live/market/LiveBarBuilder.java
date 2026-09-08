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
 * Rebuilds, from live quotes, the exact kind of {@link Bar} the backtest reads out of the excel —
 * so a saved strategy's indicator conditions mean the same thing on a real account.
 *
 * <p>Two properties of the source data are reproduced here, both verified against the seed files,
 * and both silently wrong if guessed:
 *
 * <ul>
 *   <li><b>A bar's timestamp is its start</b>, and the bucket is {@code [t, t + interval)} aligned
 *       to minute-of-day. In the 3-minute futures file the closing-auction bars 15:36 / 15:39 /
 *       15:42 carry zero volume; if the label were the bar's end, the 15:36 bar would span 15:34
 *       (still in continuous trading) and could not be empty.</li>
 *   <li><b>MA<i>n</i> is an <i>n</i>-bar average, not an n-minute one.</b> MA5 on the 3-minute file
 *       matches the mean of the previous 5 bars' closes exactly. So the same spec means different
 *       things at different bar lengths, and live bars must be built at the same interval the
 *       strategy was backtested on.</li>
 * </ul>
 *
 * <p>Indicators that lack enough history stay {@link Double#NaN}. That is deliberate: the engine
 * treats any comparison involving NaN as false, so an unwarmed MA60 simply never fires instead of
 * firing on a made-up number.
 */
public class LiveBarBuilder {

    /** Longest lookback any indicator needs (volMA120), plus room to spare. */
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
     * Seeds history from the broker's 1-minute bars, aggregating them up to this builder's
     * interval. Call with the previous session first, then today — volMA120 on 3-minute bars needs
     * six hours of history, which is most of a trading day.
     *
     * <p>Best-effort by design: a short warm-up leaves the long indicators NaN rather than failing.
     */
    public void warmUp(List<Bar> oneMinuteBars) {
        for (Bar aggregated : aggregate(oneMinuteBars, intervalMinutes)) {
            // Indicators are recomputed as each bar lands, not left as they came in: the engine
            // reads the *previous* bar for CROSS operators, so a history of NaN-indicator bars
            // would silently disable every crossover rule for the first bars of the session.
            push(withIndicators(aggregated.ts(), aggregated.open(), aggregated.high(),
                    aggregated.low(), aggregated.close(), aggregated.volume()));
        }
    }

    /**
     * Feeds one live quote in. Returns the bar that just closed, if this quote belongs to a new
     * bucket — the caller evaluates signal/time/day-end exits on that completed bar, exactly as the
     * backtest does. Returns empty while the current bar is still forming.
     *
     * @param cumulativeVolume session-to-date traded volume, or null when the feed doesn't report it
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

        // A quote landed in a later bucket, so the previous bar is final. Seal it *before* taking
        // this quote's cumulative volume, or the new bar's trades get counted in the old one.
        Bar completed = sealCurrent();
        push(completed);
        if (cumulativeVolume != null) {
            latestCumulativeVolume = cumulativeVolume;
        }
        startBucket(bucket, price, cumulativeVolume);
        return java.util.Optional.of(completed);
    }

    /**
     * The bar currently forming, with indicators computed as if it had closed now. Used for
     * intrabar exit checks and for display; it is not pushed into history until it closes.
     */
    public java.util.Optional<Bar> formingBar() {
        if (bucketStart == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(sealCurrent());
    }

    /** Completed bars, oldest first. */
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
     * Which indicators have enough history to hold a real number. The screen shows this so a user
     * can see that, say, MA60 is not armed yet rather than wondering why a rule never fires.
     */
    public Map<Indicator, Boolean> readiness() {
        Map<Indicator, Boolean> out = new EnumMap<>(Indicator.class);
        // The forming bar counts: an average of n bars is the current one plus n-1 from history,
        // which is how the excel's MA5 covers the labelled bar and the four before it.
        int n = history.size() + (bucketStart == null ? 0 : 1);
        for (Indicator indicator : Indicator.values()) {
            int need = lookbackOf(indicator);
            out.put(indicator, n >= need);
        }
        return out;
    }

    /** How many bars an indicator needs before it stops being NaN. */
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

    // ------------------------------------------------------------------ internals

    private void startBucket(LocalDateTime bucket, double price, Double cumulativeVolume) {
        bucketStart = bucket;
        open = price;
        high = price;
        low = price;
        close = price;
        bucketStartCumulativeVolume = cumulativeVolume;
    }

    /** Builds the current bucket into a Bar with indicators taken from completed history. */
    private Bar sealCurrent() {
        double volume = Double.NaN;
        if (bucketStartCumulativeVolume != null && latestCumulativeVolume != null) {
            double delta = latestCumulativeVolume - bucketStartCumulativeVolume;
            volume = delta >= 0 ? delta : Double.NaN; // a reset (new session) makes the delta meaningless
        }
        return withIndicators(bucketStart, open, high, low, close, volume);
    }

    /**
     * Computes the moving averages for a bar that closes now. Averages include the bar itself, the
     * same way the excel's MA5 covers the labelled bar plus the four before it.
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
                return Double.NaN; // a gap in volume makes the average a lie
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
     * Start of the bucket a timestamp belongs to: minute-of-day floored to the interval. For
     * 3-minute bars this reproduces the 08:45 / 08:48 / … / 15:45 grid in the source files.
     */
    public static LocalDateTime bucketStartOf(LocalDateTime ts, int intervalMinutes) {
        int minuteOfDay = ts.getHour() * 60 + ts.getMinute();
        int floored = (minuteOfDay / intervalMinutes) * intervalMinutes;
        return LocalDateTime.of(ts.toLocalDate(), LocalTime.of(floored / 60, floored % 60));
    }

    /**
     * Folds 1-minute bars into {@code intervalMinutes} bars. Indicators are left NaN here and
     * recomputed as each aggregated bar is pushed, so warm-up and live bars are built the same way.
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

    /** Convenience for callers holding a date rather than a timestamp. */
    public static LocalDateTime firstBucketOf(LocalDate date, LocalTime sessionStart, int intervalMinutes) {
        return bucketStartOf(LocalDateTime.of(date, sessionStart), intervalMinutes);
    }
}
