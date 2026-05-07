package fi.monopoly.application.session.turn;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.domain.session.SeatState;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.TurnContinuationState;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure domain implementation of {@link TurnContinuationGateway} — no Processing runtime objects.
 *
 * <p>Resumes a stalled turn by applying the {@link fi.monopoly.domain.session.TurnContinuationAction}
 * stored in the {@link TurnContinuationState} directly to {@link SessionState}:</p>
 * <ul>
 *   <li>{@code APPLY_TURN_FOLLOW_UP} — same player gets to re-roll (after doubles),
 *       consecutive-doubles count is preserved from stored state.</li>
 *   <li>{@code END_TURN_WITH_SWITCH} — advance to the next non-eliminated player.</li>
 *   <li>{@code END_TURN_WITHOUT_SWITCH} — same player, reset to rolling phase (e.g. jail).</li>
 *   <li>{@code NONE} — no-op.</li>
 * </ul>
 */
@RequiredArgsConstructor
public final class DomainTurnContinuationGateway implements TurnContinuationGateway {
    private final SessionStateStore store;

    @Override
    public boolean resume(TurnContinuationState continuationState) {
        if (continuationState == null) return false;
        switch (continuationState.completionAction()) {
            case APPLY_TURN_FOLLOW_UP -> store.update(s -> s.toBuilder()
                    .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_ROLL, true, false,
                            s.turn().consecutiveDoubles()))
                    .turnContinuationState(null)
                    .build());
            case END_TURN_WITH_SWITCH -> store.update(s -> {
                String next = nextActivePlayerId(s, s.turn().activePlayerId());
                if (next == null) return s;
                return s.toBuilder()
                        .turn(new TurnState(next, TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                        .turnContinuationState(null)
                        .build();
            });
            case END_TURN_WITHOUT_SWITCH -> store.update(s -> s.toBuilder()
                    .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                    .turnContinuationState(null)
                    .build());
            case NONE -> { /* intentional no-op */ }
        }
        return true;
    }

    static String nextActivePlayerId(SessionState state, String currentPlayerId) {
        Map<String, Integer> seatIndex = state.seats().stream()
                .collect(Collectors.toMap(SeatState::playerId, SeatState::seatIndex));
        List<PlayerSnapshot> active = state.players().stream()
                .filter(p -> !p.eliminated() && !p.bankrupt())
                .sorted(Comparator.comparingInt(p -> seatIndex.getOrDefault(p.playerId(), Integer.MAX_VALUE)))
                .toList();
        if (active.isEmpty()) return null;
        int idx = -1;
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).playerId().equals(currentPlayerId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return active.get(0).playerId();
        return active.get((idx + 1) % active.size()).playerId();
    }
}
