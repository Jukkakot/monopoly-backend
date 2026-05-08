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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        boolean hasMortgage = recorder.commands.stream()
                .anyMatch(c -> c instanceof MortgagePropertyForDebtCommand m && DEBT_ID.equals(m.debtId()));
        boolean hasSell = recorder.commands.stream().anyMatch(c -> c instanceof SellBuildingForDebtCommand);
        assertTrue(hasMortgage, "Expected MortgagePropertyForDebtCommand but got: " + recorder.commands);
        assertFalse(hasSell, "Should NOT dispatch SellBuildingForDebtCommand when no buildings exist");
    }

    // -------------------------------------------------------------------------
    // NORMAL mode: buys affordable property
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void normalBotBuysAffordableProperty() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        var payload = new PropertyPurchaseDecisionPayload("B1", "Bulevardi", 60);
        var decision = new PendingDecision("dec-1", DecisionType.PROPERTY_PURCHASE, BOT_PLAYER, List.of(), null, payload);

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_DECISION, false, false),
                decision, null, List.of());
        state = state.toBuilder()
                .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 200, 1, false, false, false, 0, 0, List.of())))
                .build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of());
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof BuyPropertyCommand),
                "NORMAL bot should buy affordable property, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // EASY mode: sometimes declines even when affordable
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void easyBotDeclinesAtLeastSomePurchasesOverManyTrials() throws InterruptedException {
        int buys = 0;
        int declines = 0;
        int trials = 20;

        for (int i = 0; i < trials; i++) {
            OneShotRecorder recorder = new OneShotRecorder();
            SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

            var payload = new PropertyPurchaseDecisionPayload("B1", "Bulevardi", 60);
            var decision = new PendingDecision("dec-" + i, DecisionType.PROPERTY_PURCHASE, BOT_PLAYER, List.of(), null, payload);

            SessionState state = buildState(
                    new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_DECISION, false, false),
                    decision, null, List.of());
            state = state.toBuilder()
                    .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of())))
                    .build();
            recorder.initState(state);

            PureDomainBotDriver d = PureDomainBotDriver.createAndRegisterIfNeeded(
                    publisher, state, Map.of(BOT_PLAYER, BotDifficulty.EASY));
            d.onSnapshotChanged(ClientSessionSnapshot.from(state, true));
            recorder.firstCommand.await(3, TimeUnit.SECONDS);
            d.stop();

            buys += (int) recorder.commands.stream().filter(c -> c instanceof BuyPropertyCommand).count();
            declines += (int) recorder.commands.stream().filter(c -> c instanceof DeclinePropertyCommand).count();
        }

        assertTrue(declines > 0, "EASY bot should decline at least some affordable purchases over " + trials + " trials");
        assertTrue(buys > 0, "EASY bot should still buy some affordable purchases over " + trials + " trials");
    }

    // -------------------------------------------------------------------------
    // EASY mode: passes some auction bids even when affordable
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void easyBotPassesSomeAuctionBidsOverManyTrials() throws InterruptedException {
        int bids = 0;
        int passes = 0;
        int trials = 20;

        for (int i = 0; i < trials; i++) {
            OneShotRecorder recorder = new OneShotRecorder();
            SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

            AuctionState auction = new AuctionState(
                    "auction-" + i, "B1", "other-player",
                    BOT_PLAYER, null,
                    10, 10,
                    Set.of(), List.of(BOT_PLAYER),
                    AuctionStatus.ACTIVE,
                    0, null
            );
            SessionState state = buildState(
                    new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_AUCTION, false, false),
                    null, null, List.of());
            state = state.toBuilder()
                    .auctionState(auction)
                    .players(List.of(new PlayerSnapshot(BOT_PLAYER, "seat-bot", "Bot", 500, 1, false, false, false, 0, 0, List.of())))
                    .build();
            recorder.initState(state);

            PureDomainBotDriver d = PureDomainBotDriver.createAndRegisterIfNeeded(
                    publisher, state, Map.of(BOT_PLAYER, BotDifficulty.EASY));
            d.onSnapshotChanged(ClientSessionSnapshot.from(state, true));
            recorder.firstCommand.await(3, TimeUnit.SECONDS);
            d.stop();

            bids += (int) recorder.commands.stream().filter(c -> c instanceof PlaceAuctionBidCommand).count();
            passes += (int) recorder.commands.stream().filter(c -> c instanceof PassAuctionCommand).count();
        }

        assertTrue(passes > 0, "EASY bot should pass at least some affordable bids over " + trials + " trials");
        assertTrue(bids > 0, "EASY bot should still bid some affordable auctions over " + trials + " trials");
    }

    // -------------------------------------------------------------------------
    // NORMAL mode: unmortgages when owns full group
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void normalBotUnmortgagesWhenOwnsFullGroupAndHasCash() throws InterruptedException {
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof ToggleMortgageCommand t && "B1".equals(t.propertyId())),
                "NORMAL bot should unmortgage B1 when it owns full BROWN group and has cash, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void normalBotDoesNotUnmortgageWithInsufficientReserve() throws InterruptedException {
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof ToggleMortgageCommand),
                "Bot should not unmortgage when reserve would be broken, got: " + recorder.commands);
        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof EndTurnCommand),
                "Bot should end turn when cannot unmortgage or build, got: " + recorder.commands);
    }

    // -------------------------------------------------------------------------
    // NORMAL mode: builds house when complete color group owned
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void normalBotBuildsHouseWhenCompleteColorGroupOwned() throws InterruptedException {
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof BuyBuildingRoundCommand),
                "NORMAL bot with complete color group should dispatch BuyBuildingRoundCommand, got: " + recorder.commands);
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof EndTurnCommand),
                "Bot should not end turn when it can build, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void normalBotEndsWhenInsufficientCashToBuild() throws InterruptedException {
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
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
    void normalBotAcceptsFavorableTradeOffer() throws InterruptedException {
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand a && "trade-1".equals(a.tradeId())),
                "NORMAL bot should accept when value received >= value given, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void normalBotDeclinesUnfavorableTradeOffer() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Bot receives 60€, gives 100€ → unfavorable → decline
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.NORMAL));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof DeclineTradeCommand d && "trade-1".equals(d.tradeId())),
                "NORMAL bot should decline when value received < value given, got: " + recorder.commands);
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void easyBotAlwaysDeclinesTrade() throws InterruptedException {
        OneShotRecorder recorder = new OneShotRecorder();
        SessionCommandPublisher publisher = new SessionCommandPublisher(recorder);

        // Even a favorable offer (receives 500€, gives nothing) is declined by EASY bot
        TradeOfferState offer = new TradeOfferState(
                "player-human", BOT_PLAYER,
                new TradeSelectionState(500, List.of(), 0),
                new TradeSelectionState(0, List.of(), 0));
        TradeState trade = new TradeState(
                "trade-1", "player-human", BOT_PLAYER, TradeStatus.SUBMITTED,
                offer, "player-human", true, BOT_PLAYER, "player-human", List.of());

        SessionState state = buildState(
                new TurnState(BOT_PLAYER, TurnPhase.WAITING_FOR_END_TURN, false, false),
                null, null, List.of());
        state = state.toBuilder().tradeState(trade).build();
        recorder.initState(state);

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.EASY));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c -> c instanceof DeclineTradeCommand d && "trade-1".equals(d.tradeId())),
                "EASY bot should always decline trades, got: " + recorder.commands);
        assertFalse(recorder.commands.stream().anyMatch(c -> c instanceof AcceptTradeCommand),
                "EASY bot should never accept trades, got: " + recorder.commands);
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.STRONG));
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.STRONG));
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

        driver = PureDomainBotDriver.createAndRegisterIfNeeded(publisher, state, Map.of(BOT_PLAYER, BotDifficulty.STRONG));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));

        assertTrue(recorder.firstCommand.await(3, TimeUnit.SECONDS), "Bot should dispatch a command within 3s");

        assertTrue(recorder.commands.stream().anyMatch(c ->
                        c instanceof SubmitTradeOfferCommand s && "trade-1".equals(s.tradeId())),
                "STRONG bot should submit when offer has property + money, got: " + recorder.commands);
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
