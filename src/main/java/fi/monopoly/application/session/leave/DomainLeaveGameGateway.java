package fi.monopoly.application.session.leave;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

import static fi.monopoly.domain.session.GameEventHelper.*;

/**
 * Handles a player permanently leaving an in-progress game.
 *
 * <p><b>Leave is final</b> — the player is marked {@code eliminated=true} and cannot rejoin.
 * Their properties are returned to the bank, any active debt/trade involving them is cancelled,
 * and the turn advances to the next active player. If only one player remains, the game ends.</p>
 *
 * <p><b>Reconnect vs. rejoin</b> — there is no "rejoin" concept in the domain. A client that
 * lost their SSE connection can reconnect (SSE reconnect with {@code Last-Event-ID}) and will
 * still see the live game state through their existing player seat. That is different from calling
 * {@code LeaveGame}, which eliminates the seat permanently. Future work: add a "restore" command
 * that un-eliminates a player within a configurable grace period.</p>
 */
@Slf4j
@RequiredArgsConstructor
public final class DomainLeaveGameGateway implements LeaveGameGateway {

    private final SessionStateStore store;

    @Override
    public void leaveGame(String playerId) {
        store.update(state -> {
            PlayerSnapshot leaving = state.players().stream()
                    .filter(p -> p.playerId().equals(playerId))
                    .findFirst().orElse(null);
            if (leaving == null || leaving.eliminated()) return state;

            log.info("Player {} ({}) left the game", leaving.name(), playerId);

            // 1. Release all their properties to the bank (no buildings)
            List<PropertyStateSnapshot> updatedProps = state.properties().stream()
                    .map(p -> playerId.equals(p.ownerPlayerId())
                            ? new PropertyStateSnapshot(p.propertyId(), null, false, 0, 0)
                            : p)
                    .toList();

            // 2. Eliminate the player; recompute owned-prop lists for everyone
            List<PlayerSnapshot> updatedPlayers = state.players().stream()
                    .map(p -> {
                        if (playerId.equals(p.playerId())) {
                            return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), 0,
                                    p.boardIndex(), true, true, false, 0, 0, List.of());
                        }
                        List<String> ownedIds = updatedProps.stream()
                                .filter(prop -> p.playerId().equals(prop.ownerPlayerId()))
                                .map(PropertyStateSnapshot::propertyId)
                                .toList();
                        return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash(),
                                p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards(), ownedIds);
                    })
                    .toList();

            // 3. Cancel any active trade involving this player
            TradeState newTrade = state.tradeState() != null
                    && (playerId.equals(state.tradeState().initiatorPlayerId())
                    || playerId.equals(state.tradeState().recipientPlayerId()))
                    ? null : state.tradeState();

            // 4. Clear active debt if this player is involved
            DebtStateModel newDebt = state.activeDebt() != null
                    && (playerId.equals(state.activeDebt().debtorPlayerId())
                    || playerId.equals(state.activeDebt().creditorPlayerId()))
                    ? null : state.activeDebt();

            // 5. Clear pending decision if it was for this player's turn
            boolean wasActiveTurn = playerId.equals(state.turn() != null ? state.turn().activePlayerId() : null);
            PendingDecision newDecision = wasActiveTurn ? null : state.pendingDecision();
            TurnContinuationState newContinuation = wasActiveTurn ? null : state.turnContinuationState();

            // 6. Check game over (only one non-eliminated, non-bankrupt player remaining)
            List<PlayerSnapshot> stillActive = updatedPlayers.stream()
                    .filter(p -> !p.eliminated() && !p.bankrupt())
                    .toList();
            String winner = stillActive.size() == 1 ? stillActive.get(0).playerId() : null;

            // 7. Advance turn if it was this player's turn
            TurnState nextTurn = state.turn();
            if (wasActiveTurn) {
                String nextPlayer = nextActivePlayerId(updatedPlayers, state.seats(), playerId);
                nextTurn = nextPlayer != null
                        ? new TurnState(nextPlayer, TurnPhase.WAITING_FOR_ROLL, true, false, 0)
                        : new TurnState(state.turn().activePlayerId(), TurnPhase.GAME_OVER, false, false, 0);
            }

            SessionState.SessionStateBuilder builder = state.toBuilder()
                    .properties(updatedProps)
                    .players(updatedPlayers)
                    .turn(nextTurn)
                    .tradeState(newTrade)
                    .activeDebt(newDebt)
                    .pendingDecision(newDecision)
                    .turnContinuationState(newContinuation);

            if (winner != null) {
                builder.winnerPlayerId(winner).status(SessionStatus.GAME_OVER);
            }

            return appendEvents(builder.build(), ev("PLAYER_LEFT", playerId, Map.of()));
        });
    }

    private static String nextActivePlayerId(List<PlayerSnapshot> players, List<SeatState> seats,
                                              String currentPlayerId) {
        return fi.monopoly.application.session.turn.DomainTurnContinuationGateway
                .nextActivePlayerId(players, seats, currentPlayerId);
    }
}
