package fi.monopoly.server.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3.1: unit tests for Curve, Consideration.combine, ProposalLedger, TradeProposal.
 */
class UtilityInfrastructureTest {

    // -------------------------------------------------------------------------
    // Curve
    // -------------------------------------------------------------------------

    @Test
    void linearCurve() {
        Curve c = Curve.linear(1.0, 0.0);
        assertEquals(0.0, c.eval(0.0), 0.001);
        assertEquals(0.5, c.eval(0.5), 0.001);
        assertEquals(1.0, c.eval(1.0), 0.001);
    }

    @Test
    void linearCurveClamps() {
        Curve c = Curve.linear(2.0, 0.0);
        assertEquals(1.0, c.eval(1.0), 0.001, "slope×x > 1 should clamp to 1");
        assertEquals(0.0, c.eval(-1.0), 0.001, "negative output clamps to 0");
    }

    @Test
    void logisticCurveMidpointIs0_5() {
        Curve c = Curve.logistic(0.0, 5.0);
        assertEquals(0.5, c.eval(0.0), 0.001, "logistic at midpoint = 0.5");
        assertTrue(c.eval(1.0) > 0.9, "high input → near 1");
        assertTrue(c.eval(-1.0) < 0.1, "low input → near 0");
    }

    @Test
    void stepCurveVeto() {
        Curve veto = Curve.veto(0.0);
        assertEquals(0.0, veto.eval(-100.0), "below threshold → 0 (veto)");
        assertEquals(1.0, veto.eval(0.0), 0.001, "at threshold → 1");
        assertEquals(1.0, veto.eval(1000.0), 0.001, "above threshold → 1");
    }

    @Test
    void polynomialCurve() {
        Curve c = Curve.polynomial(2.0, 1.0, 0.0);  // x^2
        assertEquals(0.0, c.eval(0.0), 0.001);
        assertEquals(0.25, c.eval(0.5), 0.001);
        assertEquals(1.0, c.eval(1.0), 0.001);
    }

    // -------------------------------------------------------------------------
    // Consideration.combine
    // -------------------------------------------------------------------------

    @Test
    void combineWithZeroVetosToZero() {
        // A consideration that scores 0 should collapse the combined score to 0
        Consideration veto   = new FixedConsideration("veto",   0.0);
        Consideration normal = new FixedConsideration("normal", 0.9);
        double score = Consideration.combine(java.util.List.of(veto, normal),
                dummyContext());
        assertEquals(0.0, score, "veto (0) should collapse product to 0");
    }

    @Test
    void combineWithAllOnesReturnsOne() {
        Consideration a = new FixedConsideration("a", 1.0);
        Consideration b = new FixedConsideration("b", 1.0);
        double score = Consideration.combine(java.util.List.of(a, b), dummyContext());
        assertEquals(1.0, score, 0.001);
    }

    @Test
    void combineCompensationPreventsExcessiveDecay() {
        // Without compensation, 0.9^8 ≈ 0.43; with compensation it should be higher.
        java.util.List<Consideration> many = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> (Consideration) new FixedConsideration("c" + i, 0.9))
                .toList();
        double raw = Math.pow(0.9, 8);  // ~0.430
        double compensated = Consideration.combine(many, dummyContext());
        assertTrue(compensated > raw, "compensation factor should lift score above raw product");
        assertTrue(compensated <= 1.0, "must stay ≤ 1");
    }

    @Test
    void emptyConsiderationsReturnsZero() {
        double score = Consideration.combine(java.util.List.of(), dummyContext());
        assertEquals(0.0, score);
    }

    // -------------------------------------------------------------------------
    // Personality
    // -------------------------------------------------------------------------

    @Test
    void tradeWillingnessFloorEnforced() {
        Personality p = new Personality(0.5, 0.5, 0.5, 0.5, 0.0);  // zero willingness
        assertTrue(p.tradeWillingness() >= Personality.MIN_TRADE_WILLINGNESS,
                "tradeWillingness must be above the floor even when 0 is passed");
    }

    @Test
    void sampleJittersWithinRange() {
        Personality base = Personality.balanced();
        fi.monopoly.utils.RandomSource rng = fi.monopoly.utils.RandomSource.seeded(42L);
        Personality sampled = Personality.sample(base, 0.15, rng);
        // Each trait should be within [0,1] and within 0.15 of the base
        assertTrue(sampled.aggression() >= 0.0 && sampled.aggression() <= 1.0);
        assertTrue(Math.abs(sampled.aggression() - base.aggression()) <= 0.151);
    }

    // -------------------------------------------------------------------------
    // ProposalLedger + TradeProposal
    // -------------------------------------------------------------------------

    @Test
    void ledgerPreventsDuplicateReopen() {
        ProposalLedger ledger = ProposalLedger.empty();
        TradeProposal proposal = new TradeProposal(
                "partner-x",
                new TradeProposal.Side(java.util.Set.of("prop1"), 100),
                new TradeProposal.Side(java.util.Set.of("prop2"), 0));

        ledger.record(proposal, ProposalLedger.Outcome.DECLINED);

        assertTrue(ledger.isFailed(proposal.id()), "declined proposal should be marked failed");
        assertTrue(ledger.wouldLoop(proposal), "re-opening a declined identical proposal loops");
    }

    @Test
    void strictlyImprovingProposalBypassesLoop() {
        ProposalLedger ledger = ProposalLedger.empty();
        TradeProposal original = new TradeProposal(
                "partner-x",
                new TradeProposal.Side(java.util.Set.of("prop1"), 100),
                new TradeProposal.Side(java.util.Set.of("prop2"), 0));
        ledger.record(original, ProposalLedger.Outcome.DECLINED);

        // Same structure but we offer $100 more cash — this should improve
        TradeProposal improved = new TradeProposal(
                "partner-x",
                new TradeProposal.Side(java.util.Set.of("prop1"), 200),  // +100 cash
                new TradeProposal.Side(java.util.Set.of("prop2"), 0));

        assertTrue(improved.strictlyImproves(original), "offering $100 more should strictly improve");
        assertFalse(ledger.wouldLoop(improved), "improving proposal should not be blocked as a loop");
    }

    @Test
    void declineCountByPartner() {
        ProposalLedger ledger = ProposalLedger.empty();
        TradeProposal p1 = new TradeProposal("partner-x",
                new TradeProposal.Side(java.util.Set.of("A1"), 50),
                new TradeProposal.Side(java.util.Set.of("B1"), 0));
        TradeProposal p2 = new TradeProposal("partner-x",
                new TradeProposal.Side(java.util.Set.of("A2"), 50),
                new TradeProposal.Side(java.util.Set.of("B2"), 0));

        ledger.record(p1, ProposalLedger.Outcome.DECLINED);
        ledger.record(p2, ProposalLedger.Outcome.DECLINED);

        assertEquals(2, ledger.declineCount("partner-x"));
        assertEquals(0, ledger.declineCount("partner-y"), "different partner has 0 declines");
    }

    // -------------------------------------------------------------------------
    // BotParams
    // -------------------------------------------------------------------------

    @Test
    void defaultParamsHaveRequiredBuyCurves() {
        BotParams params = BotParams.defaults();
        assertNotNull(params.curve("affordability"));
        assertNotNull(params.curve("reserve_margin"));
        assertNotNull(params.curve("set_completion"));
        assertNotNull(params.curve("set_progress"));
        assertNotNull(params.curve("property_roi"));
    }

    @Test
    void defaultParamsHaveRequiredBuildCurves() {
        BotParams params = BotParams.defaults();
        assertNotNull(params.curve("build_affordability"));
        assertNotNull(params.curve("build_reserve_margin"));
        assertNotNull(params.curve("build_group_roi"));
        assertNotNull(params.curve("build_level_efficiency"));
        assertTrue(params.weight("build_end_turn_baseline", 0) > 0);
    }

    @Test
    void defaultParamsHaveRequiredUnmortgageCurves() {
        BotParams params = BotParams.defaults();
        assertNotNull(params.curve("unmortgage_affordability"));
        assertNotNull(params.curve("unmortgage_group_complete"));
        assertNotNull(params.curve("unmortgage_group_roi"));
        assertNotNull(params.curve("unmortgage_cash_comfort"));
        assertTrue(params.weight("unmortgage_end_turn_baseline", 0) > 0);
    }

    @Test
    void defaultParamsHaveRequiredAuctionCurves() {
        BotParams params = BotParams.defaults();
        assertNotNull(params.curve("bid_affordability"));
        assertNotNull(params.curve("bid_value_ratio"));
        assertNotNull(params.curve("bid_set_completion"));
        assertNotNull(params.curve("bid_opponent_blocking"));
        assertNotNull(params.curve("bid_group_roi"));
        assertTrue(params.weight("bid_aggression", 0) > 0);
        assertTrue(params.weight("bid_pass_baseline", 0) > 0);
    }

    @Test
    void aggressiveParamsHaveLowerAuctionBaseline() {
        BotParams aggressive = BotParams.aggressive();
        BotParams cautious   = BotParams.cautious();
        // Aggressive bots should prefer buying outright (lower auction baseline)
        double aggressiveBase = aggressive.weight("auction_baseline", 0.25);
        double cautiousBase   = cautious.weight("auction_baseline", 0.25);
        assertTrue(aggressiveBase <= cautiousBase,
                "aggressive bots should have lower or equal auction baseline than cautious");
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private record FixedConsideration(String id, double fixedScore) implements Consideration {
        @Override public double score(DecisionContext ctx) { return fixedScore; }
    }

    private static DecisionContext dummyContext() {
        // Minimal context with default params and a trivial action
        return new DecisionContext(null, "bot", BotMemory.empty(), BotParams.defaults(),
                new CandidateAction.DeclineProperty("dummy"));
    }
}
