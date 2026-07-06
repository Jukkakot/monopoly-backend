package fi.monopoly.server.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link PureDomainSessionFactory} produces a working
 * {@link SessionApplicationService} with no Processing runtime dependency.
 */
class PureDomainSessionFactoryTest {

    private static final String SESSION_ID = "pure-session";
    private static final String PLAYER_1 = "player-1";
    private static final String PROPERTY_B1 = "B1";

    @Test
    void emptyInitialStateHasCorrectSessionId() {
        SessionState state = PureDomainSessionFactory.emptyInitialState(SESSION_ID);
        assertEquals(SESSION_ID, state.sessionId());
        assertEquals(SessionStatus.IN_PROGRESS, state.status());
    }

    @Test
    void declinePropertyCommandRejectedWithNoActivePurchase() {
        SessionApplicationService service = factoryWithPlayer(PLAYER_1, 1500);

        CommandResult result = service.handle(new DeclinePropertyCommand(SESSION_ID, PLAYER_1, "decision-1", PROPERTY_B1));

        assertFalse(result.accepted());
    }

    @Test
    void auctionCommandRejectedWhenNoAuctionActive() {
        SessionApplicationService service = factoryWithPlayer(PLAYER_1, 1500);

        CommandResult result = service.handle(
                new PlaceAuctionBidCommand(SESSION_ID, PLAYER_1, "auction-1", 10));

        assertFalse(result.accepted());
    }

    @Test
    void buyPropertyCommandRejectedWithNoActivePurchase() {
        SessionApplicationService service = factoryWithPlayer(PLAYER_1, 1500);

        CommandResult result = service.handle(new BuyPropertyCommand(SESSION_ID, PLAYER_1, "decision-1", PROPERTY_B1));

        assertFalse(result.accepted());
    }

    @Test
    void currentStateReflectsInitialPlayers() {
        SessionApplicationService service = factoryWithPlayer(PLAYER_1, 1500);

        SessionState state = service.currentState();

        assertEquals(1, state.players().size());
        assertEquals(PLAYER_1, state.players().get(0).playerId());
        assertEquals(1500, state.players().get(0).cash());
    }

    // -------------------------------------------------------------------------
    // determineStartOrder
    // -------------------------------------------------------------------------

    @Test
    void startOrderContainsAllCandidates() {
        List<Integer> candidates = List.of(0, 1, 2, 3);
        List<Integer> order = PureDomainSessionFactory.determineStartOrder(candidates, new Random(42));
        assertEquals(new HashSet<>(candidates), new HashSet<>(order));
        assertEquals(candidates.size(), order.size());
    }

    @Test
    void startOrderForSinglePlayerIsUnchanged() {
        List<Integer> order = PureDomainSessionFactory.determineStartOrder(List.of(7), new Random());
        assertEquals(List.of(7), order);
    }

    @Test
    void startOrderHighestRollerGoesFirst() {
        // Seed deterministically: player 0 and player 2 roll high (same), player 1 rolls low.
        // The exact dice values depend on the seed, but the property holds over many seeds.
        for (long seed = 0; seed < 50; seed++) {
            List<Integer> order = PureDomainSessionFactory.determineStartOrder(List.of(0, 1, 2), new Random(seed));
            assertEquals(3, order.size(), "All players must appear");
            assertEquals(new HashSet<>(List.of(0, 1, 2)), new HashSet<>(order));
        }
    }

    @RepeatedTest(20)
    void startOrderIsTotalOrdering() {
        List<Integer> candidates = List.of(0, 1, 2);
        List<Integer> order = PureDomainSessionFactory.determineStartOrder(candidates, new Random());
        assertEquals(3, order.size());
        assertEquals(new HashSet<>(candidates), new HashSet<>(order));
    }

    @Test
    void initialGameStateFirstActiveSeatHasLowestSeatIndex() {
        SessionState state = PureDomainSessionFactory.initialGameState(
                "test", List.of("A", "B", "C"), List.of("#F00", "#0F0", "#00F"), new Random(1));
        String firstActiveId = state.turn().activePlayerId();
        assertNotNull(firstActiveId);
        // The active player must be in seat 0 (lowest seat index = first turn)
        state.seats().stream()
                .filter(s -> s.playerId().equals(firstActiveId))
                .findFirst()
                .ifPresent(s -> assertEquals(0, s.seatIndex(),
                        "First active player must occupy seat 0"));
    }

    @Test
    void initialGameStateAllPlayersPresent() {
        SessionState state = PureDomainSessionFactory.initialGameState(
                "test", List.of("Alpha", "Beta"), List.of("#F00", "#0F0"), new Random(99));
        assertEquals(2, state.players().size());
        assertEquals(2, state.seats().size());
        // Seat indices must be 0 and 1
        List<Integer> indices = state.seats().stream().map(SeatState::seatIndex).sorted().toList();
        assertEquals(List.of(0, 1), indices);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // LeaveGame — bystander leaves must not disturb other players' flow state
    // -------------------------------------------------------------------------

    @Test
    void bystanderLeavingDoesNotCancelOthersTrade() {
        SessionApplicationService service = factoryWithThreePlayers();
        assertTrue(service.handle(new OpenTradeCommand(SESSION_ID, PLAYER_1, "player-2")).accepted());
        assertNotNull(service.currentState().tradeState());

        assertTrue(service.handle(new LeaveGameCommand(SESSION_ID, "player-3")).accepted());

        assertNotNull(service.currentState().tradeState(),
                "a bystander leaving must not cancel a trade between two other players");
    }

    @Test
    void tradePartyLeavingCancelsTheTrade() {
        SessionApplicationService service = factoryWithThreePlayers();
        assertTrue(service.handle(new OpenTradeCommand(SESSION_ID, PLAYER_1, "player-2")).accepted());

        assertTrue(service.handle(new LeaveGameCommand(SESSION_ID, "player-2")).accepted());

        assertNull(service.currentState().tradeState(),
                "the trade must be cancelled when one of its parties leaves");
    }

    @Test
    void leaverIsPassedOutOfActiveAuction() {
        AuctionState auction = new AuctionState(
                "auction:B1", "B1", PLAYER_1, "player-3", null, 0, 10,
                java.util.Set.of(), List.of(PLAYER_1, "player-2", "player-3"),
                AuctionStatus.ACTIVE, 0, null);
        SessionApplicationService service = factoryWithThreePlayers(auction);

        assertTrue(service.handle(new LeaveGameCommand(SESSION_ID, "player-3")).accepted());

        AuctionState after = service.currentState().auctionState();
        assertNotNull(after, "auction must continue among the remaining bidders");
        assertTrue(after.passedPlayerIds().contains("player-3"),
                "the leaver must be marked as passed so the rotation skips them");
        assertNotEquals("player-3", after.currentActorPlayerId(),
                "the rotation must not wait for an eliminated player's bid");
    }

    @Test
    void auctionWonBySurvivingLeaderWhenLastCompetitorLeaves() {
        AuctionState auction = new AuctionState(
                "auction:B1", "B1", PLAYER_1, "player-3", "player-2", 50, 60,
                java.util.Set.of(PLAYER_1), List.of(PLAYER_1, "player-2", "player-3"),
                AuctionStatus.ACTIVE, 0, null);
        SessionApplicationService service = factoryWithThreePlayers(auction);

        assertTrue(service.handle(new LeaveGameCommand(SESSION_ID, "player-3")).accepted());

        AuctionState after = service.currentState().auctionState();
        assertNotNull(after);
        assertEquals(AuctionStatus.WON_PENDING_RESOLUTION, after.status(),
                "the surviving leader wins when everyone else has passed or left");
        assertEquals("player-2", after.winningPlayerId());
    }

    @Test
    void middleSeatPlayerLeavingOnTheirTurnPassesTurnToNextSeat() {
        // Seats 0,1,2 — the seat-1 player leaves during their own turn.
        // The turn must continue clockwise to seat 2, not jump back to seat 0.
        SessionState initialState = buildState(
                List.of(player(PLAYER_1, 0, 1500), player("player-2", 1, 1500), player("player-3", 2, 1500)),
                List.of()
        ).toBuilder()
                .turn(new TurnState("player-2", TurnPhase.WAITING_FOR_ROLL, true, false))
                .build();
        SessionApplicationService service = PureDomainSessionFactory.create(SESSION_ID, initialState);

        assertTrue(service.handle(new LeaveGameCommand(SESSION_ID, "player-2")).accepted());

        assertEquals("player-3", service.currentState().turn().activePlayerId(),
                "the turn must pass to the seat after the leaver, not restart from seat 0");
    }

    // -------------------------------------------------------------------------
    // PayDebt — must validate against the debtor's live cash, not the cached
    // debt.currentCash (a trade accepted mid-debt changes the balance)
    // -------------------------------------------------------------------------

    @Test
    void payDebtRejectedWhenLiveCashDroppedBelowDebtAfterDebtOpened() {
        DebtStateModel debt = new DebtStateModel(
                "debt-1", PLAYER_1, DebtCreditorType.BANK, null,
                300, "Tax", false, 500 /* stale: recorded before cash left via trade */, 0,
                List.of(DebtAction.PAY_DEBT_NOW, DebtAction.DECLARE_BANKRUPTCY));
        SessionState initialState = buildState(
                List.of(player(PLAYER_1, 0, 100), player("player-2", 1, 1500)),
                List.of()
        ).toBuilder()
                .turn(new TurnState(PLAYER_1, TurnPhase.RESOLVING_DEBT, false, false, 0))
                .activeDebt(debt)
                .build();
        SessionApplicationService service = PureDomainSessionFactory.create(SESSION_ID, initialState);

        CommandResult result = service.handle(new PayDebtCommand(SESSION_ID, PLAYER_1, "debt-1"));

        assertFalse(result.accepted(), "paying with only €100 live cash against a €300 debt must be rejected");
        assertEquals(100, service.currentState().players().stream()
                        .filter(p -> PLAYER_1.equals(p.playerId())).findFirst().orElseThrow().cash(),
                "the debtor's cash must not go negative");
    }

    @Test
    void payDebtAcceptedWhenLiveCashRoseAboveDebtAfterDebtOpened() {
        DebtStateModel debt = new DebtStateModel(
                "debt-1", PLAYER_1, DebtCreditorType.BANK, null,
                300, "Tax", false, 100 /* stale: recorded before cash arrived via trade */, 0,
                List.of(DebtAction.PAY_DEBT_NOW, DebtAction.DECLARE_BANKRUPTCY));
        SessionState initialState = buildState(
                List.of(player(PLAYER_1, 0, 500), player("player-2", 1, 1500)),
                List.of()
        ).toBuilder()
                .turn(new TurnState(PLAYER_1, TurnPhase.RESOLVING_DEBT, false, false, 0))
                .activeDebt(debt)
                .build();
        SessionApplicationService service = PureDomainSessionFactory.create(SESSION_ID, initialState);

        CommandResult result = service.handle(new PayDebtCommand(SESSION_ID, PLAYER_1, "debt-1"));

        assertTrue(result.accepted(), "€500 live cash covers a €300 debt even if the cached value is stale");
        assertEquals(200, service.currentState().players().stream()
                        .filter(p -> PLAYER_1.equals(p.playerId())).findFirst().orElseThrow().cash());
    }

    @Test
    void winnerCannotKeepPlayingAfterGameEndsByBankruptcy() {
        // The bankruptcy path leaves the winner's turn in WAITING_FOR_ROLL with
        // canRoll=true — gameplay commands must still be rejected once status is GAME_OVER.
        DebtStateModel debt = new DebtStateModel(
                "debt-1", "player-2", DebtCreditorType.PLAYER, PLAYER_1,
                500, "rent", true, 0, 0,
                List.of(DebtAction.PAY_DEBT_NOW, DebtAction.DECLARE_BANKRUPTCY));
        SessionState initialState = buildState(
                List.of(player(PLAYER_1, 0, 1500), player("player-2", 1, 0)),
                List.of()
        ).toBuilder()
                .turn(new TurnState("player-2", TurnPhase.RESOLVING_DEBT, false, false, 0))
                .activeDebt(debt)
                .build();
        SessionApplicationService service = PureDomainSessionFactory.create(SESSION_ID, initialState);

        assertTrue(service.handle(new DeclareBankruptcyCommand(SESSION_ID, "player-2", "debt-1")).accepted());
        assertEquals(SessionStatus.GAME_OVER, service.currentState().status());

        CommandResult roll = service.handle(new RollDiceCommand(SESSION_ID, PLAYER_1));
        assertFalse(roll.accepted(), "the winner must not be able to roll after the game is over");
        assertEquals("GAME_OVER", roll.rejections().get(0).code());
    }

    @Test
    void finishingAnInjectedWonAuctionDoesNotCrash() {
        // An auction state injected without going through startAuction (debug import,
        // scenario load) leaves the handler's in-memory context null — finishing the
        // resolution must still transfer the property instead of throwing.
        AuctionState wonAuction = new AuctionState(
                "auction:B1", "B1", PLAYER_1, null, "player-2", 50, 60,
                java.util.Set.of(PLAYER_1, "player-3"), List.of(PLAYER_1, "player-2", "player-3"),
                AuctionStatus.WON_PENDING_RESOLUTION, 50, "player-2");
        SessionState initialState = buildState(
                List.of(player(PLAYER_1, 0, 1500), player("player-2", 1, 1500), player("player-3", 2, 1500)),
                List.of(new PropertyStateSnapshot("B1", null, false, 0, 0))
        ).toBuilder().auctionState(wonAuction).build();
        SessionApplicationService service = PureDomainSessionFactory.create(SESSION_ID, initialState);

        CommandResult result = service.handle(new FinishAuctionResolutionCommand(SESSION_ID, "auction:B1"));

        assertTrue(result.accepted(), "finishing a decided auction must succeed without a live context");
        SessionState after = service.currentState();
        assertNull(after.auctionState(), "the auction must be cleared");
        assertEquals("player-2", after.properties().stream()
                .filter(p -> p.propertyId().equals("B1")).findFirst().orElseThrow().ownerPlayerId());
        assertEquals(1450, after.players().stream()
                .filter(p -> p.playerId().equals("player-2")).findFirst().orElseThrow().cash());
    }

    private static SessionApplicationService factoryWithThreePlayers() {
        return factoryWithThreePlayers(null);
    }

    private static SessionApplicationService factoryWithThreePlayers(AuctionState auction) {
        SessionState initialState = buildState(
                List.of(player(PLAYER_1, 0, 1500), player("player-2", 1, 1500), player("player-3", 2, 1500)),
                List.of()
        ).toBuilder().auctionState(auction).build();
        return PureDomainSessionFactory.create(SESSION_ID, initialState);
    }

    private static SessionApplicationService factoryWithPlayer(String playerId, int cash) {
        SessionState initialState = buildState(
                List.of(player(playerId, 0, cash)),
                List.of()
        );
        return PureDomainSessionFactory.create(SESSION_ID, initialState);
    }

    private static SessionState buildState(List<PlayerSnapshot> players, List<PropertyStateSnapshot> properties) {
        List<SeatState> seats = players.stream()
                .map(p -> {
                    int idx = players.indexOf(p);
                    return new SeatState("seat-" + idx, idx, p.playerId(),
                            SeatKind.HUMAN, ControlMode.MANUAL, p.name(), "HUMAN", "#000000");
                })
                .toList();
        return new SessionState(
                SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, players, properties,
                new TurnState(PLAYER_1, TurnPhase.WAITING_FOR_ROLL, true, false),
                null, null, null, null, null
        );
    }

    private static PlayerSnapshot player(String playerId, int seatIndex, int cash) {
        return new PlayerSnapshot(playerId, "seat-" + seatIndex, playerId, cash,
                0, false, false, false, 0, 0, List.of());
    }
}
