package fi.monopoly.server.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;

import java.util.*;

/**
 * Headless bot tournament engine.
 *
 * <p>Drives games synchronously using {@link SessionApplicationService} directly — no bot driver,
 * no delays. Each player's decisions are governed by their {@link StrongBotConfig}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * List<BotTournament.Entry> configs = List.of(
 *     new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
 *     new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
 *     new BotTournament.Entry("cautious",   StrongBotConfig.cautious())
 * );
 * List<BotTournament.Standing> standings = BotTournament.roundRobin(configs, 50, 42L);
 * BotTournament.printStandings(standings);
 * }</pre>
 */
public final class BotTournament {

    private static final int MAX_STEPS = 20_000;
    private static final int MAX_CONSECUTIVE_REJECTS = 10;
    private static final String[] COLOR_PALETTE = {
            "#E63946","#2A9D8F","#E9C46A","#264653","#F4A261","#8338EC"
    };

    public record Entry(String name, StrongBotConfig config) {}

    /** Result of a single game: winner seat index (-1 = genuine stall) and step count. */
    public record GameResult(int winner, int steps, boolean byBankruptcy) {}

    /** Internal: game task for parallel execution */
    private record GameTask(int i, int j, long seed, boolean swapped) {}

    /** Internal: game result with metadata */
    private record GameResultTask(int i, int j, GameResult result) {}

    public record Standing(
            String name,
            int wins,
            int losses,
            int draws,
            int games,
            long totalSteps
    ) {
        public double winRate()  { return games > 0 ? (double) wins / games : 0; }
        public double avgSteps() { return games > 0 ? (double) totalSteps / games : 0; }

        @Override public String toString() {
            return String.format("%-22s  W=%3d  L=%3d  D=%3d  (%d games)  win%%=%.1f  avgSteps=%.0f",
                    name, wins, losses, draws, games, winRate() * 100, avgSteps());
        }
    }

    private BotTournament() {}

    // -------------------------------------------------------------------------
    // Round-robin tournament
    // -------------------------------------------------------------------------

    /**
     * Runs a full round-robin tournament: every unique pair of configs plays
     * {@code gamesPerPair} games. Returns standings sorted by win rate descending.
     *
     * @param configs     named configs to compete
     * @param gamesPerPair number of games each pair plays
     * @param seedBase    base random seed (each game uses seedBase + gameIndex)
     */
    public static List<Standing> roundRobin(List<Entry> configs, int gamesPerPair, long seedBase) {
        int n = configs.size();
        int[]  wins   = new int[n];
        int[]  losses = new int[n];
        int[]  draws  = new int[n];
        int[]  games  = new int[n];
        long[] steps  = new long[n];

        // Build list of all game tasks
        List<GameTask> allGames = new ArrayList<>();
        int gameIndex = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int g = 0; g < gamesPerPair; g++) {
                    long seed = seedBase + gameIndex++;
                    boolean swapped = (g % 2 == 1);
                    allGames.add(new GameTask(i, j, seed, swapped));
                }
            }
        }

        // Execute all games in parallel
        List<GameResultTask> results = allGames.parallelStream()
            .map(task -> {
                int playerA = task.swapped() ? task.j() : task.i();
                int playerB = task.swapped() ? task.i() : task.j();
                List<StrongBotConfig> cfgs = List.of(configs.get(playerA).config(), configs.get(playerB).config());
                GameResult result = runGameDetailed(cfgs, task.seed());
                return new GameResultTask(task.i(), task.j(), result);
            })
            .toList();

        // Aggregate results (single-threaded, safe because no concurrent modification)
        for (GameResultTask task : results) {
            int i = task.i();
            int j = task.j();
            GameResult result = task.result();
            
            games[i]++;   games[j]++;
            steps[i] += result.steps(); steps[j] += result.steps();
            
            if (result.winner() == 0) {
                wins[i]++; losses[j]++;
            } else if (result.winner() == 1) {
                wins[j]++; losses[i]++;
            } else {
                draws[i]++; draws[j]++;
            }
        }

        List<Standing> standings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            standings.add(new Standing(configs.get(i).name(), wins[i], losses[i], draws[i], games[i], steps[i]));
        }
        standings.sort(Comparator.comparingDouble(Standing::winRate).reversed());
        return standings;
    }

    /**
     * Runs a full round-robin with N players per game (all configs in one game).
     * Each set of N configs plays {@code gamesPerGroup} games together.
     * Works best when all configs should compete simultaneously (e.g. 4-player games).
     */
    public static List<Standing> freeForAll(List<Entry> configs, int gamesPerGroup, long seedBase) {
        int n = configs.size();
        int[]  wins   = new int[n];
        int[]  losses = new int[n];
        int[]  draws  = new int[n];
        int[]  games  = new int[n];
        long[] steps  = new long[n];

        List<StrongBotConfig> cfgs = configs.stream().map(Entry::config).toList();

        // Build list of all free-for-all game tasks
        List<Integer> gameIndices = new ArrayList<>();
        for (int g = 0; g < gamesPerGroup; g++) gameIndices.add(g);

        // Execute all games in parallel
        List<GameResultTask> results = gameIndices.parallelStream()
            .map(g -> {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < n; i++) order.add(i);
                Collections.rotate(order, g);
                
                List<StrongBotConfig> seatedCfgs = order.stream().map(cfgs::get).toList();
                GameResult result = runGameDetailed(seatedCfgs, seedBase + g);
                // For freeForAll, encode the order rotation in the result's indices
                // We'll handle this in aggregation
                return new GameResultTask(g, -1, result);  // g used as groupIndex, -1 as placeholder
            })
            .toList();

        // Aggregate results (need to recompute orders for each game)
        for (int g = 0; g < gamesPerGroup; g++) {
            GameResult result = results.get(g).result();
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) order.add(i);
            Collections.rotate(order, g);
            
            for (int i = 0; i < n; i++) {
                games[order.get(i)]++;
                steps[order.get(i)] += result.steps();
            }
            int winnerSeat = result.winner();
            if (winnerSeat >= 0 && winnerSeat < n) {
                int winner = order.get(winnerSeat);
                wins[winner]++;
                for (int i = 0; i < n; i++) if (i != winner) losses[order.get(i)]++;
            } else {
                for (int i = 0; i < n; i++) draws[i]++;
            }
        }

        List<Standing> standings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            standings.add(new Standing(configs.get(i).name(), wins[i], losses[i], draws[i], games[i], steps[i]));
        }
        standings.sort(Comparator.comparingDouble(Standing::winRate).reversed());
        return standings;
    }

    // -------------------------------------------------------------------------
    // Evolutionary search
    // -------------------------------------------------------------------------

    /**
     * Runs a sampled tournament where each game picks {@code playerCount} configs
     * at random from the population, plays the game, and awards the winner.
     * Runs {@code totalGames} games total. Used for 3–6 player optimization where
     * full round-robin is too expensive.
     */
    public static List<Standing> sampledTournament(List<Entry> configs, int totalGames,
                                                    int playerCount, long seedBase) {
        int n = configs.size();
        int[]  wins   = new int[n];
        int[]  losses = new int[n];
        int[]  draws  = new int[n];
        int[]  games  = new int[n];
        long[] steps  = new long[n];

        Random rng = new Random(seedBase);
        for (int g = 0; g < totalGames; g++) {
            // Sample playerCount distinct configs
            List<Integer> indices = new ArrayList<>();
            List<Integer> pool = new ArrayList<>();
            for (int i = 0; i < n; i++) pool.add(i);
            Collections.shuffle(pool, rng);
            for (int k = 0; k < Math.min(playerCount, n); k++) indices.add(pool.get(k));

            List<StrongBotConfig> cfgs = indices.stream().map(i -> configs.get(i).config()).toList();
            GameResult result = runGameDetailed(cfgs, seedBase + g);

            for (int k = 0; k < indices.size(); k++) {
                int idx = indices.get(k);
                games[idx]++;
                steps[idx] += result.steps();
                if (result.winner() == k) wins[idx]++;
                else if (result.winner() >= 0) losses[idx]++;
                else draws[idx]++;
            }
        }

        List<Standing> standings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            standings.add(new Standing(configs.get(i).name(), wins[i], losses[i], draws[i], games[i], steps[i]));
        }
        standings.sort(Comparator.comparingDouble(Standing::winRate).reversed());
        return standings;
    }

    /**
     * Evolutionary search for optimal bot config.
     *
     * <p>Uses {@code playerCount} to choose the tournament format:
     * <ul>
     *   <li>2 → full pair round-robin (most precise, recommended for 2–3 player tuning)</li>
     *   <li>3–6 → sampled N-player groups (approximation, used for 4–6 player tuning)</li>
     * </ul>
     *
     * @param populationSize  configs per generation (≥4)
     * @param generations     evolutionary cycles
     * @param gamesPerGen     games per generation (pairs for playerCount=2, total for others)
     * @param playerCount     players per simulated game (2–6)
     * @param baseConfigs     initial seed configs (defaults/aggressive/cautious added automatically)
     * @param seedBase        base random seed
     * @param verbose         print per-generation standings
     */
    public static Entry evolve(int populationSize, int generations, int gamesPerGen,
                               int playerCount, List<Entry> baseConfigs,
                               long seedBase, boolean verbose) {
        Random rng = new Random(seedBase);
        List<Entry> population = new ArrayList<>(baseConfigs);
        // Ensure standard presets are included
        if (population.stream().noneMatch(e -> "defaults".equals(e.name())))
            population.add(new Entry("defaults", StrongBotConfig.defaults()));
        if (population.stream().noneMatch(e -> "aggressive".equals(e.name())))
            population.add(new Entry("aggressive", StrongBotConfig.aggressive()));
        // Fill remainder with mutations of the first base config
        StrongBotConfig seedConfig = baseConfigs.isEmpty()
                ? StrongBotConfig.defaults() : baseConfigs.get(0).config();
        while (population.size() < populationSize) {
            StrongBotConfig base = seedConfig;
            int mutations = 3 + rng.nextInt(4);
            for (int m = 0; m < mutations; m++) base = base.mutate(rng, 0.30);
            population.add(new Entry("rnd-" + population.size(), base));
        }

        Entry bestEver = population.get(0);
        double bestWinRate = 0;

        for (int gen = 0; gen < generations; gen++) {
            long genSeed = seedBase + (long) gen * 100_000;
            List<Standing> standings = playerCount == 2
                    ? roundRobin(population, gamesPerGen, genSeed)
                    : sampledTournament(population, gamesPerGen, playerCount, genSeed);

            if (verbose) {
                System.out.printf("=== Generation %d (playerCount=%d) ===%n", gen + 1, playerCount);
                standings.subList(0, Math.min(6, standings.size()))
                         .forEach(s -> System.out.println("  " + s));
            }

            Standing top = standings.get(0);
            Entry topEntry = population.stream()
                    .filter(e -> e.name().equals(top.name()))
                    .findFirst().orElse(population.get(0));
            if (top.winRate() > bestWinRate) { bestWinRate = top.winRate(); bestEver = topEntry; }

            int survivors = populationSize / 2;
            List<Entry> nextGen = new ArrayList<>();
            for (Standing s : standings.subList(0, survivors)) {
                population.stream().filter(e -> e.name().equals(s.name()))
                        .findFirst().ifPresent(nextGen::add);
            }
            int child = 0;
            while (nextGen.size() < populationSize) {
                Entry p1 = nextGen.get(rng.nextInt(survivors));
                Entry p2 = nextGen.get(rng.nextInt(survivors));
                StrongBotConfig childCfg = p1.config().crossover(p2.config(), rng).mutate(rng, 0.15);
                nextGen.add(new Entry("child-" + gen + "-" + child++, childCfg));
            }
            population = nextGen;
        }

        if (verbose) System.out.printf("%nBest: %s  win=%.1f%%%n", bestEver.name(), bestWinRate * 100);
        return bestEver;
    }

    /** Convenience overload for 2-player evolution with standard seed configs. */
    public static Entry evolve(int populationSize, int generations, int gamesPerPair,
                               long seedBase, boolean verbose) {
        return evolve(populationSize, generations, gamesPerPair, 2,
                List.of(new Entry("defaults", StrongBotConfig.defaults()),
                        new Entry("aggressive", StrongBotConfig.aggressive()),
                        new Entry("cautious", StrongBotConfig.cautious())),
                seedBase, verbose);
    }

    // -------------------------------------------------------------------------
    // Single-game engine
    // -------------------------------------------------------------------------

    /** Runs one headless game; returns winner seat or -1. */
    public static int runGame(List<StrongBotConfig> configs, long seed) {
        return runGameDetailed(configs, seed).winner();
    }

    /**
     * Runs one complete headless game with the given configs in seat order.
     * Returns full {@link GameResult} including step count and whether the game
     * ended by bankruptcy (vs net-worth tiebreaker).
     */
    public static GameResult runGameDetailed(List<StrongBotConfig> configs, long seed) {
        int n = configs.size();
        List<String> names  = new ArrayList<>();
        List<String> colors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            names.add("Bot" + i);
            colors.add(COLOR_PALETTE[i % COLOR_PALETTE.length]);
        }

        SessionState initial = PureDomainSessionFactory.initialGameState(
                "tournament-" + seed, names, colors, new Random(seed));
        List<String> playerIds = initial.players().stream()
                .map(PlayerSnapshot::playerId).toList();
        Map<String, StrongBotConfig> cfgByPlayer = new HashMap<>();
        for (int i = 0; i < Math.min(n, playerIds.size()); i++) {
            cfgByPlayer.put(playerIds.get(i), configs.get(i));
        }

        SessionApplicationService service = PureDomainSessionFactory.create("tournament-" + seed, initial);

        int steps = 0;
        int consecutiveRejects = 0;

        while (steps++ < MAX_STEPS) {
            SessionState state = service.currentState();

            if (state.status() == SessionStatus.GAME_OVER) {
                return new GameResult(findWinnerSeat(state, playerIds), steps, true);
            }
            long active = state.players().stream().filter(p -> !p.bankrupt() && !p.eliminated()).count();
            if (active <= 1) {
                return new GameResult(findWinnerSeat(state, playerIds), steps, true);
            }

            String activeId = resolveActivePlayer(state);
            StrongBotConfig cfg = activeId != null
                    ? cfgByPlayer.getOrDefault(activeId, StrongBotConfig.defaults())
                    : StrongBotConfig.defaults();

            CommandResult result = dispatch(service, state, activeId, cfg, cfgByPlayer);
            if (!result.accepted()) {
                if (++consecutiveRejects >= MAX_CONSECUTIVE_REJECTS) return new GameResult(-1, steps, false);
            } else {
                consecutiveRejects = 0;
            }
        }
        // Hit step limit — net-worth tiebreaker
        return new GameResult(winnerByNetWorth(service.currentState(), playerIds), steps, false);
    }

    // -------------------------------------------------------------------------
    // Dispatch layer
    // -------------------------------------------------------------------------

    private static CommandResult dispatch(SessionApplicationService service, SessionState state,
                                          String activeId, StrongBotConfig cfg,
                                          Map<String, StrongBotConfig> cfgByPlayer) {
        // WON_PENDING_RESOLUTION: currentActorPlayerId is null but we still need to act
        if (state.auctionState() != null
                && state.auctionState().status() == AuctionStatus.WON_PENDING_RESOLUTION) {
            return service.handle(new FinishAuctionResolutionCommand(
                    state.sessionId(), state.auctionState().auctionId()));
        }

        if (activeId == null) return reject("no-active-player", state);

        // Trade state handling
        if (state.tradeState() != null) {
            TradeState trade = state.tradeState();
            // Someone needs to evaluate an incoming offer
            String evaluator = trade.decisionRequiredFromPlayerId();
            if (evaluator != null && cfgByPlayer.containsKey(evaluator)) {
                StrongBotConfig evalCfg = cfgByPlayer.getOrDefault(evaluator, StrongBotConfig.defaults());
                return dispatchTradeDecision(service, state, evaluator, trade, evalCfg);
            }
            // Bot is the proposer — fill in or cancel the offer
            if (trade.status() == TradeStatus.EDITING && cfgByPlayer.containsKey(activeId)) {
                return dispatchTradeEditing(service, state, activeId, trade, cfg, cfgByPlayer);
            }
            // Countered or stuck — cancel
            return service.handle(new CancelTradeCommand(state.sessionId(), activeId, trade.tradeId()));
        }

        TurnPhase phase = state.turn() != null ? state.turn().phase() : null;
        if (phase == null) return reject("null-phase", state);

        return switch (phase) {
            case WAITING_FOR_ROLL     -> service.handle(new RollDiceCommand(state.sessionId(), activeId));
            case WAITING_FOR_CARD_ACK -> service.handle(new AcknowledgeCardCommand(state.sessionId(), activeId));
            case WAITING_FOR_END_TURN -> dispatchEndTurn(service, state, activeId, cfg, cfgByPlayer);
            case WAITING_FOR_DECISION -> dispatchDecision(service, state, activeId, cfg);
            case RESOLVING_DEBT       -> dispatchDebt(service, state);
            case WAITING_FOR_AUCTION  -> dispatchAuction(service, state, cfg, cfgByPlayer);
            default -> reject("unhandled-phase:" + phase, state);
        };
    }

    private static CommandResult dispatchEndTurn(SessionApplicationService service, SessionState state,
                                                  String activeId, StrongBotConfig cfg,
                                                  Map<String, StrongBotConfig> cfgByPlayer) {
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, activeId);
        if (player == null) return service.handle(new EndTurnCommand(state.sessionId(), activeId));

        int reserve = StrongBotStrategy.dynamicReserve(state, activeId, cfg);

        CommandResult unmortgage = tryUnmortgage(service, state, activeId, cfg, player, reserve);
        if (unmortgage != null && unmortgage.accepted()) return unmortgage;

        CommandResult build = tryBuild(service, state, activeId, cfg, player, reserve);
        if (build != null && build.accepted()) return build;

        // Try to open a strategic trade if bot has enough cash and no monopoly yet
        if (StrongBotStrategy.completedColorGroups(state, activeId).isEmpty()
                && player.cash() > reserve + 80) {
            CommandResult trade = tryOpenStrategicTrade(service, state, activeId, cfg, cfgByPlayer);
            if (trade != null && trade.accepted()) return trade;
        }

        return service.handle(new EndTurnCommand(state.sessionId(), activeId));
    }

    private static CommandResult tryUnmortgage(SessionApplicationService service, SessionState state,
                                                String playerId, StrongBotConfig cfg,
                                                PlayerSnapshot player, int reserve) {
        PropertyStateSnapshot candidate = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && p.mortgaged())
                .filter(p -> StrongBotStrategy.botOwnsFullGroup(state, playerId, StrongBotStrategy.spotType(p.propertyId()).streetType))
                .filter(p -> player.cash() - StrongBotStrategy.unmortgageCost(p.propertyId()) >= reserve)
                .max(Comparator.comparingDouble(p -> StrongBotStrategy.unmortgageScore(p, state, cfg)))
                .orElse(null);
        if (candidate == null) return null;
        return service.handle(new ToggleMortgageCommand(state.sessionId(), playerId, candidate.propertyId()));
    }

    private static CommandResult tryBuild(SessionApplicationService service, SessionState state,
                                          String playerId, StrongBotConfig cfg,
                                          PlayerSnapshot player, int reserve) {
        StreetType bestGroup = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (StreetType group : StrongBotStrategy.completedColorGroups(state, playerId)) {
            int maxLevel = StrongBotStrategy.maxLevelInGroup(state, playerId, group);
            if (maxLevel >= cfg.buildRoundCap()) continue;
            if (!StrongBotStrategy.canAffordBuildRound(state, player, group, reserve)) continue;
            double score = StrongBotStrategy.buildGroupScore(state, playerId, group, cfg);
            if (score > bestScore) { bestScore = score; bestGroup = group; }
        }
        if (bestGroup == null) return null;
        PropertyStateSnapshot target = StrongBotStrategy.findBuildTarget(state, playerId, bestGroup);
        if (target == null) return null;
        return service.handle(new BuyBuildingRoundCommand(state.sessionId(), playerId, target.propertyId()));
    }

    private static CommandResult dispatchDecision(SessionApplicationService service, SessionState state,
                                                   String activeId, StrongBotConfig cfg) {
        PendingDecision decision = state.pendingDecision();
        if (decision == null) return service.handle(new EndTurnCommand(state.sessionId(), activeId));

        if (!(decision.payload() instanceof PropertyPurchaseDecisionPayload purchase)) {
            return service.handle(new EndTurnCommand(state.sessionId(), activeId));
        }

        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, activeId);
        int cash = player != null ? player.cash() : 0;
        int reserve = StrongBotStrategy.dynamicReserve(state, activeId, cfg);
        int postCash = cash - purchase.price();
        String propId = purchase.propertyId();

        // Can't afford even outright: try mortgaging, then decline
        if (postCash < 0) {
            PropertyStateSnapshot toMortgage = findMortgageCandidate(state, activeId, purchase.price() - cash);
            if (toMortgage != null) {
                return service.handle(new ToggleMortgageCommand(state.sessionId(), activeId, toMortgage.propertyId()));
            }
            return service.handle(new DeclinePropertyCommand(state.sessionId(), activeId, decision.decisionId(), propId));
        }

        // Set-completing: buy if post-cash >= reserve-100
        if (StrongBotStrategy.wouldCompleteSet(state, activeId, propId)) {
            if (postCash >= Math.max(0, reserve - 100)) {
                return service.handle(new BuyPropertyCommand(state.sessionId(), activeId, decision.decisionId(), propId));
            }
            return service.handle(new DeclinePropertyCommand(state.sessionId(), activeId, decision.decisionId(), propId));
        }

        // Below reserve: decline
        if (postCash < reserve) {
            return service.handle(new DeclinePropertyCommand(state.sessionId(), activeId, decision.decisionId(), propId));
        }

        // Score-based decision
        double score = StrongBotStrategy.buyScore(state, activeId, propId, cfg);
        double threshold = StrongBotStrategy.buyThreshold(state, propId, cfg);
        if (score >= threshold) {
            return service.handle(new BuyPropertyCommand(state.sessionId(), activeId, decision.decisionId(), propId));
        }
        return service.handle(new DeclinePropertyCommand(state.sessionId(), activeId, decision.decisionId(), propId));
    }

    private static CommandResult dispatchDebt(SessionApplicationService service, SessionState state) {
        DebtStateModel debt = state.activeDebt();
        if (debt == null) return reject("no-debt", state);
        String debtorId = debt.debtorPlayerId();
        List<DebtAction> allowed = debt.allowedActions();

        if (allowed.contains(DebtAction.PAY_DEBT_NOW) && debt.currentCash() >= debt.amountRemaining()) {
            return service.handle(new PayDebtCommand(state.sessionId(), debtorId, debt.debtId()));
        }
        if (allowed.contains(DebtAction.SELL_BUILDING)) {
            // Sell building with lowest rent-loss-per-sell-value first (least costly)
            PropertyStateSnapshot best = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && buildingLevel(p) > 0)
                    .filter(p -> evenSellEligible(state, p))
                    .min(Comparator.comparingDouble(StrongBotStrategy::debtBuildingSellScore)
                            .thenComparingInt(p -> -buildingLevel(p)))
                    .orElse(null);
            if (best != null) return service.handle(new SellBuildingForDebtCommand(
                    state.sessionId(), debtorId, debt.debtId(), best.propertyId(), 1));
        }
        if (allowed.contains(DebtAction.MORTGAGE_PROPERTY)) {
            // Mortgage in strategic priority: utilities first, monopolies last
            PropertyStateSnapshot toMortgage = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && !p.mortgaged())
                    .min(Comparator.comparingInt(
                            p -> StrongBotStrategy.debtMortgagePriority(state, debtorId, p)))
                    .orElse(null);
            if (toMortgage != null) return service.handle(new MortgagePropertyForDebtCommand(
                    state.sessionId(), debtorId, debt.debtId(), toMortgage.propertyId()));
        }
        return service.handle(new DeclareBankruptcyCommand(state.sessionId(), debtorId, debt.debtId()));
    }

    private static CommandResult dispatchAuction(SessionApplicationService service, SessionState state,
                                                  StrongBotConfig cfg,
                                                  Map<String, StrongBotConfig> cfgByPlayer) {
        AuctionState auction = state.auctionState();
        if (auction == null) return reject("no-auction", state);
        if (auction.status() == AuctionStatus.WON_PENDING_RESOLUTION) {
            return service.handle(new FinishAuctionResolutionCommand(state.sessionId(), auction.auctionId()));
        }
        String bidderId = auction.currentActorPlayerId();
        if (bidderId == null) return reject("no-auction-bidder", state);

        StrongBotConfig bidderCfg = cfgByPlayer.getOrDefault(bidderId, StrongBotConfig.defaults());
        PlayerSnapshot bidder = StrongBotStrategy.findPlayer(state, bidderId);
        int cash = bidder != null ? bidder.cash() : 0;
        int minBid = auction.minimumNextBid();
        if (minBid <= 0) return service.handle(new PassAuctionCommand(state.sessionId(), bidderId, auction.auctionId()));

        int reserve = StrongBotStrategy.dynamicReserve(state, bidderId, bidderCfg);
        String propId = auction.propertyId();
        int facePrice = propId != null ? SpotType.valueOf(propId).getIntegerProperty("price") : minBid;
        int ceiling = (int)(facePrice * bidderCfg.auctionAggression());
        if (propId != null && StrongBotStrategy.wouldCompleteSet(state, bidderId, propId)) {
            ceiling += bidderCfg.auctionSetCompletionBonus();
        }
        if (cash - minBid >= reserve && minBid <= ceiling) {
            return service.handle(new PlaceAuctionBidCommand(state.sessionId(), bidderId, auction.auctionId(), minBid));
        }
        return service.handle(new PassAuctionCommand(state.sessionId(), bidderId, auction.auctionId()));
    }

    // -------------------------------------------------------------------------
    // Trading helpers
    // -------------------------------------------------------------------------

    /**
     * Evaluates an incoming trade offer and accepts, counters, or declines.
     * Accepts if value received >= value given minus tradeFairnessTolerance,
     * OR if the trade completes a color monopoly for the bot.
     */
    private static CommandResult dispatchTradeDecision(SessionApplicationService service, SessionState state,
                                                        String botId, TradeState trade, StrongBotConfig cfg) {
        TradeOfferState offer = trade.currentOffer();
        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());
        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();

        int valueReceived = evaluateTradeSelection(state, botId, myReceiving, true, cfg);
        int valueGiven    = evaluateTradeSelection(state, botId, myGiving,    false, cfg);

        // Accept if within fairness tolerance or if it completes a monopoly
        boolean monopolyGain = myReceiving.propertyIds().stream()
                .anyMatch(p -> StrongBotStrategy.wouldCompleteSet(state, botId, p));
        if (monopolyGain || valueReceived >= valueGiven - cfg.tradeFairnessTolerance()) {
            return service.handle(new AcceptTradeCommand(state.sessionId(), botId, trade.tradeId()));
        }
        return service.handle(new DeclineTradeCommand(state.sessionId(), botId, trade.tradeId()));
    }

    /**
     * Fills in a trade offer the bot initiated: first requests a target property,
     * then offers money (up to the property price, capped by available cash).
     */
    private static CommandResult dispatchTradeEditing(SessionApplicationService service, SessionState state,
                                                       String botId, TradeState trade, StrongBotConfig cfg,
                                                       Map<String, StrongBotConfig> cfgByPlayer) {
        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();
        boolean iAmProposer = botId.equals(trade.initiatorPlayerId());
        TradeSelectionState myRequest = iAmProposer ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        TradeSelectionState myGive    = iAmProposer ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        boolean requestSide = !iAmProposer;
        boolean giveSide    = iAmProposer;

        // Step 1: request a target property
        if (myRequest.propertyIds().isEmpty()) {
            String partnerId = iAmProposer ? trade.recipientPlayerId() : trade.initiatorPlayerId();
            String target = findBestTradeTarget(state, botId, partnerId);
            if (target == null) return service.handle(new CancelTradeCommand(state.sessionId(), botId, tradeId));
            return service.handle(new EditTradeOfferCommand(state.sessionId(), botId, tradeId,
                    new TradeEditPatch(null, requestSide, null, List.of(target), List.of(), null)));
        }

        // Step 2: offer money
        if (myGive.moneyAmount() == 0) {
            String targetId = myRequest.propertyIds().get(0);
            int price = SpotType.valueOf(targetId).getIntegerProperty("price");
            PlayerSnapshot bot = StrongBotStrategy.findPlayer(state, botId);
            int reserve = StrongBotStrategy.dynamicReserve(state, botId, cfg);
            int available = bot != null ? Math.max(0, bot.cash() - reserve) : 0;
            int offer2 = Math.min(price, available);
            if (offer2 < 10) return service.handle(new CancelTradeCommand(state.sessionId(), botId, tradeId));
            return service.handle(new EditTradeOfferCommand(state.sessionId(), botId, tradeId,
                    new TradeEditPatch(null, giveSide, offer2, List.of(), List.of(), null)));
        }

        // Step 3: submit
        return service.handle(new SubmitTradeOfferCommand(state.sessionId(), botId, tradeId));
    }

    /** Try to open a trade with any opponent who has a property the bot needs. */
    private static CommandResult tryOpenStrategicTrade(SessionApplicationService service, SessionState state,
                                                        String botId, StrongBotConfig cfg,
                                                        Map<String, StrongBotConfig> cfgByPlayer) {
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (findBestTradeTarget(state, botId, other.playerId()) == null) continue;
            CommandResult r = service.handle(new OpenTradeCommand(state.sessionId(), botId, other.playerId()));
            if (r.accepted()) return r;
        }
        return null;
    }

    /** Find the best property from partnerId's portfolio that would help botId form a monopoly. */
    private static String findBestTradeTarget(SessionState state, String botId, String partnerId) {
        // Priority 1: completes a monopoly
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            int gs = StrongBotStrategy.setSize(group);
            if (gs == 0) continue;
            if (StrongBotStrategy.ownedInSet(state, botId, group) != gs - 1) continue;
            String found = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && StrongBotStrategy.spotType(p.propertyId()).streetType == group)
                    .map(PropertyStateSnapshot::propertyId).findFirst().orElse(null);
            if (found != null) return found;
        }
        // Priority 2: any group where bot has most properties (≥1)
        return StreetType.values() == null ? null :
                java.util.Arrays.stream(StreetType.values())
                        .filter(g -> g.placeType == PlaceType.STREET)
                        .filter(g -> StrongBotStrategy.ownedInSet(state, botId, g) > 0)
                        .sorted(Comparator.comparingInt((StreetType g) ->
                                StrongBotStrategy.ownedInSet(state, botId, g)).reversed())
                        .flatMap(g -> state.properties().stream()
                                .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                                        && p.houseCount() == 0 && p.hotelCount() == 0
                                        && StrongBotStrategy.spotType(p.propertyId()).streetType == g)
                                .map(PropertyStateSnapshot::propertyId))
                        .findFirst().orElse(null);
    }

    /** Evaluate a trade selection from the bot's perspective. */
    private static int evaluateTradeSelection(SessionState state, String botId,
                                               TradeSelectionState selection, boolean receiving,
                                               StrongBotConfig cfg) {
        int value = (int)(selection.moneyAmount() * cfg.tradeLiquidityWeight());
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            int price = SpotType.valueOf(propId).getIntegerProperty("price");
            value += receiving && StrongBotStrategy.wouldCompleteSet(state, botId, propId)
                    ? price + cfg.tradeSetCompletionWeight()
                    : price;
        }
        return value;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolveActivePlayer(SessionState state) {
        if (state.tradeState() != null) {
            TradeState t = state.tradeState();
            // If someone needs to decide on an offer, they are "active"
            if (t.decisionRequiredFromPlayerId() != null) return t.decisionRequiredFromPlayerId();
            // Otherwise the initiator/proposer is editing
            return t.initiatorPlayerId();
        }
        if (state.activeDebt() != null) return state.activeDebt().debtorPlayerId();
        if (state.auctionState() != null) return state.auctionState().currentActorPlayerId();
        return state.turn() != null ? state.turn().activePlayerId() : null;
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

    /** Determines winner by net worth when no bankruptcy occurs within the step limit. */
    private static int winnerByNetWorth(SessionState state, List<String> playerIds) {
        int bestSeat = -1;
        int bestWorth = Integer.MIN_VALUE;
        for (int i = 0; i < playerIds.size(); i++) {
            String pid = playerIds.get(i);
            PlayerSnapshot player = state.players().stream()
                    .filter(p -> pid.equals(p.playerId()) && !p.bankrupt() && !p.eliminated())
                    .findFirst().orElse(null);
            if (player == null) continue;
            int cash = player.cash();
            int propertyEquity = state.properties().stream()
                    .filter(p -> pid.equals(p.ownerPlayerId()))
                    .mapToInt(p -> {
                        int price = SpotType.valueOf(p.propertyId()).getIntegerProperty("price");
                        SpotType spotType = SpotType.valueOf(p.propertyId());
                        int buildingValue = 0;
                        if (spotType.streetType.placeType == PlaceType.STREET) {
                            int housePrice = spotType.getIntegerProperty("housePrice");
                            buildingValue = (p.hotelCount() > 0 ? 5 : p.houseCount()) * (housePrice / 2);
                        }
                        return p.mortgaged() ? price / 2 : price + buildingValue;
                    })
                    .sum();
            int netWorth = cash + propertyEquity;
            if (netWorth > bestWorth) { bestWorth = netWorth; bestSeat = i; }
        }
        return bestSeat;
    }

    private static PropertyStateSnapshot findMortgageCandidate(SessionState state, String playerId, int needed) {
        List<PropertyStateSnapshot> candidates = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && !p.mortgaged())
                .filter(p -> {
                    if (StrongBotStrategy.spotType(p.propertyId()).streetType.placeType == PlaceType.STREET) {
                        return state.properties().stream()
                                .filter(q -> StrongBotStrategy.spotType(q.propertyId()).streetType
                                        == StrongBotStrategy.spotType(p.propertyId()).streetType)
                                .noneMatch(q -> q.houseCount() > 0 || q.hotelCount() > 0);
                    }
                    return true;
                })
                .filter(p -> !StrongBotStrategy.botOwnsFullGroup(state, playerId, StrongBotStrategy.spotType(p.propertyId()).streetType))
                .toList();
        if (candidates.isEmpty()) return null;
        int totalRaisable = candidates.stream()
                .mapToInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price") / 2)
                .sum();
        if (totalRaisable < needed) return null;
        return candidates.stream()
                .min(Comparator
                        .comparingInt((PropertyStateSnapshot p) ->
                                StrongBotStrategy.spotType(p.propertyId()).streetType.placeType == PlaceType.STREET ? 1 : 0)
                        .thenComparingInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price")))
                .orElse(null);
    }

    private static int buildingLevel(PropertyStateSnapshot p) {
        return p.hotelCount() > 0 ? 5 : p.houseCount();
    }

    private static boolean evenSellEligible(SessionState state, PropertyStateSnapshot prop) {
        if (StrongBotStrategy.spotType(prop.propertyId()).streetType.placeType != PlaceType.STREET) return true;
        int level = buildingLevel(prop);
        int maxRest = state.properties().stream()
                .filter(p -> !p.propertyId().equals(prop.propertyId())
                        && StrongBotStrategy.spotType(p.propertyId()).streetType
                        == StrongBotStrategy.spotType(prop.propertyId()).streetType)
                .mapToInt(BotTournament::buildingLevel)
                .max().orElse(0);
        return level - 1 >= maxRest - 1;
    }

    private static CommandResult reject(String reason, SessionState state) {
        return new CommandResult(false, state, List.of(),
                List.of(new CommandRejection(reason, null)), List.of());
    }

    // -------------------------------------------------------------------------
    // Formatted output
    // -------------------------------------------------------------------------

    public static void printStandings(List<Standing> standings) {
        System.out.println("=".repeat(90));
        System.out.println("Bot Tournament Results");
        System.out.println("=".repeat(90));
        for (int i = 0; i < standings.size(); i++) {
            System.out.printf("#%d  %s%n", i + 1, standings.get(i));
        }
        System.out.println("=".repeat(90));
    }
}
