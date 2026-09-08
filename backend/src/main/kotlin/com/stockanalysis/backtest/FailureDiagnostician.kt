package com.stockanalysis.backtest

import com.stockanalysis.backtest.BacktestResult.Diagnosis
import com.stockanalysis.backtest.BacktestResult.Finding
import com.stockanalysis.backtest.BacktestResult.Severity
import com.stockanalysis.backtest.spec.CapitalMode
import com.stockanalysis.backtest.spec.CapitalSpec
import com.stockanalysis.backtest.spec.ExitSpec
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 끝난 백테스트를 짧은 한국어 진단으로 바꿉니다: **가장 큰 문제가 무엇이고 뭘 바꿔야 하는지**.
 *
 * 모델을 부르지 않고 결정론적 규칙만 씁니다 — 그래서 모든 실행이 즉시, 항상 진단을 받습니다.
 *
 * 각 규칙은 한 문장 쓸 만큼 근거가 쌓였을 때만 발동하고, 카드가 읽을 만하도록 개수를 자릅니다.
 * 임계값은 일부러 뭉툭합니다. 이건 판결문이 아니라 **"여기부터 보세요"라는 손가락**입니다.
 */
internal object FailureDiagnostician {

    /** '손실의 몇 %'라는 주장이 의미를 가지려면 최소한 이만큼의 손실 거래는 있어야 합니다. */
    private const val MIN_LOSSES_FOR_SHARE = 10
    private const val MAX_FINDINGS = 5

    @JvmStatic
    fun diagnose(
        s: BacktestResult.Summary,
        money: BacktestResult.MoneySummary?,
        fa: BacktestResult.FailureAnalysis,
        trades: List<TradeRecord>,
        exit: ExitSpec,
        capital: CapitalSpec?,
        premarket: Boolean
    ): Diagnosis {
        if (s.totalTrades == 0) {
            return Diagnosis(
                "거래가 한 건도 발생하지 않았습니다.",
                listOf(
                    Finding(
                        Severity.HIGH,
                        if (premarket) "장전 추세 조건을 만족한 날이 없음" else "진입 조건이 한 번도 충족되지 않음",
                        if (premarket) "선택한 기간 동안 장전 선물 변화율이 기준치를 넘은 날이 0일이었습니다."
                        else "선택한 기간 동안 매수 신호가 0건이었습니다.",
                        if (premarket) "추세 판단 기준(%)을 낮추거나 장전 시간대(08:45~09:00)를 넓혀보세요."
                        else "조건을 느슨하게 바꾸거나(예: 지표 비교를 CROSS 대신 GT로), 백테스트 기간을 넓혀보세요."
                    )
                )
            )
        }

        val findings = mutableListOf<Finding>()
        feeDrag(findings, money)
        payoffVsWinRate(findings, s)
        dominantExitReason(findings, s, fa, exit, premarket)
        if (!premarket) {
            // 장전 모드는 설계상 09:00 직후에 무조건 진입합니다. 그러니 "이 시간대를 피하세요"는
            // 사용자가 손댈 수 있는 조언이 아닙니다 — 진입 시각이 곧 전략이지 조정 항목이 아닙니다.
            worstHour(findings, s, fa)
        }
        overtrading(findings, trades, money)
        drawdownAndStreak(findings, s)
        unaffordable(findings, money, capital)

        findings.sortBy { it.severity.ordinal }
        val top = findings.subList(0, min(MAX_FINDINGS, findings.size)).toList()
        return Diagnosis(headline(s, money, top), top)
    }

    // ------------------------------------------------------------------ 규칙들

    /** 단타는 매매가 잦아서, 수수료만으로도 이기던 전략이 지는 전략으로 뒤집힙니다. */
    private fun feeDrag(out: MutableList<Finding>, m: BacktestResult.MoneySummary?) {
        if (m == null || m.totalFeeAmount <= 0) {
            return
        }
        val gross = m.totalProfitAmount + m.totalFeeAmount
        if (gross > 0 && m.totalProfitAmount < 0) {
            out.add(
                Finding(
                    Severity.HIGH, "수수료가 수익을 전부 삼켰습니다",
                    "수수료 제외 전에는 ${won(gross)} 이익이었는데, 수수료 ${won(m.totalFeeAmount)}을 빼면 " +
                        "${won(-m.totalProfitAmount)} 손실입니다.",
                    "매매 횟수를 줄이세요 — 익절/손절 폭을 키우거나 진입 조건을 더 좁혀 거래 건수를 낮추는 방향입니다."
                )
            )
        } else if (gross != 0.0 && m.totalFeeAmount > abs(gross) * 0.5) {
            out.add(
                Finding(
                    Severity.MEDIUM, "수수료 부담이 큽니다",
                    "수수료 ${won(m.totalFeeAmount)}이 손익 규모(${won(abs(gross))})의 절반을 넘습니다.",
                    "거래 횟수를 줄이거나, 수수료가 낮은 계좌 조건을 수수료율에 반영해 다시 확인해보세요."
                )
            )
        }
    }

    /**
     * 구조적인 검사: 손익비가 R이면 **본전에 필요한 승률은 1/(1+R)** 입니다.
     *
     * "승률이 낮다"는 말은 이 숫자와 견줄 때만 의미가 있습니다 — 손익비가 3:1이면 승률 30%도
     * 충분하고, 1:2면 승률 60%도 모자랍니다.
     */
    private fun payoffVsWinRate(out: MutableList<Finding>, s: BacktestResult.Summary) {
        val avgLoss = abs(s.avgLossPct)
        if (s.avgWinPct <= 0 || avgLoss <= 0) {
            return
        }
        val payoff = s.avgWinPct / avgLoss
        val required = 100.0 / (1.0 + payoff)
        val gap = required - s.winRate
        if (gap <= 0) {
            return
        }
        out.add(
            Finding(
                if (gap >= 10) Severity.HIGH else Severity.MEDIUM,
                "손익비 대비 승률이 부족합니다",
                "이길 때 %.2f%%, 질 때 %.2f%%(손익비 %.2f:1)이므로 본전에 필요한 승률은 %.1f%%인데 실제는 %.2f%%입니다."
                    .format(s.avgWinPct, avgLoss, payoff, required, s.winRate),
                if (payoff < 1.0)
                    "익절 폭을 손절 폭보다 크게 잡아 손익비를 1 이상으로 올리거나, 진입 조건을 좁혀 승률을 높이세요."
                else
                    "승률을 높이는 쪽이 빠릅니다 — 진입 조건을 추가해 신호의 질을 올려보세요."
            )
        )
    }

    /** 어떤 청산이 손실을 만들고 있는지. 사유마다 손댈 곳이 다릅니다. */
    private fun dominantExitReason(
        out: MutableList<Finding>,
        s: BacktestResult.Summary,
        fa: BacktestResult.FailureAnalysis,
        exit: ExitSpec,
        premarket: Boolean
    ) {
        if (s.losses < MIN_LOSSES_FOR_SHARE) {
            return
        }
        val top = fa.byExitReason.maxByOrNull { it.value.count } ?: return
        val topCount = top.value.count
        val share = topCount.toDouble() / s.losses * 100.0
        if (share < 40.0) {
            return
        }
        val reason = top.key
        val lbl = label(reason)
        val where = "손실 %,d건 중 %,d건(%.0f%%)이 '%s'%s 끝났습니다."
            .format(s.losses, topCount, share, lbl, roParticle(lbl))

        val fix = when (reason) {
            ExitReason.STOP_LOSS -> exit.stopLossPct?.let { pct ->
                "손절 %.2f%%가 정상적인 흔들림에 걸리는지 확인하세요. ".format(pct) +
                    if (premarket) "손절 폭을 넓히거나, 추세 판단 기준(%)을 높여 확신이 큰 날만 진입해보세요."
                    else "손절 폭을 넓히거나 진입 시점을 늦춰보세요."
            } ?: "손절 규칙을 확인해보세요."

            ExitReason.SIGNAL ->
                "청산 시그널이 너무 이르게 발동합니다. 청산 조건을 완화하거나 익절 폭에 맡겨보세요."

            ExitReason.DAY_END -> if (premarket)
                "장전 추세가 하루 방향을 제대로 예측하지 못한 날이 많습니다. 추세 판단 기준(%)을 높여 확신이 큰 날만 진입해보세요."
            else
                "방향은 맞아도 당일 안에 오르지 못하고 끝납니다. 보유 시간을 늘릴 수 없으니 진입을 더 이른 시각으로 옮기거나 조건을 좁히세요."

            ExitReason.TIME -> exit.maxHoldBars?.let { bars ->
                "최대 보유 %d봉이 짧습니다. 봉수를 늘려 추세가 나올 시간을 주세요.".format(bars)
            } ?: "최대 보유 봉수 설정을 확인해보세요."

            ExitReason.TAKE_PROFIT ->
                "익절로 끝났는데 손실인 건 이례적입니다 — 익절 폭과 수수료율을 함께 확인하세요."

            ExitReason.END_OF_DATA ->
                "데이터 끝에서 강제 청산된 건이 많습니다. 기간을 넓히거나 당일청산을 켜보세요."
        }
        out.add(Finding(Severity.HIGH, "손실이 '$lbl'에 몰려 있습니다", where, fix))
    }

    /** 장중 전략은 시간대를 타는 경우가 많아, 한 시간대가 손실을 독차지하면 단서가 됩니다. */
    private fun worstHour(
        out: MutableList<Finding>,
        s: BacktestResult.Summary,
        fa: BacktestResult.FailureAnalysis
    ) {
        if (s.losses < MIN_LOSSES_FOR_SHARE || fa.byHour.isEmpty()) {
            return
        }
        val worst = fa.byHour.maxByOrNull { it.value.lossCount } ?: return
        val share = worst.value.lossCount.toDouble() / s.losses * 100.0
        // 시간대가 하나뿐이면 "그 시간대가 손실의 100%"는 자명하게 참이라 아무 정보가 없습니다.
        if (share < 35.0 || fa.byHour.size < 2) {
            return
        }
        val hour = worst.key
        out.add(
            Finding(
                Severity.MEDIUM, "${hour}시대 진입이 손실의 대부분입니다",
                "%d시대에 진입한 거래에서 손실 %d건(전체 손실의 %.0f%%), 평균 %.2f%%가 발생했습니다."
                    .format(hour, worst.value.lossCount, share, worst.value.avgLossPct),
                "%d시대를 피해 진입하는 조건을 넣고 비교해보세요. 시가 직후 변동성 구간이면 특히 효과가 큽니다."
                    .format(hour)
            )
        )
    }

    /** 거래일당 매매 횟수 — 단타에서 수수료 부담을 가장 잘 예측하는 지표입니다. */
    private fun overtrading(
        out: MutableList<Finding>,
        trades: List<TradeRecord>,
        m: BacktestResult.MoneySummary?
    ) {
        val days: Set<LocalDate> = trades.mapTo(HashSet()) { it.entryTs.toLocalDate() }
        if (days.isEmpty()) {
            return
        }
        val perDay = trades.size.toDouble() / days.size
        if (perDay < 5.0) {
            return
        }
        val feeNote = if (m == null || m.totalFeeAmount <= 0) ""
        else " 누적 수수료는 ${won(m.totalFeeAmount)}입니다."
        out.add(
            Finding(
                Severity.MEDIUM, "과매매 구간입니다",
                "거래일 %d일 동안 %,d건, 하루 평균 %.1f회 매매했습니다.%s"
                    .format(days.size, trades.size, perDay, feeNote),
                "신호가 너무 자주 나옵니다. 조건을 AND로 더 묶거나 최대 보유 봉수를 늘려 하루 매매 횟수를 낮춰보세요."
            )
        )
    }

    private fun drawdownAndStreak(out: MutableList<Finding>, s: BacktestResult.Summary) {
        if (s.maxDrawdownPct >= 30.0) {
            out.add(
                Finding(
                    Severity.MEDIUM, "자본 낙폭이 깊습니다",
                    "최대 낙폭(MDD)이 %.2f%%입니다.".format(s.maxDrawdownPct),
                    "실제로는 이 낙폭을 버텨야 합니다. 손절을 조이거나 1회 투자금을 줄여 감당 가능한 수준으로 맞추세요."
                )
            )
        }
        if (s.maxConsecutiveLosses >= 8) {
            out.add(
                Finding(
                    Severity.INFO, "연속 손실이 깁니다",
                    "최대 %d회 연속 손실이 있었습니다.".format(s.maxConsecutiveLosses),
                    "복리 모드라면 이 구간에서 매수 규모가 크게 줄어듭니다. 고정 금액 모드와도 비교해보세요."
                )
            )
        }
    }

    private fun unaffordable(
        out: MutableList<Finding>,
        m: BacktestResult.MoneySummary?,
        cap: CapitalSpec?
    ) {
        if (m == null || m.unaffordableTrades == 0) {
            return
        }
        out.add(
            Finding(
                Severity.HIGH, "투자금이 부족해 매수하지 못한 거래가 있습니다",
                "%,d건은 1주 값보다 투자금이 적어 0주로 계산됐습니다.".format(m.unaffordableTrades),
                if (cap != null && cap.mode == CapitalMode.COMPOUND)
                    "복리 모드에서 잔고가 1주 값 아래로 줄어든 구간입니다. 시작 자금을 올리거나 고정 금액 모드로 확인하세요."
                else
                    "1회 매수 금액을 종목 주가보다 충분히 크게 올려주세요."
            )
        )
    }

    // ------------------------------------------------------------------ 보조

    private fun headline(
        s: BacktestResult.Summary,
        m: BacktestResult.MoneySummary?,
        top: List<Finding>
    ): String {
        val money = if (m == null) "" else {
            val amount = if (m.totalProfitAmount >= 0) "${won(m.totalProfitAmount)} 이익"
            else "${won(-m.totalProfitAmount)} 손실"
            " 수익금은 ${amount}입니다."
        }
        val base = "거래 %,d건, 승률 %.2f%%.%s".format(s.totalTrades, s.winRate, money)
        return if (top.isEmpty()) "$base 뚜렷한 문제 패턴은 찾지 못했습니다."
        else "$base 가장 큰 문제는 \"${top[0].title}\"입니다."
    }

    /**
     * 단어 뒤에 붙는 조사: 모음이나 받침 ㄹ 뒤에는 '로', 그 외에는 '으로'.
     *
     * 한글 음절은 U+AC00부터 연속이고 종성 인덱스는 28로 나눈 나머지입니다 — 0이면 받침 없음,
     * 8이면 ㄹ입니다.
     */
    private fun roParticle(word: String): String {
        val last = word.last()
        if (last.code < 0xAC00 || last.code > 0xD7A3) {
            return "으로"
        }
        val jong = (last.code - 0xAC00) % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    private fun label(r: ExitReason): String = when (r) {
        ExitReason.STOP_LOSS -> "손절"
        ExitReason.TAKE_PROFIT -> "익절"
        ExitReason.SIGNAL -> "청산 시그널"
        ExitReason.TIME -> "시간청산"
        ExitReason.DAY_END -> "당일청산"
        ExitReason.END_OF_DATA -> "데이터 종료"
    }

    private fun won(v: Double): String = "%,d원".format(v.roundToLong())
}
