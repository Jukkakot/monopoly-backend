package fi.monopoly.domain.session;

public record TurnContinuationState(
        String continuationId,
        String activePlayerId,
        TurnContinuationType continuationType,
        TurnContinuationAction completionAction,
        String propertyId,
        String reason
) {
}
