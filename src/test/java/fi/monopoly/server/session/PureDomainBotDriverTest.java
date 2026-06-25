package fi.monopoly.server.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.decision.DecisionType;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.domain.session.TradeOfferState;
import fi.monopoly.domain.session.TradeSelectionState;
import fi.monopoly.domain.session.TradeState;
import fi.monopoly.domain.session.TradeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PureDomainBotDriver}.
 *
 * <p>Uses a one-shot recording command port: after the first accepted command it returns a
 * GAME_OVER snapshot so the driver stops scheduling further steps, preventing infinite loops.</p>
 */
class PureDomainBotDriverTest {

    private static final String SESSION_ID = "test-sid";
    private static final String BOT_PLAYER = "player-bot";
    private static final String HUMAN_PLAYER = "player-human";
    private static final String DEBT_ID = "debt-1";

    @org.junit.jupiter.api.BeforeAll
    static void configureTestDelay() {
        System.setProperty("monopoly.bot.think.delay.ms", "0");
    }

    private PureDomainBotDriver driver;

    @AfterEach
    void tearDown() {
        if (driver != null) driver.stop();
    }

    // -------------------------------------------------------------------------
    // Debt handler: SELL_BUILDING with no buildings must fall through to MORTGAGE
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void debtHandlerMortgagesPropertyWhenSellBuildingAllowedButNoBuildingsExist() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        PropertyStateSnapshot property = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        DebtStateModel debt = new DebtStateModel(
                DEBT_ID, BOT_PLAYER, DebtCreditorType.BANK, null,
                500, "rent", false, 100, 300,
                List.of(DebtAction.SELL_BUILDING, DebtAction.MORTGAGE_PROPERTY, DebtAction.DECLARE_BANKRUPTCY));

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.RESOLVING_DEBT, false, false),
                null, debt, List.of(property));
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        boolean hasMortgage = recorder.commands.stream()
                .anyMatch(c -> c instanceof MortgagePropertyForDebtCommand m && DEBT_ID.equals(m.debtId()));
        boolean hasSell = recorder.commands.stream().anyMatch(c -> c instanceof SellBuildingForDebtCommand);
        assertTrue(hasMortgage, "Expected MortgagePropertyForDebtCommand but got: " + recorder.commands);
        assertFalse(hasSell, "Should NOT dispatch SellBuildingForDebtCommand when no buildings exist");
    }

    // -------------------------------------------------------------------------
    // Bot buys affordable property
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botBuysPropertyThatCompletesColorGroup() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot already owns B2; buying B1 completes the BROWN group → always buys regardless of score
        var payload = new PropertyPurchaseDecisionPayload("B1", "Bulevardi", 60);
        var decision = new PendingDecision("dec-1", DecisionType.PROPERTY_PURCHASE, BOT_PLAYER, List.of(), null, payload);

        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 0, 0);
        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_DECISION, false, false),
                decision, null, List.of(b2));
        state = state.toBuilder()
                .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 600, 1, false, false, false, 0, 0, List.of("B2"))))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof BuyPropertyCommand),
                "Bot should buy property that completes color group, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Regression: STRONG bot must still bid in late-game auction despite high dynamic reserve.
    // Previously the full dynamicReserve (500+) was applied, making maxBid negative.
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotBidsInAuctionDespiteHighLateGameReserve() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Human owns all RED properties with hotels → dangerScore >> 300 → dynamicReserve >> 500
        List<PropertyStateSnapshot> props = List.of(
                new PropertyStateSnapshot("R1", HUMAN_PLAYER, false, 0, 1),
                new PropertyStateSnapshot("R2", HUMAN_PLAYER, false, 0, 1),
                new PropertyStateSnapshot("R3", HUMAN_PLAYER, false, 0, 1)
        );
        TurnState turn = new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_AUCTION, false, false);
        SessionState state = buildTwoPlayerState(turn, props);
        state = state.toBuilder()
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 600, 1, false, false, false, 0, 0, List.of()),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 0, false, false, false, 0, 0, List.of())
                ))
                .build();

        // Auction for O1 (Hermanni €180); human already bid 90, bot's min is 100
        AuctionState auction = new AuctionState(
                "auction-1", "O1", HUMAN_PLAYER,
                BOT_PLAYER, HUMAN_PLAYER,
                90, 100,
                Set.of(), List.of(BOT_PLAYER, HUMAN_PLAYER),
                AuctionStatus.ACTIVE, 0, null
        );
        state = state.toBuilder().auctionState(auction).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should respond within 3s");
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof PlaceAuctionBidCommand),
                "STRONG bot with 600 cash should bid in auction even with high late-game reserve; got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Regression: STRONG bot must NOT bid above face price for a property with no strategic value.
    // (User report: the bot paid >€200 at auction for a €200 railway it would gladly sell for €200.)
    // The base auction ceiling is hard-capped at face price; only monopoly completion or opponent
    // blocking lifts it above — neither applies here, so the bot must pass once the bid exceeds face.
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotPassesRatherThanBidAboveFacePrice() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        TurnState turn = new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_AUCTION, false, false);
        SessionState state = buildTwoPlayerState(turn, List.of());
        state = state.toBuilder()
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 600, 1, false, false, false, 0, 0, List.of()),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 0, false, false, false, 0, 0, List.of())
                ))
                .build();

        // O1 = Hermanni, face price €180. Human bid exactly 180 → next min = 190 > face.
        // The bot owns nothing in this group and no opponent is one away, so the ceiling is capped
        // at face price (180). minBid 190 > 180 → the bot must pass rather than overpay.
        AuctionState auction = new AuctionState(
                "auction-1", "O1", HUMAN_PLAYER,
                BOT_PLAYER, HUMAN_PLAYER,
                180, 190,
                Set.of(), List.of(BOT_PLAYER, HUMAN_PLAYER),
                AuctionStatus.ACTIVE, 0, null
        );
        state = state.toBuilder().auctionState(auction).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should respond within 3s");
        assertTrue(recorder.commands.stream().noneMatch(c -> c instanceof PlaceAuctionBidCommand),
                "STRONG bot must NOT bid above face price (minBid 190 > face 180) for a property with no strategic value; got: " + recorder.commands);
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof PassAuctionCommand),
                "STRONG bot should pass when the bid would exceed face price; got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Jail strategy: leave when the board is safe, stay when it is dangerous.
    // defaults() preset: preferJailLateGame=true, jailExitThreshold=500.
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botPaysFineToLeaveJailWhenBoardIsSafe() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // No developed properties → boardDangerScore 0 < threshold 500 → the bot should get out
        // and keep acquiring. It holds no jail card and can afford the €50 fine, so it pays.
        TurnState turn = new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_ROLL, true, false);
        SessionState state = buildTwoPlayerState(turn, List.of());
        state = state.toBuilder()
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 500, 10, false, false, true, 3, 0, List.of()),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 0, false, false, false, 0, 0, List.of())
                ))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should respond within 3s");
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof PayJailFineCommand),
                "Bot should pay the jail fine to leave jail on a safe board; got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botStaysInJailWhenBoardIsDangerous() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Human owns O1 with a hotel → boardDangerScore = 900 (O1 hotel rent) ≥ threshold 500.
        // The bot should stay put (just roll, never pay/use card) to avoid landing on the hotel.
        PropertyStateSnapshot o1Hotel = new PropertyStateSnapshot("O1", HUMAN_PLAYER, false, 0, 1);
        TurnState turn = new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_ROLL, true, false);
        SessionState state = buildTwoPlayerState(turn, List.of(o1Hotel));
        state = state.toBuilder()
                .players(List.of(
                        // Bot holds a jail card and plenty of cash — it should still NOT use them.
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 800, 10, false, false, true, 3, 1, List.of()),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 0, false, false, false, 0, 0, List.of("O1"))
                ))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should respond within 3s");
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof RollDiceCommand),
                "Bot should stay in jail (just roll) on a dangerous board; got: " + recorder.commands);
        assertTrue(recorder.commands.stream().noneMatch(c -> c instanceof PayJailFineCommand
                        || c instanceof UseGetOutOfJailCardCommand),
                "Bot must not pay the fine or use a card to leave jail when staying is correct; got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Bot unmortgages when owns full group
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botUnmortgagesWhenOwnsFullGroupAndHasCash() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns both BROWN properties; B1 is mortgaged — B.price=60, unmortgage cost = 33
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, true, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 0, 0);

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                null, null, List.of(b1, b2));
        // Give enough cash to unmortgage (33) + keep 200€ reserve = 233+
        state = state.toBuilder()
                .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 400, 1, false, false, false, 0, 0, List.of())))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof ToggleMortgageCommand t && "B1".equals(t.propertyId())),
                "Bot should unmortgage B1 when it owns full BROWN group and has cash, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botDoesNotUnmortgageWithInsufficientReserve() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, true, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 0, 0);

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                null, null, List.of(b1, b2));
        // Only 220 cash: unmortgage cost 33 + reserve 200 = 233 needed; 220 < 233
        state = state.toBuilder()
                .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 220, 1, false, false, false, 0, 0, List.of())))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof ToggleMortgageCommand),
                "Bot should not unmortgage when reserve would be broken, got: " + recorder.commands);
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof EndTurnCommand),
                "Bot should end turn when cannot unmortgage or build, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Bot builds house when complete color group owned
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botBuildsHouseWhenCompleteColorGroupOwned() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns both BROWN properties (complete group, no hotels, no houses yet)
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 0, 0);

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                null, null, List.of(b1, b2));
        // Give enough cash for house price (BROWN house = 50)
        state = state.toBuilder()
                .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of())))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof BuyBuildingRoundCommand),
                "Bot with complete color group should dispatch BuyBuildingRoundCommand, got: " + recorder.commands);
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof EndTurnCommand),
                "Bot should not end turn when it can build, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botEndsWhenInsufficientCashToBuild() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 0, 0);

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                null, null, List.of(b1, b2));
        // Not enough cash to build (BROWN house = 50, player has 30)
        state = state.toBuilder()
                .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 30, 1, false, false, false, 0, 0, List.of())))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof EndTurnCommand),
                "Bot should end turn when it cannot afford to build, got: " + recorder.commands);
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof BuyBuildingRoundCommand),
                "Bot should not attempt build with insufficient cash, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Auction: bot winner dispatches FinishAuctionResolutionCommand
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botAuctionWinnerDispatchesFinishResolutionCommand() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        AuctionState auction = new AuctionState(
                "auction-1", "B1", "other-player",
                null,              // currentActorPlayerId = null (WON_PENDING_RESOLUTION)
                BOT_PLAYER, 60, 0, // leading = bot
                Set.of(), List.of(BOT_PLAYER),
                AuctionStatus.WON_PENDING_RESOLUTION,
                60, BOT_PLAYER     // winning player = bot
        );
        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_AUCTION, false, false),
                null, null, List.of());
        state = state.toBuilder().auctionState(auction).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof FinishAuctionResolutionCommand),
                "Bot auction winner should dispatch FinishAuctionResolutionCommand, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Debt handler: even-selling rule selects eligible property with most buildings
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void debtHandlerSellsBuildingFromEligiblePropertyUnderEvenSellingRule() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // B1: 1 house — NOT eligible (maxRest=2, level 1 < 2)
        // B2: 2 houses — eligible (maxRest=1, level 2 >= 1), and has the most buildings
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 1, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 2, 0);
        DebtStateModel debt = new DebtStateModel(
                DEBT_ID, BOT_PLAYER, DebtCreditorType.BANK, null,
                500, "rent", false, 100, 300,
                List.of(DebtAction.SELL_BUILDING, DebtAction.MORTGAGE_PROPERTY, DebtAction.DECLARE_BANKRUPTCY));

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.RESOLVING_DEBT, false, false),
                null, debt, List.of(b1, b2));
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream()
                        .anyMatch(c -> c instanceof SellBuildingForDebtCommand s && "B2".equals(s.propertyId())),
                "Bot should sell from B2 (eligible with most buildings), got: " + recorder.commands);
        assertFalse(recorder.commands.stream()
                        .anyMatch(c -> c instanceof SellBuildingForDebtCommand s && "B1".equals(s.propertyId())),
                "Bot must not sell from B1 (violates even-selling rule), got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Trade: bot responds to trade offers
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botAcceptsFavorableTradeOffer() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot receives 100€, gives 60€ → favorable → accept
        TradeOfferState offer = new TradeOfferState(
                "player-human", BOT_PLAYER,
                new TradeSelectionState(100, List.of(), 0),
                new TradeSelectionState(60, List.of(), 0));
        TradeState trade = new TradeState(
                "trade-1", "player-human", BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, "player-human", true, BOT_PLAYER, "player-human", List.of());

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                null, null, List.of());
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand a && "trade-1".equals(a.tradeId())),
                "Bot should accept when value received >= value given, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botRejectsUnfavorableTradeOffer() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot receives 60€, gives 100€ → unfavorable → counter or decline (not accept)
        TradeOfferState offer = new TradeOfferState(
                "player-human", BOT_PLAYER,
                new TradeSelectionState(60, List.of(), 0),
                new TradeSelectionState(100, List.of(), 0));
        TradeState trade = new TradeState(
                "trade-1", "player-human", BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, "player-human", true, BOT_PLAYER, "player-human", List.of());

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                null, null, List.of());
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "Bot should not accept when value received < value given (outside fairness tolerance), got: " + recorder.commands);
        assertTrue(recorder.commands.stream().anyMatch(c ->
                        (c instanceof DeclineTradeCommand d && "trade-1".equals(d.tradeId()))
                        || c instanceof CounterTradeCommand),
                "Bot should counter or decline unfavorable trade, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Regression: bot must NOT sell 2/3 of a color group to a human who already owns the 3rd,
    // for a cash sum near face value — that hands the human a monopoly. The human offers 200€
    // (≈ face value of LB1+LB2) for the bot's two light-blue deeds while owning LB3.
    // Accepting completes the human's LIGHT_BLUE monopoly, so the bot must decline/counter.
    // -------------------------------------------------------------------------
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botRejectsSellingTwoOfThreeWhenHumanOwnsThirdAndGainsMonopoly() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns LB1+LB2 (2/3 light blue); human owns LB3. LB prices: 100,100,120.
        PropertyStateSnapshot lb1 = new PropertyStateSnapshot("LB1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot lb2 = new PropertyStateSnapshot("LB2", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot lb3 = new PropertyStateSnapshot("LB3", HUMAN_PLAYER, false, 0, 0);

        // Human (proposer) offers 200€ for the bot's LB1+LB2 → completes human's monopoly.
        TradeOfferState offer = new TradeOfferState(
                HUMAN_PLAYER, BOT_PLAYER,
                new TradeSelectionState(200, List.of(), 0),            // offered to bot: 200€
                new TradeSelectionState(0, List.of("LB1", "LB2"), 0)); // requested from bot: LB1+LB2
        TradeState trade = new TradeState(
                "trade-1", HUMAN_PLAYER, BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, null, true, BOT_PLAYER, HUMAN_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(HUMAN_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(lb1, lb2, lb3));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should respond within 3s");
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "Bot must not sell 2/3 of a group for near-face cash when it hands the human a monopoly, got: "
                        + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Regression: STRONG bot must build counter-offer, not decline, when status=COUNTERED.
    // Bug: handleCounter() sets decisionRequiredFromPlayerId=bot, causing dispatchGreedy to
    // call handleTradeDecision (which declines) instead of handleCounterEditing.
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotBuildsCounterOfferInsteadOfDeclining() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Unfavorable offer: human requests 150€ from bot, offers nothing in return.
        // The bot must NOT hand over cash for nothing. With no value on the received side it
        // either requests something of value back (Edit) or, if it has nothing worth requesting
        // from this partner, cancels the trade. It must never submit a give-for-nothing counter
        // and (being in COUNTERED editing mode) must never decline.
        TradeOfferState offer = new TradeOfferState(
                HUMAN_PLAYER, BOT_PLAYER,
                new TradeSelectionState(0, List.of(), 0),    // offered to bot: nothing
                new TradeSelectionState(150, List.of(), 0)); // requested from bot: 150€
        // COUNTERED state mirrors what handleCounter() produces:
        // editingPlayerId = BOT_PLAYER, decisionRequiredFromPlayerId = BOT_PLAYER (the bug trigger)
        TradeState trade = new TradeState(
                "trade-1", HUMAN_PLAYER, BOT_PLAYER, TradeStatus.COUNTERED,
                offer,
                BOT_PLAYER,   // editingPlayerId
                false,
                BOT_PLAYER,   // decisionRequiredFromPlayerId — same as editor, triggers old bug
                HUMAN_PLAYER,
                List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of());
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should respond within 3s");
        // In COUNTERED editing mode the bot must never decline.
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof DeclineTradeCommand),
                "STRONG bot in COUNTERED editing mode must not decline; got: " + recorder.commands);
        // It must respond by either requesting value (Edit) or cancelling — never by submitting a
        // counter that still gives the bot's cash away for nothing.
        boolean requestedValueOrCancelled = recorder.commands.stream()
                .anyMatch(c -> c instanceof EditTradeOfferCommand || c instanceof CancelTradeCommand);
        assertTrue(requestedValueOrCancelled,
                "STRONG bot should request value back or cancel a give-for-nothing offer; got: " + recorder.commands);
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof SubmitTradeOfferCommand),
                "STRONG bot must not submit a give-for-nothing counter; got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // STRONG mode: proactive trade initiation and editing
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotInitiatesTradeWhenPartnerHasGroupProperty() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot has B1, human has B2 → bot would benefit from acquiring B2 (BROWN group)
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", HUMAN_PLAYER, false, 0, 0);

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(b1, b2));
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c ->
                        c instanceof OpenTradeCommand o
                                && BOT_PLAYER.equals(o.actorPlayerId())
                                && HUMAN_PLAYER.equals(o.recipientPlayerId())),
                "STRONG bot should open trade with the partner who has B2, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotEditsTradeByRequestingTargetProperty() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", HUMAN_PLAYER, false, 0, 0);

        TradeState trade = new TradeState(
                "trade-1", BOT_PLAYER, HUMAN_PLAYER, TradeStatus.EDITING,
                new TradeOfferState(BOT_PLAYER, HUMAN_PLAYER, TradeSelectionState.NONE, TradeSelectionState.NONE),
                BOT_PLAYER, true, null, BOT_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(b1, b2));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c ->
                        c instanceof EditTradeOfferCommand e
                                && "trade-1".equals(e.tradeId())
                                && e.patch().propertyIdsToAdd().contains("B2")),
                "STRONG bot should request B2 from partner, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotSubmitsWhenOfferIsComplete() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", HUMAN_PLAYER, false, 0, 0);

        // Offer already has B2 requested and 60€ offered (B2 price = 60)
        TradeState trade = new TradeState(
                "trade-1", BOT_PLAYER, HUMAN_PLAYER, TradeStatus.EDITING,
                new TradeOfferState(
                        BOT_PLAYER, HUMAN_PLAYER,
                        new TradeSelectionState(60, List.of(), 0),
                        new TradeSelectionState(0, List.of("B2"), 0)),
                BOT_PLAYER, true, null, BOT_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(b1, b2));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c ->
                        c instanceof SubmitTradeOfferCommand s && "trade-1".equals(s.tradeId())),
                "STRONG bot should submit when offer has property + money, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Trade: bot escalates to mutual-swap after a cash offer is declined
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotProposesSwapAfterCashOfferDeclined() throws InterruptedException {
        CopyOnWriteArrayList<SessionCommand> commands = new CopyOnWriteArrayList<>();
        CountDownLatch anyCommand = new CountDownLatch(1);

        // Bot owns B1, human owns B2 — both need the other's property to complete the brown monopoly.
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", HUMAN_PLAYER, false, 0, 0);

        SessionState baseState = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(b1, b2));

        // Bot previously offered 60€ for B2; human declined (decisionRequiredFromPlayerId = HUMAN)
        TradeOfferState declinedOffer = new TradeOfferState(
                BOT_PLAYER, HUMAN_PLAYER,
                new TradeSelectionState(60, List.of(), 0),
                new TradeSelectionState(0, List.of("B2"), 0));
        TradeState pendingHumanDecision = new TradeState(
                "trade-1", BOT_PLAYER, HUMAN_PLAYER, TradeStatus.SUBMITTED,
                declinedOffer, null, true, HUMAN_PLAYER, BOT_PLAYER, List.of());
        SessionState stateWithTrade = baseState.toBuilder().tradeState(pendingHumanDecision).build();

        SessionCommandPort port = new SessionCommandPort() {
            @Override
            public CommandResult handle(SessionCommand command) {
                commands.add(command);
                anyCommand.countDown();
                SessionState over = baseState.toBuilder().status(SessionStatus.GAME_OVER).build();
                return new CommandResult(true, over, List.of(), List.of(), List.of());
            }
            @Override
            public SessionState currentState() { return baseState; }
        };

        SessionCommandPublisher publisher = new SessionCommandPublisher(port);
        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, baseState, Map.of());

        // Inject trade-pending snapshot (driver won't act — human decides)
        driver.onSnapshotChanged(ClientSessionSnapshot.from(stateWithTrade, true));
        // Inject trade-gone snapshot → decline detected, lastDeclinedOfferAmount[HUMAN][B2]=60
        driver.onSnapshotChanged(ClientSessionSnapshot.from(baseState, true));

        assertTrue(anyCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch some command after decline");

        // After a cash offer is declined, the mutual-swap pass fires because both players
        // need each other's property to complete the brown group.  Bot opens a new trade.
        assertTrue(commands.stream().anyMatch(c -> c instanceof OpenTradeCommand),
                "Bot should re-propose as a mutual swap (B1 for B2) after cash offer declined; got: " + commands);
    }

    // -------------------------------------------------------------------------
    // Trade: bot offers own property when cash-poor (property-for-property)
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotOffersOwnPropertyInTradeWhenCashPoor() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns U1 (utility — lowest mortgage priority=1, always expendable); human owns B2
        PropertyStateSnapshot u1 = new PropertyStateSnapshot("U1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", HUMAN_PLAYER, false, 0, 0);

        // Trade in editing: B2 already requested, give side empty
        TradeState trade = new TradeState(
                "trade-1", BOT_PLAYER, HUMAN_PLAYER, TradeStatus.EDITING,
                new TradeOfferState(
                        BOT_PLAYER, HUMAN_PLAYER,
                        new TradeSelectionState(0, List.of(), 0),
                        new TradeSelectionState(0, List.of("B2"), 0)),
                BOT_PLAYER, true, null, BOT_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(u1, b2));
        // Bot cash = 100, reserve ≈ 150 → available = 0 < price(B2)=60 → triggers prop offer
        state = state.toBuilder()
                .tradeState(trade)
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 100, 1, false, false, false, 0, 0, List.of("U1")),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 1, false, false, false, 0, 0, List.of("B2"))
                ))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertTrue(recorder.commands.stream().anyMatch(c ->
                        c instanceof EditTradeOfferCommand e
                                && "trade-1".equals(e.tradeId())
                                && e.patch().propertyIdsToAdd().contains("U1")),
                "STRONG bot should offer own property U1 when cash is insufficient; got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Auction: budget-aware — bid minimum when competitors are cash-poor
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void strongBotBidsMinimumWhenCompetitorsAreCashPoor() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Human is eligible but has only 5 cash (< 2 * minBid=10) — can't compete
        AuctionState auction = new AuctionState(
                "auction-1", "B1", HUMAN_PLAYER,
                BOT_PLAYER, null,
                0, 10,
                Set.of(), List.of(BOT_PLAYER, HUMAN_PLAYER),
                AuctionStatus.ACTIVE, 0, null
        );
        TurnState turn = new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_AUCTION, false, false);
        SessionState state = buildTwoPlayerState(turn, List.of());
        state = state.toBuilder()
                .auctionState(auction)
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of()),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 5, 1, false, false, false, 0, 0, List.of())
                ))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertTrue(recorder.commands.stream().anyMatch(c ->
                        c instanceof PlaceAuctionBidCommand b && b.amount() == 10),
                "STRONG bot should bid exactly minBid when competitors are cash-poor; got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Debt: position-aware mortgage priority protects high-danger properties
    // -------------------------------------------------------------------------

    @Test
    void debtMortgagePriorityIncreasedWhenOpponentNearProperty() {
        // B1 is at SPOT_TYPES index 1; opponent at boardIndex=0 → distance=1 (within danger range)
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.RESOLVING_DEBT, false, false),
                List.of(b1));
        // Opponent at board position 0 (GO) — exactly 1 step before B1
        state = state.toBuilder()
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of("B1")),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 0, false, false, false, 0, 0, List.of())
                ))
                .build();

        int priorityWithDanger = StrongBotStrategy.debtMortgagePriority(state, BOT_PLAYER, b1);

        // Move opponent far away (boardIndex=10 → distance=(1-10+40)%40=31 > 7 → no danger)
        SessionState stateFar = state.toBuilder()
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of("B1")),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 10, false, false, false, 0, 0, List.of())
                ))
                .build();

        int priorityWithoutDanger = StrongBotStrategy.debtMortgagePriority(stateFar, BOT_PLAYER, b1);

        assertTrue(priorityWithDanger > priorityWithoutDanger,
                "Priority with danger (" + priorityWithDanger + ") should exceed priority without danger (" + priorityWithoutDanger + ")");
    }

    // -------------------------------------------------------------------------
    // Trade goal-orientation: bot must not accept property-only trades that don't
    // advance any monopoly goal (regression for aimless property-shuffling).
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botDeclinesPurePropertySwapWithNoMonopolyBenefit() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns O1 (ORANGE), human wants to swap it for R1 (RED).
        // Bot has zero other RED or ORANGE properties → receiving R1 advances no group.
        // This is a pure property shuffle — bot should decline.
        PropertyStateSnapshot o1 = new PropertyStateSnapshot("O1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot r1 = new PropertyStateSnapshot("R1", HUMAN_PLAYER, false, 0, 0);

        TradeOfferState offer = new TradeOfferState(
                HUMAN_PLAYER, BOT_PLAYER,
                new TradeSelectionState(0, List.of("R1"), 0),    // offered to bot: R1
                new TradeSelectionState(0, List.of("O1"), 0));   // requested from bot: O1
        TradeState trade = new TradeState(
                "trade-shuffle", HUMAN_PLAYER, BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, HUMAN_PLAYER, true, BOT_PLAYER, HUMAN_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(o1, r1));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "Bot must not accept a property swap that advances no monopoly goal; got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botDeclinesTradeThatCompletesOpponentMonopolyWithoutExtraordinaryCompensation() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Human owns B1 and needs B2 to complete BROWN monopoly.
        // Human offers just 50€ for B2 (face price 60, mortgage value 30).
        // Giving away B2 completes the opponent's monopoly — must be vetoed.
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", HUMAN_PLAYER, false, 0, 0);

        TradeOfferState offer = new TradeOfferState(
                HUMAN_PLAYER, BOT_PLAYER,
                new TradeSelectionState(50, List.of(), 0),     // offered: 50€
                new TradeSelectionState(0, List.of("B2"), 0)); // requested: B2 (completes human's BROWN)
        TradeState trade = new TradeState(
                "trade-monopoly-gift", HUMAN_PLAYER, BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, HUMAN_PLAYER, true, BOT_PLAYER, HUMAN_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(b1, b2));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "Bot must not accept a trade that completes the opponent's monopoly for modest cash; got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botDoesNotInitiateTradeForIsolatedProperty() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns O1 (ORANGE 1 of 3), human owns R1 (RED 1 of 3).
        // Neither player has more than 1 property in any group → no near-monopoly.
        // The bot should NOT initiate a foothold-only trade — it has no clear goal.
        PropertyStateSnapshot o1 = new PropertyStateSnapshot("O1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot r1 = new PropertyStateSnapshot("R1", HUMAN_PLAYER, false, 0, 0);

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(o1, r1));
        // Give bot plenty of cash so the cash-guard doesn't prevent trade initiation
        state = state.toBuilder()
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 800, 1, false, false, false, 0, 0, List.of("O1")),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 1, false, false, false, 0, 0, List.of("R1"))
                ))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof OpenTradeCommand),
                "Bot must not initiate a trade when neither player has a near-monopoly position; got: " + recorder.commands);
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof EndTurnCommand),
                "Bot should end turn when there is no strategic trade opportunity; got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botInitiatesTradeWhenNearMonopoly() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns O1 + O2 (2 of 3 ORANGE properties = n-1 → critical).
        // Human owns O3. Bot should immediately initiate a trade to complete the ORANGE monopoly.
        PropertyStateSnapshot o1 = new PropertyStateSnapshot("O1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot o2 = new PropertyStateSnapshot("O2", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot o3 = new PropertyStateSnapshot("O3", HUMAN_PLAYER, false, 0, 0);

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(o1, o2, o3));
        state = state.toBuilder()
                .players(List.of(
                        new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 600, 1, false, false, false, 0, 0, List.of("O1","O2")),
                        new PlayerSnapshot(HUMAN_PLAYER, "seat-human", "Ihminen", 500, 1, false, false, false, 0, 0, List.of("O3"))
                ))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertTrue(recorder.commands.stream().anyMatch(c ->
                        c instanceof OpenTradeCommand o
                                && BOT_PLAYER.equals(o.actorPlayerId())
                                && HUMAN_PLAYER.equals(o.recipientPlayerId())),
                "Bot should initiate trade when 1 step from completing ORANGE monopoly; got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botAcceptsTradeWhenReceivingPropertyAdvancesMonopoly() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns O1 (ORANGE), human offers O2 for 60€ cash.
        // Bot receiving O2 advances its ORANGE set (already has O1 → foothold).
        // This is a goal-oriented trade — bot should accept if price is fair.
        PropertyStateSnapshot o1 = new PropertyStateSnapshot("O1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot o2 = new PropertyStateSnapshot("O2", HUMAN_PLAYER, false, 0, 0);

        // Human offers O2 for free (no cash requested) — an outright gift.
        // evaluateSelectionContextual: receiving O2 advances O group (1+1=2 of 3, so partial bonus).
        // myGiving: nothing. Should accept because valueReceived >= 0 and advancing a monopoly.
        TradeOfferState offer = new TradeOfferState(
                HUMAN_PLAYER, BOT_PLAYER,
                new TradeSelectionState(0, List.of("O2"), 0),  // offered: O2
                new TradeSelectionState(0, List.of(), 0));     // requested: nothing
        TradeState trade = new TradeState(
                "trade-advance", HUMAN_PLAYER, BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, HUMAN_PLAYER, true, BOT_PLAYER, HUMAN_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(o1, o2));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "Bot should accept free property that advances its ORANGE monopoly; got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Net monopoly delta: bot gains 2 groups, partner gains 1 — should accept
    // (regression for the old hard veto that blocked this as "opponent gains monopoly")
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botAcceptsTradeWhenGainingTwoMonopoliesVsOpponentGainingOne() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns O1, O2 (2/3 ORANGE) and B1 (1/2 BROWN).
        // Human owns O3 (1/3 ORANGE) and B2 (1/2 BROWN).
        // Trade: bot gives B1 and receives O3.
        // Result: bot completes ORANGE (monopoly); human completes BROWN (monopoly).
        // netDelta = bot gains 1 monopoly, opponent gains 1 monopoly… but bot's ORANGE group
        // is stronger (streetStrengthScore=5 vs BROWN=2), so net delta is positive.
        // The old hard veto would have blocked this because "partner gains a monopoly".
        PropertyStateSnapshot o1 = new PropertyStateSnapshot("O1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot o2 = new PropertyStateSnapshot("O2", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot o3 = new PropertyStateSnapshot("O3", HUMAN_PLAYER, false, 0, 0);
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", HUMAN_PLAYER, false, 0, 0);

        // Human proposes: bot gives B1, bot receives O3
        TradeOfferState offer = new TradeOfferState(
                HUMAN_PLAYER, BOT_PLAYER,
                new TradeSelectionState(0, List.of("O3"), 0),  // offered to bot: O3
                new TradeSelectionState(0, List.of("B1"), 0)); // requested from bot: B1
        TradeState trade = new TradeState(
                "trade-two-monopolies", HUMAN_PLAYER, BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, HUMAN_PLAYER, true, BOT_PLAYER, HUMAN_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(o1, o2, b1, o3, b2));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof DeclineTradeCommand),
                "Bot must NOT decline a trade where it gains more monopoly progress than the opponent "
                + "(ORANGE > BROWN in value); got: " + recorder.commands);
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "Bot should accept when net monopoly delta is positive (gains ORANGE, concedes BROWN); "
                + "got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botDeclinesTradeWhenOpponentGainsMoreMonopolyProgressThanBot() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot owns B2 (1/2 BROWN). Human owns B1 (1/2 BROWN).
        // Human offers 50€ for B2 — this would give human the BROWN monopoly while bot gains nothing.
        // netDelta: bot loses BROWN progress, human gains BROWN monopoly → netDelta negative → decline.
        // This verifies the net-delta logic still rejects bad deals (replacing the old hard veto).
        PropertyStateSnapshot b2 = new PropertyStateSnapshot("B2", BOT_PLAYER, false, 0, 0);
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", HUMAN_PLAYER, false, 0, 0);

        TradeOfferState offer = new TradeOfferState(
                HUMAN_PLAYER, BOT_PLAYER,
                new TradeSelectionState(50, List.of(), 0),     // offered: 50€ cash
                new TradeSelectionState(0, List.of("B2"), 0)); // requested: B2 (completes human's BROWN)
        TradeState trade = new TradeState(
                "trade-net-delta-negative", HUMAN_PLAYER, BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, HUMAN_PLAYER, true, BOT_PLAYER, HUMAN_PLAYER, List.of());

        SessionState state = buildTwoPlayerState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                List.of(b1, b2));
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "Bot must not accept a trade where opponent gains a monopoly and bot gains none "
                + "(negative net monopoly delta); got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SessionState buildState(
            TurnState turn,
            PendingDecision decision,
            DebtStateModel debt,
            List<PropertyStateSnapshot> properties) {
        SeatState seat = new SeatState("seat-bot", 0, BOT_PLAYER, SeatKind.BOT,
                ControlMode.AUTOPLAY, "Bot", null, "#E63946");
        PlayerSnapshot player = new PlayerSnapshot(
                BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of());
        return new SessionState(
                SESSION_ID, 1L, SessionStatus.IN_PROGRESS,
                List.of(seat), List.of(player), properties,
                turn, decision, null, debt, null, null);
    }

    private static SessionState buildTwoPlayerState(TurnState turn, List<PropertyStateSnapshot> properties) {
        SeatState botSeat = new SeatState("seat-bot", 0, BOT_PLAYER, SeatKind.BOT,
                ControlMode.AUTOPLAY, "Bot", null, "#E63946");
        SeatState humanSeat = new SeatState("seat-human", 1, HUMAN_PLAYER, SeatKind.HUMAN,
                ControlMode.MANUAL, "Ihminen", null, "#2A9D8F");
        PlayerSnapshot botPlayer = new PlayerSnapshot(
                BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of());
        PlayerSnapshot humanPlayer = new PlayerSnapshot(
                HUMAN_PLAYER, "seat-human", "Ihminen", 500, 1, false, false, false, 0, 0, List.of());
        return new SessionState(
                SESSION_ID, 1L, SessionStatus.IN_PROGRESS,
                List.of(botSeat, humanSeat), List.of(botPlayer, humanPlayer), properties,
                turn, null, null, null, null, null);
    }

    /**
     * Records the first accepted command, then returns GAME_OVER so the bot stops.
     */
    private static final class OneShotRecorder implements SessionCommandPort {
        final List<SessionCommand> commands = new CopyOnWriteArrayList<>();
        final CountDownLatch firstCommand = new CountDownLatch(1);
        private volatile SessionState activeState;
        private volatile SessionState gameOverState;

        void initState(SessionState s) {
            this.activeState = s;
            this.gameOverState = s.toBuilder()
                    .status(SessionStatus.GAME_OVER)
                    .turn(null)
                    .activeDebt(null)
                    .auctionState(null)
                    .pendingDecision(null)
                    .build();
        }

        @Override
        public synchronized CommandResult handle(SessionCommand command) {
            commands.add(command);
            firstCommand.countDown();
            // Return GAME_OVER state on first accepted command → bot driver stops
            return new CommandResult(true, gameOverState, List.of(), List.of(), List.of());
        }

        @Override
        public SessionState currentState() {
            return gameOverState != null && !commands.isEmpty() ? gameOverState : activeState;
        }
    }
}
