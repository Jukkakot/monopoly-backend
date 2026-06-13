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
    /** Per-player bot configs. Falls back to StrongBotConfig.defaults() if absent. */
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
    /** True until the bot acts for the first time this game — adds extra delay so players can orient. */
    private volatile boolean isFirstTurn = true;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> tradeDeclinesByPartnerId
            = new java.util.concurrent.ConcurrentHashMap<>();
    /** Last bot playerId whose WAITING_FOR_ROLL we observed — used to detect turn transitions. */
    private volatile String lastBotTurnStartId = null;
    /** Consecutive EditTradeOffer attempts per tradeId — safety net against infinite edit loops. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> counterEditAttempts
            = new java.util.concurrent.ConcurrentHashMap<>();
    // Generic stuck-detection: if the bot keeps dispatching in the same situation repeatedly,
    // something is wrong. Volatile is safe — dispatcher runs on a single-thread executor.
    private volatile String lastDispatchFingerprint = "";
    private volatile int consecutiveDispatchCount = 0;
    /**
     * Last money amount the bot offered per (partnerId -> propertyId) that was declined.
     * Next offer for the same property+partner must strictly exceed this amount.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Integer>>
            lastDeclinedOfferAmount = new java.util.concurrent.ConcurrentHashMap<>();

    private PureDomainBotDriver(
            SessionCommandPublisher publisher,
            String sessionId,
            Set<String> botPlayerIds,
            Map<String, StrongBotConfig> configs) {
        this.publisher = publisher;
        this.sessionId = sessionId;
        this.botPlayerIds = botPlayerIds;
        this.configs = Map.copyOf(configs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("bot-driver-" + sessionId.substring(0, 8), 0).factory());
        // Watchdog: fires every 5 s to catch cases where the bot missed an action due to
        // lag-gating, rapid-fire snapshots, or scheduler/executor edge cases.  Only active
        // when viewer gating is enabled (i.e. a real game, not a unit-test simulation).
        this.scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!viewerGatingEnabled || viewerCount.get() == 0) return;
                if (pendingAction.get()) return;
                SessionState s = publisher.currentState();
                if (s == null || s.status() == SessionStatus.GAME_OVER
                        || s.status() == SessionStatus.LOBBY) return;
                if (!needsBotAction(s)) return;
                ClientSessionSnapshot snap = publisher.currentSnapshot();
                long lag = snap != null ? snap.version() - latestClientVersion.get() : 0;
                log.info("Bot watchdog: retriggering for session {} (lag {})", sessionId.substring(0, 8), lag);
                retrigger();
            } catch (Exception e) {
                log.warn("Bot watchdog threw", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Creates and registers a {@link PureDomainBotDriver} for any BOT seats in the given state.
     * Returns {@code null} if the session has no bot seats.
     */
    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher,
            SessionState initialState,
            Map<String, StrongBotConfig> configs) {
        Set<String> botIds = collectBotPlayerIds(initialState);
        if (botIds.isEmpty()) {
            return null;
        }
        PureDomainBotDriver driver = new PureDomainBotDriver(
                publisher, initialState.sessionId(), botIds, configs);
        publisher.addListener(driver);
        log.info("Bot driver registered for session {} — bots: {}",
                initialState.sessionId().substring(0, 8), botIds);
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
        return createAndRegisterIfNeeded(publisher, initialState, Map.of());
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
        isFirstTurn = false;
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
        // Reset per-partner decline counts at the start of each bot turn so the bot can
        // re-attempt trading with the same partner in later turns.
        var turn = state.turn();
        if (turn != null && turn.phase() == TurnPhase.WAITING_FOR_ROLL
                && botPlayerIds.contains(turn.activePlayerId())
                && !turn.activePlayerId().equals(lastBotTurnStartId)) {
            lastBotTurnStartId = turn.activePlayerId();
            tradeDeclinesByPartnerId.clear();
        }

        // Track when bot-initiated trades are declined to avoid re-proposing to the same partner.
        // A trade that disappears while someone was required to respond = declined (not cancelled).
        // handleDecline() never writes a "DECLINED" history entry, so we detect via decisionRequiredFromPlayerId.
        TradeState prevTrade = lastObservedTrade;
        lastObservedTrade = state.tradeState();
        // Clear the edit-loop counter when the trade resolves (tradeId changes or trade disappears)
        if (prevTrade != null) {
            String prevId = prevTrade.tradeId();
            TradeState cur = state.tradeState();
            if (cur == null || !prevId.equals(cur.tradeId())) {
                counterEditAttempts.remove(prevId);
            }
        }
        if (prevTrade != null && state.tradeState() == null
                && botPlayerIds.contains(prevTrade.openedByPlayerId())
                && prevTrade.decisionRequiredFromPlayerId() != null) {
            String partner = prevTrade.openedByPlayerId().equals(prevTrade.initiatorPlayerId())
                    ? prevTrade.recipientPlayerId() : prevTrade.initiatorPlayerId();
            tradeDeclinesByPartnerId.merge(partner, 1, Integer::sum);
            log.debug("Bot trade declined by {} (cumulative: {})", partner,
                    tradeDeclinesByPartnerId.get(partner));
            // Record what was offered so we require a strictly better offer next time
            TradeOfferState lastOffer = prevTrade.currentOffer();
            if (lastOffer != null) {
                boolean botIsProposer = prevTrade.openedByPlayerId().equals(prevTrade.initiatorPlayerId());
                TradeSelectionState botWanted = botIsProposer
                        ? lastOffer.requestedFromRecipient() : lastOffer.offeredToRecipient();
                TradeSelectionState botGave = botIsProposer
                        ? lastOffer.offeredToRecipient() : lastOffer.requestedFromRecipient();
                if (!botWanted.propertyIds().isEmpty()) {
                    String propId = botWanted.propertyIds().get(0);
                    lastDeclinedOfferAmount
                            .computeIfAbsent(partner, k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(propId, botGave.moneyAmount());
                    log.debug("Recorded declined offer: partner={} prop={} amount={}",
                            partner, propId, botGave.moneyAmount());
                }
            }
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
        isFirstTurn = false;
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

    /** Fingerprint of the current game situation — used to detect stuck/looping bot behaviour. */
    private String situationFingerprint(SessionState state) {
        String phase = state.turn() != null ? state.turn().phase().name() : "NONE";
        String tradeId = state.tradeState() != null ? state.tradeState().tradeId() : "-";
        String tradeStatus = state.tradeState() != null ? state.tradeState().status().name() : "-";
        String auctionStatus = state.auctionState() != null ? state.auctionState().status().name() : "-";
        String actor = resolveActorId(state);
        return phase + "|" + (actor != null ? actor.substring(Math.max(0, actor.length() - 4)) : "?")
                + "|" + tradeId.substring(Math.max(0, tradeId.length() - 8))
                + "|" + tradeStatus + "|" + auctionStatus;
    }

    private void dispatchGreedy(SessionState state) {
        // Generic stuck detection: warn if the same situation triggers dispatch repeatedly.
        String fp = situationFingerprint(state);
        if (fp.equals(lastDispatchFingerprint)) {
            consecutiveDispatchCount++;
            if (consecutiveDispatchCount == 10) {
                log.warn("Bot stuck-detector: same situation dispatched {} times — session={} fingerprint={}",
                        consecutiveDispatchCount, sessionId.substring(0, 8), fp);
            }
        } else {
            lastDispatchFingerprint = fp;
            consecutiveDispatchCount = 1;
        }

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
                if (tryUnmortgageGreedy(state, activeId)) return;
                if (tryBuildGreedy(state, activeId)) return;
                if (state.tradeState() == null && tryInitiateStrategicTrade(state, activeId)) return;
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
            // WAITING_FOR_DECISION is set by OverlaySessionStateStore whenever tradeState OR
            // pendingDecision is non-null.  If only tradeState is active, the trade conditions
            // in dispatchGreedy() should have already returned — but as a safety guard, don't
            // send EndTurnCommand while a trade is open: it would be rejected and leave the bot
            // stuck waiting for a state change that never comes.
            if (state.tradeState() != null) {
                log.warn("Bot in WAITING_FOR_DECISION with active trade but no pending decision — trade actor is not a bot, waiting");
                return;
            }
            publisher.handle(new EndTurnCommand(sessionId, activeId));
            return;
        }
        if (decision.payload() instanceof PropertyPurchaseDecisionPayload purchase) {
            PlayerSnapshot player = findPlayer(state, activeId);
            int cash = player != null ? player.cash() : 0;
            handleDecisionStrong(state, activeId, decision, purchase, cash);
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

        // Apply phase-based threshold, relaxed when losing and tightened when leading
        double posFactor = StrongBotStrategy.positionFactor(state, botId);
        double threshold = buyThreshold(state, propId, cfg) / posFactor;
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
                            .comparingDouble((PropertyStateSnapshot p) -> StrongBotStrategy.debtBuildingSellScore(state, debtorId, p))
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

        // Determine perspective: the currentOffer always expresses things from the proposer's view.
        // If botId == offer.recipientPlayerId → bot receives offeredToRecipient, gives requestedFromRecipient.
        // Otherwise (bot is the proposer, e.g. deciding on a counter) → sides are reversed.
        TradeOfferState offer = trade.currentOffer();
        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());

        String tradePartnerId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();

        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();

        int valueReceived = evaluateSelectionContextual(state, botId, myReceiving, true);
        int valueGiven    = evaluateSelectionContextual(state, botId, myGiving, false)
                          + monopolyGiftPenalty(state, tradePartnerId, myGiving);

        // Acceptance threshold scales with the partner's threat score (0-1):
        //   low threat  → standard fairness tolerance
        //   high threat → up to 25% profit premium required (proportional to property value)
        double posFactor = StrongBotStrategy.positionFactor(state, botId);
        int fairnessTolerance = (int)(configFor(botId).tradeFairnessTolerance() * posFactor);
        double ts = StrongBotStrategy.threatScore(state, tradePartnerId);
        int requiredPremium = (int)(valueGiven * ts * 0.25);
        if (valueReceived >= valueGiven - fairnessTolerance + requiredPremium) {
            publisher.handle(new AcceptTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // Prefer countering over declining — always counter the first time,
        // and again if the follow-up is at least 25% reasonable (avoids infinite loops).
        if (valueGiven > 0) {
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

        String counterPartnerId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();
        int valueGiven = evaluateSelectionContextual(state, botId, myGiving, false)
                       + monopolyGiftPenalty(state, counterPartnerId, myGiving);
        int currentMoneyReceived = myReceiving.moneyAmount();
        int nonMoneyReceived = evaluateSelectionValue(myReceiving, state) - currentMoneyReceived;

        // Profit target scales with partner threat score (0-1): 1.05 (no threat) to 1.25 (full threat).
        double ts = StrongBotStrategy.threatScore(state, counterPartnerId);
        double profitFactor = 1.05 + ts * 0.20;
        int targetMoneyReceived = Math.max(0, (int) (valueGiven * profitFactor) - nonMoneyReceived);

        // editOfferedSide: side the bot RECEIVES; editGiveSide: side the bot GIVES
        boolean editOfferedSide = botIsRecipient;
        boolean editGiveSide    = !editOfferedSide;

        // Single-side money rule: never submit an offer with money on both sides — it is confusing.
        // If the given side already carries cash AND we'd also put money on the received side,
        // resolve the conflict first (in a separate command step) before continuing.
        int givenMoney = myGiving.moneyAmount();
        if (givenMoney > 0 && (targetMoneyReceived > 0 || currentMoneyReceived > 0)) {
            if (!myGiving.propertyIds().isEmpty()) {
                // Given side = property + cash: drop the cash; the property is the trade-in.
                publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                        new TradeEditPatch(null, editGiveSide, 0, List.of(), List.of(), null)));
            } else {
                // Given side = pure cash: this is cash-for-properties. Counter by reducing the
                // payment to a fair rate (~95 % of the received property value) instead of
                // adding cash to the other side.
                int fairGiveMoney = Math.max(0, (int) (nonMoneyReceived * 0.95));
                if (fairGiveMoney < 10) {
                    publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
                } else {
                    publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                            new TradeEditPatch(null, editGiveSide, fairGiveMoney, List.of(), List.of(), null)));
                }
            }
            return;
        }

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
            recordBotCancelAsDecline(botId, trade);
            publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // If cash alone can't reach the target AND the receive side has no properties yet,
        // try requesting a strategic property from the partner to bridge the gap.
        // If no strategic property exists and cash doesn't even cover face value, cancel.
        if (actualMoney < targetMoneyReceived && myReceiving.propertyIds().isEmpty()) {
            String extraProp = findStrategicTargetProperty(state, botId, otherPartyId);
            if (extraProp != null) {
                publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                        new TradeEditPatch(null, editOfferedSide, null, List.of(extraProp), List.of(), null)));
                return;
            }
            // No strategic property to soften the deal — cancel if it doesn't break even.
            if (actualMoney < valueGiven) {
                recordBotCancelAsDecline(botId, trade);
                publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                return;
            }
        }

        if (actualMoney == currentMoneyReceived) {
            // Other party's cash caps us at what's already set — submit the best we can get
            publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
            return;
        }

        // Safety net: if the bot has tried to edit this counter too many times, something is
        // wrong with the convergence — cancel rather than loop indefinitely.
        int attempts = counterEditAttempts.merge(tradeId, 1, Integer::sum);
        if (attempts > 8) {
            log.warn("Bot {} counter-edit loop detected for trade {} ({} attempts) — cancelling",
                    botId.substring(0, 8), tradeId.substring(0, 12), attempts);
            counterEditAttempts.remove(tradeId);
            recordBotCancelAsDecline(botId, trade);
            publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // Edit the money on the side the bot RECEIVES
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
                recordBotCancelAsDecline(botId, trade);
                publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                return;
            }
            publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                    new TradeEditPatch(null, requestSide, null, List.of(targetProp), List.of(), null)));
            return;
        }

        String targetPropId0 = myRequest.propertyIds().get(0);
        String partnerId0 = iAmProposer ? trade.recipientPlayerId() : trade.initiatorPlayerId();
        int targetPrice = SpotType.valueOf(targetPropId0).getIntegerProperty("price");
        PlayerSnapshot botSnap = findPlayer(state, botId);
        int available0 = botSnap != null ? Math.max(0, botSnap.cash() - dynamicReserve(state, botId)) : 0;

        // Step 2a: offer an own property when needed to sweeten the deal.
        // The bot never proactively offers a property just because the partner wants it —
        // that is the partner's job to ask for via counter-offer. The bot only adds a property
        // when chasing a P1 target (the partner will demand a premium, so a bundle helps) or
        // when cash alone cannot cover the target price.
        // Only run when the give-side is completely empty (no property AND no money already set).
        boolean targetIsP1 = isMonopolyCompletingTarget(state, botId, targetPropId0);
        if (myGive.propertyIds().isEmpty() && myGive.moneyAmount() == 0) {
            if (available0 < targetPrice || targetIsP1) {
                String expendable = findExpendableOwnProperty(state, botId, partnerId0);
                if (expendable != null) {
                    publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                            new TradeEditPatch(null, giveSide, null, List.of(expendable), List.of(), null)));
                    return;
                }
            }
        }

        // Step 2: offer money (target price minus own-property value already given, capped by cash)
        if (myGive.moneyAmount() == 0) {
            int ownPropValue = myGive.propertyIds().stream()
                    .mapToInt(id -> SpotType.valueOf(id).getIntegerProperty("price"))
                    .sum();
            int cashNeeded = Math.max(0, targetPrice - ownPropValue);
            if (cashNeeded > 0) {
                int offerAmount = Math.min(cashNeeded, available0);
                // Must strictly beat the last declined offer when no own property sweetens the deal
                boolean hasOwnProp = !myGive.propertyIds().isEmpty();
                int prevDeclined = lastDeclinedOfferAmount
                        .getOrDefault(partnerId0, new java.util.concurrent.ConcurrentHashMap<>())
                        .getOrDefault(targetPropId0, 0);
                if (offerAmount < 10 || (!hasOwnProp && offerAmount <= prevDeclined)) {
                    recordBotCancelAsDecline(botId, trade);
                    publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                    return;
                }
                publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                        new TradeEditPatch(null, giveSide, offerAmount, List.of(), List.of(), null)));
                return;
            }
            // cashNeeded == 0: own property covers full price, fall through to submit
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

        // Pass 1: bot has a P1 (monopoly-completing) target from partner.
        // The bot acts in its own interest only — it does NOT check whether the partner also
        // benefits. The partner can counter-offer to ask for what they want. Near-monopoly
        // partners are NOT skipped here: completing our own monopoly is worth the attempt.
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (tradeDeclinesByPartnerId.getOrDefault(other.playerId(), 0) >= MAX_DECLINES_PER_PARTNER) continue;
            String botWantsFromPartner = findCriticalTargetProperty(state, botId, other.playerId());
            if (botWantsFromPartner != null) {
                CommandResult result = publisher.handle(new OpenTradeCommand(sessionId, botId, other.playerId()));
                if (result.accepted()) return true;
            }
        }

        // Pass 2: P3 acquisition — pay cash (or offer an expendable) for a foothold property.
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            // Skip partners who have repeatedly declined bot-initiated offers
            if (tradeDeclinesByPartnerId.getOrDefault(other.playerId(), 0) >= MAX_DECLINES_PER_PARTNER) continue;
            // Verify handleTradeEditing would actually find a target and afford an offer
            String targetProp = findStrategicTargetProperty(state, botId, other.playerId());
            if (targetProp == null) continue;
            int price = SpotType.valueOf(targetProp).getIntegerProperty("price");
            int available = Math.max(0, bot.cash() - reserve);
            int wouldOffer = Math.min(price, available);
            if (wouldOffer < 10) continue;
            // Skip if this offer would not beat the last declined offer for this partner+property
            int prevDeclined = lastDeclinedOfferAmount
                    .getOrDefault(other.playerId(), new java.util.concurrent.ConcurrentHashMap<>())
                    .getOrDefault(targetProp, 0);
            if (wouldOffer <= prevDeclined) continue;
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

    /**
     * Extra cost added to {@code valueGiven} when the giving selection contains a property that
     * would complete {@code partnerId}'s street monopoly. Mirrors the receiving-side completion
     * bonus so the bot demands fair compensation for handing an opponent a monopoly.
     */
    private int monopolyGiftPenalty(SessionState state, String partnerId, TradeSelectionState giving) {
        int penalty = 0;
        StrongBotConfig cfg = configFor(partnerId); // use same weights as the receiving-side bonus
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
                penalty += groupPriceSum + cfg.tradeSetCompletionWeight();
            }
        }
        return penalty;
    }

    /** Returns true if acquiring {@code propId} would complete a street monopoly for {@code botId}. */
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

    /**
     * Finds a high-priority target property (Priority 1: monopoly completion only).
     * Unlike {@link #findStrategicTargetProperty}, this excludes railroad (P2) and foothold-group
     * (P3) targets so that Pass-1 mutual swaps are only initiated when the bot would complete a
     * color monopoly. Railroads are all equivalent, so swapping one for another yields zero benefit;
     * they are better acquired via cash (Pass 2).
     */
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

    /**
     * Finds the best own property to include as a sweetener in a trade offer.
     * Ranked by deadweight score: face_value × (1 − bot's group progress).
     * A property the bot has no chance of completing into a monopoly, but that looks
     * expensive on paper, is ideal — it costs the bot little strategically while making
     * the offer attractive. Properties that would complete the partner's monopoly are
     * excluded regardless of score.
     */
    private String findExpendableOwnProperty(SessionState state, String botId, String partnerId) {
        return state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0)
                .filter(p -> StrongBotStrategy.debtMortgagePriority(state, botId, p) <= 3)
                .filter(p -> !wouldCompletePartnerMonopoly(state, partnerId, p.propertyId()))
                .max(java.util.Comparator.comparingDouble(
                        p -> deadweightScore(state, botId, p.propertyId())))
                .map(PropertyStateSnapshot::propertyId)
                .orElse(null);
    }

    /**
     * Deadweight score for offering a property: face_value × (1 − group_progress).
     * High score = expensive-looking but strategically unimportant to the bot.
     * group_progress = botOwnsInGroup / groupSize (0 if bot owns nothing else in the group).
     */
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

    private static int evaluateSelectionValue(TradeSelectionState selection) {
        return evaluateSelectionValue(selection, null);
    }

    /** Returns the total estimated value of a trade selection.
     *  Mortgaged properties are valued at half face price (their mortgage value),
     *  since the recipient must spend ~55% of face price to unmortgage them. */
    private static int evaluateSelectionValue(TradeSelectionState selection, SessionState state) {
        int value = selection.moneyAmount();
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            int facePrice = SpotType.valueOf(propId).getIntegerProperty("price");
            boolean mortgaged = state != null && state.properties().stream()
                    .anyMatch(p -> propId.equals(p.propertyId()) && p.mortgaged());
            value += mortgaged ? facePrice / 2 : facePrice;
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

    /**
     * Context-aware trade evaluation that considers what the bot already owns.
     * For STRONG bots uses {@link StrongBotConfig#tradeSetCompletionWeight} and
     * {@link StrongBotConfig#tradeLiquidityWeight}; others use fixed ratios.
     */
    private int evaluateSelectionContextual(SessionState state, String botId,
                                             TradeSelectionState selection, boolean receiving) {
        StrongBotConfig cfg = configFor(botId);

        // Base value: cash weighted by liquidity preference, then property prices.
        // Mortgaged properties are worth only their mortgage value (face/2) since the
        // recipient must spend ~55% of face price to unmortgage before earning any rent.
        int value = (int)(selection.moneyAmount() * cfg.tradeLiquidityWeight());
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            int facePrice = SpotType.valueOf(propId).getIntegerProperty("price");
            boolean mortgaged = state.properties().stream()
                    .anyMatch(p -> propId.equals(p.propertyId()) && p.mortgaged());
            value += mortgaged ? facePrice / 2 : facePrice;
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

        // Bid up to a config-based ceiling based on property value and set completion
        if (minBid > 0) {
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
            // Bid more aggressively to block an opponent who is one property away from a monopoly
            if (propId != null) {
                StreetType aGroup = StrongBotStrategy.spotType(propId).streetType;
                if (aGroup.placeType == PlaceType.STREET) {
                    int aSize = StrongBotStrategy.setSize(aGroup);
                    boolean wouldBlockOpponent = aSize > 1 && state.players().stream()
                            .filter(p -> !p.playerId().equals(bidderId) && !p.bankrupt() && !p.eliminated())
                            .anyMatch(p -> StrongBotStrategy.ownedInSet(state, p.playerId(), aGroup) == aSize - 1);
                    if (wouldBlockOpponent) {
                        ceiling += cfg.auctionSetCompletionBonus();
                    }
                }
            }
            int maxBid = Math.min(ceiling, cash - reserve);

            // Budget-aware: if no remaining bidder can realistically outbid, just bid minimum
            java.util.Set<String> activeBidders = new java.util.HashSet<>(auction.eligiblePlayerIds());
            activeBidders.removeAll(auction.passedPlayerIds());
            activeBidders.remove(bidderId);
            boolean noEffectiveCompetition = activeBidders.isEmpty() || activeBidders.stream()
                    .allMatch(id -> {
                        PlayerSnapshot p = findPlayer(state, id);
                        return p == null || p.cash() < 2 * minBid;
                    });
            if (noEffectiveCompetition && maxBid >= minBid) {
                publisher.handle(new PlaceAuctionBidCommand(sessionId, bidderId, auction.auctionId(), minBid));
                return;
            }

            if (maxBid >= minBid) {
                // Jump ~⅓ of remaining headroom toward ceiling with ±40 % random jitter
                int headroom = maxBid - minBid;
                double factor = 0.6 + ThreadLocalRandom.current().nextDouble() * 0.8; // 0.6–1.4
                int extra = ((int) (headroom / 3.0 * factor) / 10) * 10;
                int bid = Math.min(maxBid, minBid + Math.max(10, extra));
                publisher.handle(new PlaceAuctionBidCommand(sessionId, bidderId, auction.auctionId(), bid));
            } else {
                // Bid at least the mortgage value — any winning bid below it is free value for the opponent
                int mortgageValue = propId != null ? facePrice / 2 : 0;
                if (minBid <= mortgageValue && cash >= minBid) {
                    publisher.handle(new PlaceAuctionBidCommand(sessionId, bidderId, auction.auctionId(), minBid));
                } else {
                    publisher.handle(new PassAuctionCommand(sessionId, bidderId, auction.auctionId()));
                }
            }
        } else {
            publisher.handle(new PassAuctionCommand(sessionId, bidderId, auction.auctionId()));
        }
    }

    // -------------------------------------------------------------------------
    // Delay computation
    // -------------------------------------------------------------------------

    /**
     * Computes a human-like delay before the bot acts, based on what the current state demands.
     * A ±20 % random jitter is applied on top.
     */
    private long computeDelay(SessionState state) {
        long botDelayMs = Long.getLong("monopoly.bot.think.delay.ms", DEFAULT_BOT_DELAY_MS);
        if (botDelayMs == 0) return 0;  // instant mode (tests / system property = 0)
        double speed = speedMultiplier;
        boolean isTradeOrAuction = state.tradeState() != null || state.auctionState() != null;
        if (speed == 0.0) return isTradeOrAuction ? 350L : MIN_FAST_DELAY_MS;
        long base = computeBaseDelay(state);
        if (isFirstTurn) {
            long firstTurnExtraMs = Long.getLong("monopoly.bot.first.turn.extra.delay.ms", 2500L);
            base += firstTurnExtraMs;
        }
        // Fixed multiplier: 0.80 (decisive / responsive feel)
        double diffMult = 0.80;
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
            if (trade.status() == TradeStatus.COUNTERED) {
                return 900;   // filling in counter-offer steps
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
                return 1500;  // winner confirming pickup
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

    /** Returns the bot config for the given player; falls back to defaults() if absent. */
    private StrongBotConfig configFor(String playerId) {
        return configs.getOrDefault(playerId, StrongBotConfig.defaults());
    }

    /**
     * Dynamic cash reserve that scales with board danger and opponent monopolies.
     */
    private int dynamicReserve(SessionState state, String playerId) {
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
        // Adjust effective reserve by position: losing bot spends more freely, leader is cautious
        int posAdjustedReserve = (int)(reserve / StrongBotStrategy.positionFactor(state, playerId));

        PropertyStateSnapshot candidate = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && p.mortgaged())
                .filter(p -> StrongBotStrategy.botOwnsFullGroup(state, playerId, StrongBotStrategy.spotType(p.propertyId()).streetType))
                .filter(p -> player.cash() - StrongBotStrategy.unmortgageCost(p.propertyId()) >= posAdjustedReserve)
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
        // Adjust effective reserve by position: losing bot spends more freely, leader is cautious
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

        if (bestGroup == null) return false;
        PropertyStateSnapshot target = StrongBotStrategy.findBuildTarget(state, playerId, bestGroup);
        if (target == null) return false;
        CommandResult result = publisher.handle(
                new BuyBuildingRoundCommand(sessionId, playerId, target.propertyId()));
        return result.accepted();
    }

    /**
     * Records a bot-initiated trade cancellation as a decline for the given partner.
     * This prevents the bot from immediately re-proposing the same trade after it had
     * to cancel due to convergence failure (counter-edit loop, insufficient funds, etc.).
     * Without this, tradeDeclinesByPartnerId stays at 0 and the bot loops indefinitely.
     */
    private void recordBotCancelAsDecline(String botId, TradeState trade) {
        if (trade == null) return;
        String partnerId = botId.equals(trade.initiatorPlayerId())
                ? trade.recipientPlayerId() : trade.initiatorPlayerId();
        tradeDeclinesByPartnerId.merge(partnerId, 1, Integer::sum);
        log.debug("Bot {} recorded self-cancel as decline for partner {} (cumulative: {})",
                botId.substring(0, 8), partnerId,
                tradeDeclinesByPartnerId.get(partnerId));
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
