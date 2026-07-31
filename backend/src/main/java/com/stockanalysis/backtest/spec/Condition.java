package com.stockanalysis.backtest.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stockanalysis.backtest.Bar;
import com.stockanalysis.backtest.Operator;

/** A single comparison between two operands. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Condition {

    private Operand left;
    private Operator op;
    private Operand right;

    public Condition() {
    }

    public Condition(Operand left, Operator op, Operand right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }

    /**
     * Evaluate against the current bar. CROSS operators additionally consult the previous bar;
     * when no previous bar exists (start of series) a cross cannot be detected and returns false.
     * Any comparison involving NaN is false.
     */
    public boolean matches(Bar cur, Bar prev, Bar curFut, Bar prevFut) {
        if (left == null || right == null || op == null) {
            return false;
        }
        double l = left.resolve(cur, curFut);
        double r = right.resolve(cur, curFut);
        return switch (op) {
            case GT -> l > r;
            case GTE -> l >= r;
            case LT -> l < r;
            case LTE -> l <= r;
            case EQ -> l == r;
            case CROSS_ABOVE -> {
                if (prev == null) {
                    yield false;
                }
                double lp = left.resolve(prev, prevFut);
                double rp = right.resolve(prev, prevFut);
                yield lp <= rp && l > r;
            }
            case CROSS_BELOW -> {
                if (prev == null) {
                    yield false;
                }
                double lp = left.resolve(prev, prevFut);
                double rp = right.resolve(prev, prevFut);
                yield lp >= rp && l < r;
            }
        };
    }

    public Operand getLeft() {
        return left;
    }

    public void setLeft(Operand left) {
        this.left = left;
    }

    public Operator getOp() {
        return op;
    }

    public void setOp(Operator op) {
        this.op = op;
    }

    public Operand getRight() {
        return right;
    }

    public void setRight(Operand right) {
        this.right = right;
    }
}
