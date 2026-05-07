package fi.monopoly.application.session.purchase;

import fi.monopoly.domain.session.TurnContinuationState;

public interface PropertyPurchaseFlow {
    void begin(String playerId, String propertyId, String displayName, int price, String message, TurnContinuationState continuationState);
}
