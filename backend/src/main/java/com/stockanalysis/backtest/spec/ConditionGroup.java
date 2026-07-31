package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stockanalysis.backtest.Bar;

import java.util.ArrayList;
import java.util.List;

/** A set of conditions combined with AND/OR. Empty groups never match. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConditionGroup {

    private Logic logic = Logic.AND;
    private List<Condition> conditions = new ArrayList<>();

    public ConditionGroup() {
    }

    public ConditionGroup(Logic logic, List<Condition> conditions) {
        this.logic = logic;
        this.conditions = conditions;
    }

    public boolean matches(Bar cur, Bar prev, Bar curFut, Bar prevFut) {
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }
        if (logic == Logic.OR) {
            for (Condition c : conditions) {
                if (c.matches(cur, prev, curFut, prevFut)) {
                    return true;
                }
            }
            return false;
        }
        for (Condition c : conditions) {
            if (!c.matches(cur, prev, curFut, prevFut)) {
                return false;
            }
        }
        return true;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return conditions == null || conditions.isEmpty();
    }

    public Logic getLogic() {
        return logic;
    }

    public void setLogic(Logic logic) {
        this.logic = logic == null ? Logic.AND : logic;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }
}
