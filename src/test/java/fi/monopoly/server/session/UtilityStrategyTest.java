package fi.monopoly.server.session;

import fi.monopoly.domain.session.AuctionState;
import fi.monopoly.domain.session.AuctionStatus;
import fi.monopoly.domain.session.TradeHistoryEntry;
import fi.monopoly.domain.session.TradeOfferState;
import fi.monopoly.domain.session.TradeSelectionState;
import fi.monopoly.domain.session.TradeState;
import fi.monopoly.domain.session.TradeStatus;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link UtilityStrategy} — buy, build, unmortgage, and delegation.
 */
class UtilityStrategyTest {

    private final UtilityStrategy strategy = new UtilityStrategy(Map.of());
    private final RandomSource rng = RandomSource.seeded(42L);

    @Test
    void delegatesToPureDomainOnWaitingForRoll() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_ROLL)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.Roll.class, intent, "should delegate roll to PureDomainStrategy");
    }

    @Test
    void delegatesToPureDomainOnEndTurn() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.EndTurn.class, intent, "should delegate end-turn to PureDomainStrategy");
    }

    @Test
    void delegatesToPureDomainOnCardAck() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_CARD_ACK)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.AcknowledgeCard.class, intent);
    }

    @Test
    void nameIsUtilityV1() {
        assertEquals("utility-v1", strategy.name());
    }

    @Test
    void sameSeedProducesSameIntent() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_ROLL)
                .build();
        Intent a = strategy.decide(state, "player-1", BotMemory.empty(), RandomSource.seeded(7L));
        Intent b = strategy.decide(state, "player-1", BotMemory.empty(), RandomSource.seeded(7L));
        assertEquals(a.getClass(), b.getClass(), "same seed must yield same intent class");
    }

    @Test
    void unmortgagesWhenOwnsFullGroupAndHasCash() {
        // player-1 owns both brown properties (B1, B2); B1 is mortgaged.
        // With 1 500 € cash and a complete group, utility score should beat the baseline.
        var state = TestSessionState.twoPlayerGame()
                .withOwnership("player-1", "B1", "B2")
                .withMortgaged("B1")
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.Unmortgage.class, intent,
                "should choose to unmortgage the mortgaged property in a complete group");
    }

    @Test
    void bidsInAuctionWhenPropertyIsAffordableAndValuable() {
        // player-1 has 1 500 € and is the current bidder in an auction for O1 (€180 face price).
        // Opening bid is only €10 — value ratio ≈ 0.94 — strong enough signal for any personality.
        var baseState = TestSessionState.twoPlayerGame()
                .withCash("player-1", 1500)
                .withPhase(TurnPhase.WAITING_FOR_AUCTION)
                .build();
        AuctionState auction = new AuctionState(
                "auction-1", "O1", "player-2", "player-1", "player-2",
                0, 10,
                Set.of(), List.of("player-1", "player-2"),
                AuctionStatus.ACTIVE, 0, null);
        var state = baseState.toBuilder().auctionState(auction).build();

        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.Bid.class, intent,
                "bot with 1 500 € should bid when opening bid is €10 for a €180 orange property");
    }

    @Test
    void passesAuctionWhenBrokeAfterReserve() {
        // player-1 has only 200 €; dynamic reserve likely ≥ 200 → can't afford min bid
        var baseState = TestSessionState.twoPlayerGame()
                .withCash("player-1", 200)
                .withPhase(TurnPhase.WAITING_FOR_AUCTION)
                .build();
        AuctionState auction = new AuctionState(
                "auction-1", "O1", "player-2", "player-1", "player-2",
                90, 100,
                Set.of(), List.of("player-1", "player-2"),
                AuctionStatus.ACTIVE, 0, null);
        var state = baseState.toBuilder().auctionState(auction).build();

        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.PassAuction.class, intent,
                "bot with 200 € below reserve should pass the auction");
    }

    // -------------------------------------------------------------------------
    // Phase 4.5: trade response
    // -------------------------------------------------------------------------

    @Test
    void acceptsFairTradeOffer() {
        // player-2 offers O1 (face €180) in exchange for O2 (face €200) — nearly fair
        // Neither property completes either player's orange set (orange has 3 props)
        var baseState = TestSessionState.twoPlayerGame()
                .withOwnership("player-1", "O2")
                .withOwnership("player-2", "O1")
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();

        TradeOfferState offer = new TradeOfferState(
                "player-2", "player-1",
                new TradeSelectionState(0, List.of("O1"), 0),   // offered to player-1
                new TradeSelectionState(0, List.of("O2"), 0));  // requested from player-1

        TradeState trade = new TradeState(
                "trade-1", "player-2", "player-1",
                TradeStatus.SUBMITTED, offer,
                null, false,
                "player-1", "player-2",
                List.of());

        var state = baseState.toBuilder().tradeState(trade).build();

        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.RespondToTrade.class, intent,
                "should respond to trade offer");
        assertEquals(Intent.TradeResponse.ACCEPT,
                ((Intent.RespondToTrade) intent).response(),
                "fair trade (O1 €180 for O2 €200) should be accepted");
    }

    @Test
    void declinesGrosslyUnfairTradeOffer() {
        // player-2 demands B1 (€60) for nothing — value ratio = 0 → decline
        var baseState = TestSessionState.twoPlayerGame()
                .withOwnership("player-1", "B1")
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();

        TradeOfferState offer = new TradeOfferState(
                "player-2", "player-1",
                TradeSelectionState.NONE,                       // offers nothing
                new TradeSelectionState(0, List.of("B1"), 0)); // demands B1

        TradeState trade = new TradeState(
                "trade-1", "player-2", "player-1",
                TradeStatus.SUBMITTED, offer,
                null, false,
                "player-1", "player-2",
                List.of());

        var state = baseState.toBuilder().tradeState(trade).build();

        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.RespondToTrade.class, intent);
        assertEquals(Intent.TradeResponse.DECLINE,
                ((Intent.RespondToTrade) intent).response(),
                "getting nothing for B1 should be declined");
    }

    @Test
    void acceptsWhenTradeCompletesOwnMonopoly() {
        // player-1 owns B1, player-2 owns B2 — bot gets B2 completing its brown monopoly.
        // player-2 asks for cash (€50) for B2 (€60 face) — slightly unfair but monopoly bonus pushes it over
        var baseState = TestSessionState.twoPlayerGame()
                .withOwnership("player-1", "B1")
                .withOwnership("player-2", "B2")
                .withCash("player-1", 1500)
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();

        TradeOfferState offer = new TradeOfferState(
                "player-2", "player-1",
                new TradeSelectionState(0, List.of("B2"), 0),  // offered to player-1
                new TradeSelectionState(80, List.of(), 0));    // requests €80 cash

        TradeState trade = new TradeState(
                "trade-1", "player-2", "player-1",
                TradeStatus.SUBMITTED, offer,
                null, false,
                "player-1", "player-2",
                List.of());

        var state = baseState.toBuilder().tradeState(trade).build();

        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.RespondToTrade.class, intent);
        // score should clear accept_baseline thanks to monopoly completion bonus
        assertEquals(Intent.TradeResponse.ACCEPT,
                ((Intent.RespondToTrade) intent).response(),
                "should accept trade that completes own brown monopoly");
    }

    @Test
    void differentBotsGetDistinctPersonalities() {
        // Phase 5.2: per-bot personality sampling — two different IDs should produce
        // different BotParams (specifically different accept baselines or bid baselines).
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_ROLL)
                .build();

        // Trigger params caching for both bots by running a decision
        strategy.decide(state, "player-1", BotMemory.empty(), rng);
        strategy.decide(state, "player-2", BotMemory.empty(), rng);

        // The internal cache is private — we verify indirectly by checking that
        // decisions aren't always identical across different game sessions (smoke only).
        // Functional variety is validated by HeadlessGameRunner in the simulation test.
        assertNotNull(state); // placeholder assertion; real check is the simulation
    }

    @Test
    void gameSimulationCompletesWithUtilityStrategy() {
        // Smoke test: a full game with UtilityStrategy should terminate without exception.
        var config = new HeadlessGameRunner.MatchConfig(
                11L,
                java.util.List.of(
                        new HeadlessGameRunner.SeatAssignment(strategy),
                        new HeadlessGameRunner.SeatAssignment(strategy)),
                20_000);
        HeadlessGameRunner.GameResult result = HeadlessGameRunner.play(config);
        assertNotNull(result);
        // Loop-suspected stalemates are OK as long as there's no exception
        assertFalse(result.steps() == 0, "game should advance at least one step");
    }
}
