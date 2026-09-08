package com.stockanalysis.backtest

import com.stockanalysis.backtest.spec.CapitalMode
import com.stockanalysis.backtest.spec.CapitalSpec
import com.stockanalysis.backtest.spec.StrategySpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 프레임워크에 의존하지 않는 단타 백테스트 엔진.
 *
 * **단일 모드**: 롱 포지션 하나, 시그널이 뜬 봉의 **종가**에 진입합니다(미래를 보지 않습니다).
 * 청산 판정은 [ExitEvaluator]에 맡깁니다.
 *
 * **ETF 장전 모드**([runEtfPremarket]): 매일 장전 선물 변화율이 레버리지/인버스를 고르고
 * ([PremarketDecider]), 고른 ETF를 장 시작에 사서 같은 청산 규칙으로 정리합니다.
 *
 * 진입·청산은 **가격만으로** 정한 뒤, 금액은 [applyCapital]이 별도 패스에서 입힙니다. 그래서
 * 포지션 사이징이나 수수료가 *어떤 거래가 일어나는지*를 바꾸지 않습니다.
 *
 * 판단 두 가지([ExitEvaluator], [PremarketDecider])를 이 클래스가 직접 들고 있지 않은 이유는
 * 실투자가 같은 `StrategySpec`으로 같은 답을 내야 하기 때문입니다.
 */
class BacktestEngine {

    // ------------------------------------------------------------------ 단일 모드

    /** 수수료 없는 편의 오버로드 — 가격만 보는 기준선으로 도구와 테스트가 씁니다. */
    fun run(spec: StrategySpec, series: BarSeries): BacktestResult =
        run(spec, series, FeeSchedule.free())

    fun run(spec: StrategySpec, series: BarSeries, fees: FeeSchedule): BacktestResult {
        val bars = series.bars()
        val n = bars.size
        val trades = mutableListOf<TradeRecord>()

        val entry = spec.entry
        val exitSpec = spec.exit
        val exitGroup = exitSpec.asGroup()

        var holding = false
        var entryIndex = -1
        var entryPrice = 0.0
        var entryTs: LocalDateTime? = null

        for (i in 0 until n) {
            val cur = bars[i]
            val prev = if (i > 0) bars[i - 1] else null
            val curFut = if (series.hasFutures()) series.futuresAt(cur.ts) else null
            val prevFut = if (prev != null && series.hasFutures()) series.futuresAt(prev.ts) else null

            if (!holding) {
                if (entry.matches(cur, prev, curFut, prevFut)) {
                    holding = true
                    entryIndex = i
                    entryPrice = cur.close
                    entryTs = cur.ts
                }
                continue
            }

            val lastOverall = i == n - 1
            val lastBarOfDay = lastOverall || cur.date() != bars[i + 1].date()
            val d = ExitEvaluator.decide(
                cur, prev, lastOverall, lastBarOfDay, i - entryIndex,
                exitSpec, exitGroup, entryPrice, curFut, prevFut
            )
            if (d != null) {
                trades.add(tradeOf(entryTs!!, cur.ts, entryPrice, d, null))
                holding = false
            }
        }

        return aggregate(trades, spec, fees)
    }

    // --------------------------------------------------------------- ETF 장전 모드

    /** 수수료 없는 편의 오버로드. */
    fun runEtfPremarket(
        spec: StrategySpec,
        leverage: BarSeries,
        inverse: BarSeries,
        futures: BarSeries
    ): BacktestResult = runEtfPremarket(spec, leverage, inverse, futures, FeeSchedule.free())

    fun runEtfPremarket(
        spec: StrategySpec,
        leverage: BarSeries,
        inverse: BarSeries,
        futures: BarSeries,
        fees: FeeSchedule
    ): BacktestResult {
        val pm = spec.premarket
        val end = LocalTime.parse(pm.endTime)

        val exitSpec = spec.exit
        val exitGroup = exitSpec.asGroup()

        val futByDay = groupByDate(futures.bars())
        val levByDay = groupByDate(leverage.bars())
        val invByDay = groupByDate(inverse.bars())

        val trades = mutableListOf<TradeRecord>()

        for ((day, dayFutures) in futByDay) {
            val pick = PremarketDecider.decide(PremarketDecider.fromBars(dayFutures), pm)
            if (!pick.shouldTrade()) {
                continue // 장전 추세가 뚜렷하지 않은 날은 건너뜁니다
            }
            val up = pick.side == PremarketDecider.Side.LEVERAGE
            val instrument = pick.instrument()
            val dayBars = (if (up) levByDay else invByDay)[day]
            if (dayBars.isNullOrEmpty()) {
                continue
            }
            simulateDay(dayBars, end, exitSpec, exitGroup, instrument)?.let { trades.add(it) }
        }

        return aggregate(trades, spec, fees)
    }

    /** 장전 구간이 끝난 뒤 첫 봉에 고른 ETF를 사고, 그날 안에 정리합니다. */
    private fun simulateDay(
        dayBars: List<Bar>,
        openAtOrAfter: LocalTime,
        exitSpec: com.stockanalysis.backtest.spec.ExitSpec,
        exitGroup: com.stockanalysis.backtest.spec.ConditionGroup,
        instrument: String?
    ): TradeRecord? {
        val n = dayBars.size
        val entryIndex = dayBars.indexOfFirst { !it.ts.toLocalTime().isBefore(openAtOrAfter) }
        if (entryIndex < 0 || entryIndex >= n - 1) {
            return null // 진입할 봉이 없거나, 청산할 봉이 남지 않았습니다
        }
        val entryBar = dayBars[entryIndex]
        val entryPrice = entryBar.close

        for (j in entryIndex + 1 until n) {
            val cur = dayBars[j]
            val prev = dayBars[j - 1]
            val lastBarOfDay = j == n - 1
            // 장전 모드는 항상 당일 청산이므로 마지막 봉에서 장마감을 강제합니다.
            var d = ExitEvaluator.decide(
                cur, prev, false, lastBarOfDay, j - entryIndex,
                exitSpec, exitGroup, entryPrice, null, null
            )
            if (d == null && lastBarOfDay) {
                d = ExitEvaluator.Decision(cur.close, ExitReason.DAY_END)
            }
            if (d != null) {
                return tradeOf(entryBar.ts, cur.ts, entryPrice, d, instrument)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 집계

    private fun aggregate(
        priceTrades: List<TradeRecord>,
        spec: StrategySpec,
        fees: FeeSchedule
    ): BacktestResult {
        val cap = spec.capital
        val trades = applyCapital(priceTrades, cap, fees)

        var wins = 0
        var sumRet = 0.0
        var sumWin = 0.0
        var sumLoss = 0.0
        var lossCount = 0
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        var consecLosses = 0
        var maxConsecLosses = 0

        val curve = mutableListOf<BacktestResult.EquityPoint>()
        for (t in trades) {
            sumRet += t.returnPct
            if (t.success) {
                wins++
                sumWin += t.returnPct
                consecLosses = 0
            } else {
                lossCount++
                sumLoss += t.returnPct
                consecLosses++
                maxConsecLosses = max(maxConsecLosses, consecLosses)
            }
            equity *= (1.0 + t.returnPct / 100.0)
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak * 100.0)
            curve.add(BacktestResult.EquityPoint(t.exitTs, equity))
        }

        val total = trades.size
        val losses = total - wins
        val summary = BacktestResult.Summary(
            totalTrades = total,
            wins = wins,
            losses = losses,
            winRate = if (total == 0) 0.0 else wins.toDouble() / total * 100.0,
            lossRate = if (total == 0) 0.0 else losses.toDouble() / total * 100.0,
            totalReturnPct = sumRet,
            compoundedReturnPct = (equity - 1.0) * 100.0,
            avgWinPct = if (wins == 0) 0.0 else sumWin / wins,
            avgLossPct = if (lossCount == 0) 0.0 else sumLoss / lossCount,
            maxDrawdownPct = maxDrawdown,
            maxConsecutiveLosses = maxConsecLosses
        )

        val money = moneyOf(trades, cap, fees)
        val failure = buildFailureAnalysis(trades)
        val diagnosis = FailureDiagnostician.diagnose(
            summary, money, failure, trades, spec.exit, cap, spec.usesPremarket()
        )

        return BacktestResult(summary, money, diagnosis, trades, curve, failure)
    }

    /**
     * 모든 거래를 원화로 환산합니다.
     *
     * 한국 주식·ETF는 소수점 주식이 없으므로 예산을 **1주 단위로 내림**하고, 남는 현금은
     * 투자되지 않습니다. 복리 모드에서는 각 거래의 순손익이 다음 진입 예산으로 굴러갑니다.
     */
    private fun applyCapital(
        trades: List<TradeRecord>,
        cap: CapitalSpec,
        fees: FeeSchedule
    ): List<TradeRecord> {
        var balance = cap.amount
        val out = ArrayList<TradeRecord>(trades.size)
        for (t in trades) {
            val feeRate = max(0.0, fees.rateFor(t.instrument)) / 100.0
            val budget = if (cap.mode == CapitalMode.COMPOUND) balance else cap.amount
            val qty = if (t.entryPrice > 0 && budget > 0) floor(budget / t.entryPrice).toLong() else 0L
            val buyValue = qty * t.entryPrice
            val sellValue = qty * t.exitPrice
            val feeCost = (buyValue + sellValue) * feeRate
            val profit = sellValue - buyValue - feeCost
            if (cap.mode == CapitalMode.COMPOUND) {
                balance += profit
            }
            out.add(t.withMoney(qty, profit, feeCost))
        }
        return out
    }

    private fun moneyOf(
        trades: List<TradeRecord>,
        cap: CapitalSpec,
        fees: FeeSchedule
    ): BacktestResult.MoneySummary {
        var totalProfit = 0.0
        var totalFees = 0.0
        var best = 0.0
        var worst = 0.0
        var unaffordable = 0
        for (t in trades) {
            totalProfit += t.profitAmount
            totalFees += t.feeAmount
            best = max(best, t.profitAmount)
            worst = min(worst, t.profitAmount)
            if (t.quantity == 0L) {
                unaffordable++
            }
        }
        val invest = cap.amount
        return BacktestResult.MoneySummary(
            mode = cap.mode.name,
            investAmount = invest,
            feeRatesPct = fees.ratesFor(trades),
            totalProfitAmount = totalProfit,
            totalFeeAmount = totalFees,
            avgProfitPerTrade = if (trades.isEmpty()) 0.0 else totalProfit / trades.size,
            finalBalance = invest + totalProfit,
            returnOnCapitalPct = if (invest == 0.0) 0.0 else totalProfit / invest * 100.0,
            bestTradeAmount = best,
            worstTradeAmount = worst,
            unaffordableTrades = unaffordable
        )
    }

    /** 손실 거래를 청산 사유별·진입 시각별로 묶고, 최악의 거래 10건을 뽑습니다. */
    private fun buildFailureAnalysis(trades: List<TradeRecord>): BacktestResult.FailureAnalysis {
        val losing = trades.filter { !it.success }

        val byReason = losing.groupBy { it.exitReason }
            .mapValues { (_, group) ->
                val sum = group.sumOf { it.returnPct }
                BacktestResult.ReasonStat(group.size, sum / group.size, sum)
            }
            .toSortedMap(compareBy { it.ordinal })

        val byHour = losing.groupBy { it.entryHour() }
            .mapValues { (_, group) ->
                BacktestResult.HourStat(group.size, group.sumOf { it.returnPct } / group.size)
            }
            .toSortedMap()

        val worst = losing.sortedBy { it.returnPct }.take(10)
        return BacktestResult.FailureAnalysis(byReason, byHour, worst)
    }

    private companion object {

        fun tradeOf(
            entryTs: LocalDateTime,
            exitTs: LocalDateTime,
            entryPrice: Double,
            d: ExitEvaluator.Decision,
            instrument: String?
        ): TradeRecord {
            val ret = (d.price - entryPrice) / entryPrice * 100.0
            return TradeRecord(entryTs, exitTs, entryPrice, d.price, ret, d.reason, ret > 0, instrument)
        }

        /** 삽입 순서(=시간순)를 유지해야 하므로 LinkedHashMap 기반으로 묶습니다. */
        fun groupByDate(bars: List<Bar>): Map<LocalDate, List<Bar>> =
            bars.groupByTo(LinkedHashMap()) { it.date() }
    }
}
