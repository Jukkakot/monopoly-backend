package fi.monopoly.domain.decision;

import java.util.List;

public record PendingDecision(
        String decisionId,
        DecisionType decisionType,
        String actorPlayerId,
        List<DecisionAction> allowedActions,
        String summaryText,
        DecisionPayload payload
) {
    public PendingDecision {
        allowedActions = List.copyOf(allowedActions);
    }
}
