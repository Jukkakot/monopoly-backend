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

import fi.monopoly.utils.RandomSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Server-side greedy bot driver for pure-domain sessions.
@Slf4j
public final class PureDomainBotDriver implements ClientSessionListener {

    // When true, dispatchGreedy routes through PureDomainStrategy + BotExecutor instead of the
    // inline greedy logic. Off by default until Phase 1.4 validation is complete.
    static final boolean USE_NEW_STRATEGY = Boolean.getBoolean("monopoly.bot.use.strategy");

    // Fallback delay used when situational logic cannot determine a better value.
    private static final long DEFAULT_BOT_DELAY_MS = 900L;

    // Minimum delay even in "fast" mode — prevents server overload from rapid-fire bot games.
    private static final long MIN_FAST_DELAY_MS = 50L;

    private static final int MAX_DECLINES_PER_PARTNER = 2;
    // Max counter-offers the bot will make within a single trade before it must accept or decline.
    // Applies to every partner equally; guarantees a trade negotiation always terminates.
    private static final int MAX_COUNTERS_PER_TRADE = 2;

    // threshold by this fraction of the value given — the bot will pay a premium (or accept a small paper loss) t...
    private static final double MONOPOLY_COMPLETION_DISCOUNT = 0.30;

    // buyer's monopoly.
    private static final double MONOPOLY_SALE_PREMIUM = 1.6;

    // relaxes its monopoly-gift caution by this factor so deals can finally happen.
    private static final double STALEMATE_GIFT_RELIEF = 0.75;
    // [Fix 3] How many bot turns without any new monopoly before stalemate easing kicks in.
    private static final int STALEMATE_TURN_THRESHOLD = 12;

    // monopoly and the bot does not — getting our own set becomes urgent, accept worse deals.
    private static final double CATCHUP_DISCOUNT = 0.25;

    // Bot pauses when the client is more than this many versions behind its latest ACK.
    // Keeps the backend from running more than ~1 full bot turn ahead of what the client
    // has received, so the animation queue never grows unboundedly.
    private static final int MAX_CLIENT_LAG_VERSIONS = 3;

    // A client ack within this window counts as "a viewer is present", independent of the SSE
    // connect counter — which misses viewers that connected during the lobby, before the driver
    // existed, leaving viewerCount stuck at 0 for the whole game.
    private static final long VIEWER_PRESENCE_ACK_GRACE_MS = 15_000L;

    // Watchdog: how many consecutive 5 s ticks the same actionable state must persist while no viewer
    // is connected before the watchdog force-recovers it. Guards against reacting to normal between-action
    // pacing while still rescuing a bot whose SSE viewer dropped mid-action.
    private static final int WATCHDOG_NO_VIEWER_STALL_TICKS = 2;

    private final SessionCommandPublisher publisher;
    private final String sessionId;
    private final Set<String> botPlayerIds;
    // Per-player bot configs. Falls back to StrongBotConfig.defaults() if absent.
    private final Map<String, StrongBotConfig> configs;
    private final RandomSource rng;
    // Strategy path: routes through BotStrategy.decide() + BotExecutor.
    // Active when USE_NEW_STRATEGY is set OR when a non-PD strategy is injected (e.g. UtilityStrategy).
    private final fi.monopoly.server.bot.BotStrategy strategy;
    private final boolean alwaysUseStrategy;
    private final BotExecutor executor;
    private final Map<String, fi.monopoly.server.bot.BotMemory> memories;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean pendingAction = new AtomicBoolean(false);
    private final AtomicInteger viewerCount = new AtomicInteger(0);
    // The highest snapshot version the client has explicitly acknowledged. -1 = no ACK received yet.
    private final AtomicLong latestClientVersion = new AtomicLong(-1);
    // Wall-clock time (ms) of the most recent client ack — a robust "viewer present" signal.
    private volatile long lastClientAckAtMillis = 0L;
    // Disabled by default so unit tests work without simulating SSE connections.
    private volatile boolean viewerGatingEnabled = false;
    private volatile double speedMultiplier = 1.0;
    private volatile TradeState lastObservedTrade = null;
    // True until the bot acts for the first time this game — adds extra delay so players can orient.
    private volatile boolean isFirstTurn = true;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> tradeDeclinesByPartnerId
            = new java.util.concurrent.ConcurrentHashMap<>();
    // [Loop fix B] Per-partner set of target properties this partner has refused to give up,
    // recorded at the bot's OWN decline decision (not inferred from snapshot transitions, so a fast
    // re-open can't drop the signal). Pass 0/1 skip re-proposing the same (partner, target) — without
    // abandoning the partner for other deals.
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> declinedSwapTargets
            = new java.util.concurrent.ConcurrentHashMap<>();
    // Last bot playerId whose WAITING_FOR_ROLL we observed — used to detect turn transitions.
    private volatile String lastBotTurnStartId = null;
    // [Fix 3] Counts bot turns observed since a monopoly last appeared, for stalemate detection.
    private volatile int turnsSinceMonopolyChange = 0;
    private volatile int lastMonopolyCount = 0;
    // Consecutive EditTradeOffer attempts per tradeId — safety net against infinite edit loops.
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> counterEditAttempts
            = new java.util.concurrent.ConcurrentHashMap<>();
    // Generic stuck-detection: if the bot keeps dispatching in the same situation repeatedly, something is wrong.
    private volatile String lastDispatchFingerprint = "";
    private volatile int consecutiveDispatchCount = 0;
    // Last money amount the bot offered per (partnerId -> propertyId) that was declined.
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Integer>>
            lastDeclinedOfferAmount = new java.util.concurrent.ConcurrentHashMap<>();
    // Watchdog stall tracking — only read/written on the single scheduler thread, so no synchronisation needed.
    private long watchdogStuckVersion = Long.MIN_VALUE;
    private int watchdogStuckTicks = 0;
    // Trade frequency limiting: track the game-turn number (WAITING_FOR_ROLL transitions) at which this
    // bot last successfully opened a trade, so we don't propose trades every single turn.
    // Access is single-threaded (all dispatch happens on the scheduler thread).
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> lastTradeOpenedAtTurn
            = new java.util.concurrent.ConcurrentHashMap<>();
    // When a trade was last COMPLETED (accepted) by this bot. Used for post-trade cooldown so bots
    // don't immediately chain into another trade right after closing one.
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> lastTradeCompletedAtTurn
            = new java.util.concurrent.ConcurrentHashMap<>();
    // Global monotonic turn counter — incremented whenever any bot enters WAITING_FOR_ROLL.
    private final java.util.concurrent.atomic.AtomicInteger globalTurnCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    private PureDomainBotDriver(
            SessionCommandPublisher publisher,
            String sessionId,
            Set<String> botPlayerIds,
            Map<String, StrongBotConfig> configs,
            RandomSource rng,
            fi.monopoly.server.bot.BotStrategy strategyOverride) {
        this.publisher = publisher;
        this.sessionId = sessionId;
        this.botPlayerIds = botPlayerIds;
        this.configs = Map.copyOf(configs);
        this.rng = rng;
        this.strategy = strategyOverride != null ? strategyOverride : new PureDomainStrategy(configs);
        this.alwaysUseStrategy = USE_NEW_STRATEGY || !(this.strategy instanceof PureDomainStrategy);
        this.executor = new BotExecutor(publisher, sessionId);
        var mem = new java.util.concurrent.ConcurrentHashMap<String, fi.monopoly.server.bot.BotMemory>();
        for (String id : botPlayerIds) mem.put(id, fi.monopoly.server.bot.BotMemory.empty());
        this.memories = java.util.Collections.unmodifiableMap(mem);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("bot-driver-" + sessionId.substring(0, 8), 0).factory());
        // Watchdog: fires every 5 s to recover a bot that has stopped acting even though the game still
        // needs it to — e.g. it paused on the client-lag gate mid-trade-edit and the awaited ACK never
        // arrived, or the SSE viewer dropped (or was miscounted) while the player was still on the screen.
        //
        // Recovery is split by viewer presence:
        //   • viewers present → retrigger immediately, as before (fast recovery while someone is watching).
        //   • no viewers      → this previously returned early and gave up, which is exactly the failure
        //     behind the "Odotetaan kaupan vastausta…" freeze: a mobile client whose SSE silently dropped
        //     leaves viewerCount at 0, so nothing ever unstuck the bot. We now still recover, but only once
        //     the same actionable state has persisted unchanged for WATCHDOG_NO_VIEWER_STALL_TICKS ticks, so
        //     we react to genuine stalls rather than normal between-action pacing. This cannot run an
        //     abandoned game away: needsBotAction() is false on a human's turn, so headless progress halts
        //     there, and idle sessions are reaped by the registry TTL regardless.
        this.scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!viewerGatingEnabled) return;
                if (pendingAction.get()) return;
                SessionState s = publisher.currentState();
                if (s == null || s.status() == SessionStatus.GAME_OVER
                        || s.status() == SessionStatus.LOBBY
                        || !needsBotAction(s)) {
                    // Not an actionable bot state (game over, lobby, or a human's turn) — clear stall tracking.
                    watchdogStuckVersion = Long.MIN_VALUE;
                    watchdogStuckTicks = 0;
                    return;
                }

                ClientSessionSnapshot snap = publisher.currentSnapshot();
                long version = snap != null ? snap.version() : -1;
                if (version == watchdogStuckVersion) {
                    watchdogStuckTicks++;
                } else {
                    watchdogStuckVersion = version;
                    watchdogStuckTicks = 1;
                }

                if (viewerPresent()) {
                    long lag = version - latestClientVersion.get();
                    log.info("Bot watchdog: retriggering for session {} (lag {})",
                            sessionId.substring(0, 8), lag);
                    retrigger();
                    return;
                }

                // No connected viewers: only recover a genuine stall (state unchanged across several ticks).
                // This is the dropped-SSE case that used to deadlock indefinitely.
                if (watchdogStuckTicks >= WATCHDOG_NO_VIEWER_STALL_TICKS) {
                    log.warn("Bot watchdog: recovering stalled bot with no connected viewers "
                                    + "(session {}, stuck {} ticks at version {}) — likely a dropped SSE connection",
                            sessionId.substring(0, 8), watchdogStuckTicks, version);
                    retrigger();
                }
            } catch (Exception e) {
                log.warn("Bot watchdog threw", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    // Creates and registers a bot driver using the given strategy.
    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher,
            SessionState initialState,
            Map<String, StrongBotConfig> configs,
            fi.monopoly.server.bot.BotStrategy strategy) {
        Set<String> botIds = collectBotPlayerIds(initialState);
        if (botIds.isEmpty()) return null;
        PureDomainBotDriver driver = new PureDomainBotDriver(
                publisher, initialState.sessionId(), botIds, configs, RandomSource.threadLocal(), strategy);
        publisher.addListener(driver);
        log.info("Bot driver registered for session {} — bots: {}",
                initialState.sessionId().substring(0, 8), botIds);
        // Trigger initial check after a short grace period.
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

    // Convenience overload: uses PureDomainStrategy with given configs.
    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher,
            SessionState initialState,
            Map<String, StrongBotConfig> configs) {
        return createAndRegisterIfNeeded(publisher, initialState, configs, new PureDomainStrategy(configs));
    }

    static PureDomainBotDriver createAndRegisterIfNeeded(
            SessionCommandPublisher publisher, SessionState initialState) {
        return createAndRegisterIfNeeded(publisher, initialState, Map.of());
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    // Forces the bot to re-evaluate and act immediately (0 ms delay).
    public void retrigger() {
        pendingAction.set(false);
        SessionState state = publisher.currentState();
        if (state == null || !needsBotAction(state)) return;
        if (pendingAction.compareAndSet(false, true)) {
            // Bypass viewer gating: explicit retrigger calls are intentional (e.g.
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

    // True if someone is watching: either an SSE viewer is counted, or the client acked recently.
    // The ack-based signal is essential because lobby-phase connects are not counted (driver was null then).
    private boolean viewerPresent() {
        return viewerCount.get() > 0
                || System.currentTimeMillis() - lastClientAckAtMillis < VIEWER_PRESENCE_ACK_GRACE_MS;
    }

    // Called when an SSE viewer connects to this session.
    public void onSseConnected() {
        if (viewerCount.incrementAndGet() == 1) {
            log.info("First SSE viewer connected for session {} — resuming bot", sessionId.substring(0, 8));
            retrigger();
        }
    }

    // Called when an SSE viewer disconnects from this session.
    public void onSseDisconnected() {
        int remaining = viewerCount.updateAndGet(v -> Math.max(0, v - 1));
        if (remaining == 0) {
            log.info("Last SSE viewer disconnected for session {} — bot will pause", sessionId.substring(0, 8));
        }
    }

    // Called when the client acknowledges it has processed a snapshot at the given version.
    public void onClientAck(long version) {
        lastClientAckAtMillis = System.currentTimeMillis();
        long prev = latestClientVersion.getAndUpdate(v -> Math.max(v, version));
        if (prev < version) {
            // An ack is reliable proof a viewer is present and caught up — more reliable than the
            // SSE connect/disconnect counter, which misses viewers that connected during the lobby
            // phase (before the bot driver existed). So drive the bot off the ack via the
            // gate-bypassing forced step, paced by computeDelay so it can't outrun itself or storm.
            // When unwatched there are no acks, so the bot still pauses (watchdog only). pendingAction guards.
            SessionState state = publisher.currentState();
            if (state != null && needsBotAction(state) && pendingAction.compareAndSet(false, true)) {
                scheduler.schedule(this::takeStepForced, computeDelay(state), TimeUnit.MILLISECONDS);
            }
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

    // ClientSessionListener

    @Override
    public void onSnapshotChanged(ClientSessionSnapshot snapshot) {
        SessionState state = snapshot.state();
        if (state == null || state.status() == SessionStatus.GAME_OVER
                || state.status() == SessionStatus.LOBBY) {
            lastObservedTrade = null;
            return;
        }
        // Decline memory persists for the whole game: once a partner has declined the bot's offers
        // MAX_DECLINES_PER_PARTNER times, the bot gives up on that partner and won't keep proposing.
        // This is what stops bots looping trades forever. (Not wiped or decayed per turn.)
        var turn = state.turn();
        if (turn != null && turn.phase() == TurnPhase.WAITING_FOR_ROLL
                && botPlayerIds.contains(turn.activePlayerId())
                && !turn.activePlayerId().equals(lastBotTurnStartId)) {
            lastBotTurnStartId = turn.activePlayerId();
            globalTurnCounter.incrementAndGet();
            // [Fix 3] Track stalemate: count total monopolies on the board.
            int monopolyCount = (int) state.players().stream()
                    .filter(p -> !p.bankrupt() && !p.eliminated())
                    .filter(p -> playerHasMonopoly(state, p.playerId()))
                    .count();
            if (monopolyCount == lastMonopolyCount) {
                turnsSinceMonopolyChange++;
            } else {
                turnsSinceMonopolyChange = 0;
                lastMonopolyCount = monopolyCount;
            }
            // Keep BotMemory stalemate tracking in sync (all bots share the same global count)
            for (fi.monopoly.server.bot.BotMemory m : memories.values()) {
                m.updateMonopolyTracking(monopolyCount);
            }
        }

        // Track when bot-initiated trades are declined to avoid re-proposing to the same partner.
        TradeState prevTrade = lastObservedTrade;
        lastObservedTrade = state.tradeState();
        // Clear the edit-loop counter when the trade resolves (tradeId changes or trade disappears)
        if (prevTrade != null) {
            String prevId = prevTrade.tradeId();
            TradeState cur = state.tradeState();
            if (cur == null || !prevId.equals(cur.tradeId())) {
                counterEditAttempts.remove(prevId);
                for (fi.monopoly.server.bot.BotMemory m : memories.values()) {
                    m.clearCounterEdits(prevId);
                }
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
            String declinedPropId = null;
            int declinedAmount = 0;
            if (lastOffer != null) {
                boolean botIsProposer = prevTrade.openedByPlayerId().equals(prevTrade.initiatorPlayerId());
                TradeSelectionState botWanted = botIsProposer
                        ? lastOffer.requestedFromRecipient() : lastOffer.offeredToRecipient();
                TradeSelectionState botGave = botIsProposer
                        ? lastOffer.offeredToRecipient() : lastOffer.requestedFromRecipient();
                if (!botWanted.propertyIds().isEmpty()) {
                    declinedPropId = botWanted.propertyIds().get(0);
                    declinedAmount = botGave.moneyAmount();
                    lastDeclinedOfferAmount
                            .computeIfAbsent(partner, k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(declinedPropId, declinedAmount);
                    log.debug("Recorded declined offer: partner={} prop={} amount={}",
                            partner, declinedPropId, declinedAmount);
                }
            }
            // Mirror updates to BotMemory for the strategy path
            fi.monopoly.server.bot.BotMemory botMem = memories.get(prevTrade.openedByPlayerId());
            if (botMem != null) {
                botMem.recordDecline(partner);
                if (declinedPropId != null) {
                    botMem.recordDeclinedAmount(partner, declinedPropId, declinedAmount);
                }
            }
        }
        if (!needsBotAction(state)) {
            return;
        }
        if (viewerGatingEnabled && !viewerPresent()) {
            return;  // nobody watching (no viewer, no recent ack) — pause until someone connects/acks
        }
        if (viewerGatingEnabled && snapshot.version() - latestClientVersion.get() > MAX_CLIENT_LAG_VERSIONS) {
            return;  // client is too far behind — wait for ACK before advancing
        }
        if (!pendingAction.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(this::takeStep, computeDelay(snapshot.state()), TimeUnit.MILLISECONDS);
    }

    // Bot step

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
        if (viewerGatingEnabled && !viewerPresent()) {
            return;  // nobody watching (no viewer, no recent ack) — pause until someone connects/acks
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
            // During WON_PENDING_RESOLUTION, currentActorPlayerId is null; use winner so a bot winner can dispatch Finish...
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

    // Fingerprint of the current game situation — used to detect stuck/looping bot behaviour.
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

        if (alwaysUseStrategy) {
            dispatchViaStrategy(state);
            return;
        }

        // Trade actions may involve a player other than the active turn player — handle first.
        if (state.tradeState() != null) {
            TradeState trade = state.tradeState();
            String tradeActor = trade.decisionRequiredFromPlayerId();
            String editor = trade.editingPlayerId();

            // COUNTERED editing must be checked before tradeActor: handleCounter sets decisionRequiredFromPlayerId = bot...
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

    private void dispatchViaStrategy(SessionState state) {
        String activeId = resolveActorId(state);
        if (activeId == null) return;
        fi.monopoly.server.bot.BotMemory memory =
                memories.getOrDefault(activeId, fi.monopoly.server.bot.BotMemory.empty());
        fi.monopoly.server.bot.Intent intent = strategy.decide(state, activeId, memory, rng);
        executor.execute(intent, activeId);
    }

    private void handleDecision(SessionState state, String activeId) {
        PendingDecision decision = state.pendingDecision();
        if (decision == null) {
            // WAITING_FOR_DECISION is set by OverlaySessionStateStore whenever tradeState OR pendingDecision is non-null.
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

    // Score-based property purchase decision for STRONG bots.
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

    // Finds the best property to mortgage to raise at least {@code needed} extra cash for a purchase.
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
        TradeOfferState offer = trade.currentOffer();
        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());


        String tradePartnerId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();

        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();

        int valueReceived = evaluateSelectionContextual(state, botId, myReceiving, true);
        int giftPenalty   = monopolyGiftPenalty(state, tradePartnerId, myGiving);
        int valueGiven    = evaluateSelectionContextual(state, botId, myGiving, false) + giftPenalty;

        // When selling properties for cash only, demand a 15% premium — but only from humans.
        // Bot-vs-bot cash offers use symmetric face-price logic; the premium would just block all such trades.
        boolean sellsPropertyForCash = !myGiving.propertyIds().isEmpty()
                && myReceiving.propertyIds().isEmpty()
                && myReceiving.jailCardCount() == 0;
        boolean partnerIsHuman = !botPlayerIds.contains(tradePartnerId);
        int cashSalePremium = (partnerIsHuman && sellsPropertyForCash && giftPenalty == 0)
                ? Math.max(40, (int)(myGiving.propertyIds().stream()
                        .mapToInt(id -> SpotType.valueOf(id).getIntegerProperty("price"))
                        .sum() * 0.15))
                : 0;

        // ── SANITY CHECKS ────────────────────────────────────────────────────────────────── These guard against deg...

        // (a) Pure giveaway: the partner asks for something and offers nothing back.
        boolean botGetsNothing = myReceiving.propertyIds().isEmpty()
                && myReceiving.moneyAmount() <= 0 && myReceiving.jailCardCount() <= 0;
        boolean botGivesSomething = !myGiving.propertyIds().isEmpty()
                || myGiving.moneyAmount() > 0 || myGiving.jailCardCount() > 0;
        if (botGetsNothing && botGivesSomething) {
            long counterCount = trade.history().stream()
                    .filter(e -> "COUNTERED".equals(e.actionType())).count();
            boolean botWantsSomething = findStrategicTargetProperty(state, botId, tradePartnerId) != null;
            if (botWantsSomething && counterCount < MAX_COUNTERS_PER_TRADE) {
                publisher.handle(new CounterTradeCommand(sessionId, botId, tradeId));
            } else {
                declineReceivedOffer(botId, tradeId, myGiving);
            }
            return;
        }

        // (b) Wildly lopsided ask: the bot is asked to give more than ~3× what it would receive.
        if (valueGiven > 0 && valueReceived * 3 < valueGiven
                && !completesOwnMonopoly(state, botId, myReceiving)) {
            declineReceivedOffer(botId, tradeId, myGiving);
            return;
        }

        // (c) Net monopoly delta check: only relevant when the bot is RECEIVING properties.
        // When selling for cash (myReceiving has no properties), the partner necessarily gains
        // monopoly progress while the bot gets compensated with money — that is expected and
        // acceptable. monopolyGiftPenalty already inflates valueGiven for monopoly-completing
        // pieces, so the final fairness threshold handles the strategic-value compensation.
        //
        // For property-for-property swaps (both sides have properties), we check who gains more:
        //   netDelta > 0  → bot gains more monopoly progress → accept if otherwise fair
        //   netDelta == 0 → equal swap → allow unless bot is also paying cash
        //   netDelta < 0  → partner gains more monopoly leverage → decline
        if (!myReceiving.propertyIds().isEmpty()) {
            double netDelta = StrongBotStrategy.tradeMonopolyNetDelta(
                    state, botId, tradePartnerId,
                    myReceiving.propertyIds(), myGiving.propertyIds());
            if (netDelta < 0) {
                declineReceivedOffer(botId, tradeId, myGiving);
                return;
            }
            if (netDelta == 0.0) {
                int botCashNet = myReceiving.moneyAmount() - myGiving.moneyAmount();
                if (botCashNet < 0) {
                    declineReceivedOffer(botId, tradeId, myGiving);
                    return;
                }
            }
        }

        // (d) Goal-orientation check: if the bot receives only properties (no significant cash) and those
        // properties don't help complete any of the bot's color groups, decline the trade. A property
        // that doesn't advance a monopoly goal is worth at most its face value — but changing hands
        // for face value has no strategic benefit; it just shuffles properties around.
        boolean receivingPropertiesOnly = !myReceiving.propertyIds().isEmpty()
                && myReceiving.moneyAmount() < 50;  // trivial cash sweetener doesn't count
        if (receivingPropertiesOnly && !completesOwnMonopoly(state, botId, myReceiving)
                && !advancesOwnMonopoly(state, botId, myReceiving)) {
            // Received properties are strategically inert — decline unless we're also getting rid of
            // inert properties (pure swap that improves neither side has no value).
            declineReceivedOffer(botId, tradeId, myGiving);
            return;
        }

        // Acceptance threshold scales with the partner's threat score (0-1): low threat → standard fairness tolerance.
        double posFactor = StrongBotStrategy.positionFactor(state, botId);
        int fairnessTolerance = (int)(configFor(botId).tradeFairnessTolerance() * posFactor);
        double ts = StrongBotStrategy.threatScore(state, tradePartnerId);
        int requiredPremium = (int)(valueGiven * ts * 0.25);

        // Strategic discount: if accepting completes one of the bot's OWN monopolies, the long-game value (rent once...
        int strategicDiscount = 0;
        if (completesOwnMonopoly(state, botId, myReceiving)) {
            // [Fix 4] If an opponent ALREADY has a monopoly and the bot has none, getting our own set is urgent — falling...
            boolean catchingUp = someoneElseHasMonopoly(state, botId) && !playerHasMonopoly(state, botId);
            double discountRate = catchingUp ? CATCHUP_DISCOUNT : MONOPOLY_COMPLETION_DISCOUNT;
            strategicDiscount = (int)(valueGiven * discountRate);
            requiredPremium = 0; // never demand a premium when closing our own set
        }

        if (valueReceived >= valueGiven - fairnessTolerance - strategicDiscount + requiredPremium + cashSalePremium) {
            publisher.handle(new AcceptTradeCommand(sessionId, botId, tradeId));
            lastTradeCompletedAtTurn.put(botId, globalTurnCounter.get());
            return;
        }

        // Prefer countering over declining. Counter count is monotonic within a trade, and the cap
        // guarantees termination — same rule for every partner, human or bot.
        if (valueGiven > 0) {
            long counterCount = trade.history().stream()
                    .filter(e -> "COUNTERED".equals(e.actionType())).count();
            boolean offerIsReasonable = counterCount == 0 || valueReceived >= valueGiven * 0.25;
            if (offerIsReasonable && counterCount < MAX_COUNTERS_PER_TRADE) {
                publisher.handle(new CounterTradeCommand(sessionId, botId, tradeId));
                return;
            }
        }

        declineReceivedOffer(botId, tradeId, myGiving);
    }

    // Called when the bot is in COUNTERED editing mode — it rejected the incoming offer terms and is now proposin...
    private void handleCounterEditing(SessionState state, String botId) {
        TradeState trade = state.tradeState();
        if (trade == null) return;
        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();

        // Safety net (checked up-front so EVERY branch below is counted, not just the final edit path).
        int attempts = counterEditAttempts.merge(tradeId, 1, Integer::sum);
        if (attempts > 8) {
            log.warn("Bot {} counter-edit loop detected for trade {} ({} attempts) — cancelling",
                    botId.substring(0, 8), tradeId.substring(0, 12), attempts);
            counterEditAttempts.remove(tradeId);
            recordBotCancelAsDecline(botId, trade);
            publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // Determine which side is bot's give vs receive, same as in handleTradeDecision
        boolean botIsRecipient = botId.equals(offer.recipientPlayerId());
        TradeSelectionState myGiving    = botIsRecipient ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        TradeSelectionState myReceiving = botIsRecipient ? offer.offeredToRecipient() : offer.requestedFromRecipient();

        String counterPartnerId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();
        int valueGiven = evaluateSelectionContextual(state, botId, myGiving, false)
                       + monopolyGiftPenalty(state, counterPartnerId, myGiving);
        int currentMoneyReceived = myReceiving.moneyAmount();
        int nonMoneyReceived = evaluateSelectionValue(myReceiving, state) - currentMoneyReceived;

        // ── GIVEAWAY GUARD ────────────────────────────────────────────────────────────────── The partner is asking...
        boolean receiveEmpty = myReceiving.propertyIds().isEmpty()
                && currentMoneyReceived <= 0 && myReceiving.jailCardCount() <= 0;
        boolean giveNonEmpty = !myGiving.propertyIds().isEmpty()
                || myGiving.moneyAmount() > 0 || myGiving.jailCardCount() > 0;
        if (receiveEmpty && giveNonEmpty) {
            boolean botGivesProperty = !myGiving.propertyIds().isEmpty();

            // Case 1: pure cash ask (or jail card) — nothing to negotiate. Cancel.
            if (!botGivesProperty) {
                counterEditAttempts.remove(tradeId);
                recordBotCancelAsDecline(botId, trade);
                publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                return;
            }

            // Case 2: the partner wants the bot's property.
            if (givingBreaksOwnMonopoly(state, botId, myGiving)) {
                counterEditAttempts.remove(tradeId);
                recordBotCancelAsDecline(botId, trade);
                publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                return;
            }
            // Ask the partner to pay for the property.
            // Monopoly-completing sales already carry a large gift penalty in valueGiven, so use 1.05.
            // Regular property sales use 1.15 to ensure the bot demands a fair premium over face value.
            boolean completesBuyerSet = myGiving.propertyIds().stream()
                    .anyMatch(id -> wouldCompletePartnerMonopoly(state, counterPartnerId, id));
            double margin = (completesBuyerSet ? 1.05 : 1.15) + StrongBotStrategy.threatScore(state, counterPartnerId) * 0.20;
            if (completesBuyerSet) margin *= MONOPOLY_SALE_PREMIUM;
            int askingPrice = (int) (valueGiven * margin);
            PlayerSnapshot partnerSnap = findPlayer(state, counterPartnerId);
            int partnerCash = partnerSnap != null ? partnerSnap.cash() : 0;
            if (askingPrice >= 10 && partnerCash >= askingPrice) {
                publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                        new TradeEditPatch(null, botIsRecipient, askingPrice, List.of(), List.of(), null)));
                return;
            }
            // Partner can't afford a fair price (and has offered no property) — nothing fair to do.
            counterEditAttempts.remove(tradeId);
            recordBotCancelAsDecline(botId, trade);
            publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // Profit target scales with partner threat score (0-1): 1.05 (no threat) to 1.25 (full threat).
        double ts = StrongBotStrategy.threatScore(state, counterPartnerId);
        double profitFactor = 1.05 + ts * 0.20;
        int targetMoneyReceived = Math.max(0, (int) (valueGiven * profitFactor) - nonMoneyReceived);

        // Sanity: if the ask would break one of the bot's OWN monopolies (e.g.
        if (givingBreaksOwnMonopoly(state, botId, myGiving)) {
            counterEditAttempts.remove(tradeId);
            recordBotCancelAsDecline(botId, trade);
            publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
            return;
        }

        // editOfferedSide: side the bot RECEIVES; editGiveSide: side the bot GIVES
        boolean editOfferedSide = botIsRecipient;
        boolean editGiveSide    = !editOfferedSide;

        // Single-side money rule: never submit an offer with money on both sides — it is confusing.
        int givenMoney = myGiving.moneyAmount();
        if (givenMoney > 0 && (targetMoneyReceived > 0 || currentMoneyReceived > 0)) {
            if (!myGiving.propertyIds().isEmpty()) {
                // Given side = property + cash: drop the cash; the property is the trade-in.
                publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                        new TradeEditPatch(null, editGiveSide, 0, List.of(), List.of(), null)));
            } else {
                // Given side = pure cash: this is cash-for-properties.
                int fairGiveMoney = Math.max(0, (int) (nonMoneyReceived * 0.95));
                if (fairGiveMoney < 10) {
                    counterEditAttempts.remove(tradeId);
                    publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
                } else {
                    publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                            new TradeEditPatch(null, editGiveSide, fairGiveMoney, List.of(), List.of(), null)));
                }
            }
            return;
        }

        // ── COUNTER CONVERGENCE (iterative; one edit per round, re-entered via snapshot) ────── Each branch makes ON...

        // [C1] Already fair: received value meets the target (small tolerance kills ±1 jitter).
        if (currentMoneyReceived >= targetMoneyReceived - 2) {
            counterEditAttempts.remove(tradeId);
            publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
            return;
        }

        // [C2] The asking price is the fair value of the deal (targetMoneyReceived), NOT a function of how much cash...
        String otherPartyId = botIsRecipient ? offer.proposerPlayerId() : offer.recipientPlayerId();
        PlayerSnapshot otherParty = findPlayer(state, otherPartyId);
        int proposerCash = otherParty != null ? otherParty.cash() : 0;

        // [C3] Opponent can pay the fair price outright → set that fair price and submit next round.
        if (proposerCash >= targetMoneyReceived) {
            if (targetMoneyReceived == currentMoneyReceived) {
                counterEditAttempts.remove(tradeId);
                publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
            } else {
                publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                        new TradeEditPatch(null, editOfferedSide, targetMoneyReceived, List.of(), List.of(), null)));
            }
            return;
        }

        // [C4] Opponent can't pay the full fair price in cash.
        String bridgeProp = findUnrequestedTarget(state, botId, otherPartyId, myReceiving);
        if (bridgeProp != null) {
            java.util.List<String> updated = new java.util.ArrayList<>(myReceiving.propertyIds());
            updated.add(bridgeProp);
            publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                    new TradeEditPatch(null, editOfferedSide, null, updated, List.of(), null)));
            return;
        }

        // [C5] No property left to bridge with and the opponent can't cover the fair price in cash.
        if (proposerCash >= valueGiven) {
            if (proposerCash == currentMoneyReceived) {
                counterEditAttempts.remove(tradeId);
                publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));
            } else {
                publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                        new TradeEditPatch(null, editOfferedSide, proposerCash, List.of(), List.of(), null)));
            }
            return;
        }

        // [C6] Cannot reach a fair deal at all — cancel cleanly.
        counterEditAttempts.remove(tradeId);
        recordBotCancelAsDecline(botId, trade);
        publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
    }

    // Called when the bot is in EDITING mode (it opened the trade or sent a counter).
    private void handleTradeEditing(SessionState state, String botId) {
        TradeState trade = state.tradeState();
        if (trade == null) return;

        TradeOfferState offer = trade.currentOffer();
        String tradeId = trade.tradeId();
        boolean iAmProposer = botId.equals(trade.initiatorPlayerId());

        // From the canonical offer perspective: offeredToRecipient = what proposer gives; requestedFromRecipient = wh...
        TradeSelectionState myGive = iAmProposer ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState myRequest = iAmProposer ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        boolean giveSide = iAmProposer;
        boolean requestSide = !iAmProposer;

        // Step 1: request a target property from partner (bundled if completing the set needs more)
        if (myRequest.propertyIds().isEmpty()) {
            String partnerId = iAmProposer ? trade.recipientPlayerId() : trade.initiatorPlayerId();
            String targetProp = findStrategicTargetProperty(state, botId, partnerId);
            if (targetProp == null) {
                recordBotCancelAsDecline(botId, trade);
                publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));
                return;
            }
            java.util.List<String> targets = bundleTargetsForGroup(state, botId, partnerId, targetProp);
            publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId,
                    new TradeEditPatch(null, requestSide, null, targets, List.of(), null)));
            return;
        }

        String targetPropId0 = myRequest.propertyIds().get(0);
        String partnerId0 = iAmProposer ? trade.recipientPlayerId() : trade.initiatorPlayerId();
        int targetPrice = myRequest.propertyIds().stream()
                .mapToInt(id -> SpotType.valueOf(id).getIntegerProperty("price"))
                .sum();
        PlayerSnapshot botSnap = findPlayer(state, botId);
        int available0 = botSnap != null ? Math.max(0, botSnap.cash() - dynamicReserve(state, botId)) : 0;

        // Step 2a: offer an own property when needed to sweeten the deal.
        boolean targetIsP1 = isMonopolyCompletingTarget(state, botId, targetPropId0);
        if (myGive.propertyIds().isEmpty() && myGive.moneyAmount() == 0) {
            if (available0 < targetPrice || targetIsP1) {
                String expendable = findExpendableOwnProperty(state, botId, partnerId0, targetPropId0);
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
                int shortfall = cashNeeded - offerAmount;
                boolean tooShortOnCash = hasOwnProp
                        ? shortfall > configFor(botId).tradeFairnessTolerance()
                        : offerAmount <= prevDeclined;
                if (offerAmount < 10 || tooShortOnCash) {
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

    // How many global turns must pass between the bot opening a trade and trying again.
    // Applies to all passes (including win-win and critical). With 6 bots, 6 global turns ≈ 1 full round.
    private static final int TRADE_COOLDOWN_TURNS = 6;
    // How many global turns must pass after a trade COMPLETES before this bot opens or accepts another.
    // Prevents cascade effect where one completed trade immediately triggers the next.
    private static final int POST_TRADE_COOLDOWN_TURNS = 8;

    // STRONG bot tries to open a trade to acquire a property that advances a color group it already partially owns.
    // Trades are only initiated when there is a clear monopoly goal — either completing our own set or helping
    // a partner complete theirs in exchange for something we need (win-win). Aimless foothold trades are skipped.
    private boolean tryInitiateStrategicTrade(SessionState state, String botId) {
        PlayerSnapshot bot = findPlayer(state, botId);
        int reserve = dynamicReserve(state, botId);
        if (bot == null || bot.cash() < reserve + 50) return false;

        int currentTurn = globalTurnCounter.get();

        // Post-completion cooldown: don't open a new trade too soon after the last one closed.
        // This is the main guard against bot-vs-bot trade cascade in multi-player games.
        int lastCompleted = lastTradeCompletedAtTurn.getOrDefault(botId, -100);
        if (currentTurn - lastCompleted < POST_TRADE_COOLDOWN_TURNS) return false;

        // Per-bot open cooldown: applies to all passes (including win-win and critical).
        int lastOpened = lastTradeOpenedAtTurn.getOrDefault(botId, -100);
        if (currentTurn - lastOpened < TRADE_COOLDOWN_TURNS) return false;

        // Pass 0 (win-win): a partner holds a piece the bot needs AND the bot holds a piece that completes the
        // PARTNER's set — both sides gain a monopoly benefit. These are high-value goal-oriented trades.
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (tradeDeclinesByPartnerId.getOrDefault(other.playerId(), 0) >= MAX_DECLINES_PER_PARTNER) continue;
            String winWinTarget = findWinWinTargetProperty(state, botId, other.playerId());
            if (winWinTarget != null
                    && !declinedSwapTargets.getOrDefault(other.playerId(), java.util.Set.of()).contains(winWinTarget)) {
                CommandResult result = publisher.handle(new OpenTradeCommand(sessionId, botId, other.playerId()));
                if (result.accepted()) {
                    lastTradeOpenedAtTurn.put(botId, currentTurn);
                    return true;
                }
            }
        }

        // Pass 1: bot is one property away from completing a monopoly and the partner holds the missing piece.
        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (tradeDeclinesByPartnerId.getOrDefault(other.playerId(), 0) >= MAX_DECLINES_PER_PARTNER) continue;
            String botWantsFromPartner = findCriticalTargetProperty(state, botId, other.playerId());
            if (botWantsFromPartner != null
                    && !declinedSwapTargets.getOrDefault(other.playerId(), java.util.Set.of()).contains(botWantsFromPartner)) {
                CommandResult result = publisher.handle(new OpenTradeCommand(sessionId, botId, other.playerId()));
                if (result.accepted()) {
                    lastTradeOpenedAtTurn.put(botId, currentTurn);
                    return true;
                }
            }
        }

        // Pass 2: near-monopoly acquisition — bot owns n-2 of a group (i.e., needs 2 more) and the partner has one.
        // Only fire when there is a genuine path to completing the set (partner owns 2+ of the same group or
        // the remaining pieces are mostly in one player's hands).

        for (PlayerSnapshot other : state.players()) {
            if (other.playerId().equals(botId) || other.bankrupt() || other.eliminated()) continue;
            if (tradeDeclinesByPartnerId.getOrDefault(other.playerId(), 0) >= MAX_DECLINES_PER_PARTNER) continue;
            // Only proceed if the target is a near-monopoly (bot owns n-2 or more of that group).
            String targetProp = findNearMonopolyTargetProperty(state, botId, other.playerId());
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
            if (result.accepted()) {
                lastTradeOpenedAtTurn.put(botId, currentTurn);
                return true;
            }
        }
        return false;
    }

    // Finds a property from the partner that would advance the bot toward a near-monopoly position.
    // Only returns a target if the bot already owns at least (groupSize - 2) properties in that group
    // (i.e., acquiring this one would leave the bot needing at most 1 more for a monopoly).
    // This gates "foothold" trades: bot needs a real path to completion, not just 1 of 3 properties.
    private String findNearMonopolyTargetProperty(SessionState state, String botId, String partnerId) {
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            // Bot needs at least groupSize-2 owned already (so acquiring 1 more brings them to n-1 or better).
            // For 2-property groups (Brown, Dark Blue), bot needs 1 (i.e., half-way = 0 threshold would be too low).
            int threshold = groupSize <= 2 ? 1 : groupSize - 2;
            if (botOwns < threshold) continue;
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

    // Finds the highest-priority property in the partner's portfolio that the bot would benefit from.
    // Like {@link #findStrategicTargetProperty} but skips any property already present in {@code alreadyRequested}.
    private String findUnrequestedTarget(SessionState state, String botId, String partnerId,
                                         TradeSelectionState alreadyRequested) {
        java.util.Set<String> have = new java.util.HashSet<>(alreadyRequested.propertyIds());
        // Primary: the normal strategic pick, if not already on the offer.
        String primary = findStrategicTargetProperty(state, botId, partnerId);
        if (primary != null && !have.contains(primary)) return primary;
        // Fallback: any unbuilt, unmortgaged partner property the bot has a foothold in, not yet requested — lets the...
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

    // Returns the highest-priority property owned by {@code partnerId} that the bot wants.
    // Only returns a property if there is a genuine monopoly-oriented reason to acquire it:
    //   1. The property immediately completes the bot's street monopoly (bot owns groupSize-1).
    //   2. Partner has a railroad and bot already owns ≥2 railroads (nearing railroad monopoly).
    //   3. Bot owns n-2 of a group (one acquisition brings them to near-monopoly position).
    // Deliberately excludes "blocking" by asking for the piece the partner needs for their own set —
    // they will never sell it willingly, and such proposals generate pointless churn.
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

        // Priority 2: railroad — only if bot already owns ≥2 railroads (genuine progress toward 4)
        long botRailroads = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId())
                        && spotType(p.propertyId()).streetType.placeType == PlaceType.RAILROAD)
                .count();
        if (botRailroads >= 2) {
            String railroadTarget = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && p.houseCount() == 0 && p.hotelCount() == 0
                            && spotType(p.propertyId()).streetType.placeType == PlaceType.RAILROAD)
                    .map(PropertyStateSnapshot::propertyId)
                    .findFirst().orElse(null);
            if (railroadTarget != null) return railroadTarget;
        }

        // Priority 3: bot owns n-2 of a group — acquiring this one leaves bot needing only 1 more.
        // This is a meaningful step forward, not just collecting random properties.
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0 || groupSize < 3) continue; // 2-property groups handled in P1
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns != groupSize - 2) continue; // bot owns exactly n-2 (e.g. 1 of 3)
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

    // Extra cost added to {@code valueGiven} when the giving selection contains a property that would complete {@...
    // True if {@code playerId} owns at least one full street monopoly.
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

    // True if any player OTHER than {@code botId} already owns a full street monopoly.
    private boolean someoneElseHasMonopoly(SessionState state, String botId) {
        return state.players().stream()
                .filter(p -> !p.playerId().equals(botId) && !p.bankrupt() && !p.eliminated())
                .anyMatch(p -> playerHasMonopoly(state, p.playerId()));
    }

    // True if NO player owns any monopoly yet — the classic stalemate where nobody will trade.
    private boolean noMonopoliesExist(SessionState state) {
        return state.players().stream()
                .filter(p -> !p.bankrupt() && !p.eliminated())
                .noneMatch(p -> playerHasMonopoly(state, p.playerId()));
    }

    private int monopolyGiftPenalty(SessionState state, String partnerId, TradeSelectionState giving) {
        int penalty = 0;
        StrongBotConfig cfg = configFor(partnerId); // use same weights as the receiving-side bonus
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET && group.placeType != PlaceType.RAILROAD
                    && group.placeType != PlaceType.UTILITY) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long inGiving = giving.propertyIds().stream()
                    .filter(id -> spotType(id).streetType == group).count();
            if (inGiving == 0) continue;
            long partnerOwns = state.properties().stream()
                    .filter(p -> partnerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            int groupPriceSum = (int) state.properties().stream()
                    .filter(p -> spotType(p.propertyId()).streetType == group)
                    .mapToInt(p -> SpotType.valueOf(p.propertyId()).getIntegerProperty("price"))
                    .sum();
            if (partnerOwns + inGiving >= groupSize) {
                int rawPenalty = groupPriceSum + cfg.tradeSetCompletionWeight();
                // [Fix 3] If the game is stalemated (no monopolies yet and many turns have passed), halve the penalty so the
                // bots are nudged into completing groups and breaking the deadlock.
                boolean stalemate = noMonopoliesExist(state)
                        && turnsSinceMonopolyChange >= STALEMATE_TURN_THRESHOLD;
                penalty += stalemate ? (int)(rawPenalty * STALEMATE_GIFT_RELIEF) : rawPenalty;
            } else if (group.placeType == PlaceType.RAILROAD && partnerOwns + inGiving == groupSize - 1) {
                // Partner reaches 3 railroads (rent: 100€) — strong synergy boost even without full set
                penalty += groupPriceSum / 2;
            }
        }
        return penalty;
    }

    // Returns true if acquiring {@code propId} would complete a street monopoly for {@code botId}.
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

    // True if giving away {@code selection} would break one of the bot's own COMPLETE street monopolies (the bot...
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
            if (botOwns >= groupSize) return true; // owns full set, giving away part of it
        }
        return false;
    }

    // True if receiving {@code selection} would complete at least one of the bot's own street monopolies (the bot...
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

    // Returns true if receiving {@code selection} would meaningfully advance the bot toward any monopoly —
    // i.e., the bot already owns at least one property in the same group as a received property
    // (it's joining an existing partial set, not starting from scratch with an isolated deed).
    private boolean advancesOwnMonopoly(SessionState state, String botId, TradeSelectionState selection) {
        for (String propId : selection.propertyIds()) {
            StreetType group = spotType(propId).streetType;
            if (group == null || group.placeType != PlaceType.STREET) continue;
            long botOwns = state.properties().stream()
                    .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                    .count();
            if (botOwns >= 1) return true; // already has a foothold — adding to an existing partial set
        }
        return false;
    }

    // Fix 1 (win-win): finds a partner who holds a property the bot needs to complete a monopoly AND who needs a
    // monopoly-completing piece back. Both sides must gain critical (n-1) or monopoly progress — if the bot only
    // reaches n-2, it's not a win-win and the partner should not give up monopoly value for so little in return.
    private String findWinWinTargetProperty(SessionState state, String botId, String partnerId) {
        // Does the bot hold something that completes the PARTNER's set? That's the sweetener that makes the partner sell.
        boolean botCanCompletePartner = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0)
                .anyMatch(p -> wouldCompletePartnerMonopoly(state, partnerId, p.propertyId()));
        if (!botCanCompletePartner) return null;
        // The bot must also get a critical property (n-1 → monopoly) in return.
        // Restricting to critical-only ensures both sides make decisive monopoly progress.
        return findCriticalTargetProperty(state, botId, partnerId);
    }

    // Fix 8 (bundling): if completing the bot's monopoly in the group of {@code primaryTarget} requires more than...
    private java.util.List<String> bundleTargetsForGroup(SessionState state, String botId,
                                                         String partnerId, String primaryTarget) {
        StreetType group = spotType(primaryTarget).streetType;
        if (group == null || group.placeType != PlaceType.STREET) return List.of(primaryTarget);
        Integer groupSize = SpotType.getNumberOfSpots(group);
        if (groupSize == null || groupSize == 0) return List.of(primaryTarget);
        long botOwns = state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .count();
        long needed = groupSize - botOwns; // how many more to complete the set
        if (needed <= 1) return List.of(primaryTarget);
        java.util.List<String> partnerPiecesInGroup = state.properties().stream()
                .filter(p -> partnerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0
                        && spotType(p.propertyId()).streetType == group)
                .map(PropertyStateSnapshot::propertyId)
                .limit(needed)
                .toList();
        return partnerPiecesInGroup.isEmpty() ? List.of(primaryTarget) : partnerPiecesInGroup;
    }

    // Finds a high-priority target property (Priority 1: monopoly completion only).
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

    // Finds the best own property to include as a sweetener in a trade offer.
    private String findExpendableOwnProperty(SessionState state, String botId, String partnerId,
                                             String requestedPropId) {
        StreetType requestedGroup = requestedPropId != null ? spotType(requestedPropId).streetType : null;
        return state.properties().stream()
                .filter(p -> botId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && p.houseCount() == 0 && p.hotelCount() == 0)
                // Never offer a property from the SAME group the bot is requesting.
                .filter(p -> requestedGroup == null || spotType(p.propertyId()).streetType != requestedGroup)
                .filter(p -> StrongBotStrategy.debtMortgagePriority(state, botId, p) <= 3)
                .filter(p -> !wouldCompletePartnerMonopoly(state, partnerId, p.propertyId()))
                .max(java.util.Comparator.comparingDouble(
                        p -> deadweightScore(state, botId, p.propertyId())))
                .map(PropertyStateSnapshot::propertyId)
                .orElse(null);
    }

    // Deadweight score for offering a property: face_value × (1 − group_progress).
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

    // Mortgaged properties are valued at half face price (their mortgage value), since the recipient must spend ~...
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

    // Context-aware trade evaluation that considers what the bot already owns.
    private int evaluateSelectionContextual(SessionState state, String botId,
                                             TradeSelectionState selection, boolean receiving) {
        StrongBotConfig cfg = configFor(botId);

        // Base value: cash weighted by liquidity preference, then property prices.
        int value = (int)(selection.moneyAmount() * cfg.tradeLiquidityWeight());
        value += selection.jailCardCount() * 50;
        for (String propId : selection.propertyIds()) {
            int facePrice = SpotType.valueOf(propId).getIntegerProperty("price");
            PropertyStateSnapshot snap = state.properties().stream()
                    .filter(p -> propId.equals(p.propertyId())).findFirst().orElse(null);
            boolean mortgaged = snap != null && snap.mortgaged();
            if (mortgaged) {
                // A mortgaged property is worth its mortgage value (face/2) MINUS the unmortgage cost the new owner must pay...
                value += Math.max(0, facePrice / 2 - StrongBotStrategy.unmortgageCost(propId));
            } else {
                value += facePrice;
                // Built houses/hotels make a property far more valuable than its face price.
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

        // Railroad/utility synergy: rent multiplies with each additional unit, so the 2nd/3rd/4th
        // railroad is worth far more than face price alone. Streets above handle PlaceType.STREET;
        // here we add analogous synergy bonuses for RAILROAD and UTILITY groups.
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.RAILROAD && group.placeType != PlaceType.UTILITY) continue;
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
                    // Full railroad set (4/4) or utility pair (2/2)
                    value += groupPriceSum + setWeight;
                } else if (group.placeType == PlaceType.RAILROAD && afterReceive == 3) {
                    // 3 railroads: rent is 100€, strong synergy toward the 4th
                    value += groupPriceSum / 2 + setWeight / 4;
                } else if (group.placeType == PlaceType.RAILROAD && afterReceive == 2 && botOwns >= 1) {
                    // 2 railroads from owning 1: rent doubles to 50€
                    value += groupPriceSum / 4;
                }
            } else {
                // Giving away: cost of losing synergy from our existing collection
                if (botOwns >= groupSize) {
                    value += groupPriceSum + setWeight; // losing full set
                } else if (group.placeType == PlaceType.RAILROAD && botOwns == 3) {
                    // Dropping from 3 to 2 railroads: our rent halves (100→50€)
                    value += groupPriceSum / 2;
                } else if (group.placeType == PlaceType.RAILROAD && botOwns == 2) {
                    // Dropping from 2 to 1 railroad: rent halves (50→25€)
                    value += groupPriceSum / 4;
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
            // Auction reserve is capped at dangerCashReserve — the full dynamic reserve (which can exceed 500 late game)...
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

            // Budget-aware: model each competitor's effective bid ceiling.
            // Competitors keep ~$100 reserve; anything above that is biddable.
            // If winning the property would complete their street set, they'd bid up to 130% of face.
            java.util.Set<String> activeBidders = new java.util.HashSet<>(auction.eligiblePlayerIds());
            activeBidders.removeAll(auction.passedPlayerIds());
            activeBidders.remove(bidderId);
            int competitorBidCeiling = activeBidders.stream()
                    .mapToInt(id -> {
                        PlayerSnapshot p = findPlayer(state, id);
                        if (p == null) return 0;
                        int theirUsableCash = Math.max(0, p.cash() - 100);
                        int theirPropValue = facePrice;
                        if (propId != null) {
                            StreetType aGroup2 = StrongBotStrategy.spotType(propId).streetType;
                            if (aGroup2.placeType == PlaceType.STREET
                                    && StrongBotStrategy.wouldCompleteSet(state, id, propId)) {
                                theirPropValue = (int)(facePrice * 1.3);
                            }
                        }
                        return Math.min(theirUsableCash, theirPropValue);
                    })
                    .max().orElse(0);
            boolean noEffectiveCompetition = activeBidders.isEmpty() || competitorBidCeiling < minBid;
            if (noEffectiveCompetition && maxBid >= minBid) {
                publisher.handle(new PlaceAuctionBidCommand(sessionId, bidderId, auction.auctionId(), minBid));
                return;
            }

            if (maxBid >= minBid) {
                int bid;
                if (competitorBidCeiling < maxBid) {
                    // Price competitors out: bid just above their ceiling (rounded to nearest 10)
                    int targetBid = ((competitorBidCeiling / 10) + 1) * 10;
                    bid = Math.min(maxBid, Math.max(minBid, targetBid));
                } else {
                    // Tough competition (ceiling ≥ our max): bid aggressively with ⅓ headroom
                    int headroom = maxBid - minBid;
                    double factor = 0.6 + rng.nextDouble() * 0.8; // 0.6–1.4
                    int extra = ((int)(headroom / 3.0 * factor) / 10) * 10;
                    bid = Math.min(maxBid, minBid + Math.max(10, extra));
                }
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

    // Delay computation

    // Computes a human-like delay before the bot acts, based on what the current state demands.
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
        return scaled + rng.nextLong(-jitter, jitter + 1);
    }

    private long computeBaseDelay(SessionState state) {
        // Trade decision: evaluating someone else's offer — most deliberate action
        if (state.tradeState() != null) {
            TradeState trade = state.tradeState();
            // Bot-to-bot trades have no human party to pace for — the human only spectates them,
            // so resolve them quickly instead of applying the deliberate "reading/editing" delays
            // (which exist so a human can follow a trade they're actually part of).
            boolean bothBots = trade.initiatorPlayerId() != null && trade.recipientPlayerId() != null
                    && botPlayerIds.contains(trade.initiatorPlayerId())
                    && botPlayerIds.contains(trade.recipientPlayerId());
            if (bothBots) {
                return 250;
            }
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

    // Helpers

    // Returns the bot config for the given player; falls back to defaults() if absent.
    private StrongBotConfig configFor(String playerId) {
        return configs.getOrDefault(playerId, StrongBotConfig.defaults());
    }

    // Dynamic cash reserve that scales with board danger and opponent monopolies.
    private int dynamicReserve(SessionState state, String playerId) {
        return StrongBotStrategy.dynamicReserve(state, playerId, configFor(playerId));
    }

    // Unmortgages the highest-scored mortgaged property the bot can afford.
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

    // Picks the best group to build on and buys one round of houses.
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

    // Records a bot-initiated trade cancellation as a decline for the given partner.
    // [Loop fix B] Decline a received offer AND record which of the bot's own properties were refused,
    // recorded at the decision point (reliable regardless of speed/snapshot timing). Pass 0/1 then skip
    // re-proposing the identical (partner, target) swap, without abandoning the partner for other deals.
    private void declineReceivedOffer(String botId, String tradeId, TradeSelectionState botGiving) {
        if (botGiving != null) {
            for (String propId : botGiving.propertyIds()) {
                declinedSwapTargets
                        .computeIfAbsent(botId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                        .add(propId);
            }
        }
        publisher.handle(new DeclineTradeCommand(sessionId, botId, tradeId));
    }

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

    // Mirrors DomainDebtRemediationGateway even-selling rule: (level-1) >= (maxRest-1), i.e. level >= maxRest.
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
