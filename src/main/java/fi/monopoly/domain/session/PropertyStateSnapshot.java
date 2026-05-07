package fi.monopoly.domain.session;

public record PropertyStateSnapshot(
        String propertyId,
        String ownerPlayerId,
        boolean mortgaged,
        int houseCount,
        int hotelCount
) {
}
