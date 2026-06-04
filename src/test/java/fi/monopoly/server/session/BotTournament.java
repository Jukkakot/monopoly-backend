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

    private static final int MAX_STEPS = 15_000;
    private static final int MAX_CONSECUTIVE_REJECTS = 10;

    public record Entry(String name, StrongBotConfig config) {}

    public record Standing(
            String name,
            int wins,
            int losses,
            int draws,
            int games
    ) {
        public double winRate() { return games > 0 ? (double) wins / games : 0; }

        @Override public String toString() {
            return String.format("%-20s  W=%3d  L=%3d  D=%3d  (%d games)  win%%=%.1f",
                    name, wins, losses, draws, games, winRate() * 100);
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
        int[] wins   = new int[n];
        int[] losses = new int[n];
        int[] draws  = new int[n];
        int[] games  = new int[n];

        int gameIndex = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int g = 0; g < gamesPerPair; g++) {
                    long seed = seedBase + gameIndex++;
                    // Alternate seat order to remove first-player bias
                    boolean swap = (g % 2 == 1);
                    int playerA = swap ? j : i;
                    int playerB = swap ? i : j;
                    List<StrongBotConfig> cfgs = List.of(configs.get(playerA).config(), configs.get(playerB).config());
                    int winner = runGame(cfgs, seed);
                    games[i]++;
                    games[j]++;
                    if (winner == 0) {
                        wins[playerA]++;
                        losses[playerB]++;
                    } else if (winner == 1) {
                        wins[playerB]++;
                        losses[playerA]++;
                    } else {
                        draws[i]++;
                        draws[j]++;
                    }
                }
            }
        }

        List<Standing> standings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            standings.add(new Standing(configs.get(i).name(), wins[i], losses[i], draws[i], games[i]));
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
        int[] wins   = new int[n];
        int[] losses = new int[n];
        int[] draws  = new int[n];
        int[] games  = new int[n];

        List<StrongBotConfig> cfgs = configs.stream().map(Entry::config).toList();

        for (int g = 0; g < gamesPerGroup; g++) {
            // Rotate seat order each game to remove positional bias
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) order.add(i);
            Collections.rotate(order, g);

            List<StrongBotConfig> seatedCfgs = order.stream().map(cfgs::get).toList();
            int winnerSeat = runGame(seatedCfgs, seedBase + g);
            for (int i = 0; i < n; i++) {
                games[order.get(i)]++;
            }
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
            standings.add(new Standing(configs.get(i).name(), wins[i], losses[i], draws[i], games[i]));
        }
        standings.sort(Comparator.comparingDouble(Standing::winRate).reversed());
        return standings;
    }

    // -------------------------------------------------------------------------
    // Evolutionary search
    // -------------------------------------------------------------------------

    /**
     * Runs an evolutionary tournament to find a strong config.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Initial population: defaults, aggressive, cautious + random mutations</li>
     *   <li>Each generation: round-robin tournament to rank configs</li>
     *   <li>Top 50% survive; bottom 50% replaced by crossover+mutate of survivors</li>
     *   <li>Returns the best config found across all generations</li>
     * </ol>
     *
     * @param populationSize  number of configs per generation (≥4)
     * @param generations     number of evolutionary cycles
     * @param gamesPerPair    games per pair per generation
     * @param seedBase        base random seed
     * @param verbose         if true, prints per-generation results to stdout
     */
    public static Entry evolve(int populationSize, int generations, int gamesPerPair,
                               long seedBase, boolean verbose) {
        Random rng = new Random(seedBase);
        List<Entry> population = new ArrayList<>();
        population.add(new Entry("defaults",   StrongBotConfig.defaults()));
        population.add(new Entry("aggressive", StrongBotConfig.aggressive()));
        population.add(new Entry("cautious",   StrongBotConfig.cautious()));
        while (population.size() < populationSize) {
            StrongBotConfig base = StrongBotConfig.defaults();
            // Apply 3-6 mutations to create diversity
            int mutations = 3 + rng.nextInt(4);
            for (int m = 0; m < mutations; m++) {
                base = base.mutate(rng, 0.30);
            }
            population.add(new Entry("random-" + population.size(), base));
        }

        Entry bestEver = population.get(0);
        double bestWinRate = 0;

        for (int gen = 0; gen < generations; gen++) {
            long genSeed = seedBase + (long) gen * 100_000;
            List<Standing> standings = roundRobin(population, gamesPerPair, genSeed);

            if (verbose) {
                System.out.printf("=== Generation %d ===%n", gen + 1);
                standings.forEach(s -> System.out.println("  " + s));
            }

            // Track best ever
            Standing top = standings.get(0);
            double topWinRate = top.winRate();
            Entry topEntry = population.stream()
                    .filter(e -> e.name().equals(top.name()))
                    .findFirst().orElse(population.get(0));
            if (topWinRate > bestWinRate) {
                bestWinRate = topWinRate;
                bestEver = topEntry;
            }

            // Selection: keep top 50%, replace bottom 50% with children
            int survivors = populationSize / 2;
            List<Entry> nextGen = new ArrayList<>();

            // Survivors (in standing order)
            for (Standing s : standings.subList(0, survivors)) {
                population.stream().filter(e -> e.name().equals(s.name()))
                        .findFirst().ifPresent(nextGen::add);
            }

            // Children: crossover pairs of survivors + mutation
            int child = 0;
            while (nextGen.size() < populationSize) {
                Entry parent1 = nextGen.get(rng.nextInt(survivors));
                Entry parent2 = nextGen.get(rng.nextInt(survivors));
                StrongBotConfig childCfg = parent1.config().crossover(parent2.config(), rng);
                childCfg = childCfg.mutate(rng, 0.15);
                nextGen.add(new Entry("child-" + gen + "-" + child++, childCfg));
            }

            population = nextGen;
        }

        if (verbose) {
            System.out.printf("%nBest config overall: %s (win rate %.1f%%)%n",
                    bestEver.name(), bestWinRate * 100);
        }
        return bestEver;
    }

    // -------------------------------------------------------------------------
    // Single-game engine
    // -------------------------------------------------------------------------

    /**
     * Runs one complete headless game with the given configs in seat order.
     *
     * @return seat index (0-based) of the winner, or -1 if the game stalled/drew
     */
    public static int runGame(List<StrongBotConfig> configs, long seed) {
        int n = configs.size();
        List<String> names   = new ArrayList<>();
        List<String> colors  = new ArrayList<>();
        String[] palette = {"#E63946","#2A9D8F","#E9C46A","#264653"};
        for (int i = 0; i < n; i++) {
            names.add("Bot" + i);
            colors.add(palette[i % palette.length]);
        }

        SessionState initial = PureDomainSessionFactory.initialGameState(
                "tournament-" + seed, names, colors, new Random(seed));

        // Map player IDs (in seat order) to configs
        List<String> playerIds = initial.players().stream()
                .map(PlayerSnapshot::playerId)
                .toList();
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
                return findWinnerSeat(state, playerIds);
            }

            long active = state.players().stream().filter(p -> !p.bankrupt() && !p.eliminated()).count();
            if (active <= 1) {
                return findWinnerSeat(state, playerIds);
            }

            String activeId = resolveActivePlayer(state);
            StrongBotConfig cfg = activeId != null ? cfgByPlayer.getOrDefault(activeId, StrongBotConfig.defaults()) : StrongBotConfig.defaults();

            CommandResult result = dispatch(service, state, activeId, cfg, cfgByPlayer);
            if (!result.accepted()) {
                if (++consecutiveRejects >= MAX_CONSECUTIVE_REJECTS) return -1;
            } else {
                consecutiveRejects = 0;
            }
        }
        return winnerByNetWorth(service.currentState(), playerIds);
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

        // Trade state: bots don't initiate trades in simulations — just decline incoming
        if (state.tradeState() != null) {
            TradeState trade = state.tradeState();
            if (trade.decisionRequiredFromPlayerId() != null
                    && cfgByPlayer.containsKey(trade.decisionRequiredFromPlayerId())) {
                return service.handle(new DeclineTradeCommand(
                        state.sessionId(), trade.decisionRequiredFromPlayerId(), trade.tradeId()));
            }
            if (trade.status() == TradeStatus.EDITING || trade.status() == TradeStatus.COUNTERED) {
                return service.handle(new CancelTradeCommand(state.sessionId(), activeId, trade.tradeId()));
            }
        }

        TurnPhase phase = state.turn() != null ? state.turn().phase() : null;
        if (phase == null) return reject("null-phase", state);

        return switch (phase) {
            case WAITING_FOR_ROLL     -> service.handle(new RollDiceCommand(state.sessionId(), activeId));
            case WAITING_FOR_CARD_ACK -> service.handle(new AcknowledgeCardCommand(state.sessionId(), activeId));
            case WAITING_FOR_END_TURN -> dispatchEndTurn(service, state, activeId, cfg);
            case WAITING_FOR_DECISION -> dispatchDecision(service, state, activeId, cfg);
            case RESOLVING_DEBT       -> dispatchDebt(service, state);
            case WAITING_FOR_AUCTION  -> dispatchAuction(service, state, cfg, cfgByPlayer);
            default -> reject("unhandled-phase:" + phase, state);
        };
    }

    private static CommandResult dispatchEndTurn(SessionApplicationService service, SessionState state,
                                                  String activeId, StrongBotConfig cfg) {
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, activeId);
        if (player == null) return service.handle(new EndTurnCommand(state.sessionId(), activeId));

        int reserve = StrongBotStrategy.dynamicReserve(state, activeId, cfg);

        // Try unmortgage first
        CommandResult unmortgage = tryUnmortgage(service, state, activeId, cfg, player, reserve);
        if (unmortgage != null && unmortgage.accepted()) return unmortgage;

        // Try build
        CommandResult build = tryBuild(service, state, activeId, cfg, player, reserve);
        if (build != null && build.accepted()) return build;

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
            PropertyStateSnapshot best = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && buildingLevel(p) > 0)
                    .filter(p -> evenSellEligible(state, p))
                    .max(Comparator.comparingInt(BotTournament::buildingLevel))
                    .orElse(null);
            if (best != null) return service.handle(new SellBuildingForDebtCommand(
                    state.sessionId(), debtorId, debt.debtId(), best.propertyId(), 1));
        }
        if (allowed.contains(DebtAction.MORTGAGE_PROPERTY)) {
            PropertyStateSnapshot unmortgaged = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && !p.mortgaged())
                    .findFirst().orElse(null);
            if (unmortgaged != null) return service.handle(new MortgagePropertyForDebtCommand(
                    state.sessionId(), debtorId, debt.debtId(), unmortgaged.propertyId()));
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
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolveActivePlayer(SessionState state) {
        if (state.tradeState() != null) return state.tradeState().decisionRequiredFromPlayerId();
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
                        int housePrice = SpotType.valueOf(p.propertyId()).getIntegerProperty("housePrice");
                        int buildingValue = (p.hotelCount() > 0 ? 5 : p.houseCount()) * (housePrice / 2);
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
        System.out.println("=".repeat(65));
        System.out.println("Bot Tournament Results");
        System.out.println("=".repeat(65));
        for (int i = 0; i < standings.size(); i++) {
            System.out.printf("#%d  %s%n", i + 1, standings.get(i));
        }
        System.out.println("=".repeat(65));
    }
}
