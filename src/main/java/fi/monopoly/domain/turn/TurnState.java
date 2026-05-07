package fi.monopoly.domain.turn;

public record TurnState(
        String activePlayerId,
        TurnPhase phase,
        boolean canRoll,
        boolean canEndTurn,
        int consecutiveDoubles
) {
    public TurnState(String activePlayerId, TurnPhase phase, boolean canRoll, boolean canEndTurn) {
        this(activePlayerId, phase, canRoll, canEndTurn, 0);
    }
}
