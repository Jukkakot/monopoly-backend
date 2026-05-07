package fi.monopoly.application.session.purchase;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.types.SpotType;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure domain implementation of {@link PropertyPurchaseGateway} — no Processing runtime objects.
 *
 * <p>Deducts the property's list price from the buyer's cash and records ownership in
 * {@link fi.monopoly.domain.session.SessionState}. The price is read from {@link SpotType}
 * metadata, which is static board data (not mutable runtime state).</p>
 */
@RequiredArgsConstructor
public final class DomainPropertyPurchaseGateway implements PropertyPurchaseGateway {
    private final SessionStateStore store;

    @Override
    public boolean buyProperty(String playerId, String propertyId) {
        int price;
        try {
            price = SpotType.valueOf(propertyId).getIntegerProperty("price");
        } catch (IllegalArgumentException e) {
            return false;
        }
        final int deduct = price;
        store.update(state -> {
            List<PlayerSnapshot> players = state.players().stream()
                    .map(p -> {
                        if (!playerId.equals(p.playerId())) {
                            return p;
                        }
                        List<String> updatedIds = new ArrayList<>(p.ownedPropertyIds());
                        updatedIds.add(propertyId);
                        return new PlayerSnapshot(
                                p.playerId(), p.seatId(), p.name(),
                                p.cash() - deduct, p.boardIndex(),
                                p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards(),
                                updatedIds
                        );
                    })
                    .toList();
            List<PropertyStateSnapshot> properties = state.properties().stream()
                    .map(prop -> prop.propertyId().equals(propertyId)
                            ? new PropertyStateSnapshot(prop.propertyId(), playerId, prop.mortgaged(), prop.houseCount(), prop.hotelCount())
                            : prop)
                    .toList();
            return state.toBuilder().players(players).properties(properties).build();
        });
        return true;
    }
}
