package fi.monopoly.domain.session;

public record TradeHistoryEntry(
        String actorPlayerId,
        String actionType,
        String summary
) {
}
