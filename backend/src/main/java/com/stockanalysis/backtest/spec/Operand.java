package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.Indicator;

/**
 * One side of a comparison: either an indicator read from a series, or a constant.
 * JSON forms: {@code {"indicator":"MA5"}}, {@code {"indicator":"CLOSE","source":"FUTURES"}},
 * or {@code {"const":1000}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Operand {

    private Indicator indicator;
    private Source source = Source.PRIMARY;

    @JsonProperty("const")
    private Double constant;

    public Operand() {
    }

    public static Operand of(Indicator indicator) {
        Operand o = new Operand();
        o.indicator = indicator;
        return o;
    }

    public static Operand of(Indicator indicator, Source source) {
        Operand o = of(indicator);
        o.source = source;
        return o;
    }

    public static Operand constant(double value) {
        Operand o = new Operand();
        o.constant = value;
        return o;
    }

    /** Resolve this operand's numeric value against the given bars. */
    public double resolve(Bar primary, Bar futures) {
        if (constant != null) {
            return constant;
        }
        if (indicator == null) {
            return Double.NaN;
        }
        Bar bar = (source == Source.FUTURES) ? futures : primary;
        return bar == null ? Double.NaN : bar.value(indicator);
    }

    public Indicator getIndicator() {
        return indicator;
    }

    public void setIndicator(Indicator indicator) {
        this.indicator = indicator;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source == null ? Source.PRIMARY : source;
    }

    public Double getConst() {
        return constant;
    }

    public void setConst(Double constant) {
        this.constant = constant;
    }
}
