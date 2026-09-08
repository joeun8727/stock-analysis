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
 * 해시는 검증받은 전략을 고쳐놓고 그대로 매매하는 것을 막는 장치입니다. 모든 규칙에 민감해야
 * 하고, 그 외의 것에는 눈이 멀어야 합니다.
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
        // 포지션 크기는 어떤 거래가 일어날지를 바꾸지 않지만, 사용자가 본 원화 결과는 바꿉니다 —
        // 그래서 검증된 것에 대한 변경으로 셉니다.
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

    /** 해싱이 스펙을 변경하면 안 됩니다 — 관리 상태의 JPA 필드일 수 있습니다. */
    @Test
    void hashingLeavesTheSpecAlone() {
        StrategySpec spec = premarketSpec();
        SpecHasher.hash(spec);
        assertEquals("장전추세", spec.getName());
    }
}
