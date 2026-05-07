package fi.monopoly.application.session.purchase;

public interface PropertyPurchaseGateway {
    boolean buyProperty(String playerId, String propertyId);
}
