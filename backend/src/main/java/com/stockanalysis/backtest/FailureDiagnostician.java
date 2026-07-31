package com.stockanalysis.backtest;

import com.stockanalysis.backtest.BacktestResult.Diagnosis;
import com.stockanalysis.backtest.BacktestResult.Finding;
import com.stockanalysis.backtest.BacktestResult.Severity;
import com.stockanalysis.backtest.spec.CapitalMode;
import com.stockanalysis.backtest.spec.CapitalSpec;
import com.stockanalysis.backtest.spec.ExitSpec;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a finished backtest into a short plain-Korean diagnosis: what the biggest problem is and
 * what to change. Deterministic rules only — no model call — so every run gets one instantly.
 *
 * <p>Each rule fires only when its evidence is strong enough to be worth a sentence, and findings
 * are capped so the card stays readable. Thresholds are deliberately blunt; this is a "look here
 * first" pointer, not a verdict.
 */
final class FailureDiagnostician {

    /** A rule needs at least this many losing trades before a share-of-losses claim means anything. */
    private static final int MIN_LOSSES_FOR_SHARE = 10;
    private static final int MAX_FINDINGS = 5;

    private FailureDiagnostician() {
    }

    static Diagnosis diagnose(BacktestResult.Summary s,
                              BacktestResult.MoneySummary money,
                              BacktestResult.FailureAnalysis fa,
                              List<TradeRecord> trades,
                              ExitSpec exit,
                              CapitalSpec capital,
                              boolean premarket) {
        if (s.totalTrades() == 0) {
            return new Diagnosis("거래가 한 건도 발생하지 않았습니다.", List.of(new Finding(
                    Severity.HIGH, premarket ? "장전 추세 조건을 만족한 날이 없음" : "진입 조건이 한 번도 충족되지 않음",
                    premarket
                            ? "선택한 기간 동안 장전 선물 변화율이 기준치를 넘은 날이 0일이었습니다."
                            : "선택한 기간 동안 매수 신호가 0건이었습니다.",
                    premarket
                            ? "추세 판단 기준(%)을 낮추거나 장전 시간대(08:45~09:00)를 넓혀보세요."
                            : "조건을 느슨하게 바꾸거나(예: 지표 비교를 CROSS 대신 GT로), 백테스트 기간을 넓혀보세요.")));
        }

        List<Finding> findings = new ArrayList<>();
        feeDrag(findings, money);
        payoffVsWinRate(findings, s);
        dominantExitReason(findings, s, fa, exit, premarket);
        if (!premarket) {
            // Pre-market mode always enters right after 09:00 by design, so "avoid this hour" is not
            // advice the user can act on — the entry time is the strategy, not a tunable condition.
            worstHour(findings, s, fa);
        }
        overtrading(findings, trades, money);
        drawdownAndStreak(findings, s);
        unaffordable(findings, money, capital);

        findings.sort(Comparator.comparingInt(f -> f.severity().ordinal()));
        List<Finding> top = findings.subList(0, Math.min(MAX_FINDINGS, findings.size()));
        return new Diagnosis(headline(s, money, top), List.copyOf(top));
    }

    // ------------------------------------------------------------------ rules

    /** Scalping fires so often that commission alone can flip a profitable edge into a loss. */
    private static void feeDrag(List<Finding> out, BacktestResult.MoneySummary m) {
        if (m == null || m.totalFeeAmount() <= 0) {
            return;
        }
        double gross = m.totalProfitAmount() + m.totalFeeAmount();
        if (gross > 0 && m.totalProfitAmount() < 0) {
            out.add(new Finding(Severity.HIGH, "수수료가 수익을 전부 삼켰습니다",
                    String.format("수수료 제외 전에는 %s 이익이었는데, 수수료 %s을 빼면 %s 손실입니다.",
                            won(gross), won(m.totalFeeAmount()), won(-m.totalProfitAmount())),
                    "매매 횟수를 줄이세요 — 익절/손절 폭을 키우거나 진입 조건을 더 좁혀 거래 건수를 낮추는 방향입니다."));
        } else if (gross != 0 && m.totalFeeAmount() > Math.abs(gross) * 0.5) {
            out.add(new Finding(Severity.MEDIUM, "수수료 부담이 큽니다",
                    String.format("수수료 %s이 손익 규모(%s)의 절반을 넘습니다.", won(m.totalFeeAmount()), won(Math.abs(gross))),
                    "거래 횟수를 줄이거나, 수수료가 낮은 계좌 조건을 수수료율에 반영해 다시 확인해보세요."));
        }
    }

    /**
     * The structural check: with a payoff ratio of R, you need a win rate above 1/(1+R) just to
     * break even. A "low win rate" is only a problem relative to that number.
     */
    private static void payoffVsWinRate(List<Finding> out, BacktestResult.Summary s) {
        double avgLoss = Math.abs(s.avgLossPct());
        if (s.avgWinPct() <= 0 || avgLoss <= 0) {
            return;
        }
        double payoff = s.avgWinPct() / avgLoss;
        double required = 100.0 / (1.0 + payoff);
        double gap = required - s.winRate();
        if (gap <= 0) {
            return;
        }
        Severity sev = gap >= 10 ? Severity.HIGH : Severity.MEDIUM;
        out.add(new Finding(sev, "손익비 대비 승률이 부족합니다",
                String.format("이길 때 %.2f%%, 질 때 %.2f%%(손익비 %.2f:1)이므로 본전에 필요한 승률은 %.1f%%인데 실제는 %.2f%%입니다.",
                        s.avgWinPct(), avgLoss, payoff, required, s.winRate()),
                payoff < 1.0
                        ? "익절 폭을 손절 폭보다 크게 잡아 손익비를 1 이상으로 올리거나, 진입 조건을 좁혀 승률을 높이세요."
                        : "승률을 높이는 쪽이 빠릅니다 — 진입 조건을 추가해 신호의 질을 올려보세요."));
    }

    /** Which exit is doing the damage. Each reason points at a different knob. */
    private static void dominantExitReason(List<Finding> out, BacktestResult.Summary s,
                                           BacktestResult.FailureAnalysis fa, ExitSpec exit,
                                           boolean premarket) {
        if (s.losses() < MIN_LOSSES_FOR_SHARE) {
            return;
        }
        ExitReason top = null;
        int topCount = 0;
        for (Map.Entry<ExitReason, BacktestResult.ReasonStat> e : fa.byExitReason().entrySet()) {
            if (e.getValue().count() > topCount) {
                topCount = e.getValue().count();
                top = e.getKey();
            }
        }
        if (top == null) {
            return;
        }
        double share = (double) topCount / s.losses() * 100.0;
        if (share < 40.0) {
            return;
        }
        String lbl = label(top);
        String where = String.format("손실 %,d건 중 %,d건(%.0f%%)이 '%s'%s 끝났습니다.",
                s.losses(), topCount, share, lbl, roParticle(lbl));
        String fix = switch (top) {
            case STOP_LOSS -> exit.getStopLossPct() == null
                    ? "손절 규칙을 확인해보세요."
                    : String.format("손절 %.2f%%가 정상적인 흔들림에 걸리는지 확인하세요. %s",
                            exit.getStopLossPct(),
                            premarket
                                    ? "손절 폭을 넓히거나, 추세 판단 기준(%)을 높여 확신이 큰 날만 진입해보세요."
                                    : "손절 폭을 넓히거나 진입 시점을 늦춰보세요.");
            case SIGNAL -> "청산 시그널이 너무 이르게 발동합니다. 청산 조건을 완화하거나 익절 폭에 맡겨보세요.";
            case DAY_END -> premarket
                    ? "장전 추세가 하루 방향을 제대로 예측하지 못한 날이 많습니다. 추세 판단 기준(%)을 높여 확신이 큰 날만 진입해보세요."
                    : "방향은 맞아도 당일 안에 오르지 못하고 끝납니다. 보유 시간을 늘릴 수 없으니 진입을 더 이른 시각으로 옮기거나 조건을 좁히세요.";
            case TIME -> exit.getMaxHoldBars() == null
                    ? "최대 보유 봉수 설정을 확인해보세요."
                    : String.format("최대 보유 %d봉이 짧습니다. 봉수를 늘려 추세가 나올 시간을 주세요.", exit.getMaxHoldBars());
            case TAKE_PROFIT -> "익절로 끝났는데 손실인 건 이례적입니다 — 익절 폭과 수수료율을 함께 확인하세요.";
            case END_OF_DATA -> "데이터 끝에서 강제 청산된 건이 많습니다. 기간을 넓히거나 당일청산을 켜보세요.";
        };
        out.add(new Finding(Severity.HIGH, "손실이 '" + label(top) + "'에 몰려 있습니다", where, fix));
    }

    /** Intraday edges are often time-of-day specific; a single hour hogging the losses is a lead. */
    private static void worstHour(List<Finding> out, BacktestResult.Summary s, BacktestResult.FailureAnalysis fa) {
        if (s.losses() < MIN_LOSSES_FOR_SHARE || fa.byHour().isEmpty()) {
            return;
        }
        int worstHour = -1;
        int worstCount = 0;
        double worstAvg = 0.0;
        for (Map.Entry<Integer, BacktestResult.HourStat> e : fa.byHour().entrySet()) {
            if (e.getValue().lossCount() > worstCount) {
                worstCount = e.getValue().lossCount();
                worstHour = e.getKey();
                worstAvg = e.getValue().avgLossPct();
            }
        }
        double share = (double) worstCount / s.losses() * 100.0;
        if (share < 35.0 || fa.byHour().size() < 2) {
            return;
        }
        out.add(new Finding(Severity.MEDIUM, worstHour + "시대 진입이 손실의 대부분입니다",
                String.format("%d시대에 진입한 거래에서 손실 %d건(전체 손실의 %.0f%%), 평균 %.2f%%가 발생했습니다.",
                        worstHour, worstCount, share, worstAvg),
                String.format("%d시대를 피해 진입하는 조건을 넣고 비교해보세요. 시가 직후 변동성 구간이면 특히 효과가 큽니다.", worstHour)));
    }

    /** Trades per trading day — the strongest predictor of fee drag in a scalping system. */
    private static void overtrading(List<Finding> out, List<TradeRecord> trades, BacktestResult.MoneySummary m) {
        Set<LocalDate> days = new HashSet<>();
        for (TradeRecord t : trades) {
            days.add(t.entryTs().toLocalDate());
        }
        if (days.isEmpty()) {
            return;
        }
        double perDay = (double) trades.size() / days.size();
        if (perDay < 5.0) {
            return;
        }
        String feeNote = (m == null || m.totalFeeAmount() <= 0) ? ""
                : String.format(" 누적 수수료는 %s입니다.", won(m.totalFeeAmount()));
        out.add(new Finding(Severity.MEDIUM, "과매매 구간입니다",
                String.format("거래일 %d일 동안 %,d건, 하루 평균 %.1f회 매매했습니다.%s",
                        days.size(), trades.size(), perDay, feeNote),
                "신호가 너무 자주 나옵니다. 조건을 AND로 더 묶거나 최대 보유 봉수를 늘려 하루 매매 횟수를 낮춰보세요."));
    }

    private static void drawdownAndStreak(List<Finding> out, BacktestResult.Summary s) {
        if (s.maxDrawdownPct() >= 30.0) {
            out.add(new Finding(Severity.MEDIUM, "자본 낙폭이 깊습니다",
                    String.format("최대 낙폭(MDD)이 %.2f%%입니다.", s.maxDrawdownPct()),
                    "실제로는 이 낙폭을 버텨야 합니다. 손절을 조이거나 1회 투자금을 줄여 감당 가능한 수준으로 맞추세요."));
        }
        if (s.maxConsecutiveLosses() >= 8) {
            out.add(new Finding(Severity.INFO, "연속 손실이 깁니다",
                    String.format("최대 %d회 연속 손실이 있었습니다.", s.maxConsecutiveLosses()),
                    "복리 모드라면 이 구간에서 매수 규모가 크게 줄어듭니다. 고정 금액 모드와도 비교해보세요."));
        }
    }

    private static void unaffordable(List<Finding> out, BacktestResult.MoneySummary m, CapitalSpec cap) {
        if (m == null || m.unaffordableTrades() == 0) {
            return;
        }
        out.add(new Finding(Severity.HIGH, "투자금이 부족해 매수하지 못한 거래가 있습니다",
                String.format("%,d건은 1주 값보다 투자금이 적어 0주로 계산됐습니다.", m.unaffordableTrades()),
                cap != null && cap.getMode() == CapitalMode.COMPOUND
                        ? "복리 모드에서 잔고가 1주 값 아래로 줄어든 구간입니다. 시작 자금을 올리거나 고정 금액 모드로 확인하세요."
                        : "1회 매수 금액을 종목 주가보다 충분히 크게 올려주세요."));
    }

    // ------------------------------------------------------------------ helpers

    private static String headline(BacktestResult.Summary s, BacktestResult.MoneySummary m, List<Finding> top) {
        String money = m == null ? ""
                : String.format(" 수익금은 %s입니다.", m.totalProfitAmount() >= 0
                        ? won(m.totalProfitAmount()) + " 이익" : won(-m.totalProfitAmount()) + " 손실");
        String base = String.format("거래 %,d건, 승률 %.2f%%.%s", s.totalTrades(), s.winRate(), money);
        if (top.isEmpty()) {
            return base + " 뚜렷한 문제 패턴은 찾지 못했습니다.";
        }
        return base + " 가장 큰 문제는 \"" + top.get(0).title() + "\"입니다.";
    }

    /**
     * The Korean instrumental particle for a word: 로 after a vowel or a final ㄹ, 으로 otherwise.
     * Hangul syllables are contiguous from U+AC00 and the final-consonant index is the remainder
     * mod 28 — 0 means no final consonant, 8 is ㄹ.
     */
    private static String roParticle(String word) {
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "으로";
        }
        int jong = (last - 0xAC00) % 28;
        return jong == 0 || jong == 8 ? "로" : "으로";
    }

    private static String label(ExitReason r) {
        return switch (r) {
            case STOP_LOSS -> "손절";
            case TAKE_PROFIT -> "익절";
            case SIGNAL -> "청산 시그널";
            case TIME -> "시간청산";
            case DAY_END -> "당일청산";
            case END_OF_DATA -> "데이터 종료";
        };
    }

    private static String won(double v) {
        return String.format("%,d원", Math.round(v));
    }
}
