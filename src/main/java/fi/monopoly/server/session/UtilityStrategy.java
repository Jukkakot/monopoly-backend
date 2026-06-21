package fi.monopoly.server.session;

import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.AuctionState;
import fi.monopoly.domain.session.AuctionStatus;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.TradeOfferState;
import fi.monopoly.domain.session.TradeSelectionState;
import fi.monopoly.domain.session.TradeState;
import fi.monopoly.domain.session.TradeStatus;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.*;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;
import fi.monopoly.utils.RandomSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility-AI strategy that handles buy/auction, build, and unmortgage decisions via
 * the IAUS model and delegates everything else to {@link PureDomainStrategy}.
 *
 * <p>This is the entry point for the new bot logic. One decision at a time is migrated
 * from the old greedy path to the utility model; until all decisions are migrated,
 * {@code PureDomainStrategy} handles the remainder.</p>
 *
 * <h3>Buy/auction decision (Phase 3)</h3>
 * <p>When the game is in {@code WAITING_FOR_DECISION} with a property purchase payload,
 * {@link BuyConsiderations} scores the BUY action with the multiplicative+compensation
 * combiner, and compares it to the {@code auction_baseline} threshold.</p>
 *
 * <h3>End-turn management (Phase 4.1–4.2)</h3>
 * <p>During {@code WAITING_FOR_END_TURN} (no active trade), scores both build and
 * unmortgage candidates in a single pass and returns the highest-scoring action.
 * If nothing beats either baseline, delegates to {@code PureDomainStrategy}.</p>
 *
 * <h3>Production default</h3>
 * <p>This strategy is NOT the production default. It runs only when explicitly selected
 * via the harness or a per-session flag (see {@link PureDomainBotDriver#USE_NEW_STRATEGY}).
 * The gauntlet must show parity with {@code pure-domain-v1} before promotion.</p>
 */
public final class UtilityStrategy implements BotStrategy {

    private final PureDomainStrategy delegate;
    private final ConcurrentHashMap<String, BotParams> paramsCache = new ConcurrentHashMap<>();

    // Ordered archetype pool — each bot gets a distinct one by hashing its ID
    private static final Personality[] ARCHETYPES = {
        Personality.balanced(),
        Personality.aggressive(),
        Personality.cautious(),
        Personality.trader(),
        Personality.hoarder()
    };

    /**
     * Constructs a {@code UtilityStrategy} backed by the given per-player configs.
     *
     * @param configs per-player {@link StrongBotConfig} — forwarded to the delegate
     *                strategy for all decisions not yet migrated to utility
     */
    public UtilityStrategy(Map<String, StrongBotConfig> configs) {
        this.delegate = new PureDomainStrategy(configs);
    }

    /** Convenience constructor — no per-player config override. */
    public UtilityStrategy() {
        this(Map.of());
    }

    @Override
    public String name() { return "utility-v1"; }

    @Override
    public Intent decide(SessionState state, String botId, BotMemory memory, RandomSource rng) {
        // ---- Phase 4.5: handle trade response via utility model --------------
        if (isTradeResponseOpportunity(state, botId)) {
            Intent tradeIntent = decideTradeResponseUtility(state, botId, memory);
            if (tradeIntent != null) return tradeIntent;
        }

        // ---- Phase 4.1–4.2: handle build + unmortgage via utility model -------
        if (isEndTurnManagementOpportunity(state, botId)) {
            Intent mgmtIntent = decideEndTurnManagement(state, botId, memory);
            if (mgmtIntent != null) return mgmtIntent;
        }

        // ---- All other decisions: delegate to PureDomainStrategy -------------
        return delegate.decide(state, botId, memory, rng);
    }

    // -------------------------------------------------------------------------
    // Trade response decision (Phase 4.5)
    // -------------------------------------------------------------------------

    private static boolean isTradeResponseOpportunity(SessionState state, String botId) {
        if (state.tradeState() == null) return false;
        TradeState trade = state.tradeState();
        // Counter-editing and trade-editing are still delegated to PureDomainStrategy
        if (trade.status() == TradeStatus.COUNTERED && botId.equals(trade.editingPlayerId())) return false;
        if (trade.status() == TradeStatus.EDITING && botId.equals(trade.editingPlayerId())) return false;
        return botId.equals(trade.decisionRequiredFromPlayerId());
    }

    private Intent decideTradeResponseUtility(SessionState state, String botId, BotMemory memory) {
        TradeState trade = state.tradeState();
        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();

        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());
        String partnerId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();

        TradeSelectionState myReceiving = botIsRecipient
                ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myGiving    = botIsRecipient
                ? offer.requestedFromRecipient() : offer.offeredToRecipient();

        BotParams params = paramsFor(botId);
        CandidateAction action = new CandidateAction.AcceptTrade(myReceiving, myGiving, partnerId);
        DecisionContext ctx = new DecisionContext(state, botId, memory, params, action);
        double score = Consideration.combine(TradeConsiderations.ACCEPT_CONSIDERATIONS, ctx);

        double acceptBaseline = params.weight("trade_accept_baseline", 0.40);

        if (score >= acceptBaseline) {
            return new Intent.RespondToTrade(Intent.TradeResponse.ACCEPT, tradeId);
        }
        // Delegate COUNTER/DECLINE to PureDomainStrategy — it manages memory correctly
        // and avoids repeated state signatures from counter-loops (which trip loop detection).
        return null;
    }

    // -------------------------------------------------------------------------
    // Buy / auction decision via IAUS
    // -------------------------------------------------------------------------

    private static boolean isBuyDecision(SessionState state, String botId) {
        if (state.turn() == null) return false;
        if (state.turn().phase() != TurnPhase.WAITING_FOR_DECISION) return false;
        PendingDecision pd = state.pendingDecision();
        if (pd == null) return false;
        if (!(pd.payload() instanceof PropertyPurchaseDecisionPayload)) return false;
        // Only act when it's actually this bot's decision
        return botId.equals(state.turn().activePlayerId());
    }

    private Intent decidePurchase(SessionState state, String botId,
                                  BotMemory memory, RandomSource rng) {
        PendingDecision pd = state.pendingDecision();
        if (!(pd.payload() instanceof PropertyPurchaseDecisionPayload purchase)) return null;

        String propId     = purchase.propertyId();
        int    price      = purchase.price();
        String decisionId = pd.decisionId();

        BotParams params = paramsFor(botId);

        // Score the BUY candidate
        CandidateAction buyAction = new CandidateAction.BuyProperty(propId, price);
        DecisionContext buyCtx    = new DecisionContext(state, botId, memory, params, buyAction);
        double buyScore = Consideration.combine(BuyConsiderations.BUY_CONSIDERATIONS, buyCtx);

        // The "decline and go to auction" baseline
        double auctionBaseline = params.weight("auction_baseline", 0.25);

        if (buyScore > auctionBaseline) {
            return new Intent.BuyProperty(decisionId, propId);
        }
        return new Intent.DeclineProperty(decisionId, propId);
    }

    // -------------------------------------------------------------------------
    // End-turn management (Phase 4.1–4.2): build + unmortgage in a single pass
    // -------------------------------------------------------------------------

    private static boolean isEndTurnManagementOpportunity(SessionState state, String botId) {
        if (state.turn() == null) return false;
        if (state.turn().phase() != TurnPhase.WAITING_FOR_END_TURN) return false;
        if (state.tradeState() != null) return false;
        return botId.equals(state.turn().activePlayerId());
    }

    private Intent decideEndTurnManagement(SessionState state, String botId, BotMemory memory) {
        BotParams params = paramsFor(botId);
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, botId);
        if (player == null) return null;

        double buildBaseline    = params.weight("build_end_turn_baseline", 0.20);
        double unmortgageBaseline = params.weight("unmortgage_end_turn_baseline", 0.30);
        double bestScore = Math.max(buildBaseline, unmortgageBaseline);
        Intent bestIntent = null;

        // ---- Score unmortgage candidates ------------------------------------
        for (PropertyStateSnapshot prop : state.properties()) {
            if (!botId.equals(prop.ownerPlayerId())) continue;
            if (!prop.mortgaged()) continue;
            StreetType group = StrongBotStrategy.spotType(prop.propertyId()).streetType;
            if (group == null) continue;
            if (!StrongBotStrategy.botOwnsFullGroup(state, botId, group)) continue;

            CandidateAction action = new CandidateAction.Unmortgage(prop.propertyId());
            DecisionContext ctx = new DecisionContext(state, botId, memory, params, action);
            double score = Consideration.combine(UnmortgageConsiderations.UNMORTGAGE_CONSIDERATIONS, ctx);
            if (score > bestScore) {
                bestScore = score;
                bestIntent = new Intent.Unmortgage(prop.propertyId());
            }
        }

        // ---- Score build candidates ----------------------------------------
        for (StreetType group : StrongBotStrategy.completedColorGroups(state, botId)) {
            int maxLevel = StrongBotStrategy.maxLevelInGroup(state, botId, group);
            if (maxLevel >= StrongBotConfig.defaults().buildRoundCap()) continue;

            PropertyStateSnapshot sampleProp = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()))
                    .filter(p -> StrongBotStrategy.spotType(p.propertyId()).streetType == group)
                    .findFirst().orElse(null);
            if (sampleProp == null) continue;

            int housePrice;
            try {
                housePrice = SpotType.valueOf(sampleProp.propertyId()).getIntegerProperty("housePrice");
            } catch (Exception e) {
                continue;
            }

            int rawReserve = StrongBotStrategy.dynamicReserve(state, botId, StrongBotConfig.defaults());
            int posAdjReserve = (int)(rawReserve / StrongBotStrategy.positionFactor(state, botId));
            if (!StrongBotStrategy.canAffordBuildRound(state, player, group, posAdjReserve)) {
                continue;
            }

            PropertyStateSnapshot target = StrongBotStrategy.findBuildTarget(state, botId, group);
            if (target == null) continue;

            CandidateAction buildAction = new CandidateAction.BuildHouses(
                    target.propertyId(), housePrice, maxLevel);
            DecisionContext ctx = new DecisionContext(state, botId, memory, params, buildAction);
            double score = Consideration.combine(BuildConsiderations.BUILD_CONSIDERATIONS, ctx);
            if (score > bestScore) {
                bestScore = score;
                bestIntent = new Intent.BuildHouses(target.propertyId());
            }
        }

        return bestIntent;
    }

    // -------------------------------------------------------------------------
    // Auction bid decision (Phase 4.4)
    // -------------------------------------------------------------------------

    private static boolean isAuctionOpportunity(SessionState state, String botId) {
        AuctionState auction = state.auctionState();
        if (auction == null) return false;
        if (auction.status() == AuctionStatus.WON_PENDING_RESOLUTION) return false;
        return botId.equals(auction.currentActorPlayerId());
    }

    private Intent decideAuction(SessionState state, String botId, BotMemory memory, RandomSource rng) {
        AuctionState auction = state.auctionState();
        if (auction == null) return null;
        if (auction.propertyId() == null) return null;

        String propId   = auction.propertyId();
        int    minBid   = auction.minimumNextBid();
        int    facePrice = AuctionConsiderations.facePrice(propId);
        if (facePrice <= 0) return null;

        BotParams params  = paramsFor(botId);
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, botId);
        int cash = player != null ? player.cash() : 0;
        StrongBotConfig cfg = StrongBotConfig.defaults();
        int reserve = Math.min(StrongBotStrategy.dynamicReserve(state, botId, cfg),
                cfg.dangerCashReserve());

        // Score at the minimum bid to determine whether we're willing to bid at all
        CandidateAction bidAction = new CandidateAction.AuctionBid(propId, minBid);
        DecisionContext ctx = new DecisionContext(state, botId, memory, params, bidAction);
        double score = Consideration.combine(AuctionConsiderations.BID_CONSIDERATIONS, ctx);

        double passBaseline = params.weight("bid_pass_baseline", 0.15);
        if (score <= passBaseline) {
            return new Intent.PassAuction(auction.auctionId());
        }

        // Ceiling is personality-based (how far above face price we'll go), not score-based.
        // Score already determined we want to bid; aggression sets the price limit.
        // Add set-completion and opponent-blocking bonuses matching pure-domain's ceiling formula.
        double aggression = params.weight("bid_aggression", 1.0);
        int ceiling = (int)(facePrice * aggression);
        int completionBonus = cfg.auctionSetCompletionBonus();
        if (StrongBotStrategy.wouldCompleteSet(state, botId, propId)) {
            ceiling += completionBonus;
        }
        StreetType bidGroup = StrongBotStrategy.spotType(propId).streetType;
        if (bidGroup != null && bidGroup.placeType == PlaceType.STREET) {
            int bSize = StrongBotStrategy.setSize(bidGroup);
            boolean wouldBlock = bSize > 1 && state.players().stream()
                    .filter(p -> !p.playerId().equals(botId) && !p.bankrupt() && !p.eliminated())
                    .anyMatch(p -> StrongBotStrategy.ownedInSet(state, p.playerId(), bidGroup) == bSize - 1);
            if (wouldBlock) ceiling += completionBonus;
        }
        int maxAffordable = cash - reserve;
        int maxBid = Math.min(ceiling, maxAffordable);

        if (maxBid < minBid) {
            // Can't afford even minimum — but if the property is mortgageable we still gain value
            int mortgageFloor = facePrice / 2;
            if (minBid <= mortgageFloor && cash >= minBid) {
                return new Intent.Bid(auction.auctionId(), minBid);
            }
            return new Intent.PassAuction(auction.auctionId());
        }

        // Avoid over-bidding when there's no competition
        java.util.Set<String> active = new java.util.HashSet<>(auction.eligiblePlayerIds());
        active.removeAll(auction.passedPlayerIds());
        active.remove(botId);
        boolean noCompetition = active.isEmpty() || active.stream()
                .allMatch(id -> {
                    PlayerSnapshot p = StrongBotStrategy.findPlayer(state, id);
                    return p == null || p.cash() < 2 * minBid;
                });
        if (noCompetition) {
            return new Intent.Bid(auction.auctionId(), minBid);
        }

        // Bid with some randomised headroom so opponents can't predict our ceiling
        int headroom = maxBid - minBid;
        double factor = 0.6 + rng.nextDouble() * 0.8;
        int extra = ((int)(headroom / 3.0 * factor) / 10) * 10;
        int bid = Math.min(maxBid, minBid + Math.max(10, extra));
        return new Intent.Bid(auction.auctionId(), bid);
    }

    // -------------------------------------------------------------------------
    // Per-player params (Phase 5.2 — sampled once per bot, cached for the game)
    // -------------------------------------------------------------------------

    private BotParams paramsFor(String botId) {
        if (botId == null) return BotParams.defaults();
        return paramsCache.computeIfAbsent(botId, id -> {
            // Deterministically pick an archetype and apply ±10 % jitter so each bot
            // has a consistent but distinct personality for the entire game.
            int archetypeIdx = Math.abs(id.hashCode()) % ARCHETYPES.length;
            Personality archetype = ARCHETYPES[archetypeIdx];
            long seed = id.chars().asLongStream().reduce(0L, Long::sum);
            RandomSource botRng = RandomSource.seeded(seed);
            Personality p = Personality.sample(archetype, 0.10, botRng);
            return BotParams.forPersonality(id, p);
        });
    }
}
