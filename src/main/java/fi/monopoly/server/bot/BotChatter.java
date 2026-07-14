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
 *   <li><b>Client owns the text</b> — a message is emitted as a bare {@code msgKey} (the situation),
 *       never literal text. Each client renders a line from its own phrase table in its current
 *       language, picking the variant deterministically from the CHAT event id — so every viewer
 *       sees the same line, localised, and it re-localises live on a language toggle. Adding or
 *       editing phrasings is therefore a client-only change. Emoji reactions need no key.</li>
 *   <li><b>Variance</b> — the client keeps a pool of phrasings per situation; the event id selects
 *       one, so the same event never yields the same line twice in a row.</li>
 *   <li><b>Restraint</b> — a per-bot cooldown (no shared/global one) plus per-situation
 *       probabilities keep each bot's chatter occasional without silencing the table.</li>
 *   <li><b>Purity</b> — {@link #onNewEvents} is a plain function of (events, state, now); all
 *       mutable pacing state lives here and is only touched from the driver's single scheduler
 *       thread, so no synchronisation is needed.</li>
 * </ul>
 *
 * <p>The {@code msgKey} values must exist in the client's {@code botChat} phrase tables
 * (src/i18n/translations.ts). The backend holds no message text at all — only the keys and the
 * decision logic (which situation, how often).</p>
 */
public final class BotChatter {

    /**
     * A queued bot utterance. For a MESSAGE the client owns the text entirely: only {@code msgKey}
     * (the situation) is sent, and each client renders a line from its own phrase table — picking
     * the variant deterministically from the CHAT event id so every viewer sees the same line. For
     * a REACTION, {@code content} is the emoji and {@code msgKey} is null. {@code content} is unused
     * for messages.
     *
     * <p>{@code targetId} is the player the line is aimed at, when the situation is inherently
     * directed (gloating over the player who paid you rent, taunting whoever just got jailed, etc.).
     * The client renders it as an "@Name" mention in that player's colour. Null for undirected
     * lines and all reactions.</p>
     */
    public record ChatIntent(String botId, String kind, String content, String msgKey, String targetId, long delayMs) {}

    // A bot won't chat again until this long after its own last line — keeps a single bot from
    // dominating. There is deliberately NO shared/global cooldown: each bot chatters on its own
    // schedule, so several can talk close together.
    private static final long PER_BOT_COOLDOWN_MS = 9_000L;
    // A "big" rent that's worth complaining / gloating about.
    private static final int BIG_RENT = 90;

    private final RandomSource rng;
    private final Map<String, Long> lastChatAtByBot = new HashMap<>();
    private long lastSeenEventId = Long.MIN_VALUE;
    private boolean seeded = false;
    private boolean greeted = false;

    public BotChatter(RandomSource rng) {
        this.rng = rng;
    }

    // ── Message situation keys (must match the client's botChat table). No text lives here. ──
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
    private static final String K_REJECT_OFFER = "rejectOffer";
    private static final String K_BANTER_LEAD = "banterLead";
    private static final String K_BANTER_TRAIL = "banterTrail";
    private static final String K_BANTER_LOW = "banterLow";
    private static final String K_SPECTATE = "spectateRent";
    private static final String K_TAX = "taxPaid";
    private static final String K_OPP_STRUGGLE = "opponentStruggle";
    private static final String K_CARD_GAIN = "cardGain";
    private static final String K_OPP_JAIL_OUT = "opponentJailOut";
    private static final String K_OPP_PASSED_GO = "opponentPassedGo";
    private static final String K_DOUBLES = "doubles";
    private static final String K_OPP_HOTEL = "opponentHotel";
    private static final String K_OPP_MORTGAGE = "opponentMortgage";
    private static final String K_OPP_REDEEM = "opponentRedeem";

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
                if (greeter != null) {
                    ChatIntent g = new ChatIntent(greeter, "MESSAGE", "", K_GREETING, null, thinkDelay());
                    out.add(g);
                    lastChatAtByBot.put(greeter, nowMs + g.delayMs());
                }
            }
        }

        for (GameEventEntry e : eventLog) {
            if (e.id() <= lastSeenEventId) continue;
            ChatIntent intent = considerEvent(e, state, botIds, nowMs);
            if (intent != null) {
                out.add(intent);
                lastChatAtByBot.put(intent.botId(), nowMs + intent.delayMs());
            }
        }
        lastSeenEventId = maxId;
        return out;
    }

    private ChatIntent considerEvent(GameEventEntry e, SessionState state, Set<String> botIds, long nowMs) {
        String author = e.playerIds().isEmpty() ? null : e.playerIds().get(0);

        switch (e.type()) {
            case "BOUGHT_PROPERTY":
                if (isBot(author, botIds)) return maybeMessage(author, K_BOUGHT, 0.30, nowMs);
                // A rival grabs a deed — a bot occasionally eyes it.
                return maybeReactionFromOther(author, REACT_OPP_BUY, 0.10, state, botIds, nowMs);
            case "BUILT_HOTEL":
                if (isBot(author, botIds)) return maybeMessage(author, K_HOTEL, 0.55, nowMs);
                // A non-bot builds a hotel — a bot notes it (awe / wariness).
                return maybeMessageFromOther(author, K_OPP_HOTEL, REACT_HOTEL, 0.22, state, botIds, nowMs);
            case "BUILT_HOUSE":
                // Frequent (each house), so keep it low — an occasional line on the investment.
                if (isBot(author, botIds)) return maybeMessage(author, K_HOUSE, 0.14, nowMs);
                // A rival is developing — a wary glance now and then.
                return maybeReactionFromOther(author, REACT_OPP_BUY, 0.07, state, botIds, nowMs);
            case "REDEEMED":
                if (isBot(author, botIds)) return maybeMessage(author, K_REDEEM, 0.28, nowMs);
                // A rival is recovering — a grudging note.
                return maybeMessageFromOther(author, K_OPP_REDEEM, REACT_TROUBLE, 0.10, state, botIds, nowMs);
            case "SOLD_HOUSE":
            case "SOLD_HOTEL":
                // Selling buildings back means cash trouble — a rueful money-decision line.
                if (isBot(author, botIds)) return maybeMessage(author, K_SELL_BUILDING, 0.30, nowMs);
                // A rival is liquidating — a bot smells blood. 🍿
                return maybeMessageFromOther(author, K_OPP_STRUGGLE, REACT_TROUBLE, 0.18, state, botIds, nowMs);
            case "MONEY_FLOW": {
                String reason = e.data().get("reason");
                // Tax payment (reason "vero") — from == payer. A bot grumbles about the taxman.
                if ("vero".equals(reason) && isBot(author, botIds)) {
                    return maybeMessage(author, K_TAX, 0.40, nowMs);
                }
                // Card payout (reason "kortti", bank → player): from is empty, to == receiving bot.
                String from = e.data().getOrDefault("from", "");
                String to = e.data().getOrDefault("to", "");
                if ("kortti".equals(reason) && from.isEmpty() && isBot(to, botIds)) {
                    return maybeMessage(to, K_CARD_GAIN, 0.30, nowMs);
                }
                break;
            }
            case "DREW_CARD":
                if (isBot(author, botIds)) return maybeMessage(author, K_CARD, 0.14, nowMs);
                break;
            case "PLAYER_LEFT":
                // A player quit — a surviving bot remarks on it.
                return maybeMessageFromOther(author, K_PLAYER_LEFT, REACT_TROUBLE, 0.50, state, botIds, nowMs);
            case "WENT_TO_JAIL":
                if (isBot(author, botIds)) return maybeMessage(author, K_JAIL, 0.30, nowMs);
                // Someone else gets sent down — a bot enjoys it, aimed straight at them.
                return maybeMessageFromOther(author, K_JAIL_TAUNT, REACT_JAIL_TAUNT, 0.25, state, botIds, nowMs, true);
            case "RELEASED_FROM_JAIL":
                if (isBot(author, botIds)) return maybeMessage(author, K_JAIL_OUT, 0.28, nowMs);
                // A rival is free again — a bot notes it now and then.
                return maybeMessageFromOther(author, K_OPP_JAIL_OUT, REACT_TROUBLE, 0.10, state, botIds, nowMs);
            case "PASSED_GO":
                // Frequent event — keep the probability low so it stays a rare treat.
                if (isBot(author, botIds)) return maybeMessage(author, K_PASS_GO, 0.15, nowMs);
                // A rival collected GO money — a bot's occasional envy. Very low prob (frequent event).
                return maybeMessageFromOther(author, K_OPP_PASSED_GO, REACT_TROUBLE, 0.05, state, botIds, nowMs);
            case "MORTGAGED":
                if (isBot(author, botIds)) return maybeMessage(author, K_MORTGAGE, 0.30, nowMs);
                // A non-bot mortgaging signals trouble — a bot smells blood.
                return maybeMessageFromOther(author, K_OPP_MORTGAGE, REACT_TROUBLE, 0.16, state, botIds, nowMs);
            case "TRADE_DECLINED":
            case "TRADE_CANCELLED": {
                // playerIds: [initiator, recipient]. The recipient is the one who was asked, so
                // a bot recipient is the one saying no → a reasoning line about why. A bot whose
                // own offer got turned down instead just shrugs it off.
                String initiator = e.playerIds().isEmpty() ? null : e.playerIds().get(0);
                String recipient = e.playerIds().size() > 1 ? e.playerIds().get(1) : null;
                if (isBot(recipient, botIds)) return maybeMessage(recipient, K_REJECT_OFFER, initiator, 0.35, nowMs);
                if (isBot(initiator, botIds)) return maybeMessage(initiator, K_TRADE_NO, 0.28, nowMs);
                break;
            }
            case "PAID_RENT": {
                // playerIds: [payer, creditor]. amount in data.
                String creditor = e.playerIds().size() > 1 ? e.playerIds().get(1) : null;
                int amount = parseInt(e.data().get("amount"));
                if (isBot(creditor, botIds)) {
                    // Bot received rent — gloat on a big one (aimed at the payer), otherwise a small chance of a 💰.
                    if (amount >= BIG_RENT) return maybeMessage(creditor, K_RENT_GLOAT, author, 0.45, nowMs);
                    return maybeReaction(creditor, REACT_RENT, 0.18, state, nowMs);
                }
                if (isBot(author, botIds) && amount >= BIG_RENT) {
                    return maybeMessage(author, K_RENT_PAIN, 0.40, nowMs);
                }
                // Neither party is a bot — on a big one, a bystander bot enjoys the drama. 🍿
                if (amount >= BIG_RENT && !isBot(author, botIds)) {
                    return maybeMessageFromOther(null, K_SPECTATE, REACT_RENT, 0.22, state, botIds, nowMs);
                }
                break;
            }
            case "WENT_BANKRUPT":
                if (isBot(author, botIds)) return maybeMessage(author, K_SELF_BANKRUPT, 0.85, nowMs);
                // A human (or another already-processed player) went bankrupt — a surviving bot reacts, at them.
                return maybeMessageFromOther(author, K_OPP_BANKRUPT, REACT_BANKRUPT, 0.55, state, botIds, nowMs, true);
            case "TRADE_ACCEPTED": {
                for (String pid : e.playerIds()) {
                    if (isBot(pid, botIds)) return maybeMessage(pid, K_TRADE, 0.30, nowMs);
                }
                break;
            }
            case "DICE_ROLLED": {
                int d1 = parseInt(e.data().get("d1"));
                int d2 = parseInt(e.data().get("d2"));
                if (d1 > 0 && d1 == d2 && isBot(author, botIds)) {
                    // Half the time a spoken "doubles!" line, otherwise the emoji reaction.
                    if (rng.nextInt(2) == 0) return maybeMessage(author, K_DOUBLES, 0.20, nowMs);
                    return maybeReaction(author, REACT_DOUBLES, 0.15, state, nowMs);
                }
                // Occasional idle banter on the bot's own roll, tuned to how the bot is doing so a
                // random line still fits the moment. Every roll fires this, so keep the probability
                // low; cooldowns thin it further.
                if (isBot(author, botIds)) {
                    String key = switch (cashStanding(author, state)) {
                        case LOW -> K_BANTER_LOW;
                        case LEAD -> K_BANTER_LEAD;
                        case TRAIL -> K_BANTER_TRAIL;
                        default -> K_BANTER;
                    };
                    return maybeMessage(author, key, 0.09, nowMs);
                }
                break;
            }
            default:
                break;
        }
        return null;
    }

    // ── Emission helpers ────────────────────────────────────────────────────────────────────

    private ChatIntent maybeMessage(String botId, String msgKey, double probability, long nowMs) {
        return maybeMessage(botId, msgKey, null, probability, nowMs);
    }

    /** A self-authored message aimed at {@code targetId} (rendered as an @mention). */
    private ChatIntent maybeMessage(String botId, String msgKey, String targetId, double probability, long nowMs) {
        if (!canChat(botId, nowMs) || rng.nextDouble() >= probability) return null;
        // Never aim a line at the speaker themselves.
        String target = (targetId != null && !targetId.equals(botId)) ? targetId : null;
        return new ChatIntent(botId, "MESSAGE", "", msgKey, target, thinkDelay());
    }

    private ChatIntent maybeReaction(String botId, String[] pool, double probability, SessionState state, long nowMs) {
        if (!isActive(botId, state) || !canChat(botId, nowMs) || rng.nextDouble() >= probability) return null;
        return new ChatIntent(botId, "REACTION", pick(pool), null, null, thinkDelay());
    }

    /** A random surviving bot other than {@code excludeId} reacts with an emoji. */
    private ChatIntent maybeReactionFromOther(String excludeId, String[] pool, double probability, SessionState state, Set<String> botIds, long nowMs) {
        if (rng.nextDouble() >= probability) return null;
        String reactor = pickEligibleBot(excludeId, state, botIds, nowMs);
        if (reactor == null) return null;
        return new ChatIntent(reactor, "REACTION", pick(pool), null, null, thinkDelay());
    }

    private ChatIntent maybeMessageFromOther(String excludeId, String msgKey, String[] reactPool, double probability, SessionState state, Set<String> botIds, long nowMs) {
        return maybeMessageFromOther(excludeId, msgKey, reactPool, probability, state, botIds, nowMs, false);
    }

    /** A random surviving bot other than {@code excludeId} comments — a message, or (30 %) an emoji reaction.
     *  When {@code directed} the message is aimed at {@code excludeId} (the player being commented on),
     *  rendered client-side as an @mention. Reactions are never directed. */
    private ChatIntent maybeMessageFromOther(String excludeId, String msgKey, String[] reactPool, double probability, SessionState state, Set<String> botIds, long nowMs, boolean directed) {
        if (rng.nextDouble() >= probability) return null;
        String reactor = pickEligibleBot(excludeId, state, botIds, nowMs);
        if (reactor == null) return null;
        if (rng.nextDouble() < 0.30) {
            return new ChatIntent(reactor, "REACTION", pick(reactPool), null, null, thinkDelay());
        }
        String target = (directed && excludeId != null && !excludeId.equals(reactor)) ? excludeId : null;
        return new ChatIntent(reactor, "MESSAGE", "", msgKey, target, thinkDelay());
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

    private enum Standing { LEAD, TRAIL, LOW, MID }

    /** Classifies a bot's position by cash: worryingly low, clear leader, clear trailer, or mid-pack.
     *  Cash is a cheap proxy for standing that avoids computing full net worth every roll. */
    private static Standing cashStanding(String botId, SessionState state) {
        PlayerSnapshot me = find(botId, state);
        if (me == null) return Standing.MID;
        if (me.cash() < 150) return Standing.LOW;
        int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE, active = 0;
        for (PlayerSnapshot p : state.players()) {
            if (p.bankrupt() || p.eliminated()) continue;
            active++;
            max = Math.max(max, p.cash());
            min = Math.min(min, p.cash());
        }
        if (active < 2) return Standing.MID;
        if (me.cash() == max) return Standing.LEAD;
        if (me.cash() == min) return Standing.TRAIL;
        return Standing.MID;
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
