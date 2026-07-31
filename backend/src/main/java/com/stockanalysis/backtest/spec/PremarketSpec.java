package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Pre-market futures-trend gate for ETF strategies. When {@code enabled}, the engine measures
 * the futures close change over [startTime, endTime) each day; if it rises at least
 * {@code thresholdPct} it buys the leverage ETF at the open, if it falls that much it buys the
 * inverse ETF, otherwise it skips the day.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PremarketSpec {

    private boolean enabled = false;
    private String startTime = "08:45";
    private String endTime = "09:00";
    private double thresholdPct = 0.1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public double getThresholdPct() {
        return thresholdPct;
    }

    public void setThresholdPct(double thresholdPct) {
        this.thresholdPct = thresholdPct;
    }
}
