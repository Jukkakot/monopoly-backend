package fi.monopoly.domain.turn;

public record TurnState(
        String activePlayerId,
        TurnPhase phase,
        boolean canRoll,
        boolean canEndTurn,
        int consecutiveDoubles,
        int[] lastDice
) {
    public TurnState(String activePlayerId, TurnPhase phase, boolean canRoll, boolean canEndTurn) {
        this(activePlayerId, phase, canRoll, canEndTurn, 0, null);
    }
    public TurnState(String activePlayerId, TurnPhase phase, boolean canRoll, boolean canEndTurn, int consecutiveDoubles) {
        this(activePlayerId, phase, canRoll, canEndTurn, consecutiveDoubles, null);
    }
}
