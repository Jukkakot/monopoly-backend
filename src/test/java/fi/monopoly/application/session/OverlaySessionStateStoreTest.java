package fi.monopoly.application.session;

import fi.monopoly.domain.session.AuctionState;
import fi.monopoly.domain.session.AuctionStatus;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.SessionStatus;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Overlay flow fields (auction/debt/trade/decision/…) are not part of the base store, so a
 * command whose only change is an overlay field would otherwise leave the version unchanged —
 * and the SSE fanout, which only forwards strictly-newer snapshots, would silently drop the
 * final consistent snapshot (e.g. opening an auction on a declined property). These tests pin
 * the fix: overlay mutations advance the reported version.
 */
class OverlaySessionStateStoreTest {

    private static SessionState baseState(long version) {
        return new SessionState("s", version, SessionStatus.IN_PROGRESS,
                List.of(), List.of(), List.of(),
                new TurnState("p1", TurnPhase.WAITING_FOR_ROLL, true, false, 0),
                null, null, null, null, null);
    }

    private static AuctionState anyAuction() {
        return new AuctionState("a", "B1", "p1", null, null, 0, 10,
                Set.of(), List.of("p1"), AuctionStatus.ACTIVE, 0, null);
    }

    @Test
    void overlayMutationAdvancesTheReportedVersion() {
        InMemorySessionState base = new InMemorySessionState(baseState(5));
        OverlaySessionStateStore overlay = new OverlaySessionStateStore(base::get);

        assertEquals(5, overlay.get().version(), "with no overlay changes the version mirrors the base");

        overlay.setAuctionState(anyAuction());
        long afterAuction = overlay.get().version();
        assertTrue(afterAuction > 5,
                "an overlay mutation must advance the reported version so the SSE fanout delivers it; got " + afterAuction);

        overlay.setActiveDebt(null);
        assertTrue(overlay.get().version() > afterAuction, "each overlay mutation advances the version");
    }

    @Test
    void baseVersionIncrementsAreStillReflected() {
        InMemorySessionState base = new InMemorySessionState(baseState(1));
        OverlaySessionStateStore overlay = new OverlaySessionStateStore(base::get);

        long v0 = overlay.get().version();
        base.update(s -> s);   // identity mutator still bumps the base version
        assertTrue(overlay.get().version() > v0, "base version bumps must still advance the reported version");
    }

    @Test
    void reportedVersionStaysMonotonicAcrossMixedBaseAndOverlayChanges() {
        InMemorySessionState base = new InMemorySessionState(baseState(0));
        OverlaySessionStateStore overlay = new OverlaySessionStateStore(base::get);

        long last = overlay.get().version();
        for (int i = 0; i < 5; i++) {
            base.update(s -> s);
            long afterBase = overlay.get().version();
            assertTrue(afterBase > last, "base update must strictly advance the version");
            last = afterBase;

            overlay.setTradeState(null);
            long afterOverlay = overlay.get().version();
            assertTrue(afterOverlay > last, "overlay change must strictly advance the version");
            last = afterOverlay;
        }
    }
}
