package fi.monopoly.server.transport;

import java.util.List;

/**
 * Partial game-state override payload for {@code PUT /sessions/{id}/debug/state}.
 * Every field is optional — null means "keep current value".
 * Use {@code clearDebt/clearDecision/clearAuction/clearTrade} to explicitly null those sub-states.
 * Use {@code nextDice} to force the next roll, {@code nextChanceCard}/{@code nextCommunityCard}
 * to put a specific card key (e.g. {@code "GO_JAIL:0"}) at the front of the respective deck.
 */
public record DebugStateImport(
        List<PlayerPatch> players,
        List<PropertyPatch> properties,
        TurnPatch turn,
        Boolean clearDebt,
        Boolean clearDecision,
        Boolean clearAuction,
        Boolean clearTrade,
        int[] nextDice,
        String nextChanceCard,
        String nextCommunityCard
) {
    public record PlayerPatch(
            String playerId,
            Integer cash,
            Integer boardIndex,
            Boolean inJail,
            Integer jailRoundsRemaining,
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
