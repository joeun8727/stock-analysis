package com.stockanalysis.live;

import com.stockanalysis.backtest.spec.StrategySpec;
import com.stockanalysis.backtest.spec.TargetType;
import com.stockanalysis.backtest.spec.TimeBand;
import com.stockanalysis.live.session.SpecHasher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hash is what stops a verified strategy from being edited and then traded anyway. It has to be
 * sensitive to every rule and blind to everything else.
 */
class SpecHasherTest {

    private static StrategySpec premarketSpec() {
        StrategySpec spec = new StrategySpec();
        spec.setName("장전추세");
        spec.setTargetType(TargetType.ETF);
        spec.getPremarket().setEnabled(true);
        spec.getPremarket().setThresholdPct(0.1);
        spec.getExit().setTakeProfitPct(1.5);
        spec.getExit().setStopLossPct(0.8);
        return spec;
    }

    @Test
    void identicalRulesHashTheSame() {
        assertEquals(SpecHasher.hash(premarketSpec()), SpecHasher.hash(premarketSpec()));
    }

    @Test
    void renamingIsNotARuleChange() {
        StrategySpec renamed = premarketSpec();
        renamed.setName("이름만 바꿈");
        assertEquals(SpecHasher.hash(premarketSpec()), SpecHasher.hash(renamed));
    }

    @Test
    void looseningTheStopLossBreaksVerification() {
        StrategySpec edited = premarketSpec();
        edited.getExit().setStopLossPct(3.0);

        assertNotEquals(SpecHasher.hash(premarketSpec()), SpecHasher.hash(edited));
        assertFalse(SpecHasher.matches(edited, SpecHasher.hash(premarketSpec())));
    }

    @Test
    void changingThePremarketThresholdBreaksVerification() {
        StrategySpec edited = premarketSpec();
        edited.getPremarket().setThresholdPct(0.5);
        assertNotEquals(SpecHasher.hash(premarketSpec()), SpecHasher.hash(edited));
    }

    @Test
    void addingATimeBandBreaksVerification() {
        StrategySpec edited = premarketSpec();
        edited.getExit().setBands(List.of(new TimeBand("10:00", "11:00", 0.5, 0.5)));
        assertNotEquals(SpecHasher.hash(premarketSpec()), SpecHasher.hash(edited));
    }

    @Test
    void changingTheInvestmentAmountBreaksVerification() {
        // Position size doesn't change which trades happen, but it does change the won result the
        // user was shown — so it counts as a change to what was verified.
        StrategySpec edited = premarketSpec();
        edited.getCapital().setAmount(50_000_000);
        assertNotEquals(SpecHasher.hash(premarketSpec()), SpecHasher.hash(edited));
    }

    @Test
    void matchesRejectsAMissingOrBlankHash() {
        assertFalse(SpecHasher.matches(premarketSpec(), null));
        assertFalse(SpecHasher.matches(premarketSpec(), ""));
        assertTrue(SpecHasher.matches(premarketSpec(), SpecHasher.hash(premarketSpec())));
    }

    /** Hashing must not mutate the spec — it can be a managed JPA field. */
    @Test
    void hashingLeavesTheSpecAlone() {
        StrategySpec spec = premarketSpec();
        SpecHasher.hash(spec);
        assertEquals("장전추세", spec.getName());
    }
}
