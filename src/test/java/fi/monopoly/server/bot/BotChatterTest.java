package fi.monopoly.server.bot;

import fi.monopoly.domain.session.GameEventEntry;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.SessionStatus;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BotChatter}: the situational bot-chat decision logic. Uses a
 * controllable RandomSource so probability gates are deterministic.
 */
class BotChatterTest {

    private static final String BOT = "bot-1";
    private static final String BOT2 = "bot-2";
    private static final String HUMAN = "human-1";
    private static final Set<String> BOTS = Set.of(BOT, BOT2);

    /** RandomSource whose gate value (nextDouble) is fixed, and whose nextInt always returns 0. */
    private static final class FixedRng implements RandomSource {
        private final double d;
        FixedRng(double d) { this.d = d; }
        @Override public int nextInt(int bound) { return 0; }
        @Override public double nextDouble() { return d; }
        @Override public long nextLong(long o, long b) { return o; }
        @Override public RandomSource derive(String salt) { return this; }
    }

    private static PlayerSnapshot player(String id, boolean bankrupt) {
        return new PlayerSnapshot(id, "seat-" + id, id, 500, 1, bankrupt, bankrupt, false, 0, 0, List.of());
    }

    private static PlayerSnapshot playerCash(String id, int cash) {
        return new PlayerSnapshot(id, "seat-" + id, id, cash, 1, false, false, false, 0, 0, List.of());
    }

    private static SessionState state(PlayerSnapshot... players) {
        return new SessionState("s", 1L, SessionStatus.IN_PROGRESS,
                List.of(), List.of(players), List.of(), null, null, null, null, null, null);
    }

    private static GameEventEntry ev(long id, String type, List<String> pids, Map<String, String> data) {
        return new GameEventEntry(id, id, type, pids, data);
    }

    private static SessionState twoBotsAndHuman() {
        return state(player(BOT, false), player(BOT2, false), player(HUMAN, false));
    }

    /** A chatter that has already seeded its event cursor (event ids 1–2) and consumed the
     *  one-time game-start greeting, so situational assertions on later events (id ≥ 3) aren't
     *  perturbed by it. */
    private static BotChatter seededChatter(double gate) {
        BotChatter c = new BotChatter(new FixedRng(gate));
        c.onNewEvents(List.of(ev(1, "DICE_ROLLED", List.of(BOT), Map.of("d1", "3", "d2", "4"))),
                twoBotsAndHuman(), BOTS, 0L);
        c.onNewEvents(List.of(ev(2, "PLAYER_MOVED", List.of(BOT), Map.of())),
                twoBotsAndHuman(), BOTS, 1_000L);
        return c;
    }

    @Test
    void firstCallSeedsAndEmitsNothingEvenForAnEligibleEvent() {
        BotChatter chatter = new BotChatter(new FixedRng(0.0)); // gate always passes
        var events = List.of(ev(5, "BOUGHT_PROPERTY", List.of(BOT), Map.of("property", "B1")));

        var intents = chatter.onNewEvents(events, twoBotsAndHuman(), BOTS, 1_000L);

        assertTrue(intents.isEmpty(), "the first snapshot seeds the cursor without replaying history");
    }

    @Test
    void botsGreetOnceWhenTheGameStarts() {
        BotChatter chatter = new BotChatter(new FixedRng(0.0));
        chatter.onNewEvents(List.of(ev(1, "DICE_ROLLED", List.of(BOT), Map.of("d1", "3", "d2", "4"))),
                twoBotsAndHuman(), BOTS, 0L);

        var first = chatter.onNewEvents(
                List.of(ev(2, "PASSED_GO", List.of(BOT), Map.of())), twoBotsAndHuman(), BOTS, 10_000L);
        assertEquals(1, first.size());
        assertEquals("greeting", first.get(0).msgKey(), "a bot greets at the start of the game");

        // Much later — no second greeting.
        var later = chatter.onNewEvents(
                List.of(ev(3, "BOUGHT_PROPERTY", List.of(BOT2), Map.of())), twoBotsAndHuman(), BOTS, 40_000L);
        for (var i : later) assertNotEquals("greeting", i.msgKey());
    }

    @Test
    void botCommentsOnItsOwnPurchaseAfterSeeding() {
        BotChatter chatter = seededChatter(0.0);

        var intents = chatter.onNewEvents(
                List.of(ev(3, "BOUGHT_PROPERTY", List.of(BOT), Map.of("property", "B1"))),
                twoBotsAndHuman(), BOTS, 25_000L);

        assertEquals(1, intents.size());
        assertEquals(BOT, intents.get(0).botId());
        assertEquals("MESSAGE", intents.get(0).kind());
        assertFalse(intents.get(0).content().isBlank());
    }

    @Test
    void aMessageCarriesALocalizationKeyAndVariantButAReactionDoesNot() {
        BotChatter chatter = seededChatter(0.0);
        var msg = chatter.onNewEvents(
                List.of(ev(3, "BOUGHT_PROPERTY", List.of(BOT), Map.of("property", "B1"))),
                twoBotsAndHuman(), BOTS, 25_000L);
        assertEquals(1, msg.size());
        assertEquals("boughtProperty", msg.get(0).msgKey(), "messages must be localizable by key");
        assertTrue(msg.get(0).variant() >= 0);

        // A doubles reaction is an emoji — no localization key needed.
        BotChatter r = seededChatter(0.0);
        var react = r.onNewEvents(
                List.of(ev(3, "DICE_ROLLED", List.of(BOT), Map.of("d1", "5", "d2", "5"))),
                twoBotsAndHuman(), BOTS, 25_000L);
        assertEquals(1, react.size());
        assertEquals("REACTION", react.get(0).kind());
        assertNull(react.get(0).msgKey());
    }

    @Test
    void lowProbabilityGateSuppressesChatter() {
        BotChatter chatter = seededChatter(0.99); // gate always fails

        var intents = chatter.onNewEvents(
                List.of(ev(3, "BOUGHT_PROPERTY", List.of(BOT), Map.of("property", "B1"))),
                twoBotsAndHuman(), BOTS, 25_000L);

        assertTrue(intents.isEmpty());
    }

    @Test
    void perBotCooldownBlocksBackToBackChatterFromTheSameBot() {
        BotChatter chatter = seededChatter(0.0);
        // First event lands a line for BOT.
        var first = chatter.onNewEvents(
                List.of(ev(3, "BUILT_HOTEL", List.of(BOT), Map.of("property", "B1"))),
                twoBotsAndHuman(), BOTS, 25_000L);
        assertEquals(1, first.size());

        // A second eligible event only 1s later — global + per-bot cooldown must suppress it.
        var second = chatter.onNewEvents(
                List.of(ev(4, "BUILT_HOTEL", List.of(BOT), Map.of("property", "B2"))),
                twoBotsAndHuman(), BOTS, 26_000L);
        assertTrue(second.isEmpty(), "cooldowns keep a single bot from chattering every event");
    }

    @Test
    void aSurvivingBotReactsToAnotherPlayersBankruptcy() {
        BotChatter chatter = seededChatter(0.0);

        var intents = chatter.onNewEvents(
                List.of(ev(3, "WENT_BANKRUPT", List.of(HUMAN), Map.of())),
                state(player(BOT, false), player(BOT2, false), player(HUMAN, true)),
                BOTS, 25_000L);

        assertEquals(1, intents.size());
        assertTrue(BOTS.contains(intents.get(0).botId()));
        assertFalse(intents.get(0).content().isBlank());
    }

    @Test
    void aBotTauntsWhenAnotherPlayerGoesToJail() {
        BotChatter chatter = seededChatter(0.0);

        var intents = chatter.onNewEvents(
                List.of(ev(3, "WENT_TO_JAIL", List.of(HUMAN), Map.of())), twoBotsAndHuman(), BOTS, 25_000L);

        assertEquals(1, intents.size());
        assertTrue(BOTS.contains(intents.get(0).botId()));
        assertFalse(intents.get(0).content().isBlank());
    }

    @Test
    void botCommentsWhenItSellsBuildingsForCash() {
        BotChatter chatter = seededChatter(0.0);

        var intents = chatter.onNewEvents(
                List.of(ev(3, "SOLD_HOUSE", List.of(BOT), Map.of("property", "B1"))),
                twoBotsAndHuman(), BOTS, 25_000L);

        assertEquals(1, intents.size());
        assertEquals(BOT, intents.get(0).botId());
        assertEquals("soldBuilding", intents.get(0).msgKey());
    }

    @Test
    void botGivesAReasoningLineWhenItRejectsAnOffer() {
        BotChatter chatter = seededChatter(0.0);

        // TRADE_DECLINED playerIds = [initiator, recipient]; the bot recipient is the rejecter.
        var intents = chatter.onNewEvents(
                List.of(ev(3, "TRADE_DECLINED", List.of(HUMAN, BOT), Map.of())),
                twoBotsAndHuman(), BOTS, 25_000L);

        assertEquals(1, intents.size());
        assertEquals(BOT, intents.get(0).botId());
        assertEquals("rejectOffer", intents.get(0).msgKey(), "the rejecter explains its reasoning");
    }

    @Test
    void idleBanterFitsTheBotsCashStanding() {
        // Very low cash → the "low cash" banter pool.
        BotChatter low = seededChatter(0.0);
        var lowIntents = low.onNewEvents(
                List.of(ev(3, "DICE_ROLLED", List.of(BOT), Map.of("d1", "2", "d2", "5"))),
                state(playerCash(BOT, 80), playerCash(BOT2, 900), playerCash(HUMAN, 900)), BOTS, 25_000L);
        assertEquals(1, lowIntents.size());
        assertEquals("banterLow", lowIntents.get(0).msgKey());

        // Most cash at the table → the "leading" banter pool.
        BotChatter lead = seededChatter(0.0);
        var leadIntents = lead.onNewEvents(
                List.of(ev(3, "DICE_ROLLED", List.of(BOT), Map.of("d1", "2", "d2", "5"))),
                state(playerCash(BOT, 2000), playerCash(BOT2, 500), playerCash(HUMAN, 500)), BOTS, 25_000L);
        assertEquals(1, leadIntents.size());
        assertEquals("banterLead", leadIntents.get(0).msgKey());
    }

    @Test
    void botGloatsWhenItReceivesBigRent() {
        BotChatter chatter = seededChatter(0.0);

        // Human pays 150 rent to BOT (playerIds = [payer, creditor]).
        var intents = chatter.onNewEvents(
                List.of(ev(3, "PAID_RENT", List.of(HUMAN, BOT), Map.of("amount", "150"))),
                twoBotsAndHuman(), BOTS, 25_000L);

        assertEquals(1, intents.size());
        assertEquals(BOT, intents.get(0).botId());
        assertEquals("MESSAGE", intents.get(0).kind());
    }
}
