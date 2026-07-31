package com.stockanalysis.backtest;

import com.stockanalysis.backtest.spec.Condition;
import com.stockanalysis.backtest.spec.ConditionGroup;
import com.stockanalysis.backtest.spec.ExitSpec;
import com.stockanalysis.backtest.spec.Logic;
import com.stockanalysis.backtest.spec.Operand;
import com.stockanalysis.backtest.spec.StrategySpec;

import java.util.List;

/**
 * Built-in example strategies. Used by tests, the CLI runner, and (later) the LLM-recommendation
 * stub so users have something to backtest immediately.
 */
public final class SampleStrategies {

    private SampleStrategies() {
    }

    /** Golden cross: buy when MA5 crosses above MA20 (with volume-free variant), 1.5% TP / 1.0% SL, exit on death cross. */
    public static StrategySpec goldenCross() {
        StrategySpec s = new StrategySpec();
        s.setName("골든크로스 단타");
        s.setSource("USER");
        s.setEntry(new ConditionGroup(Logic.AND, List.of(
                new Condition(Operand.of(Indicator.MA5), Operator.CROSS_ABOVE, Operand.of(Indicator.MA20))
        )));
        ExitSpec exit = new ExitSpec();
        exit.setTakeProfitPct(1.5);
        exit.setStopLossPct(1.0);
        exit.setCloseAtDayEnd(true);
        exit.setLogic(Logic.OR);
        exit.setConditions(List.of(
                new Condition(Operand.of(Indicator.MA5), Operator.CROSS_BELOW, Operand.of(Indicator.MA20))
        ));
        s.setExit(exit);
        return s;
    }

    /** Volume breakout: buy when close &gt; MA20 and volume &gt; its 20-avg; tight 0.8% TP / 0.6% SL. */
    public static StrategySpec volumeBreakout() {
        StrategySpec s = new StrategySpec();
        s.setName("거래량 돌파 단타");
        s.setSource("USER");
        s.setEntry(new ConditionGroup(Logic.AND, List.of(
                new Condition(Operand.of(Indicator.CLOSE), Operator.GT, Operand.of(Indicator.MA20)),
                new Condition(Operand.of(Indicator.VOLUME), Operator.GT, Operand.of(Indicator.VOL_MA20))
        )));
        ExitSpec exit = new ExitSpec();
        exit.setTakeProfitPct(0.8);
        exit.setStopLossPct(0.6);
        exit.setMaxHoldBars(10);
        exit.setCloseAtDayEnd(true);
        s.setExit(exit);
        return s;
    }
}
