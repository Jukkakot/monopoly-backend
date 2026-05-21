package fi.monopoly.server.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side greedy bot driver for pure-domain sessions.
 *
 * <p>Registers as a {@link ClientSessionListener} on the provided {@link SessionCommandPublisher}.
 * When a state snapshot arrives and the active actor is a bot seat, schedules a delayed command
 * via a {@link ScheduledExecutorService} — giving the client a brief window to observe the state
 * before the bot responds. The greedy strategy mirrors {@code PureDomainGameSimulationTest}:
 * always buy when affordable, pay debt when possible, mortgage otherwise, declare bankruptcy last.</p>
 *
 * <p>Thread safety: {@code pendingAction} is an {@link AtomicBoolean} to prevent double-scheduling.
 * All commands go through the synchronized {@link SessionCommandPublisher#handle} method.</p>
 */
@Slf4j
public final class PureDomainBotDriver implements ClientSessionListener {

    /** Fallback delay used when situational logic cannot determine a better value. */
    private static final long BOT_FALLBACK_DELAY_MS =
            Long.getLong("monopoly.bot.think.delay.ms", 900L);

    private final SessionCommandPublisher publisher;
    private final String sessionId;
    private final Set<String> botPlayerIds;
    private final Map<String, BotDifficulty> difficulties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean pendingAction = new AtomicBoolean(false);

    private PureDomainBotDriver(
            SessionCommandPublisher publisher,
            String sessionId,
            Set<String> botPlayerIds,
            Map<String, BotDifficulty> difficulties) {
        this.publisher = publisher;
        this.sessionId = sessionId;
        this.botPlayerIds = botPlayerIds;
        this.difficulties = difficulties;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("bot-driver-" + sessionId.substring(0, 8), 0).factory());
    }

    /**
     * Creates and registers a {@link PureDomainBotDriver} for any BOT seats in the given state.
     * Returns {@code null} if the session has no bot seats.
     *
     * @param difficulties per-player difficulty; defaults to NORMAL for any player not in the map
     */
    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher,
            SessionState initialState,
            Map<String, BotDifficulty> difficulties) {
        Set<String> botIds = collectBotPlayerIds(initialState);
        if (botIds.isEmpty()) {
            return null;
        }
        PureDomainBotDriver driver = new PureDomainBotDriver(
                publisher, initialState.sessionId(), botIds, Map.copyOf(difficulties));
        publisher.addListener(driver);
        log.info("Bot driver registered for session {} — bots: {} difficulties: {}",
                initialState.sessionId().substring(0, 8), botIds,
                botIds.stream().collect(java.util.stream.Collectors.toMap(
                        id -> id, id -> difficulties.getOrDefault(id, BotDifficulty.NORMAL))));
        // Trigger initial check so a bot-first turn starts without waiting for a human command.
        driver.onSnapshotChanged(ClientSessionSnapshot.from(initialState, true));
        return driver;
    }

    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher, SessionState initialState) {
        return createAndRegisterIfNeeded(publisher, initialState, Map.of());
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // ClientSessionListener
    // -------------------------------------------------------------------------

    @Override
    public void onSnapshotChanged(ClientSessionSnapshot snapshot) {
        SessionState state = snapshot.state();
        if (state == null || state.status() == SessionStatus.GAME_OVER
                || state.status() == SessionStatus.LOBBY) {
            return;
        }
        if (!needsBotAction(state)) {
            return;
        }
        if (!pendingAction.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(this::takeStep, computeDelay(snapshot.state()), TimeUnit.MILLISECONDS);
    }

    // -------------------------------------------------------------------------
    // Bot step
    // -------------------------------------------------------------------------

    private void takeStep() {
        pendingAction.set(false);
        SessionState state = publisher.currentState();
        if (state == null || state.status() == SessionStatus.GAME_OVER
                || state.status() == SessionStatus.LOBBY) {
            return;
        }
        if (!needsBotAction(state)) {
            return;
        }
        dispatchGreedy(state);
    }

    private boolean needsBotAction(SessionState state) {
        String actorId = resolveActorId(state);
        return actorId != null && botPlayerIds.contains(actorId);
    }

    private String resolveActorId(SessionState state) {
        if (state.activeDebt() != null) {
            return state.activeDebt().debtorPlayerId();
        }
        if (state.auctionState() != null) {
            AuctionState auction = state.auctionState();
            // During WON_PENDING_RESOLUTION, currentActorPlayerId is null; use winner so a bot
            // winner can dispatch FinishAuctionResolutionCommand.
            if (auction.status() == AuctionStatus.WON_PENDING_RESOLUTION) {
                return auction.winningPlayerId();
            }
            return auction.currentActorPlayerId();
        }
        if (state.tradeState() != null) {
            // Someone needs to decide (accept/decline/counter)
            if (state.tradeState().decisionRequiredFromPlayerId() != null) {
                return state.tradeState().decisionRequiredFromPlayerId();
            }
            // A bot is in editing mode filling in or countering an offer
            if (state.tradeState().status() == TradeStatus.EDITING
                    && state.tradeState().editingPlayerId() != null) {
                return state.tradeState().editingPlayerId();
            }
        }
        if (state.turn() == null) return null;
        return state.turn().activePlayerId();
    }

    private void dispatchGreedy(SessionState state) {
        // Trade actions may involve a player other than the active turn player — handle first.
        if (state.tradeState() != null) {
            TradeState trade = state.tradeState();
            String tradeActor = trade.decisionRequiredFromPlayerId();
            String editor = trade.editingPlayerId();

            if (tradeActor != null && botPlayerIds.contains(tradeActor)) {
                handleTradeDecision(state, tradeActor);
                return;
            }
            if (trade.status() == TradeStatus.EDITING && editor != null && botPlayerIds.contains(editor)) {
                handleTradeEditing(state, editor);
                return;
            }
        }

        TurnPhase phase = state.turn() != null ? state.turn().phase() : TurnPhase.UNKNOWN;
        String activeId = resolveActorId(state);
        if (activeId == null) return;

        switch (phase) {
            case WAITING_FOR_ROLL -> publisher.handle(new RollDiceCommand(sessionId, activeId));
            case WAITING_FOR_END_TURN -> {
                if (!isEasy(activeId) && tryUnmortgageGreedy(state, activeId)) return;
                if (!isEasy(activeId) && tryBuildGreedy(state, activeId)) return;
                if (isStrong(activeId) && state.tradeState() == null
                        && tryInitiateStrategicTrade(state, activeId)) return;
                publisher.handle(new EndTurnCommand(sessionId, activeId));
            }
            case WAITING_FOR_DECISION -> handleDecision(state, activeId);
            case RESOLVING_DEBT -> handleDebt(state);
            case WAITING_FOR_AUCTION -> handleAuction(state);
            default -> log.debug("Bot driver: unhandled phase {} for player {}", phase, activeId);
        }
    }

    private void handleDecision(SessionState state, String activeId) {
        PendingDecision decision = state.pendingDecision();
        if (decision == null) {
            publisher.handle(new EndTurnCommand(sessionId, activeId));
            return;
        }
        if (decision.payload() instanceof PropertyPurchaseDecisionPayload purchase) {
            PlayerSnapshot player = findPlayer(state, activeId);
            int cash = player != null ? player.cash() : 0;
            boolean canAfford = cash >= purchase.price();
            // EASY mode: skip 40% of affordable purchases
            if (canAfford && isEasy(activeId) && ThreadLocalRandom.current().nextDouble() < 0.40) {
                publisher.handle(new DeclinePropertyCommand(sessionId, activeId, decision.decisionId(), purchase.propertyId()));
            } else if (canAfford) {
                publisher.handle(new BuyPropertyCommand(sessionId, activeId, decision.decisionId(), purchase.propertyId()));
            } else {
                publisher.handle(new DeclinePropertyCommand(sessionId, activeId, decision.decisionId(), purchase.propertyId()));
            }
        } else {
            publisher.handle(new EndTurnCommand(sessionId, activeId));
        }
    }

    private void handleDebt(SessionState state) {
        DebtStateModel debt = state.activeDebt();
        if (debt == null) return;
        String debtorId = debt.debtorPlayerId();
        List<DebtAction> allowed = debt.allowedActions();

        if (allowed.contains(DebtAction.PAY_DEBT_NOW) && debt.currentCash() >= debt.amountRemaining()) {
            publisher.handle(new PayDebtCommand(sessionId, debtorId, debt.debtId()));
            return;
        }
        if (allowed.contains(DebtAction.SELL_BUILDING)) {
            // Pick the eligible property with most buildings (even-selling rule: level >= maxRest)
            var buildingProp = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && buildingLevel(p) > 0)
                    .filter(p -> evenSellEligible(state, p))
                    .max(java.util.Comparator.comparingInt(PureDomainBotDriver::buildingLevel));
            if (buildingProp.isPresent()) {
                publisher.handle(new SellBuildingForDebtCommand(sessionId, debtorId, debt.debtId(), buildingProp.get().propertyId(), 1));
                return;
            }
        }
        if (allowed.contains(DebtAction.MORTGAGE_PROPERTY)) {
            var unmortgaged = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && !p.mortgaged())
                    .findFirst();
            if (unmortgaged.isPresent()) {
                publisher.handle(new MortgagePropertyForDebtCommand(sessionId, debtorId, debt.debtId(), unmortgaged.get().propertyId()));
                return;
            }
        }
        if (allowed.contains(DebtAction.DECLARE_BANKRUPTCY)) {
            publisher.handle(new DeclareBankruptcyCommand(sessionId, debtorId, debt.debtId()));
        }
    }

    private void handleTradeDecision(SessionState state, String botId) {
        TradeState trade = state.tradeState();
        String tradeId = trade.tradeId();

        if (isEasy(botId)) {
            publisher.handle(new DeclineTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // NORMAL: accept if raw monetary value received >= value given
        TradeOfferState offer = trade.currentOffer();
        int valueReceived = evaluateSelectionValue(offer.offeredToRecipient());
        int valueGiven = evaluateSelectionValue(offer.requestedFromRecipient());

        if (valueReceived >= valueGiven) {
            publisher.handle(new AcceptTradeCommand(sessionId, botId, tradeId));
        } else {
            publisher.handle(new DeclineTradeCommand(sessionId, botId, tradeId));
        }
    }

    /**
     * Called when the bot is in EDITING mode (it opened the trade or sent a counter).
     * Fills the offer in stages: first request a target property, then offer money, then submit.
     */
    private void handleTradeEditing(SessionState state, String botId) {
        TradeState trade = state.tradeState();
        if (trade == null) return;

        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();
        boolean iAmProposer = botId.equals(trade.initiatorPlayerId());

        // From the canonical offer perspective:
        // offeredToRecipient = what proposer gives; requestedFromRecipient = what proposer wants.
        TradeSelectionState myGive = iAmProposer ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myRequest = iAmProposer ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        boolean giveSide = iAmProposer;
        boolean requestSide = !iAmProposer;

        // Step 1: request a target property from partner
        if (myRequest.propertyIds().isEmpty()) {
            String partnerId = iAmProposer ? trade.recipientPlayerId() : trade.initiatorPlayerId();
            String targetProp = findStrategicTargetProperty(state, botId, partnerId);
            if (targetProp == null) {
                publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                return;
            }
            publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                    new TradeEditPatch(null, requestSide, null, List.of(targetProp), List.of(), null)));
            return;
        }

        // Step 2: offer money (full price of the target property, capped by available cash)
        if (myGive.moneyAmount() == 0) {
            String targetPropId = myRequest.propertyIds().get(0);
            int price = SpotType.valueOf(targetPropId).getIntegerProperty("price");
            PlayerSnapshot bot = findPlayer(state, botId);
            int available = bot != null ? Math.max(0, bot.cash() - MIN_CASH_RESERVE) : 0;
            int offerAmount = Math.min(price, available);
            if (offerAmount < 10) {
                publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                return;
            }
            publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                    new TradeEditPatch(null, giveSide, offerAmount, List.of(), List.of(), null)));
            return;
        }

        // Step 3: offer is ready — submit it
        publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
    }

    /**
     * STRONG bot tries to open a trade to acquire a property that advances a color group it already
     * partially owns. Returns true if {@link OpenTradeCommand} was dispatched.
     */
    private boolean tryInitiateStrategicTrade(SessionState state, String botId) {
        PlayerSnapshot bot = findPlayer(state, botId);
        if (bot == null || bot.cash() < MIN_CASH_RESERVE + 50) return false;

        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            for (PropertyStateSnapshot prop : state.properties()) {
                if (!other.playerId().equals(prop.ownerPlayerId())) continue;
                if (prop.mortgaged() || prop.houseCount() > 0 || prop.hotelCount() > 0) continue;
                SpotType st = spotType(prop.propertyId());
                if (st.streetType.placeType != PlaceType.STREET) continue;
                if (botWouldBenefitFrom(state, botId, st.streetType)) {
                    CommandResult result = publisher.handle(new OpenTradeCommand(sessionId, botId, other.playerId()));
                    return result.accepted();
                }
            }
        }
        return false;
    }

    /** Returns true if bot already owns at least one property in {@code group}. */
    private boolean botWouldBenefitFrom(SessionState state, String botId, StreetType group) {
        return state.properties().stream()
                .anyMatch(p -> botId.equals(p.ownerPlayerId())
                        && spotType(p.propertyId()).streetType == group);
    }

    /**
     * Finds the highest-priority property in partner's portfolio that would advance the color group
     * where the bot has the most existing properties.
     */
    private String findStrategicTargetProperty(SessionState state, String botId, String partnerId) {
        StreetType bestGroup = null;
        long bestCount = -1;
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            long count = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (count > bestCount) { bestCount = count; bestGroup = group; }
        }
        if (bestGroup == null || bestCount == 0) return null;

        StreetType target = bestGroup;
        return state.properties().stream()
                .filter(p -> partnerId.equals(p.ownerPlayerId()))
                .filter(p -> !p.mortgaged() && p.houseCount() == 0 && p.hotelCount() == 0)
                .filter(p -> spotType(p.propertyId()).streetType == target)
                .map(PropertyStateSnapshot::propertyId)
                .findFirst()
                .orElse(null);
    }

    private static int evaluateSelectionValue(TradeSelectionState selection) {
        int value = selection.moneyAmount();
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            value += SpotType.valueOf(propId).getIntegerProperty("price");
        }
        return value;
    }

    private void handleAuction(SessionState state) {
        AuctionState auction = state.auctionState();
        if (auction == null) return;
        if (auction.status() == AuctionStatus.WON_PENDING_RESOLUTION) {
            publisher.handle(new FinishAuctionResolutionCommand(sessionId, auction.auctionId()));
            return;
        }
        String bidderId = auction.currentActorPlayerId();
        if (bidderId == null || !botPlayerIds.contains(bidderId)) return;
        PlayerSnapshot bidder = findPlayer(state, bidderId);
        int cash = bidder != null ? bidder.cash() : 0;
        int minBid = auction.minimumNextBid();
        // EASY mode: pass 50% of the time even when affordable
        boolean wouldBid = cash >= minBid && minBid > 0;
        if (wouldBid && isEasy(bidderId) && ThreadLocalRandom.current().nextDouble() < 0.50) {
            publisher.handle(new PassAuctionCommand(sessionId, bidderId, auction.auctionId()));
        } else if (wouldBid) {
            publisher.handle(new PlaceAuctionBidCommand(sessionId, bidderId, auction.auctionId(), minBid));
        } else {
            publisher.handle(new PassAuctionCommand(sessionId, bidderId, auction.auctionId()));
        }
    }

    // -------------------------------------------------------------------------
    // Delay computation
    // -------------------------------------------------------------------------

    /**
     * Computes a human-like delay before the bot acts, based on what the current state demands.
     * Harder bots react slightly faster; easier bots are more hesitant.
     * A ±20 % random jitter is applied on top.
     */
    private long computeDelay(SessionState state) {
        if (BOT_FALLBACK_DELAY_MS == 0) return 0;  // instant mode (tests / system property = 0)
        long base = computeBaseDelay(state);
        String actorId = resolveActorId(state);
        BotDifficulty diff = actorId != null
                ? difficulties.getOrDefault(actorId, BotDifficulty.NORMAL)
                : BotDifficulty.NORMAL;
        double diffMult = switch (diff) {
            case EASY   -> 1.25;   // hesitant, slower
            case NORMAL -> 1.0;
            case STRONG -> 0.80;   // more decisive
        };
        long scaled = Math.max(200L, (long) (base * diffMult));
        long jitter = (long) (scaled * 0.20);
        return scaled + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }

    private long computeBaseDelay(SessionState state) {
        // Trade decision: evaluating someone else's offer — most deliberate action
        if (state.tradeState() != null) {
            TradeState trade = state.tradeState();
            if (trade.decisionRequiredFromPlayerId() != null) {
                return 2900;  // reading the offer, weighing it
            }
            if (trade.status() == TradeStatus.EDITING) {
                return 1200;  // filling in individual offer steps
            }
        }

        // Debt: stress level depends on available options
        if (state.activeDebt() != null) {
            DebtStateModel debt = state.activeDebt();
            List<DebtAction> allowed = debt.allowedActions();
            if (allowed.size() == 1 && allowed.contains(DebtAction.DECLARE_BANKRUPTCY)) {
                return 4500;  // dramatic — last resort
            }
            if (allowed.contains(DebtAction.PAY_DEBT_NOW)
                    && debt.currentCash() >= debt.amountRemaining()) {
                return 1050;  // has money, just pays
            }
            return 2600;      // needs to liquidate assets, thinking hard
        }

        // Auction
        if (state.auctionState() != null) {
            AuctionState auction = state.auctionState();
            if (auction.status() == AuctionStatus.WON_PENDING_RESOLUTION) {
                return 650;   // winner confirming pickup — quick
            }
            return 1450;      // deciding whether to bid
        }

        // Normal turn phases
        if (state.turn() == null) return BOT_FALLBACK_DELAY_MS;
        return switch (state.turn().phase()) {
            case WAITING_FOR_ROLL -> 1550;  // pause before throwing dice
            case WAITING_FOR_DECISION -> {
                // Buying a property: longer think when affordable, quick decline when broke
                PendingDecision decision = state.pendingDecision();
                if (decision != null
                        && decision.payload() instanceof PropertyPurchaseDecisionPayload purchase) {
                    PlayerSnapshot actor = findPlayer(state, state.turn().activePlayerId());
                    int cash = actor != null ? actor.cash() : 0;
                    yield cash >= purchase.price() ? 2600 : 900;
                }
                yield 1200;
            }
            case WAITING_FOR_END_TURN -> 900;  // reviewing board briefly before ending
            default -> BOT_FALLBACK_DELAY_MS;
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Keeps enough cash to handle rent and other expenses before spending on buildings or unmortgaging.
     */
    private static final int MIN_CASH_RESERVE = 200;

    /**
     * Unmortgages the cheapest mortgaged property in a group where the bot owns all properties.
     * Returns true if a command was dispatched and accepted (turn continues), false if nothing to do.
     */
    private boolean tryUnmortgageGreedy(SessionState state, String playerId) {
        PlayerSnapshot player = findPlayer(state, playerId);
        if (player == null) return false;
        // Only unmortgage in groups where we own all properties (targeted strategic spending)
        PropertyStateSnapshot candidate = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && p.mortgaged())
                .filter(p -> botOwnsFullGroup(state, playerId, spotType(p.propertyId()).streetType))
                .min(java.util.Comparator.comparingInt(p -> unmortgageCost(p.propertyId())))
                .orElse(null);
        if (candidate == null) return false;
        int cost = unmortgageCost(candidate.propertyId());
        if (player.cash() - cost < MIN_CASH_RESERVE) return false;
        CommandResult result = publisher.handle(
                new ToggleMortgageCommand(sessionId, playerId, candidate.propertyId()));
        return result.accepted();
    }

    private static boolean botOwnsFullGroup(SessionState state, String playerId, StreetType group) {
        Integer groupSize = SpotType.getNumberOfSpots(group);
        if (groupSize == null || groupSize == 0) return false;
        long owned = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId())
                        && spotType(p.propertyId()).streetType == group)
                .count();
        return owned == groupSize;
    }

    private static int unmortgageCost(String propertyId) {
        int mortgageValue = SpotType.valueOf(propertyId).getIntegerProperty("price") / 2;
        return mortgageValue + (int) (mortgageValue * 0.1);
    }

    /**
     * Attempts to buy one house on the lowest-level property in a complete color group.
     * Returns true if a build command was accepted (turn continues), false if nothing buildable.
     * If the domain rejects the build (e.g., bank out of houses), returns false so the caller
     * falls through to EndTurn.
     */
    private boolean tryBuildGreedy(SessionState state, String playerId) {
        PlayerSnapshot player = findPlayer(state, playerId);
        if (player == null) return false;
        for (StreetType group : completedColorGroups(state, playerId)) {
            List<PropertyStateSnapshot> groupProps = state.properties().stream()
                    .filter(p -> playerId.equals(p.ownerPlayerId())
                            && !p.mortgaged()
                            && spotType(p.propertyId()).streetType == group
                            && p.hotelCount() == 0)
                    .toList();
            if (groupProps.isEmpty()) continue;
            int minLevel = groupProps.stream().mapToInt(PropertyStateSnapshot::houseCount).min().orElse(0);
            for (PropertyStateSnapshot target : groupProps) {
                if (target.houseCount() != minLevel) continue;
                int housePrice = spotType(target.propertyId()).getIntegerProperty("housePrice");
                if (housePrice <= 0 || player.cash() < housePrice) continue;
                CommandResult result = publisher.handle(
                        new BuyBuildingRoundCommand(sessionId, playerId, target.propertyId()));
                return result.accepted();
            }
        }
        return false;
    }

    private static Set<StreetType> completedColorGroups(SessionState state, String playerId) {
        Set<StreetType> result = new HashSet<>();
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long owned = state.properties().stream()
                    .filter(p -> playerId.equals(p.ownerPlayerId())
                            && !p.mortgaged()
                            && spotType(p.propertyId()).streetType == group)
                    .count();
            if (owned == groupSize) result.add(group);
        }
        return result;
    }

    private static SpotType spotType(String propertyId) {
        return SpotType.valueOf(propertyId);
    }

    private static int buildingLevel(PropertyStateSnapshot p) {
        return p.hotelCount() > 0 ? 5 : p.houseCount();
    }

    /** Mirrors DomainDebtRemediationGateway even-selling rule: (level-1) >= (maxRest-1), i.e. level >= maxRest. */
    private static boolean evenSellEligible(SessionState state, PropertyStateSnapshot prop) {
        SpotType st = spotType(prop.propertyId());
        if (st.streetType.placeType != PlaceType.STREET) return true;
        int level = buildingLevel(prop);
        int maxRest = state.properties().stream()
                .filter(p -> !p.propertyId().equals(prop.propertyId())
                        && spotType(p.propertyId()).streetType == st.streetType)
                .mapToInt(PureDomainBotDriver::buildingLevel)
                .max().orElse(0);
        return level - 1 >= maxRest - 1;
    }

    private boolean isEasy(String playerId) {
        return difficulties.getOrDefault(playerId, BotDifficulty.NORMAL) == BotDifficulty.EASY;
    }

    private boolean isStrong(String playerId) {
        return difficulties.getOrDefault(playerId, BotDifficulty.NORMAL) == BotDifficulty.STRONG;
    }

    private static Set<String> collectBotPlayerIds(SessionState state) {
        Set<String> ids = new java.util.HashSet<>();
        for (SeatState seat : state.seats()) {
            if (seat.seatKind() == SeatKind.BOT) {
                ids.add(seat.playerId());
            }
        }
        return ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    private static PlayerSnapshot findPlayer(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> playerId.equals(p.playerId()))
                .findFirst().orElse(null);
    }
}
