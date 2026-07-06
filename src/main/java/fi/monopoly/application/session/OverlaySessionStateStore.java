package fi.monopoly.application.session;

import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * SessionStateStore that overlays mutable flow-state fields (auction, debt, trade, pending
 * decision, turn continuation) on top of a base state supplier.
 *
 * <p>The base supplier is typically a legacy projector or an {@link InMemorySessionState} read
 * handle. Flow-state fields set here take precedence over the base; turn phase is re-derived from
 * the active flow fields on every {@link #get()} call.</p>
 */
public final class OverlaySessionStateStore implements SessionStateStore {

    private final Supplier<SessionState> baseSupplier;
    private volatile PendingDecision pendingDecision;
    private volatile AuctionState auctionState;
    private volatile DebtStateModel activeDebt;
    private volatile TradeState tradeState;
    private volatile TurnContinuationState turnContinuation;
    private volatile SessionStatus statusOverride;
    private volatile List<String> bankruptcyAuctionQueue = List.of();

    // Overlay flow fields (decision/auction/debt/trade/…) are NOT part of the base store, so
    // changing them does not bump the base version. The SSE fanout drops any snapshot whose
    // version is not strictly newer than the last sent, which would silently swallow the final
    // consistent snapshot of a command whose only change was an overlay field (e.g. opening an
    // auction on a declined property). This epoch is added to the base version so every overlay
    // mutation advances the reported version and the snapshot is delivered.
    private final AtomicLong overlayEpoch = new AtomicLong(0);

    public OverlaySessionStateStore(Supplier<SessionState> baseSupplier) {
        this.baseSupplier = baseSupplier;
    }

    private void touch() {
        overlayEpoch.incrementAndGet();
    }

    @Override
    public SessionState get() {
        SessionState base = baseSupplier.get();
        PendingDecision pd = pendingDecision != null ? pendingDecision : base.pendingDecision();
        AuctionState as = auctionState != null ? auctionState : base.auctionState();
        DebtStateModel ad = activeDebt != null ? activeDebt : base.activeDebt();
        TradeState ts = tradeState != null ? tradeState : base.tradeState();
        TurnContinuationState tcs = turnContinuation != null ? turnContinuation : base.turnContinuationState();
        if (shouldClearStalePendingDecision(base, pd, as, ad, ts)) {
            pendingDecision = null;
            pd = null;
        }
        TurnState turn = computeTurnPhase(base.turn(), pd, as, ad, ts);
        SessionStatus status = statusOverride != null ? statusOverride : base.status();
        List<String> baq = !bankruptcyAuctionQueue.isEmpty() ? bankruptcyAuctionQueue : base.bankruptcyAuctionQueue();
        return base.toBuilder()
                .version(base.version() + overlayEpoch.get())
                .status(status)
                .turn(turn)
                .pendingDecision(pd)
                .auctionState(as)
                .activeDebt(ad)
                .tradeState(ts)
                .turnContinuationState(tcs)
                .bankruptcyAuctionQueue(baq)
                .build();
    }

    @Override
    public synchronized void update(UnaryOperator<SessionState> mutator) {
        SessionState updated = mutator.apply(get());
        pendingDecision = updated.pendingDecision();
        auctionState = updated.auctionState();
        activeDebt = updated.activeDebt();
        tradeState = updated.tradeState();
        turnContinuation = updated.turnContinuationState();
        bankruptcyAuctionQueue = updated.bankruptcyAuctionQueue();
        touch();
    }

    public void setPendingDecision(PendingDecision pd) {
        this.pendingDecision = pd;
        touch();
    }

    public void setAuctionState(AuctionState as) {
        this.auctionState = as;
        touch();
    }

    public void setActiveDebt(DebtStateModel ad) {
        this.activeDebt = ad;
        touch();
    }

    public void setTradeState(TradeState ts) {
        this.tradeState = ts;
        touch();
    }

    public void setTurnContinuation(TurnContinuationState tcs) {
        this.turnContinuation = tcs;
        touch();
    }

    public void setStatusOverride(SessionStatus status) {
        this.statusOverride = status;
        touch();
    }

    public boolean hasAuctionState() {
        return auctionState != null;
    }

    public boolean hasTradeState() {
        return tradeState != null;
    }

    public boolean hasPendingDecision() {
        return pendingDecision != null;
    }

    public void restoreFrom(SessionState state) {
        if (state == null) {
            pendingDecision = null;
            auctionState = null;
            activeDebt = null;
            tradeState = null;
            turnContinuation = null;
            bankruptcyAuctionQueue = List.of();
            touch();
            return;
        }
        pendingDecision = state.pendingDecision();
        auctionState = state.auctionState();
        activeDebt = state.activeDebt();
        tradeState = state.tradeState();
        turnContinuation = state.turnContinuationState();
        bankruptcyAuctionQueue = state.bankruptcyAuctionQueue();
        touch();
    }

    public void setBankruptcyAuctionQueue(List<String> queue) {
        this.bankruptcyAuctionQueue = queue != null ? List.copyOf(queue) : List.of();
        touch();
    }

    public List<String> getBankruptcyAuctionQueue() {
        return bankruptcyAuctionQueue;
    }

    private static TurnState computeTurnPhase(
            TurnState base,
            PendingDecision pd,
            AuctionState as,
            DebtStateModel ad,
            TradeState ts
    ) {
        if (ad != null) {
            return new TurnState(base.activePlayerId(), TurnPhase.RESOLVING_DEBT, false, false, base.consecutiveDoubles(), base.lastDice());
        }
        if (as != null) {
            return new TurnState(base.activePlayerId(), TurnPhase.WAITING_FOR_AUCTION, false, false, base.consecutiveDoubles(), base.lastDice());
        }
        if (ts != null || pd != null) {
            return new TurnState(base.activePlayerId(), TurnPhase.WAITING_FOR_DECISION, false, false, base.consecutiveDoubles(), base.lastDice());
        }
        return base;
    }

    private boolean shouldClearStalePendingDecision(
            SessionState base,
            PendingDecision pd,
            AuctionState as,
            DebtStateModel ad,
            TradeState ts
    ) {
        if (pd == null || base.pendingDecision() != null || as != null || ad != null || ts != null) {
            return false;
        }
        if (!(pd.payload() instanceof PropertyPurchaseDecisionPayload payload)) {
            return false;
        }
        return base.properties().stream()
                .filter(p -> payload.propertyId().equals(p.propertyId()))
                .findFirst()
                .map(p -> p.ownerPlayerId() != null)
                .orElse(true);
    }
}
