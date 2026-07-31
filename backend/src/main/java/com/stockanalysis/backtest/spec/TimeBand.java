package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * A time-of-day override of take-profit / stop-loss, held by {@link ExitSpec}. The window is
 * {@code [startTime, endTime)} in KST wall-clock ("09:00"), matched against the <em>current</em>
 * bar while a position is open — so the levels move as the clock crosses into the next band.
 *
 * <p>A null percentage means "no override for this band": the {@code ExitSpec} base value applies.
 * Times are parsed lazily and cached, since this is consulted once per held bar.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TimeBand {

    private String startTime;
    private String endTime;
    private Double takeProfitPct;
    private Double stopLossPct;

    @JsonIgnore
    private LocalTime startCache;
    @JsonIgnore
    private LocalTime endCache;
    @JsonIgnore
    private boolean parsed;

    public TimeBand() {
    }

    public TimeBand(String startTime, String endTime, Double takeProfitPct, Double stopLossPct) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.takeProfitPct = takeProfitPct;
        this.stopLossPct = stopLossPct;
    }

    /** True when {@code t} falls in [start, end). A malformed or inverted window never matches. */
    public boolean covers(LocalTime t) {
        parse();
        if (startCache == null || endCache == null || !startCache.isBefore(endCache)) {
            return false;
        }
        return !t.isBefore(startCache) && t.isBefore(endCache);
    }

    private void parse() {
        if (parsed) {
            return;
        }
        startCache = parseOrNull(startTime);
        endCache = parseOrNull(endTime);
        parsed = true;
    }

    /** null (not an exception) for anything that isn't an HH:mm time — validation happens on save. */
    public static LocalTime parseOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
        this.parsed = false;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
        this.parsed = false;
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
}
