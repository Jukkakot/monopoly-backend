package fi.monopoly.application.session.turn;

import fi.monopoly.domain.session.TurnContinuationState;

public interface TurnContinuationGateway {
    boolean resume(TurnContinuationState continuationState);
}
