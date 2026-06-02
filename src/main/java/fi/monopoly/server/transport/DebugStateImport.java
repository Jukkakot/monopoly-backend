package fi.monopoly.server.transport;

import java.util.List;

/**
 * Partial game-state override payload for {@code PUT /sessions/{id}/debug/state}.
 * Every field is optional — null means "keep current value".
 * Use {@code clearDebt/clearDecision/clearAuction/clearTrade} to explicitly null those sub-states.
 */
public record DebugStateImport(
        List<PlayerPatch> players,
        List<PropertyPatch> properties,
        TurnPatch turn,
        Boolean clearDebt,
        Boolean clearDecision,
        Boolean clearAuction,
        Boolean clearTrade
) {
    public record PlayerPatch(
            String playerId,
            Integer cash,
            Integer boardIndex,
            Boolean inJail,
            Boolean bankrupt,
            Integer getOutOfJailCards,
            List<String> ownedPropertyIds
    ) {}

    public record PropertyPatch(
            String propertyId,
            String ownerPlayerId,
            Boolean mortgaged,
            Integer houseCount,
            Integer hotelCount
    ) {}

    public record TurnPatch(
            String activePlayerId,
            String phase,
            Integer consecutiveDoubles,
            int[] lastDice
    ) {}
}
