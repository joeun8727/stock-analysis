package com.stockanalysis.backtest;

import com.stockanalysis.backtest.spec.CapitalMode;
import com.stockanalysis.backtest.spec.CapitalSpec;
import com.stockanalysis.backtest.spec.ConditionGroup;
import com.stockanalysis.backtest.spec.ExitSpec;
import com.stockanalysis.backtest.spec.PremarketSpec;
import com.stockanalysis.backtest.spec.StrategySpec;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Framework-independent intraday (scalping) backtest engine.
 *
 * <p>Single mode: one long position, 1 unit, entry at the close of the signal bar
 * (no look-ahead). Exit priority per held bar: stop-loss → take-profit → signal → time →
 * day-end → end-of-data; take-profit/stop-loss are intrabar via high/low, stop-loss winning
 * ties (conservative). Their percentages are re-resolved on every held bar against the bar's own
 * wall-clock time, so an {@link ExitSpec} time band can move the levels mid-position.
 *
 * <p>ETF pre-market mode ({@link #runEtfPremarket}): each day the futures close change over the
 * pre-market window picks leverage (up) or inverse (down); the chosen ETF is bought at the open
 * and closed by the same exit rules.
 *
 * <p>Entries and exits are decided purely on price; money is layered on afterwards by
 * {@link #applyCapital} using the spec's {@code CapitalSpec} and a {@link FeeSchedule} of per-symbol
 * commission rates, so position sizing and fees never change which trades happen.
 */
public class BacktestEngine {

    private record ExitDecision(double price, ExitReason reason) {
    }

    // ------------------------------------------------------------------ single mode

    /** Commission-free convenience overload: the price-only baseline used by tools and tests. */
    public BacktestResult run(StrategySpec spec, BarSeries series) {
        return run(spec, series, FeeSchedule.free());
    }

    public BacktestResult run(StrategySpec spec, BarSeries series, FeeSchedule fees) {
        List<Bar> bars = series.bars();
        int n = bars.size();
        List<TradeRecord> trades = new ArrayList<>();

        ConditionGroup entry = spec.getEntry();
        ExitSpec exitSpec = spec.getExit();
        ConditionGroup exitGroup = exitSpec.asGroup();

        boolean holding = false;
        int entryIndex = -1;
        double entryPrice = 0.0;
        LocalDateTime entryTs = null;

        for (int i = 0; i < n; i++) {
            Bar cur = bars.get(i);
            Bar prev = i > 0 ? bars.get(i - 1) : null;
            Bar curFut = series.hasFutures() ? series.futuresAt(cur.ts()) : null;
            Bar prevFut = (prev != null && series.hasFutures()) ? series.futuresAt(prev.ts()) : null;

            if (!holding) {
                if (entry.matches(cur, prev, curFut, prevFut)) {
                    holding = true;
                    entryIndex = i;
                    entryPrice = cur.close();
                    entryTs = cur.ts();
                }
                continue;
            }

            boolean lastOverall = (i == n - 1);
            boolean lastBarOfDay = lastOverall || !cur.date().equals(bars.get(i + 1).date());
            ExitDecision d = decideExit(cur, prev, lastOverall, lastBarOfDay, i - entryIndex,
                    exitSpec, exitGroup, entryPrice, curFut, prevFut);
            if (d != null) {
                trades.add(tradeOf(entryTs, cur.ts(), entryPrice, d, null));
                holding = false;
            }
        }

        return aggregate(trades, spec, fees);
    }

    // --------------------------------------------------------------- ETF pre-market mode

    /** Commission-free convenience overload. */
    public BacktestResult runEtfPremarket(StrategySpec spec, BarSeries leverage, BarSeries inverse, BarSeries futures) {
        return runEtfPremarket(spec, leverage, inverse, futures, FeeSchedule.free());
    }

    public BacktestResult runEtfPremarket(StrategySpec spec, BarSeries leverage, BarSeries inverse,
                                          BarSeries futures, FeeSchedule fees) {
        PremarketSpec pm = spec.getPremarket();
        LocalTime start = LocalTime.parse(pm.getStartTime());
        LocalTime end = LocalTime.parse(pm.getEndTime());
        double threshold = Math.abs(pm.getThresholdPct());

        ExitSpec exitSpec = spec.getExit();
        ConditionGroup exitGroup = exitSpec.asGroup();

        Map<LocalDate, List<Bar>> futByDay = groupByDate(futures.bars());
        Map<LocalDate, List<Bar>> levByDay = groupByDate(leverage.bars());
        Map<LocalDate, List<Bar>> invByDay = groupByDate(inverse.bars());

        List<TradeRecord> trades = new ArrayList<>();

        for (Map.Entry<LocalDate, List<Bar>> e : futByDay.entrySet()) {
            Double trend = premarketTrend(e.getValue(), start, end);
            if (trend == null || Math.abs(trend) < threshold) {
                continue; // no clear pre-market trend → skip the day
            }
            boolean up = trend > 0;
            String instrument = up ? "LEVERAGE" : "INVERSE";
            List<Bar> dayBars = (up ? levByDay : invByDay).get(e.getKey());
            if (dayBars == null || dayBars.isEmpty()) {
                continue;
            }
            TradeRecord tr = simulateDay(dayBars, end, exitSpec, exitGroup, instrument);
            if (tr != null) {
                trades.add(tr);
            }
        }

        return aggregate(trades, spec, fees);
    }

    /** Buy the chosen ETF at the first bar at/after the pre-market window ends; exit intraday. */
    private TradeRecord simulateDay(List<Bar> dayBars, LocalTime openAtOrAfter,
                                    ExitSpec exitSpec, ConditionGroup exitGroup, String instrument) {
        int n = dayBars.size();
        int entryIndex = -1;
        for (int i = 0; i < n; i++) {
            if (!dayBars.get(i).ts().toLocalTime().isBefore(openAtOrAfter)) {
                entryIndex = i;
                break;
            }
        }
        if (entryIndex < 0 || entryIndex >= n - 1) {
            return null; // no bar to enter on, or no bar left to exit
        }
        Bar entryBar = dayBars.get(entryIndex);
        double entryPrice = entryBar.close();

        for (int j = entryIndex + 1; j < n; j++) {
            Bar cur = dayBars.get(j);
            Bar prev = dayBars.get(j - 1);
            boolean lastBarOfDay = (j == n - 1);
            // Pre-market mode always closes same day: force day-end at the last bar.
            ExitDecision d = decideExit(cur, prev, false, lastBarOfDay, j - entryIndex,
                    exitSpec, exitGroup, entryPrice, null, null);
            if (d == null && lastBarOfDay) {
                d = new ExitDecision(cur.close(), ExitReason.DAY_END);
            }
            if (d != null) {
                return tradeOf(entryBar.ts(), cur.ts(), entryPrice, d, instrument);
            }
        }
        return null;
    }

    /** Futures close change (%) over [start, end); null if the window has too few bars. */
    private static Double premarketTrend(List<Bar> dayBars, LocalTime start, LocalTime end) {
        Bar first = null;
        Bar last = null;
        for (Bar b : dayBars) {
            LocalTime t = b.ts().toLocalTime();
            if (!t.isBefore(start) && t.isBefore(end)) {
                if (first == null) {
                    first = b;
                }
                last = b;
            }
        }
        if (first == null || last == null || first == last || first.close() == 0.0) {
            return null;
        }
        return (last.close() - first.close()) / first.close() * 100.0;
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Stop price for a position entered at {@code entryPrice}, as of wall-clock {@code at} — the
     * time of the bar being checked, so a time band that starts mid-trade moves the level.
     */
    private static double stopPriceOf(ExitSpec exit, double entryPrice, LocalTime at) {
        Double pct = exit.stopLossPctAt(at);
        return pct != null ? entryPrice * (1.0 - pct / 100.0) : Double.NaN;
    }

    private static double tpPriceOf(ExitSpec exit, double entryPrice, LocalTime at) {
        Double pct = exit.takeProfitPctAt(at);
        return pct != null ? entryPrice * (1.0 + pct / 100.0) : Double.NaN;
    }

    private static TradeRecord tradeOf(LocalDateTime entryTs, LocalDateTime exitTs, double entryPrice,
                                       ExitDecision d, String instrument) {
        double ret = (d.price() - entryPrice) / entryPrice * 100.0;
        return new TradeRecord(entryTs, exitTs, entryPrice, d.price(), ret, d.reason(), ret > 0, instrument);
    }

    private static ExitDecision decideExit(Bar cur, Bar prev, boolean lastOverall, boolean lastBarOfDay,
                                           int barsHeld, ExitSpec exit, ConditionGroup exitGroup,
                                           double entryPrice, Bar curFut, Bar prevFut) {
        // Resolved per bar, not per trade: an ExitSpec time band can move these mid-position.
        LocalTime at = cur.ts().toLocalTime();
        double stopPrice = stopPriceOf(exit, entryPrice, at);
        double tpPrice = tpPriceOf(exit, entryPrice, at);
        if (!Double.isNaN(stopPrice) && cur.low() <= stopPrice) {
            return new ExitDecision(stopPrice, ExitReason.STOP_LOSS);
        }
        if (!Double.isNaN(tpPrice) && cur.high() >= tpPrice) {
            return new ExitDecision(tpPrice, ExitReason.TAKE_PROFIT);
        }
        if (!exitGroup.isEmpty() && exitGroup.matches(cur, prev, curFut, prevFut)) {
            return new ExitDecision(cur.close(), ExitReason.SIGNAL);
        }
        if (exit.getMaxHoldBars() != null && barsHeld >= exit.getMaxHoldBars()) {
            return new ExitDecision(cur.close(), ExitReason.TIME);
        }
        if (exit.isCloseAtDayEnd() && lastBarOfDay && !lastOverall) {
            return new ExitDecision(cur.close(), ExitReason.DAY_END);
        }
        if (lastOverall) {
            return new ExitDecision(cur.close(), ExitReason.END_OF_DATA);
        }
        return null;
    }

    private static Map<LocalDate, List<Bar>> groupByDate(List<Bar> bars) {
        Map<LocalDate, List<Bar>> byDay = new LinkedHashMap<>();
        for (Bar b : bars) {
            byDay.computeIfAbsent(b.date(), k -> new ArrayList<>()).add(b);
        }
        return byDay;
    }

    // ------------------------------------------------------------------ aggregation

    private BacktestResult aggregate(List<TradeRecord> priceTrades, StrategySpec spec, FeeSchedule fees) {
        CapitalSpec cap = spec.getCapital() == null ? new CapitalSpec() : spec.getCapital();
        List<TradeRecord> trades = applyCapital(priceTrades, cap, fees);
        int total = trades.size();
        int wins = 0;
        double sumRet = 0.0;
        double sumWin = 0.0;
        double sumLoss = 0.0;
        int lossCount = 0;
        double equity = 1.0;
        double peak = 1.0;
        double maxDrawdown = 0.0;
        int consecLosses = 0;
        int maxConsecLosses = 0;

        List<BacktestResult.EquityPoint> curve = new ArrayList<>();
        for (TradeRecord t : trades) {
            sumRet += t.returnPct();
            if (t.success()) {
                wins++;
                sumWin += t.returnPct();
                consecLosses = 0;
            } else {
                lossCount++;
                sumLoss += t.returnPct();
                consecLosses++;
                maxConsecLosses = Math.max(maxConsecLosses, consecLosses);
            }
            equity *= (1.0 + t.returnPct() / 100.0);
            peak = Math.max(peak, equity);
            double drawdown = (peak - equity) / peak * 100.0;
            maxDrawdown = Math.max(maxDrawdown, drawdown);
            curve.add(new BacktestResult.EquityPoint(t.exitTs(), equity));
        }

        int losses = total - wins;
        double winRate = total == 0 ? 0.0 : (double) wins / total * 100.0;
        double lossRate = total == 0 ? 0.0 : (double) losses / total * 100.0;
        double avgWin = wins == 0 ? 0.0 : sumWin / wins;
        double avgLoss = lossCount == 0 ? 0.0 : sumLoss / lossCount;
        double compounded = (equity - 1.0) * 100.0;

        BacktestResult.Summary summary = new BacktestResult.Summary(
                total, wins, losses, winRate, lossRate, sumRet, compounded,
                avgWin, avgLoss, maxDrawdown, maxConsecLosses);

        BacktestResult.MoneySummary money = moneyOf(trades, cap, fees);
        BacktestResult.FailureAnalysis failure = buildFailureAnalysis(trades);
        BacktestResult.Diagnosis diagnosis =
                FailureDiagnostician.diagnose(summary, money, failure, trades, spec.getExit(), cap,
                        spec.usesPremarket());

        return new BacktestResult(summary, money, diagnosis, trades, curve, failure);
    }

    /**
     * Sizes every trade in won. Shares are whole (Korean equities/ETFs don't trade fractions), so
     * the budget is floored to the nearest share; the leftover cash simply stays uninvested. In
     * compound mode the running balance carries each trade's net result into the next entry.
     */
    private static List<TradeRecord> applyCapital(List<TradeRecord> trades, CapitalSpec cap, FeeSchedule fees) {
        double balance = cap.getAmount();
        List<TradeRecord> out = new ArrayList<>(trades.size());
        for (TradeRecord t : trades) {
            double feeRate = Math.max(0.0, fees.rateFor(t.instrument())) / 100.0;
            double budget = cap.getMode() == CapitalMode.COMPOUND ? balance : cap.getAmount();
            long qty = (t.entryPrice() > 0 && budget > 0) ? (long) Math.floor(budget / t.entryPrice()) : 0L;
            double buyValue = qty * t.entryPrice();
            double sellValue = qty * t.exitPrice();
            double feeCost = (buyValue + sellValue) * feeRate;
            double profit = sellValue - buyValue - feeCost;
            if (cap.getMode() == CapitalMode.COMPOUND) {
                balance += profit;
            }
            out.add(t.withMoney(qty, profit, feeCost));
        }
        return out;
    }

    private static BacktestResult.MoneySummary moneyOf(List<TradeRecord> trades, CapitalSpec cap, FeeSchedule fees) {
        double totalProfit = 0.0;
        double totalFees = 0.0;
        double best = 0.0;
        double worst = 0.0;
        int unaffordable = 0;
        for (TradeRecord t : trades) {
            totalProfit += t.profitAmount();
            totalFees += t.feeAmount();
            best = Math.max(best, t.profitAmount());
            worst = Math.min(worst, t.profitAmount());
            if (t.quantity() == 0) {
                unaffordable++;
            }
        }
        double invest = cap.getAmount();
        double avg = trades.isEmpty() ? 0.0 : totalProfit / trades.size();
        double roc = invest == 0.0 ? 0.0 : totalProfit / invest * 100.0;
        return new BacktestResult.MoneySummary(
                cap.getMode().name(), invest, fees.ratesFor(trades), totalProfit, totalFees,
                avg, invest + totalProfit, roc, best, worst, unaffordable);
    }

    private BacktestResult.FailureAnalysis buildFailureAnalysis(List<TradeRecord> trades) {
        Map<ExitReason, int[]> reasonCount = new EnumMap<>(ExitReason.class);
        Map<ExitReason, double[]> reasonSum = new EnumMap<>(ExitReason.class);
        Map<Integer, int[]> hourLosses = new TreeMap<>();
        Map<Integer, double[]> hourLossSum = new TreeMap<>();

        List<TradeRecord> losing = new ArrayList<>();
        for (TradeRecord t : trades) {
            if (t.success()) {
                continue;
            }
            losing.add(t);
            reasonCount.computeIfAbsent(t.exitReason(), k -> new int[1])[0]++;
            reasonSum.computeIfAbsent(t.exitReason(), k -> new double[1])[0] += t.returnPct();
            hourLosses.computeIfAbsent(t.entryHour(), k -> new int[1])[0]++;
            hourLossSum.computeIfAbsent(t.entryHour(), k -> new double[1])[0] += t.returnPct();
        }

        Map<ExitReason, BacktestResult.ReasonStat> byReason = new EnumMap<>(ExitReason.class);
        for (Map.Entry<ExitReason, int[]> e : reasonCount.entrySet()) {
            int c = e.getValue()[0];
            double sum = reasonSum.get(e.getKey())[0];
            byReason.put(e.getKey(), new BacktestResult.ReasonStat(c, sum / c, sum));
        }

        Map<Integer, BacktestResult.HourStat> byHour = new TreeMap<>();
        for (Map.Entry<Integer, int[]> e : hourLosses.entrySet()) {
            int c = e.getValue()[0];
            double sum = hourLossSum.get(e.getKey())[0];
            byHour.put(e.getKey(), new BacktestResult.HourStat(c, sum / c));
        }

        List<TradeRecord> worst = new ArrayList<>(losing);
        worst.sort((a, b) -> Double.compare(a.returnPct(), b.returnPct()));
        List<TradeRecord> worstTop = worst.subList(0, Math.min(10, worst.size()));

        return new BacktestResult.FailureAnalysis(byReason, byHour, List.copyOf(worstTop));
    }
}
