package fi.monopoly.server.session;

import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.BotStrategy;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;
import fi.monopoly.utils.RandomSource;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 1.2: pure extraction of {@link PureDomainBotDriver#dispatchGreedy} (and its sub-methods)
 * into a synchronous {@code decide(state, botId, memory, rng) → Intent}.
 *
 * <p>Behaviour is intentionally <em>identical</em> to the driver — the golden-master test
 * ({@link BotGoldenMasterTest}) must pass before and after Phase 1.4 wires this into
 * production.</p>
 */
@Slf4j
public final class PureDomainStrategy implements BotStrategy {

    private static final int MAX_DECLINES_PER_PARTNER = 2;
    private static final int MAX_COUNTERS_PER_TRADE = 2;
    private static final double MONOPOLY_COMPLETION_DISCOUNT = 0.30;
    private static final double MONOPOLY_SALE_PREMIUM = 1.6;
    private static final double STALEMATE_GIFT_RELIEF = 0.5;
    private static final int STALEMATE_TURN_THRESHOLD = 12;
    private static final double CATCHUP_DISCOUNT = 0.40;

    private final Map<String, StrongBotConfig> configs;
    /** When non-null, this config is used for every seat regardless of playerId (config A/B seam). */
    private final StrongBotConfig fixedConfig;

    public PureDomainStrategy(Map<String, StrongBotConfig> configs) {
        this.configs = Map.copyOf(configs);
        this.fixedConfig = null;
    }

    public PureDomainStrategy() {
        this(Map.of());
    }

    /**
     * Constructs a strategy that applies {@code fixedConfig} to every seat it drives, regardless of
     * player ID. Used for head-to-head config A/B experiments where a single config must be pinned
     * to a seat whose generated player ID is not known upfront.
     */
    public PureDomainStrategy(StrongBotConfig fixedConfig) {
        this.configs = Map.of();
        this.fixedConfig = fixedConfig;
    }

    @Override
    public String name() { return "pure-domain-v1"; }

    // -------------------------------------------------------------------------
    // Primary decision entry point
    // -------------------------------------------------------------------------

    @Override
    public Intent decide(SessionState state, String botId, BotMemory memory, RandomSource rng) {
        if (state.tradeState() != null) {
            TradeState trade = state.tradeState();
            String editor = trade.editingPlayerId();
            if (trade.status() == TradeStatus.COUNTERED && botId.equals(editor)) {
                return decideCounterEditing(state, botId, memory);
            }
            if (trade.status() == TradeStatus.EDITING && botId.equals(editor)) {
                return decideTradeEditing(state, botId, memory);
            }
            if (botId.equals(trade.decisionRequiredFromPlayerId())) {
                return decideTradeResponse(state, botId, memory);
            }
        }

        TurnPhase phase = state.turn() != null ? state.turn().phase() : TurnPhase.UNKNOWN;
        return switch (phase) {
            case WAITING_FOR_ROLL     -> decideRollOrJail(state, botId);
            case WAITING_FOR_CARD_ACK -> new Intent.AcknowledgeCard();
            case WAITING_FOR_END_TURN -> decideEndTurn(state, botId, memory, rng);
            case WAITING_FOR_DECISION -> decidePropertyDecision(state, botId);
            case RESOLVING_DEBT       -> decideDebt(state);
            case WAITING_FOR_AUCTION  -> decideAuction(state, rng);
            default -> new Intent.NoOp();
        };
    }

    // -------------------------------------------------------------------------
    // WAITING_FOR_ROLL (including jail strategy)
    // -------------------------------------------------------------------------

    private static final int JAIL_FINE = 50;

    private Intent decideRollOrJail(SessionState state, String botId) {
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, botId);
        if (player == null || !player.inJail()) return new Intent.Roll();

        StrongBotConfig cfg = configFor(botId);
        int danger = StrongBotStrategy.boardDangerScore(state, botId);

        // Late-game safe-haven: stay jailed when the board is dangerous (just roll for doubles).
        if (cfg.preferJailLateGame() && danger >= cfg.jailExitThreshold()) {
            return new Intent.Roll();
        }

        int reserve = StrongBotStrategy.dynamicReserve(state, botId, cfg);
        boolean hasCard = player.getOutOfJailCards() > 0;
        boolean canAffordFine = player.cash() - JAIL_FINE >= reserve;
        boolean hoardCard = cfg.jailCardHoldBias() >= 1.5;

        if (hasCard && (!hoardCard || !canAffordFine)) return new Intent.UseGetOutOfJailCard();
        if (canAffordFine) return new Intent.PayJailFine();
        if (hasCard)       return new Intent.UseGetOutOfJailCard();
        return new Intent.Roll();
    }

    // -------------------------------------------------------------------------
    // WAITING_FOR_END_TURN
    // -------------------------------------------------------------------------

    private Intent decideEndTurn(SessionState state, String botId, BotMemory memory, RandomSource rng) {
        Optional<Intent> unmortgage = tryUnmortgage(state, botId);
        if (unmortgage.isPresent()) return unmortgage.get();
        Optional<Intent> build = tryBuild(state, botId);
        if (build.isPresent()) return build.get();
        Optional<Intent> mortgageToBuild = tryMortgageToBuild(state, botId);
        if (mortgageToBuild.isPresent()) return mortgageToBuild.get();
        if (state.tradeState() == null) {
            Optional<Intent> trade = tryInitiateTrade(state, botId, memory);
            if (trade.isPresent()) return trade.get();
        }
        return new Intent.EndTurn();
    }

    private Optional<Intent> tryUnmortgage(SessionState state, String playerId) {
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, playerId);
        if (player == null) return Optional.empty();
        int reserve = dynamicReserve(state, playerId);
        StrongBotConfig cfg = configFor(playerId);
        int posAdjustedReserve = (int)(reserve / StrongBotStrategy.positionFactor(state, playerId));

        PropertyStateSnapshot candidate = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && p.mortgaged())
                .filter(p -> {
                    SpotType st = StrongBotStrategy.spotType(p.propertyId());
                    if (st.streetType.placeType == PlaceType.RAILROAD) {
                        return StrongBotStrategy.ownedInSet(state, playerId, st.streetType) >= 3;
                    }
                    return StrongBotStrategy.botOwnsFullGroup(state, playerId, st.streetType);
                })
                .filter(p -> player.cash() - StrongBotStrategy.unmortgageCost(p.propertyId()) >= posAdjustedReserve)
                .max(java.util.Comparator.comparingDouble(p -> StrongBotStrategy.unmortgageScore(p, state, cfg)))
                .orElse(null);
        if (candidate == null) return Optional.empty();
        return Optional.of(new Intent.Unmortgage(candidate.propertyId()));
    }

    private Optional<Intent> tryBuild(SessionState state, String playerId) {
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, playerId);
        if (player == null) return Optional.empty();
        int reserve = dynamicReserve(state, playerId);
        StrongBotConfig cfg = configFor(playerId);
        int posAdjustedReserve = (int)(reserve / StrongBotStrategy.positionFactor(state, playerId));

        StreetType bestGroup = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (StreetType group : StrongBotStrategy.completedColorGroups(state, playerId)) {
            int maxLevel = StrongBotStrategy.maxLevelInGroup(state, playerId, group);
            if (maxLevel >= cfg.buildRoundCap()) continue;
            if (!StrongBotStrategy.canAffordBuildRound(state, player, group, posAdjustedReserve)) continue;
            double score = StrongBotStrategy.buildGroupScore(state, playerId, group, cfg);
            if (score > bestScore) { bestScore = score; bestGroup = group; }
        }
        if (bestGroup == null) return Optional.empty();
        PropertyStateSnapshot target = StrongBotStrategy.findBuildTarget(state, playerId, bestGroup);
        if (target == null) return Optional.empty();
        return Optional.of(new Intent.BuildHouses(target.propertyId()));
    }

    /**
     * When the bot owns a monopoly worth developing but cannot afford the build round from cash,
     * mortgage its most expendable non-synergy deed to fund it. Reusing a low-rent isolated
     * property as build capital is strongly +EV — the houses earn far more than the deed's rent.
     *
     * <p>Only fires when (1) the group is genuinely worth building (positive build score), (2) the
     * round is unaffordable from cash above the reserve, and (3) the freed cash actually enables the
     * round. {@code tryBuild} runs first each turn-step, so once enough cash is raised the bot builds
     * on its next step; when no expendable deed remains, this returns empty and the turn ends —
     * guaranteeing termination.
     */
    private Optional<Intent> tryMortgageToBuild(SessionState state, String playerId) {
        StrongBotConfig cfg = configFor(playerId);
        if (!cfg.mortgageToBuild()) return Optional.empty();
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, playerId);
        if (player == null) return Optional.empty();
        int reserve = dynamicReserve(state, playerId);
        int posAdjustedReserve = (int)(reserve / StrongBotStrategy.positionFactor(state, playerId));

        StreetType bestGroup = null;
        double bestScore = 0.0;   // strictly positive: only develop groups genuinely worth building
        int bestRoundCost = 0;
        for (StreetType group : StrongBotStrategy.completedColorGroups(state, playerId)) {
            if (StrongBotStrategy.maxLevelInGroup(state, playerId, group) >= cfg.buildRoundCap()) continue;
            int roundCost = buildRoundCost(state, playerId, group);
            if (roundCost <= 0) continue;
            // Skip groups already affordable from cash — tryBuild handles those.
            if (player.cash() - roundCost >= posAdjustedReserve) continue;
            double score = StrongBotStrategy.buildGroupScore(state, playerId, group, cfg);
            if (score > bestScore) { bestScore = score; bestGroup = group; bestRoundCost = roundCost; }
        }
        if (bestGroup == null) return Optional.empty();

        int needed = (posAdjustedReserve + bestRoundCost) - player.cash();
        if (needed <= 0) return Optional.empty();
        PropertyStateSnapshot toMortgage = findMortgageCandidateForPurchase(state, playerId, needed);
        if (toMortgage == null) return Optional.empty();
        return Optional.of(new Intent.MortgageProperty(toMortgage.propertyId()));
    }

    /** Total cost to add one house to every unmortgaged property in a group the player owns. */
    private static int buildRoundCost(SessionState state, String playerId, StreetType group) {
        return state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && spotType(p.propertyId()).streetType == group)
                .mapToInt(p -> spotType(p.propertyId()).getIntegerProperty("housePrice"))
                .sum();
    }

    private Optional<Intent> tryInitiateTrade(SessionState state, String botId, BotMemory memory) {
        PlayerSnapshot bot = StrongBotStrategy.findPlayer(state, botId);
        int reserve = dynamicReserve(state, botId);
        if (bot == null || bot.cash() < reserve + 50) return Optional.empty();

        // Pass 0: win-win swap
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (memory.declineCount(other.playerId()) >= MAX_DECLINES_PER_PARTNER) continue;
            String winWinTarget = findWinWinTargetProperty(state, botId, other.playerId());
            if (winWinTarget != null
                    && !memory.declinedSwapTargets(other.playerId()).contains(winWinTarget)) {
                return Optional.of(new Intent.ProposeTrade(other.playerId()));
            }
        }

        // Pass 1: P1 (monopoly-completing) target
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (memory.declineCount(other.playerId()) >= MAX_DECLINES_PER_PARTNER) continue;
            String target = findCriticalTargetProperty(state, botId, other.playerId());
            if (target != null
                    && !memory.declinedSwapTargets(other.playerId()).contains(target)) {
                return Optional.of(new Intent.ProposeTrade(other.playerId()));
            }
        }

        // Pass 2: P3 foothold acquisition
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (memory.declineCount(other.playerId()) >= MAX_DECLINES_PER_PARTNER) continue;
            String targetProp = findStrategicTargetProperty(state, botId, other.playerId());
            if (targetProp == null) continue;
            int price = SpotType.valueOf(targetProp).getIntegerProperty("price");
            int available = Math.max(0, bot.cash() - reserve);
            int wouldOffer = Math.min(price, available);
            if (wouldOffer < 10) continue;
            int prevDeclined = memory.lastDeclinedAmount(other.playerId(), targetProp);
            if (wouldOffer <= prevDeclined) continue;
            return Optional.of(new Intent.ProposeTrade(other.playerId()));
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // WAITING_FOR_DECISION (property purchase)
    // -------------------------------------------------------------------------

    private Intent decidePropertyDecision(SessionState state, String botId) {
        PendingDecision decision = state.pendingDecision();
        if (decision == null) {
            if (state.tradeState() != null) {
                log.warn("Bot in WAITING_FOR_DECISION with active trade but no pending decision — NoOp");
                return new Intent.NoOp();
            }
            return new Intent.EndTurn();
        }
        if (decision.payload() instanceof PropertyPurchaseDecisionPayload purchase) {
            PlayerSnapshot player = StrongBotStrategy.findPlayer(state, botId);
            int cash = player != null ? player.cash() : 0;
            return decidePropertyPurchase(state, botId, decision, purchase, cash);
        }
        return new Intent.EndTurn();
    }

    private Intent decidePropertyPurchase(SessionState state, String botId,
                                           PendingDecision decision,
                                           PropertyPurchaseDecisionPayload purchase, int cash) {
        StrongBotConfig cfg = configFor(botId);
        int reserve = dynamicReserve(state, botId);
        int postCash = cash - purchase.price();
        String propId = purchase.propertyId();

        if (postCash < 0) {
            PropertyStateSnapshot toMortgage =
                    findMortgageCandidateForPurchase(state, botId, purchase.price() - cash);
            if (toMortgage != null) {
                return new Intent.MortgageProperty(toMortgage.propertyId());
            }
            return new Intent.DeclineProperty(decision.decisionId(), propId);
        }

        boolean completesSet = StrongBotStrategy.wouldCompleteSet(state, botId, propId);
        if (completesSet) {
            boolean buy = postCash >= Math.max(0, reserve - 100);
            return buy
                    ? new Intent.BuyProperty(decision.decisionId(), propId)
                    : new Intent.DeclineProperty(decision.decisionId(), propId);
        }

        if (postCash < reserve) {
            return new Intent.DeclineProperty(decision.decisionId(), propId);
        }

        double posFactor = StrongBotStrategy.positionFactor(state, botId);
        double score = StrongBotStrategy.buyScore(state, botId, propId, cfg);
        double threshold = StrongBotStrategy.buyThreshold(state, propId, cfg) / posFactor;
        return score >= threshold
                ? new Intent.BuyProperty(decision.decisionId(), propId)
                : new Intent.DeclineProperty(decision.decisionId(), propId);
    }

    // -------------------------------------------------------------------------
    // RESOLVING_DEBT
    // -------------------------------------------------------------------------

    private Intent decideDebt(SessionState state) {
        DebtStateModel debt = state.activeDebt();
        if (debt == null) return new Intent.NoOp();
        String debtorId = debt.debtorPlayerId();
        List<DebtAction> allowed = debt.allowedActions();

        if (allowed.contains(DebtAction.PAY_DEBT_NOW) && debt.currentCash() >= debt.amountRemaining()) {
            return new Intent.ResolveDebt(debt.debtId(), DebtAction.PAY_DEBT_NOW, null);
        }
        if (allowed.contains(DebtAction.SELL_BUILDING)) {
            var buildingProp = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && buildingLevel(p) > 0)
                    .filter(p -> evenSellEligible(state, p))
                    .min(java.util.Comparator
                            .comparingDouble((PropertyStateSnapshot p) ->
                                    StrongBotStrategy.debtBuildingSellScore(state, debtorId, p))
                            .thenComparingInt(p -> -buildingLevel(p)));
            if (buildingProp.isPresent()) {
                return new Intent.ResolveDebt(debt.debtId(), DebtAction.SELL_BUILDING,
                        buildingProp.get().propertyId());
            }
        }
        if (allowed.contains(DebtAction.MORTGAGE_PROPERTY)) {
            var toMortgage = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && !p.mortgaged())
                    .min(java.util.Comparator.comparingInt(
                            p -> StrongBotStrategy.debtMortgagePriority(state, debtorId, p)));
            if (toMortgage.isPresent()) {
                return new Intent.ResolveDebt(debt.debtId(), DebtAction.MORTGAGE_PROPERTY,
                        toMortgage.get().propertyId());
            }
        }
        if (allowed.contains(DebtAction.DECLARE_BANKRUPTCY)) {
            return new Intent.DeclareBankruptcy(debt.debtId());
        }
        return new Intent.NoOp();
    }

    // -------------------------------------------------------------------------
    // WAITING_FOR_AUCTION
    // -------------------------------------------------------------------------

    private Intent decideAuction(SessionState state, RandomSource rng) {
        AuctionState auction = state.auctionState();
        if (auction == null) return new Intent.NoOp();
        if (auction.status() == AuctionStatus.WON_PENDING_RESOLUTION) {
            return new Intent.FinishAuction(auction.auctionId());
        }
        String bidderId = auction.currentActorPlayerId();
        if (bidderId == null) return new Intent.NoOp();
        PlayerSnapshot bidder = StrongBotStrategy.findPlayer(state, bidderId);
        int cash = bidder != null ? bidder.cash() : 0;
        int minBid = auction.minimumNextBid();

        if (minBid > 0) {
            StrongBotConfig cfg = configFor(bidderId);
            String propId = auction.propertyId();
            int facePrice = propId != null
                    ? SpotType.valueOf(propId).getIntegerProperty("price") : minBid;

            // Classify the strategic stakes of winning this property. A monopoly is the
            // game's money engine, so completing our own set — or denying an opponent
            // who is one deed away — is worth paying far above face price for: the
            // long-term rent more than repays the auction premium. Once an opponent
            // takes the missing piece of a contested group, that monopoly is dead for
            // everyone, so a live foothold is also worth defending above face.
            boolean completesOwnSet = false;
            boolean blocksOpponent = false;
            boolean advancesOwnSet = false;
            double groupStrength = 1.0;
            double opponentThreat = 1.0;
            if (propId != null) {
                StreetType aGroup = StrongBotStrategy.spotType(propId).streetType;
                int aSize = StrongBotStrategy.setSize(aGroup);
                if (aSize > 1) {
                    int botOwns = StrongBotStrategy.ownedInSet(state, bidderId, aGroup);
                    completesOwnSet = botOwns == aSize - 1;
                    advancesOwnSet = botOwns >= 1 && botOwns < aSize - 1;
                    for (PlayerSnapshot p : state.players()) {
                        if (p.playerId().equals(bidderId) || p.bankrupt() || p.eliminated()) continue;
                        if (StrongBotStrategy.ownedInSet(state, p.playerId(), aGroup) == aSize - 1) {
                            blocksOpponent = true;
                            opponentThreat = Math.max(opponentThreat,
                                    StrongBotStrategy.threatScore(state, p.playerId()));
                        }
                    }
                    if (aGroup.placeType == PlaceType.STREET) {
                        groupStrength = StrongBotStrategy.streetStrengthScore(aGroup); // 1..5
                    }
                }
            }
            boolean monopolyCritical = completesOwnSet || blocksOpponent;

            // Reserve: keep a survival buffer normally, but for a monopoly-critical bid
            // spend down to the minimum floor — winning the set IS the path to safety.
            double posFactor = StrongBotStrategy.positionFactor(state, bidderId);
            int reserve;
            if (monopolyCritical) {
                reserve = Math.max(cfg.minCashReserve(), (int)(cfg.minCashReserve() / posFactor));
            } else {
                reserve = Math.min(dynamicReserve(state, bidderId), cfg.dangerCashReserve());
                reserve = Math.max(cfg.minCashReserve(), (int)(reserve / posFactor));
            }
            int available = Math.max(0, cash - reserve);

            // Base ceiling never exceeds face price for a property with no strategic value.
            int ceiling = Math.min(facePrice, (int)(facePrice * cfg.auctionAggression()));
            if (completesOwnSet) {
                // Spend nearly all discretionary cash; stronger groups justify spending more.
                double share = Math.min(1.0, 0.6 + groupStrength * 0.08); // 0.68 … 1.0
                ceiling = Math.max(ceiling, Math.max((int)(available * share), facePrice * 2));
            } else if (blocksOpponent) {
                // Denying a monopoly is nearly as valuable, scaled by how dangerous the opponent is.
                double share = Math.min(0.9, 0.45 + opponentThreat * 0.20);
                ceiling = Math.max(ceiling, Math.max((int)(available * share), (int)(facePrice * 1.5)));
            } else if (advancesOwnSet) {
                // Keep a live monopoly path: premium above face, scaled by group strength.
                double premium = 0.40 + groupStrength * 0.12; // 0.52 … 1.0 over face
                ceiling = Math.max(ceiling, facePrice + (int)(facePrice * premium));
            }
            int maxBid = Math.min(ceiling, available);

            java.util.Set<String> activeBidders = new java.util.HashSet<>(auction.eligiblePlayerIds());
            activeBidders.removeAll(auction.passedPlayerIds());
            activeBidders.remove(bidderId);
            boolean noEffectiveCompetition = activeBidders.isEmpty() || activeBidders.stream()
                    .allMatch(id -> {
                        PlayerSnapshot p = StrongBotStrategy.findPlayer(state, id);
                        return p == null || p.cash() < 2 * minBid;
                    });
            if (noEffectiveCompetition && maxBid >= minBid) {
                return new Intent.Bid(auction.auctionId(), minBid);
            }
            if (maxBid >= minBid) {
                int headroom = maxBid - minBid;
                double factor = 0.6 + rng.nextDouble() * 0.8;
                int extra = ((int)(headroom / 3.0 * factor) / 10) * 10;
                int bid = Math.min(maxBid, minBid + Math.max(10, extra));
                return new Intent.Bid(auction.auctionId(), bid);
            }
            int mortgageValue = propId != null ? facePrice / 2 : 0;
            if (minBid <= mortgageValue && cash >= minBid) {
                return new Intent.Bid(auction.auctionId(), minBid);
            }
        }
        return new Intent.PassAuction(auction.auctionId());
    }

    // -------------------------------------------------------------------------
    // Trade decision (WAITING_FOR_DECISION on incoming offer)
    // -------------------------------------------------------------------------

    private Intent decideTradeResponse(SessionState state, String botId, BotMemory memory) {
        TradeState trade = state.tradeState();
        String tradeId = trade.tradeId();
        TradeOfferState offer = trade.currentOffer();
        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());
        String tradePartnerId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();

        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();

        int valueReceived = evaluateSelectionContextual(state, botId, myReceiving, true);
        int valueGiven    = evaluateSelectionContextual(state, botId, myGiving, false)
                          + monopolyGiftPenalty(state, tradePartnerId, myGiving, memory);

        boolean botGetsNothing = myReceiving.propertyIds().isEmpty()
                && myReceiving.moneyAmount() <= 0 && myReceiving.jailCardCount() <= 0;
        boolean botGivesSomething = !myGiving.propertyIds().isEmpty()
                || myGiving.moneyAmount() > 0 || myGiving.jailCardCount() > 0;
        if (botGetsNothing && botGivesSomething) {
            long counterCount = trade.history().stream()
                    .filter(e -> "COUNTERED".equals(e.actionType())).count();
            boolean botWantsSomething = findStrategicTargetProperty(state, botId, tradePartnerId) != null;
            if (botWantsSomething && counterCount < MAX_COUNTERS_PER_TRADE) {
                return new Intent.RespondToTrade(Intent.TradeResponse.COUNTER, tradeId);
            }
            return declineOffer(tradeId);
        }

        if (valueGiven > 0 && valueReceived * 3 < valueGiven
                && !completesOwnMonopoly(state, botId, myReceiving)) {
            return declineOffer(tradeId);
        }

        // Decline pure STREET-property shuffles: property-for-property swaps that don't
        // advance any monopoly goal (neither completing nor getting to groupSize-1).
        boolean givingOnlyStreets = !myGiving.propertyIds().isEmpty()
                && myGiving.moneyAmount() <= 0 && myGiving.jailCardCount() <= 0
                && myGiving.propertyIds().stream().allMatch(id -> spotType(id).streetType.placeType == PlaceType.STREET);
        boolean receivingOnlyStreets = !myReceiving.propertyIds().isEmpty()
                && myReceiving.moneyAmount() <= 0 && myReceiving.jailCardCount() <= 0
                && myReceiving.propertyIds().stream().allMatch(id -> spotType(id).streetType.placeType == PlaceType.STREET);
        if (givingOnlyStreets && receivingOnlyStreets && !advancesMonopolyGoal(state, botId, myReceiving)) {
            return declineOffer(tradeId);
        }

        double posFactor = StrongBotStrategy.positionFactor(state, botId);
        int fairnessTolerance = (int)(configFor(botId).tradeFairnessTolerance() * posFactor);
        double ts = StrongBotStrategy.threatScore(state, tradePartnerId);
        int requiredPremium = (int)(valueGiven * ts * 0.25);

        int strategicDiscount = 0;
        if (completesOwnMonopoly(state, botId, myReceiving)) {
            boolean catchingUp = someoneElseHasMonopoly(state, botId) && !playerHasMonopoly(state, botId);
            double discountRate = catchingUp ? CATCHUP_DISCOUNT : MONOPOLY_COMPLETION_DISCOUNT;
            strategicDiscount = (int)(valueGiven * discountRate);
            requiredPremium = 0;
        }

        if (valueReceived >= valueGiven - fairnessTolerance - strategicDiscount + requiredPremium) {
            return new Intent.RespondToTrade(Intent.TradeResponse.ACCEPT, tradeId);
        }

        if (valueGiven > 0) {
            long counterCount = trade.history().stream()
                    .filter(e -> "COUNTERED".equals(e.actionType())).count();
            boolean offerIsReasonable = counterCount == 0 || valueReceived >= valueGiven * 0.25;
            if (offerIsReasonable && counterCount < MAX_COUNTERS_PER_TRADE) {
                return new Intent.RespondToTrade(Intent.TradeResponse.COUNTER, tradeId);
            }
        }

        return declineOffer(tradeId);
    }

    // -------------------------------------------------------------------------
    // Trade counter-editing (COUNTERED state, bot fills counter-offer)
    // -------------------------------------------------------------------------

    private Intent decideCounterEditing(SessionState state, String botId, BotMemory memory) {
        TradeState trade = state.tradeState();
        if (trade == null) return new Intent.NoOp();
        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();

        int attempts = memory.incrementCounterEdits(tradeId);
        if (attempts > 8) {
            log.warn("Bot {} counter-edit loop detected for trade {} ({} attempts) — cancelling",
                    botId.substring(0, 8), tradeId.substring(0, 12), attempts);
            memory.clearCounterEdits(tradeId);
            return cancelAsDecline(botId, trade, memory);
        }

        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        String counterPartnerId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();

        int valueGiven = evaluateSelectionContextual(state, botId, myGiving, false)
                       + monopolyGiftPenalty(state, counterPartnerId, myGiving, memory);
        int currentMoneyReceived = myReceiving.moneyAmount();
        int nonMoneyReceived = evaluateSelectionValue(myReceiving, state) - currentMoneyReceived;

        boolean receiveEmpty = myReceiving.propertyIds().isEmpty()
                && currentMoneyReceived <= 0 && myReceiving.jailCardCount() <= 0;
        boolean giveNonEmpty = !myGiving.propertyIds().isEmpty()
                || myGiving.moneyAmount() > 0 || myGiving.jailCardCount() > 0;
        if (receiveEmpty && giveNonEmpty) {
            boolean botGivesProperty = !myGiving.propertyIds().isEmpty();
            if (!botGivesProperty) {
                memory.clearCounterEdits(tradeId);
                return cancelAsDecline(botId, trade, memory);
            }
            if (givingBreaksOwnMonopoly(state, botId, myGiving)) {
                memory.clearCounterEdits(tradeId);
                return cancelAsDecline(botId, trade, memory);
            }
            double margin = saleMargin(StrongBotStrategy.threatScore(state, counterPartnerId), configFor(botId));
            boolean completesBuyerSet = myGiving.propertyIds().stream()
                    .anyMatch(id -> wouldCompletePartnerMonopoly(state, counterPartnerId, id));
            if (completesBuyerSet) margin *= MONOPOLY_SALE_PREMIUM;
            int askingPrice = (int)(valueGiven * margin);
            PlayerSnapshot partnerSnap = StrongBotStrategy.findPlayer(state, counterPartnerId);
            int partnerCash = partnerSnap != null ? partnerSnap.cash() : 0;
            if (askingPrice >= 10 && partnerCash >= askingPrice) {
                return new Intent.EditTrade(tradeId,
                        new TradeEditPatch(null, botIsRecipient, askingPrice, List.of(), List.of(), null));
            }
            memory.clearCounterEdits(tradeId);
            return cancelAsDecline(botId, trade, memory);
        }

        double ts = StrongBotStrategy.threatScore(state, counterPartnerId);
        double profitFactor = saleMargin(ts, configFor(botId));
        int targetMoneyReceived = Math.max(0, (int)(valueGiven * profitFactor) - nonMoneyReceived);

        if (givingBreaksOwnMonopoly(state, botId, myGiving)) {
            memory.clearCounterEdits(tradeId);
            return cancelAsDecline(botId, trade, memory);
        }

        boolean editOfferedSide = botIsRecipient;
        boolean editGiveSide    = !editOfferedSide;

        int givenMoney = myGiving.moneyAmount();
        if (givenMoney > 0 && (targetMoneyReceived > 0 || currentMoneyReceived > 0)) {
            if (!myGiving.propertyIds().isEmpty()) {
                return new Intent.EditTrade(tradeId,
                        new TradeEditPatch(null, editGiveSide, 0, List.of(), List.of(), null));
            } else {
                int fairGiveMoney = Math.max(0, (int)(nonMoneyReceived * 0.95));
                if (fairGiveMoney < 10) {
                    memory.clearCounterEdits(tradeId);
                    return new Intent.SubmitTrade(tradeId);
                }
                return new Intent.EditTrade(tradeId,
                        new TradeEditPatch(null, editGiveSide, fairGiveMoney, List.of(), List.of(), null));
            }
        }

        if (currentMoneyReceived >= targetMoneyReceived - 2) {
            memory.clearCounterEdits(tradeId);
            return new Intent.SubmitTrade(tradeId);
        }

        String otherPartyId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();
        PlayerSnapshot otherParty = StrongBotStrategy.findPlayer(state, otherPartyId);
        int proposerCash = otherParty != null ? otherParty.cash() : 0;

        if (proposerCash >= targetMoneyReceived) {
            if (targetMoneyReceived == currentMoneyReceived) {
                memory.clearCounterEdits(tradeId);
                return new Intent.SubmitTrade(tradeId);
            }
            return new Intent.EditTrade(tradeId,
                    new TradeEditPatch(null, editOfferedSide, targetMoneyReceived, List.of(), List.of(), null));
        }

        String bridgeProp = findUnrequestedTarget(state, botId, otherPartyId, myReceiving);
        if (bridgeProp != null) {
            List<String> updated = new ArrayList<>(myReceiving.propertyIds());
            updated.add(bridgeProp);
            return new Intent.EditTrade(tradeId,
                    new TradeEditPatch(null, editOfferedSide, null, updated, List.of(), null));
        }

        if (proposerCash >= valueGiven) {
            if (proposerCash == currentMoneyReceived) {
                memory.clearCounterEdits(tradeId);
                return new Intent.SubmitTrade(tradeId);
            }
            return new Intent.EditTrade(tradeId,
                    new TradeEditPatch(null, editOfferedSide, proposerCash, List.of(), List.of(), null));
        }

        memory.clearCounterEdits(tradeId);
        return cancelAsDecline(botId, trade, memory);
    }

    // -------------------------------------------------------------------------
    // Trade editing (EDITING state, bot fills offer it opened)
    // -------------------------------------------------------------------------

    private Intent decideTradeEditing(SessionState state, String botId, BotMemory memory) {
        TradeState trade = state.tradeState();
        if (trade == null) return new Intent.NoOp();
        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();
        boolean iAmProposer = botId.equals(trade.initiatorPlayerId());

        TradeSelectionState myGive    = iAmProposer ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myRequest = iAmProposer ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        boolean giveSide    = iAmProposer;
        boolean requestSide = !iAmProposer;

        // Step 1: request a target property from partner
        if (myRequest.propertyIds().isEmpty()) {
            String partnerId = iAmProposer ? trade.recipientPlayerId() : trade.initiatorPlayerId();
            String targetProp = findStrategicTargetProperty(state, botId, partnerId);
            if (targetProp == null) {
                return cancelAsDecline(botId, trade, memory);
            }
            List<String> targets = bundleTargetsForGroup(state, botId, partnerId, targetProp);
            return new Intent.EditTrade(tradeId,
                    new TradeEditPatch(null, requestSide, null, targets, List.of(), null));
        }

        String targetPropId0 = myRequest.propertyIds().get(0);
        String partnerId0 = iAmProposer ? trade.recipientPlayerId() : trade.initiatorPlayerId();
        int targetPrice = myRequest.propertyIds().stream()
                .mapToInt(id -> SpotType.valueOf(id).getIntegerProperty("price"))
                .sum();
        PlayerSnapshot botSnap = StrongBotStrategy.findPlayer(state, botId);
        int available0 = botSnap != null ? Math.max(0, botSnap.cash() - dynamicReserve(state, botId)) : 0;

        // Step 2a: offer own property as sweetener
        boolean targetIsP1 = isMonopolyCompletingTarget(state, botId, targetPropId0);
        if (myGive.propertyIds().isEmpty() && myGive.moneyAmount() == 0) {
            if (available0 < targetPrice || targetIsP1) {
                String expendable = findExpendableOwnProperty(state, botId, partnerId0, targetPropId0);
                if (expendable != null) {
                    return new Intent.EditTrade(tradeId,
                            new TradeEditPatch(null, giveSide, null, List.of(expendable), List.of(), null));
                }
            }
        }

        // Step 2: offer money
        if (myGive.moneyAmount() == 0) {
            int ownPropValue = myGive.propertyIds().stream()
                    .mapToInt(id -> SpotType.valueOf(id).getIntegerProperty("price"))
                    .sum();
            int cashNeeded = Math.max(0, targetPrice - ownPropValue);
            if (cashNeeded > 0) {
                int offerAmount = Math.min(cashNeeded, available0);
                boolean hasOwnProp = !myGive.propertyIds().isEmpty();
                int prevDeclined = memory.lastDeclinedAmount(partnerId0, targetPropId0);
                int shortfall = cashNeeded - offerAmount;
                boolean tooShortOnCash = hasOwnProp
                        ? shortfall > configFor(botId).tradeFairnessTolerance()
                        : offerAmount <= prevDeclined;
                if (offerAmount < 10 || tooShortOnCash) {
                    return cancelAsDecline(botId, trade, memory);
                }
                return new Intent.EditTrade(tradeId,
                        new TradeEditPatch(null, giveSide, offerAmount, List.of(), List.of(), null));
            }
        }

        // Step 3: submit
        return new Intent.SubmitTrade(tradeId);
    }

    // -------------------------------------------------------------------------
    // Trade memory helpers
    // -------------------------------------------------------------------------

    /**
     * Declines the offer. The opener's memory (declineCount / declinedSwapTargets /
     * lastDeclinedAmount) is updated by the runtime that owns all memories —
     * {@code PureDomainBotDriver} at dispatch, {@code HeadlessGameRunner} in its loop —
     * because this strategy only ever sees the acting bot's own memory.
     */
    private Intent declineOffer(String tradeId) {
        return new Intent.RespondToTrade(Intent.TradeResponse.DECLINE, tradeId);
    }

    private Intent cancelAsDecline(String botId, TradeState trade, BotMemory memory) {
        if (trade != null) {
            String partnerId = botId.equals(trade.initiatorPlayerId())
                    ? trade.recipientPlayerId() : trade.initiatorPlayerId();
            memory.recordDecline(partnerId);
            log.debug("Bot {} recorded self-cancel as decline for partner {} (cumulative: {})",
                    botId.substring(0, 8), partnerId, memory.declineCount(partnerId));
        }
        return new Intent.CancelTrade(trade != null ? trade.tradeId() : "");
    }

    // -------------------------------------------------------------------------
    // Trade property helpers
    // -------------------------------------------------------------------------

    private String findWinWinTargetProperty(SessionState state, String botId, String partnerId) {
        boolean botCanCompletePartner = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0)
                .anyMatch(p -> wouldCompletePartnerMonopoly(state, partnerId, p.propertyId()));
        if (!botCanCompletePartner) return null;
        String botWants = findCriticalTargetProperty(state, botId, partnerId);
        if (botWants == null) botWants = findStrategicTargetProperty(state, botId, partnerId);
        return botWants;
    }

    private String findCriticalTargetProperty(SessionState state, String botId, String partnerId) {
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns != groupSize - 1) continue;
            String found = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType == group)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (found != null) return found;
        }
        return null;
    }

    private String findStrategicTargetProperty(SessionState state, String botId, String partnerId) {
        // Priority 1: completes bot's street monopoly
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns != groupSize - 1) continue;
            String found = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType == group)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (found != null) return found;
        }
        // Priority 2: railroad if bot has ≥1
        long botRailroads = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId())
                        && spotType(p.propertyId()).streetType.placeType == PlaceType.RAILROAD)
                .count();
        if (botRailroads >= 1) {
            String rr = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType.placeType == PlaceType.RAILROAD)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (rr != null) return rr;
        }
        // Priority 2.5: block partner one away from monopoly
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer gSize = SpotType.getNumberOfSpots(group);
            if (gSize == null || gSize == 0) continue;
            long partnerOwns = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (partnerOwns != gSize - 1) continue;
            String blockPiece = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType == group)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (blockPiece != null) return blockPiece;
        }
        // Priority 3: foothold group (descending bot ownership)
        List<StreetType> groupsByBotOwnership = java.util.Arrays.stream(StreetType.values())
                .filter(g -> g.placeType == PlaceType.STREET)
                .sorted(java.util.Comparator.comparingLong((StreetType g) ->
                        state.properties().stream()
                                .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == g)
                                .count()).reversed())
                .toList();
        for (StreetType group : groupsByBotOwnership) {
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns == 0) continue;
            String found = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType == group)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (found != null) return found;
        }
        return null;
    }

    private String findUnrequestedTarget(SessionState state, String botId, String partnerId,
                                         TradeSelectionState alreadyRequested) {
        java.util.Set<String> have = new java.util.HashSet<>(alreadyRequested.propertyIds());
        String primary = findStrategicTargetProperty(state, botId, partnerId);
        if (primary != null && !have.contains(primary)) return primary;
        return state.properties().stream()
                .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0)
                .map(PropertyStateSnapshot::propertyId)
                .filter(id -> !have.contains(id))
                .filter(id -> {
                    StreetType g = spotType(id).streetType;
                    if (g.placeType != PlaceType.STREET) return false;
                    return state.properties().stream().anyMatch(q ->
                            botId.equals(q.ownerPlayerId()) && spotType(q.propertyId()).streetType == g);
                })
                .findFirst().orElse(null);
    }

    private List<String> bundleTargetsForGroup(SessionState state, String botId,
                                               String partnerId, String primaryTarget) {
        StreetType group = spotType(primaryTarget).streetType;
        if (group == null || group.placeType != PlaceType.STREET) return List.of(primaryTarget);
        Integer groupSize = SpotType.getNumberOfSpots(group);
        if (groupSize == null || groupSize == 0) return List.of(primaryTarget);
        long botOwns = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .count();
        long needed = groupSize - botOwns;
        if (needed <= 1) return List.of(primaryTarget);
        List<String> partnerPieces = state.properties().stream()
                .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0
                        && spotType(p.propertyId()).streetType == group)
                .map(PropertyStateSnapshot::propertyId)
                .limit(needed)
                .toList();
        return partnerPieces.isEmpty() ? List.of(primaryTarget) : partnerPieces;
    }

    private String findExpendableOwnProperty(SessionState state, String botId, String partnerId,
                                              String requestedPropId) {
        StreetType requestedGroup = requestedPropId != null ? spotType(requestedPropId).streetType : null;
        return state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0)
                .filter(p -> requestedGroup == null || spotType(p.propertyId()).streetType != requestedGroup)
                .filter(p -> StrongBotStrategy.debtMortgagePriority(state, botId, p) <= 3)
                .filter(p -> !wouldCompletePartnerMonopoly(state, partnerId, p.propertyId()))
                .max(java.util.Comparator.comparingDouble(
                        p -> deadweightScore(state, botId, p.propertyId())))
                .map(PropertyStateSnapshot::propertyId)
                .orElse(null);
    }

    private PropertyStateSnapshot findMortgageCandidateForPurchase(SessionState state,
                                                                     String playerId, int needed) {
        List<PropertyStateSnapshot> candidates = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && !p.mortgaged())
                .filter(p -> {
                    SpotType st = spotType(p.propertyId());
                    if (st.streetType.placeType == PlaceType.STREET) {
                        return state.properties().stream()
                                .filter(q -> spotType(q.propertyId()).streetType == st.streetType)
                                .noneMatch(q -> q.houseCount() > 0 || q.hotelCount() > 0);
                    }
                    return true;
                })
                .filter(p -> !StrongBotStrategy.botOwnsFullGroup(state, playerId,
                        spotType(p.propertyId()).streetType))
                .toList();
        if (candidates.isEmpty()) return null;
        int totalRaisable = candidates.stream()
                .mapToInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price") / 2)
                .sum();
        if (totalRaisable < needed) return null;
        return candidates.stream()
                .min(java.util.Comparator
                        .comparingInt((PropertyStateSnapshot p) ->
                                spotType(p.propertyId()).streetType.placeType == PlaceType.STREET ? 1 : 0)
                        .thenComparingInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price")))
                .orElse(null);
    }

    // -------------------------------------------------------------------------
    // Trade valuation helpers
    // -------------------------------------------------------------------------

    /**
     * Profit margin demanded when selling a deed in a trade. The markup above the deed's
     * contextual value scales with the partner's threat (charge the leader more) and with the
     * configured {@link StrongBotConfig#tradeSaleAggression()}. At aggression 1.0 this reproduces
     * the legacy {@code 1.05 + threatScore × 0.20} margin.
     */
    private static double saleMargin(double threatScore, StrongBotConfig cfg) {
        return 1.0 + (0.05 + threatScore * 0.20) * cfg.tradeSaleAggression();
    }

    private int monopolyGiftPenalty(SessionState state, String partnerId,
                                     TradeSelectionState giving, BotMemory memory) {
        int penalty = 0;
        StrongBotConfig cfg = configFor(partnerId);
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long inGiving = giving.propertyIds().stream()
                    .filter(id -> spotType(id).streetType == group).count();
            if (inGiving == 0) continue;
            long partnerOwns = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (partnerOwns + inGiving >= groupSize) {
                int groupPriceSum = (int) state.properties().stream()
                        .filter(p -> spotType(p.propertyId()).streetType == group)
                        .mapToInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price"))
                        .sum();
                int rawPenalty = groupPriceSum + cfg.tradeSetCompletionWeight();
                boolean stalemate = noMonopoliesExist(state)
                        && memory.turnsSinceMonopolyChange() >= STALEMATE_TURN_THRESHOLD;
                if (stalemate) {
                    rawPenalty = (int)(rawPenalty * STALEMATE_GIFT_RELIEF);
                } else {
                    rawPenalty = (int)(rawPenalty * partnerBuildabilityFactor(state, partnerId, group));
                }
                penalty += rawPenalty;
            }
        }
        return penalty;
    }

    private double partnerBuildabilityFactor(SessionState state, String partnerId, StreetType group) {
        PlayerSnapshot partner = StrongBotStrategy.findPlayer(state, partnerId);
        int cash = partner != null ? partner.cash() : 0;
        int buildRound = java.util.Arrays.stream(SpotType.values())
                .filter(s -> s.streetType == group)
                .mapToInt(s -> { Integer hp = s.getIntegerProperty("housePrice"); return hp != null ? hp : 0; })
                .sum();
        if (buildRound <= 0) return 1.0;
        if (cash >= buildRound * 2) return 1.25;
        if (cash >= buildRound)     return 1.0;
        if (cash >= buildRound / 2) return 0.8;
        return 0.6;
    }

    private int evaluateSelectionContextual(SessionState state, String botId,
                                             TradeSelectionState selection, boolean receiving) {
        StrongBotConfig cfg = configFor(botId);
        int value = (int)(selection.moneyAmount() * cfg.tradeLiquidityWeight());
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            int facePrice = SpotType.valueOf(propId).getIntegerProperty("price");
            PropertyStateSnapshot snap = state.properties().stream()
                    .filter(p -> propId.equals(p.propertyId())).findFirst().orElse(null);
            boolean mortgaged = snap != null && snap.mortgaged();
            if (mortgaged) {
                value += Math.max(0, facePrice / 2 - StrongBotStrategy.unmortgageCost(propId));
            } else {
                value += facePrice;
                if (snap != null && (snap.houseCount() > 0 || snap.hotelCount() > 0)) {
                    int housePrice = SpotType.valueOf(propId).getIntegerProperty("housePrice");
                    int buildingUnits = snap.houseCount() + snap.hotelCount() * 5;
                    value += buildingUnits * housePrice;
                }
            }
        }
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long inSelection = selection.propertyIds().stream()
                    .filter(id -> spotType(id).streetType == group).count();
            if (inSelection == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            int groupPriceSum = (int) state.properties().stream()
                    .filter(p -> spotType(p.propertyId()).streetType == group)
                    .mapToInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price"))
                    .sum();
            int setWeight = cfg.tradeSetCompletionWeight();
            if (receiving) {
                long afterReceive = botOwns + inSelection;
                if (afterReceive >= groupSize) {
                    value += groupPriceSum + setWeight;
                } else if (afterReceive == groupSize - 1) {
                    value += groupPriceSum / 3 + setWeight / 4;
                }
            } else {
                if (botOwns >= groupSize) {
                    value += groupPriceSum * 2 + setWeight;
                } else if (botOwns == groupSize - 1 && inSelection >= 1) {
                    value += groupPriceSum / 2 + setWeight / 3;
                } else if (botOwns >= 1) {
                    // Foothold premium: don't sell a deed in a group we still have a live
                    // shot at monopolising for bare face price. Scale by group strength so
                    // strong groups (orange/red) are guarded harder than weak ones (brown).
                    double strength = StrongBotStrategy.streetStrengthScore(group); // 1..5
                    value += groupPriceSum / 4 + (int)(setWeight * strength / 20.0);
                }
            }
        }
        return value;
    }

    private static int evaluateSelectionValue(TradeSelectionState selection, SessionState state) {
        int value = selection.moneyAmount();
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            int facePrice = SpotType.valueOf(propId).getIntegerProperty("price");
            boolean mortgaged = state != null && state.properties().stream()
                    .anyMatch(p -> propId.equals(p.propertyId()) && p.mortgaged());
            value += mortgaged ? facePrice / 2 : facePrice;
        }
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long inSelection = selection.propertyIds().stream()
                    .filter(id -> SpotType.valueOf(id).streetType == group)
                    .count();
            if (inSelection == groupSize) {
                int groupValue = selection.propertyIds().stream()
                        .filter(id -> SpotType.valueOf(id).streetType == group)
                        .mapToInt(id -> {
                            int fp = SpotType.valueOf(id).getIntegerProperty("price");
                            boolean m = state != null && state.properties().stream()
                                    .anyMatch(p -> id.equals(p.propertyId()) && p.mortgaged());
                            return m ? fp / 2 : fp;
                        })
                        .sum();
                value += groupValue / 2;
            }
        }
        return value;
    }

    // -------------------------------------------------------------------------
    // Monopoly state checks
    // -------------------------------------------------------------------------

    private boolean playerHasMonopoly(SessionState state, String playerId) {
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long owns = state.properties().stream()
                    .filter(p -> playerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (owns >= groupSize) return true;
        }
        return false;
    }

    private boolean someoneElseHasMonopoly(SessionState state, String botId) {
        return state.players().stream()
                .filter(p -> !p.playerId().equals(botId) && !p.bankrupt() && !p.eliminated())
                .anyMatch(p -> playerHasMonopoly(state, p.playerId()));
    }

    private boolean noMonopoliesExist(SessionState state) {
        return state.players().stream()
                .filter(p -> !p.bankrupt() && !p.eliminated())
                .noneMatch(p -> playerHasMonopoly(state, p.playerId()));
    }

    private boolean isMonopolyCompletingTarget(SessionState state, String botId, String propId) {
        StreetType group = spotType(propId).streetType;
        if (group == null || group.placeType != PlaceType.STREET) return false;
        Integer groupSize = SpotType.getNumberOfSpots(group);
        if (groupSize == null || groupSize == 0) return false;
        long botOwns = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .count();
        return botOwns == groupSize - 1;
    }

    private boolean givingBreaksOwnMonopoly(SessionState state, String botId, TradeSelectionState selection) {
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long inSelection = selection.propertyIds().stream()
                    .filter(id -> spotType(id).streetType == group).count();
            if (inSelection == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns >= groupSize) return true;
        }
        return false;
    }

    private boolean completesOwnMonopoly(SessionState state, String botId, TradeSelectionState selection) {
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long inSelection = selection.propertyIds().stream()
                    .filter(id -> spotType(id).streetType == group).count();
            if (inSelection == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns + inSelection >= groupSize) return true;
        }
        return false;
    }

    private boolean advancesMonopolyGoal(SessionState state, String botId, TradeSelectionState selection) {
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long inSelection = selection.propertyIds().stream()
                    .filter(id -> spotType(id).streetType == group).count();
            if (inSelection == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            long afterReceive = botOwns + inSelection;
            if (afterReceive >= groupSize || afterReceive == groupSize - 1) return true;
        }
        return false;
    }

    private boolean wouldCompletePartnerMonopoly(SessionState state, String partnerId, String propId) {
        StreetType group = spotType(propId).streetType;
        if (group == null || group.placeType != PlaceType.STREET) return false;
        Integer groupSize = SpotType.getNumberOfSpots(group);
        if (groupSize == null || groupSize == 0) return false;
        long partnerOwns = state.properties().stream()
                .filter(p -> partnerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .count();
        return partnerOwns >= groupSize - 1;
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private double deadweightScore(SessionState state, String botId, String propId) {
        StreetType group = spotType(propId).streetType;
        int facePrice = SpotType.valueOf(propId).getIntegerProperty("price");
        Integer groupSize = group != null ? SpotType.getNumberOfSpots(group) : null;
        if (groupSize == null || groupSize == 0) return facePrice * 0.5;
        long botOwnsInGroup = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .count();
        return facePrice * (1.0 - (double) botOwnsInGroup / groupSize);
    }

    private static boolean evenSellEligible(SessionState state, PropertyStateSnapshot prop) {
        SpotType st = spotType(prop.propertyId());
        if (st.streetType.placeType != PlaceType.STREET) return true;
        int level = buildingLevel(prop);
        int maxRest = state.properties().stream()
                .filter(p -> !p.propertyId().equals(prop.propertyId())
                        && spotType(p.propertyId()).streetType == st.streetType)
                .mapToInt(PureDomainStrategy::buildingLevel)
                .max().orElse(0);
        return level - 1 >= maxRest - 1;
    }

    private StrongBotConfig configFor(String playerId) {
        if (fixedConfig != null) return fixedConfig;
        return configs.getOrDefault(playerId, StrongBotConfig.defaults());
    }

    private int dynamicReserve(SessionState state, String playerId) {
        return StrongBotStrategy.dynamicReserve(state, playerId, configFor(playerId));
    }

    private static SpotType spotType(String propertyId) {
        return StrongBotStrategy.spotType(propertyId);
    }

    private static int buildingLevel(PropertyStateSnapshot p) {
        return StrongBotStrategy.buildingLevel(p);
    }
}
