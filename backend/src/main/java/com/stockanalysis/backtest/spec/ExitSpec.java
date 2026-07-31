package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Exit rules. Take-profit / stop-loss are percentages from entry price; {@code maxHoldBars}
 * forces a time exit after N bars; {@code closeAtDayEnd} force-closes at the last bar of the
 * trading day (intraday scalping default). Signal conditions may also trigger an exit.
 *
 * <p>{@code bands} optionally overrides take-profit / stop-loss per time of day. The band is
 * resolved against the <em>current</em> bar on every held bar, not against the entry bar, so a
 * position opened at 09:20 switches to the 10:00 band's levels once the clock passes 10:00.
 * First matching band wins; a band that doesn't set a percentage falls back to the base value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExitSpec {

    private Double takeProfitPct;
    private Double stopLossPct;
    private Integer maxHoldBars;
    private boolean closeAtDayEnd = true;
    private Logic logic = Logic.OR;
    private List<Condition> conditions = new ArrayList<>();
    private List<TimeBand> bands = new ArrayList<>();

    public ConditionGroup asGroup() {
        return new ConditionGroup(logic, conditions);
    }

    /** Take-profit % in effect at {@code t}: first matching band's value, else the base value. */
    public Double takeProfitPctAt(LocalTime t) {
        TimeBand b = bandAt(t);
        return (b != null && b.getTakeProfitPct() != null) ? b.getTakeProfitPct() : takeProfitPct;
    }

    /** Stop-loss % in effect at {@code t}: first matching band's value, else the base value. */
    public Double stopLossPctAt(LocalTime t) {
        TimeBand b = bandAt(t);
        return (b != null && b.getStopLossPct() != null) ? b.getStopLossPct() : stopLossPct;
    }

    /** Bands are matched in list order, so an earlier entry wins an overlap. */
    public TimeBand bandAt(LocalTime t) {
        if (bands == null || bands.isEmpty() || t == null) {
            return null;
        }
        for (TimeBand b : bands) {
            if (b != null && b.covers(t)) {
                return b;
            }
        }
        return null;
    }

    public Double getTakeProfitPct() {
        return takeProfitPct;
    }

    public void setTakeProfitPct(Double takeProfitPct) {
        this.takeProfitPct = takeProfitPct;
    }

    public Double getStopLossPct() {
        return stopLossPct;
    }

    public void setStopLossPct(Double stopLossPct) {
        this.stopLossPct = stopLossPct;
    }

    public Integer getMaxHoldBars() {
        return maxHoldBars;
    }

    public void setMaxHoldBars(Integer maxHoldBars) {
        this.maxHoldBars = maxHoldBars;
    }

    public boolean isCloseAtDayEnd() {
        return closeAtDayEnd;
    }

    public void setCloseAtDayEnd(boolean closeAtDayEnd) {
        this.closeAtDayEnd = closeAtDayEnd;
    }

    public Logic getLogic() {
        return logic;
    }

    public void setLogic(Logic logic) {
        this.logic = logic == null ? Logic.OR : logic;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public List<TimeBand> getBands() {
        return bands;
    }

    public void setBands(List<TimeBand> bands) {
        this.bands = bands == null ? new ArrayList<>() : bands;
    }
}
