package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Full strategy rule spec. This is exactly the JSON persisted in {@code strategy.spec_json}
 * and the format the (future) LLM recommender produces.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StrategySpec {

    private String name;
    private String source = "USER";
    private Position position = Position.LONG;
    private TargetType targetType = TargetType.SINGLE;
    private ConditionGroup entry = new ConditionGroup();
    private ExitSpec exit = new ExitSpec();
    private PremarketSpec premarket = new PremarketSpec();
    private CapitalSpec capital = new CapitalSpec();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position == null ? Position.LONG : position;
    }

    public ConditionGroup getEntry() {
        return entry;
    }

    public void setEntry(ConditionGroup entry) {
        this.entry = entry;
    }

    public ExitSpec getExit() {
        return exit;
    }

    public void setExit(ExitSpec exit) {
        this.exit = exit;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(TargetType targetType) {
        this.targetType = targetType == null ? TargetType.SINGLE : targetType;
    }

    public PremarketSpec getPremarket() {
        return premarket;
    }

    public void setPremarket(PremarketSpec premarket) {
        this.premarket = premarket;
    }

    public CapitalSpec getCapital() {
        return capital;
    }

    public void setCapital(CapitalSpec capital) {
        this.capital = capital == null ? new CapitalSpec() : capital;
    }

    /** True when this strategy should run in the ETF pre-market futures-trend mode. */
    public boolean usesPremarket() {
        return targetType == TargetType.ETF && premarket != null && premarket.isEnabled();
    }
}
