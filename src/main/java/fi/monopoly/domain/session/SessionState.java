package fi.monopoly.domain.session;

import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.turn.TurnState;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record SessionState(
        String sessionId,
        long version,
        SessionStatus status,
        List<SeatState> seats,
        List<PlayerSnapshot> players,
        List<PropertyStateSnapshot> properties,
        TurnState turn,
        PendingDecision pendingDecision,
        AuctionState auctionState,
        DebtStateModel activeDebt,
        TradeState tradeState,
        TurnContinuationState turnContinuationState,
        String winnerPlayerId,
        List<String> chanceDeck,
        List<String> communityDeck
) {
    public SessionState {
        seats = List.copyOf(seats);
        players = List.copyOf(players);
        properties = List.copyOf(properties);
        if (chanceDeck != null) chanceDeck = List.copyOf(chanceDeck);
        if (communityDeck != null) communityDeck = List.copyOf(communityDeck);
    }

    /** Backward-compat: no turnContinuationState, no card decks. */
    public SessionState(
            String sessionId,
            long version,
            SessionStatus status,
            List<SeatState> seats,
            List<PlayerSnapshot> players,
            List<PropertyStateSnapshot> properties,
            TurnState turn,
            PendingDecision pendingDecision,
            AuctionState auctionState,
            DebtStateModel activeDebt,
            TradeState tradeState,
            String winnerPlayerId
    ) {
        this(sessionId, version, status, seats, players, properties, turn,
                pendingDecision, auctionState, activeDebt, tradeState,
                null, winnerPlayerId, null, null);
    }

    /** Backward-compat: with turnContinuationState, no card decks. */
    public SessionState(
            String sessionId,
            long version,
            SessionStatus status,
            List<SeatState> seats,
            List<PlayerSnapshot> players,
            List<PropertyStateSnapshot> properties,
            TurnState turn,
            PendingDecision pendingDecision,
            AuctionState auctionState,
            DebtStateModel activeDebt,
            TradeState tradeState,
            TurnContinuationState turnContinuationState,
            String winnerPlayerId
    ) {
        this(sessionId, version, status, seats, players, properties, turn,
                pendingDecision, auctionState, activeDebt, tradeState,
                turnContinuationState, winnerPlayerId, null, null);
    }
}
