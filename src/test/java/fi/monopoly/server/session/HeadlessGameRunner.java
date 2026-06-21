package fi.monopoly.server.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.session.TradeStatus;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.BotStrategy;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.PlaceType;
import fi.monopoly.utils.RandomSource;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Phase 2: headless, deterministic single-game runner driven by the {@link BotStrategy} interface.
 *
 * <p>Plays one game synchronously against the domain engine with no SSE, no delays, and no
 * bot driver threads. All randomness (dice, card decks, bot decisions) flows through a single
 * seeded {@link RandomSource}, so {@code play(config)} is byte-identical for the same seed.</p>
 *
 * <p>Contrast with the existing {@link BotTournament} which hardwires {@link StrongBotConfig}
 * decision logic. This runner accepts any {@link BotStrategy}, making it the comparison seam
 * for the utility-bot refactor.</p>
 */
public final class HeadlessGameRunner {

    private static final int MAX_CONSECUTIVE_REJECTS = 15;
    // Steps without any property-ownership or development change before we declare stalemate.
    // This catches genuine bot-logic loops (trade/debt infinite cycling) while allowing
    // the natural board cycling and small-rent periods that occur in slow Monopoly games.
    // At 20 000 maxSteps, this means a game must be structurally frozen for 25 %+ of its
    // budget before we consider it stuck.
    private static final int MAX_NO_STRUCTURAL_PROGRESS = 5_000;
    private static final List<String> COLOR_PALETTE = List.of(
            "#E63946", "#2A9D8F", "#E9C46A", "#264653", "#F4A261", "#8338EC");

    /** A strategy assigned to one seat. */
    public record SeatAssignment(BotStrategy strategy) {}

    /**
     * Configuration for one game.
     *
     * @param seed     drives all randomness (dice, cards, bot RNGs)
     * @param seats    one entry per player in seat order
     * @param maxSteps hard cap on command dispatch count; hitting it ⇒ {@link Outcome#STALEMATE}
     */
    public record MatchConfig(long seed, List<SeatAssignment> seats, int maxSteps) {}

    public enum Outcome { WIN, STALEMATE }

    /**
     * Result of one headless game.
     *
     * @param winnerSeat    index into {@link MatchConfig#seats()}; -1 = no winner
     * @param steps         command dispatch count
     * @param outcome       how the game ended
     * @param loopSuspected true when a state-signature repetition threshold was exceeded
     */
    public record GameResult(int winnerSeat, int steps, Outcome outcome, boolean loopSuspected) {}

    private HeadlessGameRunner() {}

    // -------------------------------------------------------------------------
    // Main API
    // -------------------------------------------------------------------------

    public static GameResult play(MatchConfig config) {
        int n = config.seats().size();
        if (n < 2 || n > 6) throw new IllegalArgumentException("2–6 players required");

        String sessionId = "harness-" + config.seed();
        RandomSource rng = RandomSource.seeded(config.seed());

        // Build initial state with seeded RNG (seat ordering dice use the same source)
        List<String> names  = IntStream.range(0, n).mapToObj(i -> "Bot" + i).toList();
        List<String> colors = COLOR_PALETTE.subList(0, n);
        SessionState initial = PureDomainSessionFactory.initialGameState(
                sessionId, names, colors, rng.toJavaRandom());
        List<String> playerIds = initial.players().stream()
                .map(PlayerSnapshot::playerId).toList();

        InMemorySessionState store = new InMemorySessionState(initial);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, store, rng);

        // Wire strategy + per-bot memory + per-bot RNG stream (derived from game seed)
        Map<String, BotStrategy> strategies = new HashMap<>();
        Map<String, BotMemory>   memories   = new HashMap<>();
        Map<String, RandomSource> botRngs   = new HashMap<>();
        for (int i = 0; i < Math.min(n, playerIds.size()); i++) {
            String pid = playerIds.get(i);
            strategies.put(pid, config.seats().get(i).strategy());
            memories.put(pid, BotMemory.empty());
            botRngs.put(pid, rng.derive(pid));
        }

        int steps = 0;
        int consecutiveRejects = 0;
        int noStructuralProgress = 0;

        while (steps < config.maxSteps()) {
            SessionState state = service.currentState();

            if (isTerminal(state)) {
                return new GameResult(findWinnerSeat(state, playerIds), steps, Outcome.WIN, false);
            }

            String actorId = resolveActorId(state);
            if (actorId == null) {
                if (++consecutiveRejects > MAX_CONSECUTIVE_REJECTS) break;
                continue;
            }

            BotStrategy strategy = strategies.get(actorId);
            if (strategy == null) {
                // Actor is not in our seat map (shouldn't happen); skip
                if (++consecutiveRejects > MAX_CONSECUTIVE_REJECTS) break;
                continue;
            }
            BotMemory   memory = memories.getOrDefault(actorId, BotMemory.empty());
            RandomSource botRng = botRngs.getOrDefault(actorId, rng);

            Intent intent = strategy.decide(state, actorId, memory, botRng);
            CommandResult result = applyIntent(service, state, intent, actorId);

            if (result.accepted()) {
                consecutiveRejects = 0;
                steps++;

                // Propagate trade outcomes to the proposer's memory.
                // In the real game, the bot driver receives decline/cancel events through
                // the SSE stream and updates memory accordingly.  In the headless runner,
                // each bot only sees its own memory, so we bridge the gap here:
                // when B declines A's trade, we record the decline in A's memory so
                // A's tryInitiateTrade() respects MAX_DECLINES_PER_PARTNER and stops looping.
                if (intent instanceof Intent.RespondToTrade resp
                        && resp.response() == Intent.TradeResponse.DECLINE
                        && state.tradeState() != null) {
                    String proposerId = state.tradeState().initiatorPlayerId();
                    BotMemory proposerMemory = memories.get(proposerId);
                    if (proposerMemory != null) proposerMemory.recordDecline(actorId);
                }

                // Structural-progress loop detection: count steps without any property
                // ownership or development change.  Board position and cash cycling are
                // intentionally ignored — they occur naturally and are not stuck loops.
                // A genuine stuck loop (trade/debt infinite cycle, bot logic bug) will
                // produce 1 000+ steps with no structural change.
                SessionState newState = service.currentState();
                if (hasPropertyProgress(state, newState)) {
                    noStructuralProgress = 0;
                } else if (newState.tradeState() == null && newState.auctionState() == null) {
                    if (++noStructuralProgress > MAX_NO_STRUCTURAL_PROGRESS) {
                        return new GameResult(-1, steps, Outcome.STALEMATE, true);
                    }
                }
            } else {
                if (++consecutiveRejects > MAX_CONSECUTIVE_REJECTS) break;
            }
        }

        // Hit step cap: net-worth tiebreaker
        return new GameResult(
                winnerByNetWorth(service.currentState(), playerIds), steps, Outcome.STALEMATE, false);
    }

    // -------------------------------------------------------------------------
    // Intent → Command translation  (same mapping as BotExecutor, sans publisher)
    // -------------------------------------------------------------------------

    private static CommandResult applyIntent(SessionApplicationService service,
                                             SessionState state,
                                             Intent intent, String actorId) {
        String sid = state.sessionId();
        return switch (intent) {
            case Intent.Roll()            -> service.handle(new RollDiceCommand(sid, actorId));
            case Intent.AcknowledgeCard() -> service.handle(new AcknowledgeCardCommand(sid, actorId));
            case Intent.EndTurn()         -> service.handle(new EndTurnCommand(sid, actorId));

            case Intent.BuildHouses(String propId) ->
                    service.handle(new BuyBuildingRoundCommand(sid, actorId, propId));

            case Intent.Unmortgage(String propId) ->
                    service.handle(new ToggleMortgageCommand(sid, actorId, propId));

            case Intent.MortgageProperty(String propId) ->
                    service.handle(new ToggleMortgageCommand(sid, actorId, propId));

            case Intent.BuyProperty(String decisionId, String propId) ->
                    service.handle(new BuyPropertyCommand(sid, actorId, decisionId, propId));

            case Intent.DeclineProperty(String decisionId, String propId) ->
                    service.handle(new DeclinePropertyCommand(sid, actorId, decisionId, propId));

            case Intent.ProposeTrade(String partnerId) ->
                    service.handle(new OpenTradeCommand(sid, actorId, partnerId));

            case Intent.RespondToTrade(Intent.TradeResponse resp, String tradeId) ->
                    switch (resp) {
                        case ACCEPT  -> service.handle(new AcceptTradeCommand(sid, actorId, tradeId));
                        case DECLINE -> service.handle(new DeclineTradeCommand(sid, actorId, tradeId));
                        case COUNTER -> service.handle(new CounterTradeCommand(sid, actorId, tradeId));
                    };

            case Intent.EditTrade(String tradeId, fi.monopoly.domain.session.TradeEditPatch patch) ->
                    service.handle(new EditTradeOfferCommand(sid, actorId, tradeId, patch));

            case Intent.SubmitTrade(String tradeId) ->
                    service.handle(new SubmitTradeOfferCommand(sid, actorId, tradeId));

            case Intent.CancelTrade(String tradeId) ->
                    service.handle(new CancelTradeCommand(sid, actorId, tradeId));

            case Intent.Bid(String auctionId, long amount) ->
                    service.handle(new PlaceAuctionBidCommand(sid, actorId, auctionId, (int) amount));

            case Intent.PassAuction(String auctionId) ->
                    service.handle(new PassAuctionCommand(sid, actorId, auctionId));

            case Intent.FinishAuction(String auctionId) ->
                    service.handle(new FinishAuctionResolutionCommand(sid, auctionId));

            case Intent.ResolveDebt(String debtId, fi.monopoly.domain.session.DebtAction action, String propId) ->
                    switch (action) {
                        case PAY_DEBT_NOW         -> service.handle(new PayDebtCommand(sid, actorId, debtId));
                        case SELL_BUILDING        -> service.handle(new SellBuildingForDebtCommand(sid, actorId, debtId, propId, 1));
                        case MORTGAGE_PROPERTY    -> service.handle(new MortgagePropertyForDebtCommand(sid, actorId, debtId, propId));
                        default -> noOp(state);
                    };

            case Intent.DeclareBankruptcy(String debtId) ->
                    service.handle(new DeclareBankruptcyCommand(sid, actorId, debtId));

            case Intent.NoOp() -> noOp(state);
        };
    }

    private static CommandResult noOp(SessionState state) {
        return new CommandResult(false, state, List.of(),
                List.of(new CommandRejection("no-op", null)), List.of());
    }

    // -------------------------------------------------------------------------
    // Actor resolution (mirrors PureDomainBotDriver.resolveActorId)
    // -------------------------------------------------------------------------

    static String resolveActorId(SessionState state) {
        if (state.activeDebt() != null) return state.activeDebt().debtorPlayerId();
        if (state.auctionState() != null) {
            AuctionState a = state.auctionState();
            if (a.status() == AuctionStatus.WON_PENDING_RESOLUTION) return a.winningPlayerId();
            return a.currentActorPlayerId();
        }
        if (state.tradeState() != null) {
            TradeState t = state.tradeState();
            if (t.decisionRequiredFromPlayerId() != null) return t.decisionRequiredFromPlayerId();
            TradeStatus ts = t.status();
            if ((ts == TradeStatus.EDITING || ts == TradeStatus.COUNTERED)
                    && t.editingPlayerId() != null) return t.editingPlayerId();
        }
        return state.turn() != null ? state.turn().activePlayerId() : null;
    }

    // -------------------------------------------------------------------------
    // Terminal / winner helpers
    // -------------------------------------------------------------------------

    private static boolean isTerminal(SessionState state) {
        if (state.status() == SessionStatus.GAME_OVER) return true;
        return state.players().stream().filter(p -> !p.bankrupt() && !p.eliminated()).count() <= 1;
    }

    private static int findWinnerSeat(SessionState state, List<String> playerIds) {
        for (int i = 0; i < playerIds.size(); i++) {
            String pid = playerIds.get(i);
            boolean alive = state.players().stream()
                    .filter(p -> pid.equals(p.playerId()))
                    .anyMatch(p -> !p.bankrupt() && !p.eliminated());
            if (alive) return i;
        }
        return -1;
    }

    private static int winnerByNetWorth(SessionState state, List<String> playerIds) {
        int bestSeat = -1, bestWorth = Integer.MIN_VALUE;
        for (int i = 0; i < playerIds.size(); i++) {
            String pid = playerIds.get(i);
            PlayerSnapshot p = state.players().stream()
                    .filter(x -> pid.equals(x.playerId()) && !x.bankrupt() && !x.eliminated())
                    .findFirst().orElse(null);
            if (p == null) continue;
            int worth = p.cash() + propertyEquity(state, pid);
            if (worth > bestWorth) { bestWorth = worth; bestSeat = i; }
        }
        return bestSeat;
    }

    private static int propertyEquity(SessionState state, String pid) {
        return state.properties().stream()
                .filter(p -> pid.equals(p.ownerPlayerId()))
                .mapToInt(p -> {
                    int price = SpotType.valueOf(p.propertyId()).getIntegerProperty("price");
                    if (p.mortgaged()) return price / 2;
                    if (SpotType.valueOf(p.propertyId()).streetType.placeType != PlaceType.STREET)
                        return price;
                    int hp = SpotType.valueOf(p.propertyId()).getIntegerProperty("housePrice");
                    int level = p.hotelCount() > 0 ? 5 : p.houseCount();
                    return price + level * (hp / 2);
                })
                .sum();
    }

    // -------------------------------------------------------------------------
    // Structural-progress check for loop detection
    // -------------------------------------------------------------------------

    /**
     * Returns true when at least one property changed ownership or development level
     * between {@code before} and {@code after}.  Board positions and cash are
     * intentionally ignored — they cycle naturally and are not reliable loop indicators.
     */
    private static boolean hasPropertyProgress(SessionState before, SessionState after) {
        List<PropertyStateSnapshot> bProps = before.properties();
        List<PropertyStateSnapshot> aProps = after.properties();
        if (bProps.size() != aProps.size()) return true; // properties added/removed
        // Properties list order is stable; iterate both lists in parallel.
        for (int i = 0; i < bProps.size(); i++) {
            PropertyStateSnapshot b = bProps.get(i);
            PropertyStateSnapshot a = aProps.get(i);
            if (!Objects.equals(b.ownerPlayerId(), a.ownerPlayerId())) return true;
            int bLevel = b.mortgaged() ? -1 : (b.hotelCount() > 0 ? 5 : b.houseCount());
            int aLevel = a.mortgaged() ? -1 : (a.hotelCount() > 0 ? 5 : a.houseCount());
            if (bLevel != aLevel) return true;
        }
        return false;
    }
}
