package fi.monopoly.domain.session;

import java.util.List;

public record TradeSelectionState(
        int moneyAmount,
        List<String> propertyIds,
        int jailCardCount
) {
    public static final TradeSelectionState NONE = new TradeSelectionState(0, List.of(), 0);

    public TradeSelectionState {
        propertyIds = List.copyOf(propertyIds == null ? List.of() : propertyIds);
    }

    public boolean isEmpty() {
        return moneyAmount <= 0 && propertyIds.isEmpty() && jailCardCount <= 0;
    }
}
