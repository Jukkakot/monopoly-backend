package fi.monopoly.server.session;

import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
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

    // Fallback delay used when situational logic cannot determine a better value.
    private static final long DEFAULT_BOT_DELAY_MS = 900L;

    // Minimum delay even in "fast" mode — prevents server overload from rapid-fire bot games.
    private static final long MIN_FAST_DELAY_MS = 50L;

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
    private final fi.monopoly.server.bot.BotStrategy strategy;
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
    // Last bot playerId whose WAITING_FOR_ROLL we observed — used to detect turn transitions.
    private volatile String lastBotTurnStartId = null;
    // Watchdog stall tracking — only read/written on the single scheduler thread, so no synchronisation needed.
    private long watchdogStuckVersion = Long.MIN_VALUE;
    private int watchdogStuckTicks = 0;

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
        // Track turn transitions to update BotMemory stalemate tracking.
        var turn = state.turn();
        if (turn != null && turn.phase() == TurnPhase.WAITING_FOR_ROLL
                && botPlayerIds.contains(turn.activePlayerId())
                && !turn.activePlayerId().equals(lastBotTurnStartId)) {
            lastBotTurnStartId = turn.activePlayerId();
            int monopolyCount = (int) state.players().stream()
                    .filter(p -> !p.bankrupt() && !p.eliminated())
                    .filter(p -> StrongBotStrategy.completedColorGroupsCount(state, p.playerId()) > 0)
                    .count();
            for (fi.monopoly.server.bot.BotMemory m : memories.values()) {
                m.updateMonopolyTracking(monopolyCount);
            }
        }

        // Track trade declines so BotMemory stays accurate for the strategy path.
        TradeState prevTrade = lastObservedTrade;
        lastObservedTrade = state.tradeState();
        // Notify BotMemory when a trade resolves (for counter-edit tracking).
        if (prevTrade != null) {
            String prevId = prevTrade.tradeId();
            TradeState cur = state.tradeState();
            if (cur == null || !prevId.equals(cur.tradeId())) {
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
                }
            }
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

    private void dispatchGreedy(SessionState state) {
        dispatchViaStrategy(state);
    }

    private void dispatchViaStrategy(SessionState state) {
        String activeId = resolveActorId(state);
        if (activeId == null) return;
        fi.monopoly.server.bot.BotMemory memory =
                memories.getOrDefault(activeId, fi.monopoly.server.bot.BotMemory.empty());
        fi.monopoly.server.bot.Intent intent = strategy.decide(state, activeId, memory, rng);
        executor.execute(intent, activeId);
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
