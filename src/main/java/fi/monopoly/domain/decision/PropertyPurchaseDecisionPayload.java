package fi.monopoly.domain.decision;

public record PropertyPurchaseDecisionPayload(
        String propertyId,
        String propertyDisplayName,
        int price
) implements DecisionPayload {
}
