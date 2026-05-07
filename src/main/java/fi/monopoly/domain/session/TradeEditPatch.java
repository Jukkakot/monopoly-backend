package fi.monopoly.domain.session;

import java.util.List;

public record TradeEditPatch(
        Boolean reversePerspective,
        Boolean offeredSide,
        Integer replaceMoneyAmount,
        List<String> propertyIdsToAdd,
        List<String> propertyIdsToRemove,
        Boolean toggleJailCard
) {
    public TradeEditPatch {
        propertyIdsToAdd = List.copyOf(propertyIdsToAdd == null ? List.of() : propertyIdsToAdd);
        propertyIdsToRemove = List.copyOf(propertyIdsToRemove == null ? List.of() : propertyIdsToRemove);
    }
}
