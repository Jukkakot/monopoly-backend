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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final long DEFAULT_BOT_DELAY_MS = 900L;

    /** Minimum delay even in "fast" mode — prevents server overload from rapid-fire bot games. */
    private static final long MIN_FAST_DELAY_MS = 50L;

    private static final int MAX_DECLINES_PER_PARTNER = 2;

    /**
     * Maximum number of state versions the bot is allowed to advance beyond the last
     * client-acknowledged version. Keeps the pending snapshot queue on the client bounded
     * and prevents the backend from doing wasted work the client will never animate.
     * Only enforced when viewerGatingEnabled is true.
     */
    private static final int MAX_CLIENT_LAG_VERSIONS = 5;

    private final SessionCommandPublisher publisher;
    private final String sessionId;
    private final Set<String> botPlayerIds;
    private final Map<String, BotDifficulty> difficulties;
    /** Per-player STRONG bot configs. Falls back to StrongBotConfig.defaults() if absent. */
    private final Map<String, StrongBotConfig> configs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean pendingAction = new AtomicBoolean(false);
    private final AtomicInteger viewerCount = new AtomicInteger(0);
    /** The highest snapshot version the client has explicitly acknowledged. -1 = no ACK received yet. */
    private final AtomicLong latestClientVersion = new AtomicLong(-1);
    /** Disabled by default so unit tests work without simulating SSE connections. */
    private volatile boolean viewerGatingEnabled = false;
    private volatile double speedMultiplier = 1.0;
    private volatile TradeState lastObservedTrade = null;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> tradeDeclinesByPartnerId
            = new java.util.concurrent.ConcurrentHashMap<>();

    private PureDomainBotDriver(
            SessionCommandPublisher publisher,
            String sessionId,
            Set<String> botPlayerIds,
            Map<String, BotDifficulty> difficulties,
            Map<String, StrongBotConfig> configs) {
        this.publisher = publisher;
        this.sessionId = sessionId;
        this.botPlayerIds = botPlayerIds;
        this.difficulties = difficulties;
        this.configs = Map.copyOf(configs);
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
        return createAndRegisterIfNeeded(publisher, initialState, difficulties, Map.of());
    }

    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher,
            SessionState initialState,
            Map<String, BotDifficulty> difficulties,
            Map<String, StrongBotConfig> configs) {
        Set<String> botIds = collectBotPlayerIds(initialState);
        if (botIds.isEmpty()) {
            return null;
        }
        PureDomainBotDriver driver = new PureDomainBotDriver(
                publisher, initialState.sessionId(), botIds, Map.copyOf(difficulties), configs);
        publisher.addListener(driver);
        log.info("Bot driver registered for session {} — bots: {} difficulties: {}",
                initialState.sessionId().substring(0, 8), botIds,
                botIds.stream().collect(java.util.stream.Collectors.toMap(
                        id -> id, id -> difficulties.getOrDefault(id, BotDifficulty.STRONG))));
        // Trigger initial check after a short grace period. Viewer-gating now handles the
        // "wait for frontend" concern — the bot pauses immediately if viewerCount == 0, so
        // this delay only needs to survive the session-creation HTTP round-trip (~200ms).
        // Reduced from 4000 ms to 500 ms; override with -Dmonopoly.bot.initial.delay.ms=N.
        long initialDelayMs = Long.getLong("monopoly.bot.initial.delay.ms", 500L);
        ClientSessionSnapshot initialSnap = ClientSessionSnapshot.from(initialState, true);
        if (initialDelayMs > 0) {
            driver.scheduler.schedule(
                    () -> driver.onSnapshotChanged(initialSnap),
                    initialDelayMs, TimeUnit.MILLISECONDS);
        } else {
            driver.onSnapshotChanged(initialSnap);
        }
        return driver;
    }

    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher, SessionState initialState) {
        return createAndRegisterIfNeeded(publisher, initialState, Map.of(), Map.of());
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * Forces the bot to re-evaluate and act immediately (0 ms delay).
     *
     * <p>Used in two cases:
     * <ul>
     *   <li>Host detects the bot is stuck (failsafe recovery) — want immediate action.</li>
     *   <li>Debug panel sets a dice/card override — want the bot to consume it right away
     *       before the already-scheduled takeStep fires with the old dice.</li>
     * </ul>
     * By scheduling with 0 delay we bypass the human-like think time so the override takes
     * effect in the current turn rather than potentially being ignored.</p>
     */
    public void retrigger() {
        pendingAction.set(false);
        SessionState state = publisher.currentState();
        if (state == null || !needsBotAction(state)) return;
        if (pendingAction.compareAndSet(false, true)) {
            // Bypass viewer gating: explicit retrigger calls are intentional (e.g. tests,
            // debug tooling) and should always produce one bot step.
            scheduler.schedule(this::takeStepForced, 0L, TimeUnit.MILLISECONDS);
        }
    }

    private void takeStepForced() {
        pendingAction.set(false);
        SessionState state = publisher.currentState();
        if (state == null || state.status() == SessionStatus.GAME_OVER
                || state.status() == SessionStatus.LOBBY) {
            return;
        }
        if (!needsBotAction(state)) return;
        dispatchGreedy(state);
    }

    /**
     * Called when an SSE viewer connects to this session.
     * If the bot was paused due to no viewers, this triggers immediate re-evaluation.
     */
    public void onSseConnected() {
        if (viewerCount.incrementAndGet() == 1) {
            log.info("First SSE viewer connected for session {} — resuming bot", sessionId.substring(0, 8));
            retrigger();
        }
    }

    /**
     * Called when an SSE viewer disconnects from this session.
     * When the last viewer leaves, the bot pauses until someone reconnects.
     */
    public void onSseDisconnected() {
        int remaining = viewerCount.updateAndGet(v -> Math.max(0, v - 1));
        if (remaining == 0) {
            log.info("Last SSE viewer disconnected for session {} — bot will pause", sessionId.substring(0, 8));
        }
    }

    /**
     * Called when the client acknowledges it has processed a snapshot at the given version.
     * Advances the pacing window so the bot can continue if it was waiting.
     */
    public void onClientAck(long version) {
        long prev = latestClientVersion.getAndUpdate(v -> Math.max(v, version));
        if (prev < version) {
            retrigger();
        }
    }

    public void setViewerGatingEnabled(boolean enabled) {
        this.viewerGatingEnabled = enabled;
    }

    public void setSpeedMultiplier(double multiplier) {
        this.speedMultiplier = Math.max(0.0, multiplier);
        log.info("Bot speed multiplier set to {} for session {}", multiplier, sessionId.substring(0, 8));
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    // -------------------------------------------------------------------------
    // ClientSessionListener
    // -------------------------------------------------------------------------

    @Override
    public void onSnapshotChanged(ClientSessionSnapshot snapshot) {
        SessionState state = snapshot.state();
        if (state == null || state.status() == SessionStatus.GAME_OVER
                || state.status() == SessionStatus.LOBBY) {
            lastObservedTrade = null;
            return;
        }
        // Track when bot-initiated trades are declined to avoid re-proposing to the same partner.
        // A trade that disappears while someone was required to respond = declined (not cancelled).
        // handleDecline() never writes a "DECLINED" history entry, so we detect via decisionRequiredFromPlayerId.
        TradeState prevTrade = lastObservedTrade;
        lastObservedTrade = state.tradeState();
        if (prevTrade != null && state.tradeState() == null
                && botPlayerIds.contains(prevTrade.openedByPlayerId())
                && prevTrade.decisionRequiredFromPlayerId() != null) {
            String partner = prevTrade.openedByPlayerId().equals(prevTrade.initiatorPlayerId())
                    ? prevTrade.recipientPlayerId() : prevTrade.initiatorPlayerId();
            tradeDeclinesByPartnerId.merge(partner, 1, Integer::sum);
            log.debug("Bot trade declined by {} (cumulative: {})", partner,
                    tradeDeclinesByPartnerId.get(partner));
        }
        if (!needsBotAction(state)) {
            return;
        }
        if (viewerGatingEnabled && viewerCount.get() == 0) {
            return;  // no SSE viewers — pause until someone connects
        }
        if (viewerGatingEnabled && snapshot.version() - latestClientVersion.get() > MAX_CLIENT_LAG_VERSIONS) {
            return;  // client is too far behind — wait for ACK before advancing
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
        if (viewerGatingEnabled && viewerCount.get() == 0) {
            return;  // no SSE viewers — pause until someone connects
        }
        ClientSessionSnapshot current = publisher.currentSnapshot();
        if (viewerGatingEnabled && current != null
                && current.version() - latestClientVersion.get() > MAX_CLIENT_LAG_VERSIONS) {
            return;  // client is too far behind — wait for ACK before advancing
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
            TradeStatus ts = state.tradeState().status();
            if ((ts == TradeStatus.EDITING || ts == TradeStatus.COUNTERED)
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

            // COUNTERED editing must be checked before tradeActor: handleCounter sets
            // decisionRequiredFromPlayerId = bot (the counter editor), which would
            // otherwise route to handleTradeDecision and immediately decline the offer.
            if (trade.status() == TradeStatus.COUNTERED && editor != null && botPlayerIds.contains(editor)) {
                handleCounterEditing(state, editor);
                return;
            }
            if (trade.status() == TradeStatus.EDITING && editor != null && botPlayerIds.contains(editor)) {
                handleTradeEditing(state, editor);
                return;
            }
            if (tradeActor != null && botPlayerIds.contains(tradeActor)) {
                handleTradeDecision(state, tradeActor);
                return;
            }
        }

        TurnPhase phase = state.turn() != null ? state.turn().phase() : TurnPhase.UNKNOWN;
        String activeId = resolveActorId(state);
        if (activeId == null) return;

        switch (phase) {
            case WAITING_FOR_ROLL -> publisher.handle(new RollDiceCommand(sessionId, activeId));
            case WAITING_FOR_CARD_ACK -> publisher.handle(new AcknowledgeCardCommand(sessionId, activeId));
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
                return;
            }
            if (isStrong(activeId)) {
                handleDecisionStrong(state, activeId, decision, purchase, cash);
                return;
            }
            // NORMAL / remaining EASY: simple afford-or-mortgage
            if (canAfford) {
                publisher.handle(new BuyPropertyCommand(sessionId, activeId, decision.decisionId(), purchase.propertyId()));
            } else if (!isEasy(activeId)) {
                PropertyStateSnapshot toMortgage = findMortgageCandidateForPurchase(state, activeId, purchase.price() - cash);
                if (toMortgage != null) {
                    publisher.handle(new ToggleMortgageCommand(sessionId, activeId, toMortgage.propertyId()));
                } else {
                    publisher.handle(new DeclinePropertyCommand(sessionId, activeId, decision.decisionId(), purchase.propertyId()));
                }
            } else {
                publisher.handle(new DeclinePropertyCommand(sessionId, activeId, decision.decisionId(), purchase.propertyId()));
            }
        } else {
            publisher.handle(new EndTurnCommand(sessionId, activeId));
        }
    }

    /**
     * Score-based property purchase decision for STRONG bots.
     * Weighs set completion, progress, opponent blocking, type bonuses, liquidity risk,
     * and color-group preferences according to the bot's StrongBotConfig.
     */
    private void handleDecisionStrong(SessionState state, String botId,
                                       PendingDecision decision,
                                       PropertyPurchaseDecisionPayload purchase, int cash) {
        StrongBotConfig cfg = configFor(botId);
        int reserve = dynamicReserve(state, botId);
        int postCash = cash - purchase.price();
        String propId = purchase.propertyId();

        if (postCash < 0) {
            // Try mortgaging to afford it
            PropertyStateSnapshot toMortgage = findMortgageCandidateForPurchase(state, botId, purchase.price() - cash);
            if (toMortgage != null) {
                publisher.handle(new ToggleMortgageCommand(sessionId, botId, toMortgage.propertyId()));
                return;
            }
            publisher.handle(new DeclinePropertyCommand(sessionId, botId, decision.decisionId(), propId));
            return;
        }

        boolean completesSet = wouldCompleteSet(state, botId, propId);
        double score = buyScore(state, botId, propId, cfg);

        // Set-completing purchases: buy if post-cash >= reserve-100 (slightly relaxed)
        if (completesSet) {
            boolean buy = postCash >= Math.max(0, reserve - 100);
            if (buy) {
                publisher.handle(new BuyPropertyCommand(sessionId, botId, decision.decisionId(), propId));
            } else {
                publisher.handle(new DeclinePropertyCommand(sessionId, botId, decision.decisionId(), propId));
            }
            return;
        }

        // Non-completing: decline if below reserve
        if (postCash < reserve) {
            publisher.handle(new DeclinePropertyCommand(sessionId, botId, decision.decisionId(), propId));
            return;
        }

        // Apply phase-based threshold
        double threshold = buyThreshold(state, propId, cfg);
        if (score >= threshold) {
            publisher.handle(new BuyPropertyCommand(sessionId, botId, decision.decisionId(), propId));
        } else {
            publisher.handle(new DeclinePropertyCommand(sessionId, botId, decision.decisionId(), propId));
        }
    }

    private double buyScore(SessionState state, String botId, String propId, StrongBotConfig cfg) {
        return StrongBotStrategy.buyScore(state, botId, propId, cfg);
    }

    private double buyThreshold(SessionState state, String propId, StrongBotConfig cfg) {
        return StrongBotStrategy.buyThreshold(state, propId, cfg);
    }

    private boolean wouldCompleteSet(SessionState state, String botId, String propId) {
        return StrongBotStrategy.wouldCompleteSet(state, botId, propId);
    }

    /**
     * Finds the best property to mortgage to raise at least {@code needed} extra cash for a purchase.
     * Avoids mortgaging complete monopolies (strategic value) and prefers utilities/railroads first.
     * Returns null if no suitable property exists or the bot wouldn't want to do this.
     */
    private PropertyStateSnapshot findMortgageCandidateForPurchase(SessionState state, String playerId, int needed) {
        // Collect mortgageable properties: not already mortgaged, no buildings in the group
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
                // Do not sacrifice complete monopolies — they are most valuable
                .filter(p -> !StrongBotStrategy.botOwnsFullGroup(state, playerId, spotType(p.propertyId()).streetType))
                .toList();

        if (candidates.isEmpty()) return null;

        int totalRaisable = candidates.stream()
                .mapToInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price") / 2)
                .sum();
        if (totalRaisable < needed) return null; // can't raise enough even by mortgaging everything

        // Prefer: non-street (railroad/utility) first, then cheapest street property
        return candidates.stream()
                .min(java.util.Comparator
                        .comparingInt((PropertyStateSnapshot p) ->
                                spotType(p.propertyId()).streetType.placeType == PlaceType.STREET ? 1 : 0)
                        .thenComparingInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price")))
                .orElse(null);
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
            // Sell the building where rent-loss-per-sell-value is lowest (least costly to remove).
            // Among tied scores, prefer the property with the most buildings (even-sell rule).
            var buildingProp = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && buildingLevel(p) > 0)
                    .filter(p -> evenSellEligible(state, p))
                    .min(java.util.Comparator
                            .comparingDouble(StrongBotStrategy::debtBuildingSellScore)
                            .thenComparingInt(p -> -buildingLevel(p)));
            if (buildingProp.isPresent()) {
                publisher.handle(new SellBuildingForDebtCommand(sessionId, debtorId, debt.debtId(), buildingProp.get().propertyId(), 1));
                return;
            }
        }
        if (allowed.contains(DebtAction.MORTGAGE_PROPERTY)) {
            // Mortgage in strategic priority order: utilities first, monopolies last.
            var toMortgage = state.properties().stream()
                    .filter(p -> debtorId.equals(p.ownerPlayerId()) && !p.mortgaged())
                    .min(java.util.Comparator.comparingInt(
                            p -> StrongBotStrategy.debtMortgagePriority(state, debtorId, p)));
            if (toMortgage.isPresent()) {
                publisher.handle(new MortgagePropertyForDebtCommand(sessionId, debtorId, debt.debtId(), toMortgage.get().propertyId()));
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

        // Determine perspective: the currentOffer always expresses things from the proposer's view.
        // If botId == offer.recipientPlayerId → bot receives offeredToRecipient, gives requestedFromRecipient.
        // Otherwise (bot is the proposer, e.g. deciding on a counter) → sides are reversed.
        TradeOfferState offer = trade.currentOffer();
        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());
        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();

        int valueReceived = evaluateSelectionContextual(state, botId, myReceiving, true);
        int valueGiven    = evaluateSelectionContextual(state, botId, myGiving, false);

        // STRONG bot uses tradeFairnessTolerance: accepts if deficit <= tolerance
        int fairnessTolerance = isStrong(botId) ? configFor(botId).tradeFairnessTolerance() : 0;
        if (valueReceived >= valueGiven - fairnessTolerance) {
            publisher.handle(new AcceptTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // STRONG bot: prefer countering over declining — always counter the first time,
        // and again if the follow-up is at least 25% reasonable (avoids infinite loops).
        if (isStrong(botId) && valueGiven > 0) {
            long counterCount = trade.history().stream()
                    .filter(e -> "COUNTERED".equals(e.actionType())).count();
            boolean offerIsReasonable = counterCount == 0 || valueReceived >= valueGiven * 0.25;
            if (offerIsReasonable && counterCount < 2) {
                publisher.handle(new CounterTradeCommand(sessionId, botId, tradeId));
                return;
            }
        }

        publisher.handle(new DeclineTradeCommand(sessionId, botId, tradeId));
    }

    /**
     * Called when the bot is in COUNTERED editing mode — it rejected the incoming offer terms
     * and is now proposing adjusted terms. Modifies the money on the received side to cover
     * the value gap, then submits.
     */
    private void handleCounterEditing(SessionState state, String botId) {
        TradeState trade = state.tradeState();
        if (trade == null) return;
        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();

        // Determine which side is bot's give vs receive, same as in handleTradeDecision
        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();

        int valueGiven = evaluateSelectionContextual(state, botId, myGiving, false);
        int currentMoneyReceived = myReceiving.moneyAmount();
        int nonMoneyReceived = evaluateSelectionValue(myReceiving) - currentMoneyReceived;

        // Target: bot wants ~5% profit over what it gives
        int targetMoneyReceived = Math.max(0, (int) (valueGiven * 1.05) - nonMoneyReceived);

        if (currentMoneyReceived >= targetMoneyReceived) {
            // Already fair — submit as-is
            publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
            return;
        }

        // Check that the other party can actually afford the counter amount
        String otherPartyId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();
        PlayerSnapshot otherParty = findPlayer(state, otherPartyId);
        int proposerCash = otherParty != null ? otherParty.cash() : 0;
        int actualMoney = Math.min(targetMoneyReceived, proposerCash);
        if (actualMoney < 10) {
            // Proposer can't cover a fair counter — cancel
            publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // Edit the money on the side the bot RECEIVES (offeredToRecipient if bot is recipient, else requestedFromRecipient)
        boolean editOfferedSide = botIsRecipient;
        publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                new TradeEditPatch(null, editOfferedSide, actualMoney, List.of(), List.of(), null)));
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
            int available = bot != null ? Math.max(0, bot.cash() - dynamicReserve(state, botId)) : 0;
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
        int reserve = dynamicReserve(state, botId);
        if (bot == null || bot.cash() < reserve + 50) return false;

        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            // Skip partners who have repeatedly declined bot-initiated offers
            if (tradeDeclinesByPartnerId.getOrDefault(other.playerId(), 0) >= MAX_DECLINES_PER_PARTNER) continue;
            // Verify handleTradeEditing would actually find a target and afford an offer
            String targetProp = findStrategicTargetProperty(state, botId, other.playerId());
            if (targetProp == null) continue;
            int price = SpotType.valueOf(targetProp).getIntegerProperty("price");
            int available = Math.max(0, bot.cash() - reserve);
            if (Math.min(price, available) < 10) continue;
            CommandResult result = publisher.handle(new OpenTradeCommand(sessionId, botId, other.playerId()));
            return result.accepted();
        }
        return false;
    }

    /**
     * Finds the highest-priority property in the partner's portfolio that the bot would benefit from.
     *
     * <p>Priority order:
     * <ol>
     *   <li>Street property that would complete the bot's monopoly (missing last piece)</li>
     *   <li>Railroad, if bot already has at least one and partner has one to spare</li>
     *   <li>Street property in any group where the bot has the most properties</li>
     * </ol>
     * All candidates must be unbuilt (no houses/hotels) and unmortgaged on the partner's side.
     */
    private String findStrategicTargetProperty(SessionState state, String botId, String partnerId) {
        // Priority 1: property that would immediately complete bot's street monopoly
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns != groupSize - 1) continue; // bot needs exactly 1 more
            String found = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType == group)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (found != null) return found;
        }

        // Priority 2: railroad if bot already has ≥1 railroad and partner has one
        long botRailroads = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId())
                        && spotType(p.propertyId()).streetType.placeType == PlaceType.RAILROAD)
                .count();
        if (botRailroads >= 1) {
            String railroadTarget = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType.placeType == PlaceType.RAILROAD)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (railroadTarget != null) return railroadTarget;
        }

        // Priority 3: any street group where bot has the most properties (descending), try partner
        java.util.List<StreetType> groupsByBotOwnership = java.util.Arrays.stream(StreetType.values())
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
            if (botOwns == 0) continue; // only trade for groups where bot already has a foothold
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

    private static int evaluateSelectionValue(TradeSelectionState selection) {
        int value = selection.moneyAmount();
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            value += SpotType.valueOf(propId).getIntegerProperty("price");
        }
        // Group completion bonus: a complete color monopoly is worth 50% more than the sum of prices
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
                        .mapToInt(id -> SpotType.valueOf(id).getIntegerProperty("price"))
                        .sum();
                value += groupValue / 2;
            }
        }
        return value;
    }

    /**
     * Context-aware trade evaluation that considers what the bot already owns.
     * For STRONG bots uses {@link StrongBotConfig#tradeSetCompletionWeight} and
     * {@link StrongBotConfig#tradeLiquidityWeight}; others use fixed ratios.
     */
    private int evaluateSelectionContextual(SessionState state, String botId,
                                             TradeSelectionState selection, boolean receiving) {
        StrongBotConfig cfg = configFor(botId);

        // Base value: cash weighted by liquidity preference, then property prices
        int value = (int)(selection.moneyAmount() * cfg.tradeLiquidityWeight());
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            value += SpotType.valueOf(propId).getIntegerProperty("price");
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
                    value += groupPriceSum + setWeight; // completing a monopoly
                } else if (afterReceive == groupSize - 1) {
                    value += groupPriceSum / 3 + setWeight / 4;
                }
            } else {
                if (botOwns >= groupSize) {
                    value += groupPriceSum * 2 + setWeight; // destroying own monopoly: huge cost
                } else if (botOwns == groupSize - 1 && inSelection >= 1) {
                    value += groupPriceSum / 2 + setWeight / 3;
                }
            }
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
        if (isEasy(bidderId)) {
            boolean wouldBid = cash >= minBid && minBid > 0;
            if (wouldBid && ThreadLocalRandom.current().nextDouble() < 0.50) {
                publisher.handle(new PassAuctionCommand(sessionId, bidderId, auction.auctionId()));
            } else if (wouldBid) {
                publisher.handle(new PlaceAuctionBidCommand(sessionId, bidderId, auction.auctionId(), minBid));
            } else {
                publisher.handle(new PassAuctionCommand(sessionId, bidderId, auction.auctionId()));
            }
            return;
        }

        // STRONG bot: bid up to a config-based ceiling based on property value and set completion
        if (isStrong(bidderId) && minBid > 0) {
            StrongBotConfig cfg = configFor(bidderId);
            // Auction reserve is capped at dangerCashReserve — the full dynamic reserve (which
            // can exceed 500 late game) is for purchase decisions, not competitive bidding.
            int reserve = Math.min(dynamicReserve(state, bidderId), cfg.dangerCashReserve());
            String propId = auction.propertyId();
            int facePrice = propId != null
                    ? SpotType.valueOf(propId).getIntegerProperty("price") : minBid;
            int ceiling = (int) (facePrice * cfg.auctionAggression());
            if (propId != null && wouldCompleteSet(state, bidderId, propId)) {
                ceiling += cfg.auctionSetCompletionBonus();
            }
            int maxBid = Math.min(ceiling, cash - reserve);
            if (maxBid >= minBid) {
                // Jump ~⅓ of remaining headroom toward ceiling with ±40 % random jitter
                int headroom = maxBid - minBid;
                double factor = 0.6 + ThreadLocalRandom.current().nextDouble() * 0.8; // 0.6–1.4
                int extra = ((int) (headroom / 3.0 * factor) / 10) * 10;
                int bid = Math.min(maxBid, minBid + Math.max(10, extra));
                publisher.handle(new PlaceAuctionBidCommand(sessionId, bidderId, auction.auctionId(), bid));
            } else {
                publisher.handle(new PassAuctionCommand(sessionId, bidderId, auction.auctionId()));
            }
            return;
        }

        // NORMAL bot: bid if affordable
        boolean wouldBid = cash >= minBid && minBid > 0;
        if (wouldBid) {
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
        long botDelayMs = Long.getLong("monopoly.bot.think.delay.ms", DEFAULT_BOT_DELAY_MS);
        if (botDelayMs == 0) return 0;  // instant mode (tests / system property = 0)
        double speed = speedMultiplier;
        if (speed == 0.0) return MIN_FAST_DELAY_MS;
        long base = computeBaseDelay(state);
        String actorId = resolveActorId(state);
        BotDifficulty diff = actorId != null
                ? difficulties.getOrDefault(actorId, BotDifficulty.STRONG)
                : BotDifficulty.NORMAL;
        double diffMult = switch (diff) {
            case EASY   -> 1.25;   // hesitant, slower
            case NORMAL -> 1.0;
            case STRONG -> 0.80;   // more decisive
        };
        long floor = speed < 0.15 ? 30L : 200L;  // fast mode: no artificial floor
        long scaled = Math.max(floor, (long) (base * diffMult * speed));
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
        if (state.turn() == null) return DEFAULT_BOT_DELAY_MS;
        return switch (state.turn().phase()) {
            case WAITING_FOR_ROLL -> 1550;  // pause before throwing dice
            case WAITING_FOR_CARD_ACK -> 3500;  // reading the card
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
            default -> DEFAULT_BOT_DELAY_MS;
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Fallback reserve for EASY/NORMAL bots (STRONG bots use dynamicReserve()). */
    private static final int MIN_CASH_RESERVE = 200;

    /** Returns the StrongBotConfig for a STRONG bot; defaults() for others. */
    private StrongBotConfig configFor(String playerId) {
        if (!isStrong(playerId)) return StrongBotConfig.defaults();
        return configs.getOrDefault(playerId, StrongBotConfig.defaults());
    }

    /**
     * Dynamic cash reserve for STRONG bots that scales with board danger and opponent monopolies.
     * EASY/NORMAL fall back to the flat MIN_CASH_RESERVE.
     */
    private int dynamicReserve(SessionState state, String playerId) {
        if (!isStrong(playerId)) return MIN_CASH_RESERVE;
        return StrongBotStrategy.dynamicReserve(state, playerId, configFor(playerId));
    }

    /**
     * Unmortgages the highest-scored mortgaged property the bot can afford.
     * STRONG: uses config weights (unmortgageAggression, colorGroupWeight, developmentBias).
     * NORMAL: unmortgages cheapest mortgaged property in a complete group.
     */
    private boolean tryUnmortgageGreedy(SessionState state, String playerId) {
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, playerId);
        if (player == null) return false;
        int reserve = dynamicReserve(state, playerId);
        StrongBotConfig cfg = configFor(playerId);

        PropertyStateSnapshot candidate = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && p.mortgaged())
                .filter(p -> StrongBotStrategy.botOwnsFullGroup(state, playerId, StrongBotStrategy.spotType(p.propertyId()).streetType))
                .filter(p -> player.cash() - StrongBotStrategy.unmortgageCost(p.propertyId()) >= reserve)
                .max(java.util.Comparator.comparingDouble(p -> StrongBotStrategy.unmortgageScore(p, state, cfg)))
                .orElse(null);
        if (candidate == null) return false;
        CommandResult result = publisher.handle(
                new ToggleMortgageCommand(sessionId, playerId, candidate.propertyId()));
        return result.accepted();
    }

    /**
     * Picks the best group to build on and buys one round of houses.
     * STRONG: uses config weights (houseBuildAggression, hotelAversion, buildRoundCap,
     * prioritizeThreeHouses, colorGroupWeight, developmentBias).
     * NORMAL/EASY: simple even-build greedy.
     */
    private boolean tryBuildGreedy(SessionState state, String playerId) {
        PlayerSnapshot player = StrongBotStrategy.findPlayer(state, playerId);
        if (player == null) return false;
        int reserve = dynamicReserve(state, playerId);
        StrongBotConfig cfg = configFor(playerId);

        StreetType bestGroup = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (StreetType group : StrongBotStrategy.completedColorGroups(state, playerId)) {
            int maxLevel = StrongBotStrategy.maxLevelInGroup(state, playerId, group);
            if (maxLevel >= cfg.buildRoundCap()) continue;
            if (!StrongBotStrategy.canAffordBuildRound(state, player, group, reserve)) continue;
            double score = StrongBotStrategy.buildGroupScore(state, playerId, group, cfg);
            if (score > bestScore) { bestScore = score; bestGroup = group; }
        }

        if (bestGroup == null) return false;
        PropertyStateSnapshot target = StrongBotStrategy.findBuildTarget(state, playerId, bestGroup);
        if (target == null) return false;
        CommandResult result = publisher.handle(
                new BuyBuildingRoundCommand(sessionId, playerId, target.propertyId()));
        return result.accepted();
    }

    private static SpotType spotType(String propertyId) {
        return StrongBotStrategy.spotType(propertyId);
    }

    private static int buildingLevel(PropertyStateSnapshot p) {
        return StrongBotStrategy.buildingLevel(p);
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
        return difficulties.getOrDefault(playerId, BotDifficulty.STRONG) == BotDifficulty.EASY;
    }

    private boolean isStrong(String playerId) {
        return difficulties.getOrDefault(playerId, BotDifficulty.STRONG) == BotDifficulty.STRONG;
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
