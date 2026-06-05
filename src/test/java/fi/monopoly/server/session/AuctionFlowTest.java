package fi.monopoly.server.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.application.session.auction.AuctionCommandHandler;
import fi.monopoly.application.session.auction.DomainAuctionGateway;
import fi.monopoly.domain.session.BotDifficulty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end auction: human vs. STRONG bot, multiple bid rounds.
 *
 * <p>Exercises the full AuctionCommandHandler + DomainAuctionGateway +
 * PureDomainBotDriver stack. Verifies that the bot stays competitive while
 * the bid is below its ceiling and yields only once the human overbids it.</p>
 */
class AuctionFlowTest {

    private static final String SESSION_ID = "test-auction-session";
    private static final String HUMAN = "human";
    private static final String BOT = "bot";
    // O1 = Hermanni, face price €180. STRONG default auctionAggression=1.1 → ceiling=(int)(180*1.1)=198.
    private static final String PROPERTY = "O1";
    private static final int FACE_PRICE = 180;
    private static final int CEILING = (int) (FACE_PRICE * 1.1); // 198

    @BeforeAll
    static void noThinkDelay() {
        System.setProperty("monopoly.bot.think.delay.ms", "0");
    }

    /**
     * Full flow: human bids incrementally, bot competes, bot finally passes once
     * the minimum bid exceeds its ceiling.
     *
     * <p>Human strategy: always bid the current minimum (cheapest valid move).
     * Bot strategy: default STRONG. The auction naturally terminates when the
     * minimum bid exceeds CEILING and the bot passes.</p>
     */
    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botCompetesUntilCeilingThenYields() throws InterruptedException {
        InMemorySessionState store = buildStore();
        AuctionCommandHandler handler = buildHandler(store);
        startAuction(store, handler);

        String auctionId = store.get().auctionState().auctionId();
        List<String> log = new ArrayList<>();
        int botBidCount = 0;
        boolean botPassed = false;

        for (int round = 0; round < 40; round++) {
            AuctionState auction = store.get().auctionState();
            if (auction == null || auction.status() != AuctionStatus.ACTIVE) break;

            if (HUMAN.equals(auction.currentActorPlayerId())) {
                int minBid = auction.minimumNextBid();
                applyCmd(handler, new PlaceAuctionBidCommand(SESSION_ID, HUMAN, auctionId, minBid));
                log.add("human:bid:" + minBid);

            } else if (BOT.equals(auction.currentActorPlayerId())) {
                String botResult = driveBot(store, handler);
                log.add("bot:" + botResult);
                if (botResult.startsWith("bid:")) {
                    botBidCount++;
                } else {
                    botPassed = true;
                    break;
                }
            }
        }

        AuctionState final_ = store.get().auctionState();
        assertNotNull(final_, "Auction state should exist after bot passes (WON_PENDING_RESOLUTION)");

        assertTrue(botBidCount >= 1,
                "Bot should have bid at least once before yielding. Rounds: " + log);
        assertTrue(botPassed,
                "Bot should eventually pass when minimum exceeds ceiling=" + CEILING + ". Rounds: " + log);
        assertEquals(AuctionStatus.WON_PENDING_RESOLUTION, final_.status(),
                "Auction should be in WON_PENDING_RESOLUTION after bot passes. Rounds: " + log);
        assertEquals(HUMAN, final_.winningPlayerId(),
                "Human should win (always bid, bot passed). Rounds: " + log);

        // Verify the last bot pass happened at/above the ceiling
        int winningBid = final_.winningBid();
        assertTrue(winningBid >= CEILING,
                "Winning bid (" + winningBid + ") should be at or above bot ceiling " + CEILING + ". Rounds: " + log);
    }

    /**
     * Bot bids when human bids exactly the face price.
     *
     * <p>Regression for auctionAggression=1.0 bug: old ceiling equaled face price,
     * so any bid at face price pushed minimumNextBid above the ceiling and the bot
     * passed immediately. With aggression=1.1, ceiling=198 > minimumNextBid=190.</p>
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botBidsAfterHumanBidsFacePrice() throws InterruptedException {
        InMemorySessionState store = buildStore();
        AuctionCommandHandler handler = buildHandler(store);
        startAuction(store, handler);

        String auctionId = store.get().auctionState().auctionId();

        // Open with a small bid so we reach face-price in a controlled way
        applyCmd(handler, new PlaceAuctionBidCommand(SESSION_ID, HUMAN, auctionId, 10));
        String botOpenResponse = driveBot(store, handler);

        // Now human bids the face price (or minimum if bot already bid above it)
        int currentMin = store.get().auctionState().minimumNextBid();
        int humanFaceBid = Math.max(FACE_PRICE, currentMin); // ensure bid is valid
        applyCmd(handler, new PlaceAuctionBidCommand(SESSION_ID, HUMAN, auctionId, humanFaceBid));

        // Bot now faces minimumNextBid = humanFaceBid + 10.
        // If humanFaceBid=180 → minBid=190 ≤ ceiling=198 → bot MUST bid.
        // If humanFaceBid>180 (bot was aggressive) → still ≤ ceiling unless already above.
        int minForBot = store.get().auctionState().minimumNextBid();
        String botFaceResponse = driveBot(store, handler);

        assertEquals("bid", botOpenResponse.split(":")[0],
                "Bot should bid on opening (human bid €10)");

        if (minForBot <= CEILING) {
            assertEquals("bid", botFaceResponse.split(":")[0],
                    "Bot should still bid when minimumNextBid=" + minForBot + " ≤ ceiling=" + CEILING
                            + " (human bid face price=" + humanFaceBid + ")");
        }
        // If minForBot > CEILING the human already overbid before reaching face price — that's fine,
        // the test still verified the opening bid was placed correctly.
    }

    /**
     * Bot wins when human passes first.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void botWinsWhenHumanPassesAfterOpeningBid() throws InterruptedException {
        InMemorySessionState store = buildStore();
        AuctionCommandHandler handler = buildHandler(store);
        startAuction(store, handler);

        String auctionId = store.get().auctionState().auctionId();

        // Human opens, bot responds, then human passes
        applyCmd(handler, new PlaceAuctionBidCommand(SESSION_ID, HUMAN, auctionId, 10));
        String botAction = driveBot(store, handler);
        assertEquals("bid", botAction.split(":")[0], "Bot should bid when human opens at €10");

        applyCmd(handler, new PassAuctionCommand(SESSION_ID, HUMAN, auctionId));

        AuctionState final_ = store.get().auctionState();
        assertNotNull(final_);
        assertEquals(AuctionStatus.WON_PENDING_RESOLUTION, final_.status());
        assertEquals(BOT, final_.winningPlayerId(), "Bot should win when human passes after opening");
    }

    // -------------------------------------------------------------------------
    // Harness helpers
    // -------------------------------------------------------------------------

    /**
     * Starts the auction and sets turn phase to WAITING_FOR_AUCTION.
     * In production this phase change is done by DomainTurnActionGateway; here we do it manually.
     */
    private static void startAuction(InMemorySessionState store, AuctionCommandHandler handler) {
        handler.startAuction(HUMAN, PROPERTY, "Hermanni", null);
        store.update(s -> s.toBuilder()
                .turn(new TurnState(HUMAN, TurnPhase.WAITING_FOR_AUCTION, false, false))
                .build());
    }

    /** Applies a command and fails the test if it is rejected. */
    private static void applyCmd(AuctionCommandHandler handler, SessionCommand cmd) {
        CommandResult result = handler.handle(cmd);
        assertTrue(result.accepted(),
                "Command should be accepted: " + cmd.getClass().getSimpleName()
                        + " rejections=" + result.rejections());
    }

    /**
     * Drives PureDomainBotDriver for one auction step, applies the resulting command to
     * the AuctionCommandHandler, and returns "bid:N" or "pass".
     */
    private String driveBot(InMemorySessionState store, AuctionCommandHandler handler)
            throws InterruptedException {
        SessionState state = store.get();
        AuctionState auction = state.auctionState();
        if (auction == null || !BOT.equals(auction.currentActorPlayerId())) return "skip";

        List<SessionCommand> captured = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Routing port: capture the first command the bot sends, return GAME_OVER to stop it.
        SessionCommandPort port = new SessionCommandPort() {
            @Override
            public CommandResult handle(SessionCommand cmd) {
                if (latch.getCount() > 0) {
                    captured.add(cmd);
                    latch.countDown();
                }
                return new CommandResult(true,
                        store.get().toBuilder().status(SessionStatus.GAME_OVER).auctionState(null).build(),
                        List.of(), List.of(), List.of());
            }
            @Override
            public SessionState currentState() { return store.get(); }
        };

        PureDomainBotDriver driver = PureDomainBotDriver.createAndRegisterIfNeeded(
                new SessionCommandPublisher(port), state, Map.of(BOT, BotDifficulty.STRONG));
        driver.onSnapshotChanged(ClientSessionSnapshot.from(state, true));
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Bot should decide within 3s");
        driver.stop();

        if (captured.isEmpty()) return "none";
        SessionCommand cmd = captured.get(0);
        if (cmd instanceof PlaceAuctionBidCommand bid) {
            applyCmd(handler, bid);
            return "bid:" + bid.amount();
        }
        if (cmd instanceof PassAuctionCommand pass) {
            applyCmd(handler, pass);
            return "pass";
        }
        return "unknown:" + cmd.getClass().getSimpleName();
    }

    private static AuctionCommandHandler buildHandler(InMemorySessionState store) {
        return new AuctionCommandHandler(
                SESSION_ID,
                store::get,
                as -> store.update(s -> s.toBuilder().auctionState(as).build()),
                cs -> {},       // turnContinuationSetter
                cs -> {},       // turnContinuationResolver
                new DomainAuctionGateway(store),
                List::of,       // bankruptcyQueueGetter
                q -> {}         // bankruptcyQueueSetter
        );
    }

    private static InMemorySessionState buildStore() {
        SeatState humanSeat = new SeatState("seat-h", 0, HUMAN,
                SeatKind.HUMAN, ControlMode.MANUAL, "Human", null, "#2A9D8F");
        SeatState botSeat = new SeatState("seat-b", 1, BOT,
                SeatKind.BOT, ControlMode.AUTOPLAY, "Bot", null, "#E63946");
        PlayerSnapshot humanPlayer = new PlayerSnapshot(
                HUMAN, "seat-h", "Human", 500, 0, false, false, false, 0, 0, List.of());
        PlayerSnapshot botPlayer = new PlayerSnapshot(
                BOT, "seat-b", "Bot", 600, 0, false, false, false, 0, 0, List.of());
        PropertyStateSnapshot property = new PropertyStateSnapshot(PROPERTY, null, false, 0, 0);
        SessionState state = new SessionState(
                SESSION_ID, 1L, SessionStatus.IN_PROGRESS,
                List.of(humanSeat, botSeat),
                List.of(humanPlayer, botPlayer),
                List.of(property),
                new TurnState(HUMAN, TurnPhase.WAITING_FOR_END_TURN, false, true),
                null, null, null, null, null
        );
        return new InMemorySessionState(state);
    }
}
