package fi.monopoly.application.session;

import fi.monopoly.domain.session.SessionState;

/**
 * Narrow port for legacy presentation-layer override state managed by the application service.
 *
 * <p>The override mechanism in {@link SessionApplicationService} bridges authoritative session
 * state with legacy popup/UI restore flows that are not yet fully rewritten against pure domain
 * state. This interface isolates those operations so the shell and presentation coordinators do
 * not need to import the full application service type.</p>
 *
 * <p>Once popup/UI restore flows are rewritten to read {@link SessionState} directly, this port
 * and its implementations can be removed.</p>
 */
public interface SessionPresentationStatePort {
    boolean hasAuctionOverride();
    boolean hasTradeOverride();
    boolean hasPendingDecisionOverride();
    void clearActiveDebtOverride();
    void restoreFrom(SessionState state);
}
