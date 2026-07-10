package fi.monopoly.server.bot;

import fi.monopoly.domain.session.GameEventEntry;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.utils.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides when — and what — a bot says in the in-game chat. Bots occasionally comment on
 * notable game events (buying, building, receiving/paying rent, jail, bankruptcy, trades)
 * and drop the odd emoji reaction, so a table of bots feels alive rather than silent.
 *
 * <p>Design goals:
 * <ul>
 *   <li><b>Localised per viewer</b> — a message is emitted as a {@code (msgKey, variant)} pair,
 *       not literal text. Each client renders it in its own current language from a matching
 *       phrase table, so the same bot line reads Finnish for one viewer and English for another,
 *       and re-localises live when a viewer toggles language. The Finnish text is also carried as
 *       a fallback so an out-of-date client still shows something. Emoji reactions need no key.</li>
 *   <li><b>Variance</b> — every situation has a pool of phrasings; a random variant is picked so
 *       the same event never yields the same line twice in a row.</li>
 *   <li><b>Restraint</b> — a per-bot cooldown plus a global cooldown plus per-situation
 *       probabilities keep the chatter occasional, never spammy.</li>
 *   <li><b>Purity</b> — {@link #onNewEvents} is a plain function of (events, state, now); all
 *       mutable pacing state lives here and is only touched from the driver's single scheduler
 *       thread, so no synchronisation is needed.</li>
 * </ul>
 *
 * <p>The {@code msgKey} values and the per-key variant counts must stay in sync with the client's
 * {@code botChat} phrase tables (src/i18n/translations.ts). The client clamps the variant index
 * modulo its pool length, so a count mismatch degrades gracefully to a different phrasing rather
 * than a crash.</p>
 */
public final class BotChatter {

    /**
     * A queued bot utterance. For a MESSAGE, {@code msgKey}/{@code variant} localise it on the
     * client and {@code content} is the Finnish fallback; for a REACTION, {@code content} is the
     * emoji and {@code msgKey} is null.
     */
    public record ChatIntent(String botId, String kind, String content, String msgKey, int variant, long delayMs) {}

    // A bot won't chat again until this long after its last line — keeps any single bot from dominating.
    private static final long PER_BOT_COOLDOWN_MS = 9_000L;
    // No two bot lines closer together than this — staggers the table so reactions don't pile up.
    private static final long GLOBAL_COOLDOWN_MS = 2_500L;
    // A "big" rent that's worth complaining / gloating about.
    private static final int BIG_RENT = 90;

    private final RandomSource rng;
    private final Map<String, Long> lastChatAtByBot = new HashMap<>();
    // 0 = "no chat yet" (never negative, so the cooldown subtraction can't overflow a MIN_VALUE seed).
    private long lastAnyChatAt = 0L;
    private long lastSeenEventId = Long.MIN_VALUE;
    private boolean seeded = false;
    private boolean greeted = false;

    public BotChatter(RandomSource rng) {
        this.rng = rng;
    }

    // ── Message situation keys (must match client botChat table) + Finnish fallback pools ────
    private static final String K_BOUGHT = "boughtProperty";
    private static final String K_HOTEL = "builtHotel";
    private static final String K_RENT_GLOAT = "rentGloat";
    private static final String K_RENT_PAIN = "rentPain";
    private static final String K_JAIL = "jail";
    private static final String K_OPP_BANKRUPT = "opponentBankrupt";
    private static final String K_SELF_BANKRUPT = "selfBankrupt";
    private static final String K_TRADE = "tradeDone";
    private static final String K_GREETING = "greeting";
    private static final String K_PASS_GO = "passedGo";
    private static final String K_JAIL_OUT = "releasedFromJail";
    private static final String K_MORTGAGE = "mortgaged";
    private static final String K_TRADE_NO = "tradeDeclined";
    private static final String K_HOUSE = "builtHouse";
    private static final String K_REDEEM = "redeemed";
    private static final String K_BANTER = "banter";
    private static final String K_CARD = "drewCard";
    private static final String K_SELL_BUILDING = "soldBuilding";
    private static final String K_JAIL_TAUNT = "jailTaunt";
    private static final String K_PLAYER_LEFT = "playerLeft";

    private static final String[] BOUGHT = {
            "Tää tontti on nyt mun. 😎", "Hyvä sijoitus!", "Tästä tulee hyvä.",
            "Ostoslistaa lyhemmäks. 🏠", "Mun kokoelma kasvaa.", "Ei jätetä hyviä tontteja väliin.",
            "Tää täydentää väriryhmää. 🎯", "Halpa hinta tästä paikasta.",
            "Strateginen osto — estän muita. 😏", "Rautatiet tuottaa varmaa tuloa. 🚂",
    };
    private static final String[] BUILT_HOTEL = {
            "Hotelli pystyssä! 🏨", "Tervetuloa — vuokra ei oo halpa. 😏",
            "Nyt alkaa kilahtaa kassaan.", "Tästä tuli kallis kulma.",
            "Hotelli maksaa itsensä pian takaisin. 💰", "Täältä pesee jos tänne osutte. 💸",
    };
    private static final String[] RENT_GLOAT = {
            "Kiitos vuokrasta! 💰", "Kassa kasvaa. 😎", "Mukava lisä tilille.",
            "Aina yhtä kivaa periä vuokraa. 🤑", "Kohta ostan lisää tontteja näillä.",
            "Sijoitus tuottaa. 📈", "Passiivista tuloa parhaimmillaan.",
    };
    private static final String[] RENT_PAIN = {
            "Auts, kallis pysähdys. 😩", "No tuo sattui.", "Voi ei, melkein koko kassa meni.",
            "Kallista huseerausta. 💸", "Pitää alkaa myydä taloja...",
            "Tuo vei budjetin. 😰", "Väärä ruutu, väärä hetki.",
    };
    private static final String[] JAIL = {
            "No niin, vankilaan taas. 😅", "Nähdään parin kierroksen päästä.",
            "Ei taas...", "Vankila kutsuu. 🚔",
    };
    private static final String[] OPPONENT_BANKRUPT = {
            "Yksi vähemmän. 😎", "Peli on peli. 🤝", "Hyvää peliä!",
            "Sääli, mutta bisnes on bisnestä.",
    };
    private static final String[] SELF_BANKRUPT = {
            "Hyvää peliä kaikille! 💀", "No tähän se loppui — onnea muille!",
            "Konkurssi. Hyvin pelattu, muut.",
    };
    private static final String[] TRADE_DONE = {
            "Hyvä diili! 🤝", "Kaupat kunnossa.", "Molemmat voittaa — tai ainakin minä. 😏",
    };
    private static final String[] GREETING = {
            "Aloitetaan! 🎲", "Tsemppiä kaikille! 🍀", "Nyt pelataan. 😎", "Onnea matkaan, tarvitsette sitä. 😏",
    };
    private static final String[] PASS_GO = {
            "Kierros täynnä, +200! 💰", "Kiitos, pankki. 💵", "Taas 200 taskuun.",
    };
    private static final String[] JAIL_OUT = {
            "Vapaana taas! 🔓", "Takaisin peliin. 😎", "Ei minua kauaa pidellä.",
    };
    private static final String[] MORTGAGE = {
            "Pakko kiinnittää... 😬", "Tarvitaan käteistä nopeasti.", "Ei muuta vaihtoehtoa nyt.",
            "Kiinnitän tämän, tarvitsen rahaa ostoon.", "Väliaikainen kiinnitys — nostan pian takaisin.",
    };
    private static final String[] TRADE_NO = {
            "No ei sitten. 🤷", "Harmi, olisi ollut hyvä diili.", "Ehkä ensi kerralla. 🤔",
    };
    private static final String[] BUILT_HOUSE = {
            "Talo nostaa vuokraa mukavasti. 🏠", "Rakennan tästä vahvan.",
            "Pieni investointi, iso tuotto. 📈", "Talo kerrallaan kohti hotellia.",
    };
    private static final String[] REDEEM = {
            "Nostin kiinnityksen — kassa kestää taas. 💪", "Takaisin omistukseen ilman lainaa.",
            "Nyt tämä tuottaa taas täyttä vuokraa. 💰",
    };
    private static final String[] BANTER = {
            "Katsotaanpa mitä nopat antavat. 🎲", "Tuuria peliin!", "Nyt ei saa mennä vankilaan... 🤞",
            "Rahaa on, uskallan pelata. 💰", "Tämä kierros on mun. 😎",
    };
    private static final String[] DREW_CARD = {
            "Katsotaan mitä kortti sanoo... 🃏", "Sattumaa peliin!", "Toivotaan hyvää korttia. 🤞",
            "Kortit ratkaisee. 🎴",
    };
    private static final String[] SOLD_BUILDING = {
            "Pakko myydä rakennuksia... 😔", "Ikävä kyllä, talot lähtee.",
            "Tarvitsen käteistä, myyn taloja. 💸", "Askel taaksepäin, mutta pakko.",
    };
    private static final String[] JAIL_TAUNT = {
            "Hei, vankilaan siitä! 😂", "Nauttikaa selleistä. 😏", "Yksi vähemmän liikkeellä. 😎",
    };
    private static final String[] PLAYER_LEFT = {
            "No sepä harmi, pelaaja lähti.", "Yksi vähemmän pöydässä.", "Peli jatkuu ilman häntä. 🤷",
    };

    // ── Reaction pools (must be from the backend allow-list) ────────────────────────────────
    private static final String[] REACT_RENT = { "💰", "😎", "🔥" };
    private static final String[] REACT_BANKRUPT = { "😮", "😢", "😎" };
    private static final String[] REACT_HOTEL = { "😮", "🔥", "👏" };
    private static final String[] REACT_DOUBLES = { "🎲", "🍀", "😅" };
    private static final String[] REACT_TROUBLE = { "😎", "🔥", "🤔" };
    private static final String[] REACT_JAIL_TAUNT = { "😂", "😎", "👏" };
    private static final String[] REACT_OPP_BUY = { "🤔", "😎", "😮" };

    /**
     * Given the freshly-arrived events and current state, returns any chat lines bots should
     * post now. On the first call it silently seeds its event cursor to the current log tail so
     * a mid-game reconnect (which re-delivers up to 50 old events) doesn't trigger a chatter
     * burst.
     */
    public List<ChatIntent> onNewEvents(List<GameEventEntry> eventLog, SessionState state, Set<String> botIds, long nowMs) {
        List<ChatIntent> out = new ArrayList<>();
        if (eventLog == null || eventLog.isEmpty() || botIds.isEmpty()) return out;

        long maxId = Long.MIN_VALUE;
        for (GameEventEntry e : eventLog) maxId = Math.max(maxId, e.id());

        if (!seeded) {
            seeded = true;
            lastSeenEventId = maxId;
            return out;
        }
        if (maxId <= lastSeenEventId) return out;

        // Game-start greeting: once, from a random bot, shortly after the first real events flow.
        if (!greeted) {
            greeted = true;
            if (rng.nextDouble() < 0.80) {
                String greeter = pickEligibleBot(null, state, botIds, nowMs);
                if (greeter != null && nowMs - lastAnyChatAt >= GLOBAL_COOLDOWN_MS) {
                    int v = rng.nextInt(GREETING.length);
                    ChatIntent g = new ChatIntent(greeter, "MESSAGE", GREETING[v], K_GREETING, v, thinkDelay());
                    out.add(g);
                    lastAnyChatAt = nowMs + g.delayMs();
                    lastChatAtByBot.put(greeter, nowMs + g.delayMs());
                }
            }
        }

        for (GameEventEntry e : eventLog) {
            if (e.id() <= lastSeenEventId) continue;
            ChatIntent intent = considerEvent(e, state, botIds, nowMs);
            if (intent != null) {
                out.add(intent);
                // Reserve the slot so a burst of events in one snapshot doesn't all fire at once.
                lastAnyChatAt = nowMs + intent.delayMs();
                lastChatAtByBot.put(intent.botId(), nowMs + intent.delayMs());
            }
        }
        lastSeenEventId = maxId;
        return out;
    }

    private ChatIntent considerEvent(GameEventEntry e, SessionState state, Set<String> botIds, long nowMs) {
        if (nowMs - lastAnyChatAt < GLOBAL_COOLDOWN_MS) return null;
        String author = e.playerIds().isEmpty() ? null : e.playerIds().get(0);

        switch (e.type()) {
            case "BOUGHT_PROPERTY":
                if (isBot(author, botIds)) return maybeMessage(author, K_BOUGHT, BOUGHT, 0.30, nowMs);
                // A rival grabs a deed — a bot occasionally eyes it.
                return maybeReactionFromOther(author, REACT_OPP_BUY, 0.10, state, botIds, nowMs);
            case "BUILT_HOTEL":
                if (isBot(author, botIds)) return maybeMessage(author, K_HOTEL, BUILT_HOTEL, 0.55, nowMs);
                // A non-bot builds a hotel — a bot might react with awe.
                return maybeReactionFromOther(author, REACT_HOTEL, 0.20, state, botIds, nowMs);
            case "BUILT_HOUSE":
                // Frequent (each house), so keep it low — an occasional line on the investment.
                if (isBot(author, botIds)) return maybeMessage(author, K_HOUSE, BUILT_HOUSE, 0.14, nowMs);
                break;
            case "REDEEMED":
                if (isBot(author, botIds)) return maybeMessage(author, K_REDEEM, REDEEM, 0.28, nowMs);
                break;
            case "SOLD_HOUSE":
            case "SOLD_HOTEL":
                // Selling buildings back means cash trouble — a rueful money-decision line.
                if (isBot(author, botIds)) return maybeMessage(author, K_SELL_BUILDING, SOLD_BUILDING, 0.30, nowMs);
                break;
            case "DREW_CARD":
                if (isBot(author, botIds)) return maybeMessage(author, K_CARD, DREW_CARD, 0.14, nowMs);
                break;
            case "PLAYER_LEFT":
                // A player quit — a surviving bot remarks on it.
                return maybeMessageFromOther(author, K_PLAYER_LEFT, PLAYER_LEFT, REACT_TROUBLE, 0.50, state, botIds, nowMs);
            case "WENT_TO_JAIL":
                if (isBot(author, botIds)) return maybeMessage(author, K_JAIL, JAIL, 0.30, nowMs);
                // Someone else gets sent down — a bot enjoys it.
                return maybeMessageFromOther(author, K_JAIL_TAUNT, JAIL_TAUNT, REACT_JAIL_TAUNT, 0.25, state, botIds, nowMs);
            case "RELEASED_FROM_JAIL":
                if (isBot(author, botIds)) return maybeMessage(author, K_JAIL_OUT, JAIL_OUT, 0.28, nowMs);
                break;
            case "PASSED_GO":
                // Frequent event — keep the probability low so it stays a rare treat.
                if (isBot(author, botIds)) return maybeMessage(author, K_PASS_GO, PASS_GO, 0.15, nowMs);
                break;
            case "MORTGAGED":
                if (isBot(author, botIds)) return maybeMessage(author, K_MORTGAGE, MORTGAGE, 0.30, nowMs);
                // A non-bot mortgaging signals trouble — a bot might smell blood.
                return maybeReactionFromOther(author, REACT_TROUBLE, 0.14, state, botIds, nowMs);
            case "TRADE_DECLINED":
            case "TRADE_CANCELLED":
                // playerIds: [initiator, recipient]. The bot on either side can shrug it off.
                for (String pid : e.playerIds()) {
                    if (isBot(pid, botIds)) return maybeMessage(pid, K_TRADE_NO, TRADE_NO, 0.28, nowMs);
                }
                break;
            case "PAID_RENT": {
                // playerIds: [payer, creditor]. amount in data.
                String creditor = e.playerIds().size() > 1 ? e.playerIds().get(1) : null;
                int amount = parseInt(e.data().get("amount"));
                if (isBot(creditor, botIds)) {
                    // Bot received rent — gloat on a big one, otherwise a small chance of a 💰.
                    if (amount >= BIG_RENT) return maybeMessage(creditor, K_RENT_GLOAT, RENT_GLOAT, 0.45, nowMs);
                    return maybeReaction(creditor, REACT_RENT, 0.18, state, nowMs);
                }
                if (isBot(author, botIds) && amount >= BIG_RENT) {
                    return maybeMessage(author, K_RENT_PAIN, RENT_PAIN, 0.40, nowMs);
                }
                break;
            }
            case "WENT_BANKRUPT":
                if (isBot(author, botIds)) return maybeMessage(author, K_SELF_BANKRUPT, SELF_BANKRUPT, 0.85, nowMs);
                // A human (or another already-processed player) went bankrupt — a surviving bot reacts.
                return maybeMessageFromOther(author, K_OPP_BANKRUPT, OPPONENT_BANKRUPT, REACT_BANKRUPT, 0.55, state, botIds, nowMs);
            case "TRADE_ACCEPTED": {
                for (String pid : e.playerIds()) {
                    if (isBot(pid, botIds)) return maybeMessage(pid, K_TRADE, TRADE_DONE, 0.30, nowMs);
                }
                break;
            }
            case "DICE_ROLLED": {
                int d1 = parseInt(e.data().get("d1"));
                int d2 = parseInt(e.data().get("d2"));
                if (d1 > 0 && d1 == d2 && isBot(author, botIds)) {
                    return maybeReaction(author, REACT_DOUBLES, 0.15, state, nowMs);
                }
                // Occasional idle banter on the bot's own roll — every roll fires this, so keep
                // the probability very low; cooldowns thin it further.
                if (isBot(author, botIds)) return maybeMessage(author, K_BANTER, BANTER, 0.06, nowMs);
                break;
            }
            default:
                break;
        }
        return null;
    }

    // ── Emission helpers ────────────────────────────────────────────────────────────────────

    private ChatIntent maybeMessage(String botId, String msgKey, String[] pool, double probability, long nowMs) {
        if (!canChat(botId, nowMs) || rng.nextDouble() >= probability) return null;
        int variant = rng.nextInt(pool.length);
        return new ChatIntent(botId, "MESSAGE", pool[variant], msgKey, variant, thinkDelay());
    }

    private ChatIntent maybeReaction(String botId, String[] pool, double probability, SessionState state, long nowMs) {
        if (!isActive(botId, state) || !canChat(botId, nowMs) || rng.nextDouble() >= probability) return null;
        return new ChatIntent(botId, "REACTION", pick(pool), null, 0, thinkDelay());
    }

    /** A random surviving bot other than {@code excludeId} reacts with an emoji. */
    private ChatIntent maybeReactionFromOther(String excludeId, String[] pool, double probability, SessionState state, Set<String> botIds, long nowMs) {
        if (rng.nextDouble() >= probability) return null;
        String reactor = pickEligibleBot(excludeId, state, botIds, nowMs);
        if (reactor == null) return null;
        return new ChatIntent(reactor, "REACTION", pick(pool), null, 0, thinkDelay());
    }

    /** A random surviving bot other than {@code excludeId} comments — a message, or (30 %) an emoji reaction. */
    private ChatIntent maybeMessageFromOther(String excludeId, String msgKey, String[] msgPool, String[] reactPool, double probability, SessionState state, Set<String> botIds, long nowMs) {
        if (rng.nextDouble() >= probability) return null;
        String reactor = pickEligibleBot(excludeId, state, botIds, nowMs);
        if (reactor == null) return null;
        if (rng.nextDouble() < 0.30) {
            return new ChatIntent(reactor, "REACTION", pick(reactPool), null, 0, thinkDelay());
        }
        int variant = rng.nextInt(msgPool.length);
        return new ChatIntent(reactor, "MESSAGE", msgPool[variant], msgKey, variant, thinkDelay());
    }

    private String pickEligibleBot(String excludeId, SessionState state, Set<String> botIds, long nowMs) {
        List<String> eligible = new ArrayList<>();
        for (String id : botIds) {
            if (id.equals(excludeId)) continue;
            if (!isActive(id, state) || !canChat(id, nowMs)) continue;
            eligible.add(id);
        }
        if (eligible.isEmpty()) return null;
        return eligible.get(rng.nextInt(eligible.size()));
    }

    // ── Small utilities ─────────────────────────────────────────────────────────────────────

    private boolean canChat(String botId, long nowMs) {
        Long last = lastChatAtByBot.get(botId);
        return last == null || nowMs - last >= PER_BOT_COOLDOWN_MS;
    }

    /** A short, slightly random "typing" delay so lines land after the event's animation, staggered. */
    private long thinkDelay() {
        return 700L + rng.nextInt(1800);
    }

    private String pick(String[] pool) {
        return pool[rng.nextInt(pool.length)];
    }

    private static boolean isBot(String playerId, Set<String> botIds) {
        return playerId != null && botIds.contains(playerId);
    }

    private static boolean isActive(String playerId, SessionState state) {
        PlayerSnapshot p = find(playerId, state);
        return p != null && !p.bankrupt() && !p.eliminated();
    }

    private static PlayerSnapshot find(String playerId, SessionState state) {
        if (playerId == null) return null;
        for (PlayerSnapshot p : state.players()) if (p.playerId().equals(playerId)) return p;
        return null;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
