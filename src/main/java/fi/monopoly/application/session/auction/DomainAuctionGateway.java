package fi.monopoly.application.session.auction;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.domain.session.SeatState;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.types.SpotType;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure domain implementation of {@link AuctionGateway} — no Processing runtime objects.
 *
 * <p>Bot bidding strategy: bots never bid more than the property list price (capped at
 * list price) and always keep a cash reserve ({@value #AUCTION_CASH_RESERVE} €).
 * The bid increment is spread over the available headroom so bots don't jump straight
 * to their maximum — they bid roughly ⅓ of the remaining headroom each step.</p>
 */
@RequiredArgsConstructor
public final class DomainAuctionGateway implements AuctionGateway {
    private final SessionStateStore store;

    @Override
    public List<String> eligibleBidderIds(String triggeringPlayerId, String propertyId) {
        SessionState state = store.get();
        return playersOrderedFrom(state, triggeringPlayerId).stream()
                .filter(p -> !p.eliminated() && !p.bankrupt())
                .filter(p -> p.cash() >= OPENING_BID)
                .map(PlayerSnapshot::playerId)
                .toList();
    }

    @Override
    public int maxBidFor(String bidderId, String propertyId) {
        // Returns full cash — used for bid validation so human players can bid
        // whatever they can afford. Bots apply their own ceiling in nextBidAmount.
        return store.get().players().stream()
                .filter(p -> bidderId.equals(p.playerId()))
                .mapToInt(PlayerSnapshot::cash)
                .findFirst()
                .orElse(0);
    }

    @Override
    public int nextBidAmount(String bidderId, String propertyId, int currentBid) {
        int cash = maxBidFor(bidderId, propertyId);
        int minBid = currentBid == 0 ? OPENING_BID : currentBid + BID_INCREMENT;
        if (cash < minBid) {
            return 0;
        }
        // Bots never auto-bid above list price — paying more than list price for an
        // unbuilt property is a bad deal. Cap applies only to bot auto-bidding here.
        int listPrice = listPriceOf(propertyId);
        int botMax = listPrice > 0 ? Math.min(cash, listPrice) : cash;
        if (botMax < minBid) {
            return 0;  // bot passes — current bid already exceeds what it's willing to pay
        }
        // Bid ~⅓ of the remaining headroom so bots don't immediately jump to their ceiling.
        int headroom = botMax - minBid;
        int extraStep = Math.max(BID_INCREMENT, roundDown(headroom / 3));
        return Math.min(botMax, minBid + extraStep);
    }

    private static int listPriceOf(String propertyId) {
        try {
            return SpotType.valueOf(propertyId).getIntegerProperty("price");
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    @Override
    public boolean transferWinningProperty(String winnerId, String propertyId, int amount) {
        store.update(state -> {
            List<PlayerSnapshot> players = state.players().stream()
                    .map(p -> {
                        if (!winnerId.equals(p.playerId())) {
                            return p;
                        }
                        List<String> updatedIds = new ArrayList<>(p.ownedPropertyIds());
                        updatedIds.add(propertyId);
                        return new PlayerSnapshot(
                                p.playerId(), p.seatId(), p.name(),
                                p.cash() - amount, p.boardIndex(),
                                p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards(),
                                updatedIds
                        );
                    })
                    .toList();
            List<PropertyStateSnapshot> properties = state.properties().stream()
                    .map(prop -> prop.propertyId().equals(propertyId)
                            ? new PropertyStateSnapshot(prop.propertyId(), winnerId, prop.mortgaged(), prop.houseCount(), prop.hotelCount())
                            : prop)
                    .toList();
            return state.toBuilder().players(players).properties(properties).build();
        });
        return true;
    }

    private static List<PlayerSnapshot> playersOrderedFrom(SessionState state, String fromPlayerId) {
        Map<String, Integer> seatIndexByPlayerId = state.seats().stream()
                .collect(Collectors.toMap(SeatState::playerId, SeatState::seatIndex));
        List<PlayerSnapshot> sorted = state.players().stream()
                .sorted(Comparator.comparingInt(p -> seatIndexByPlayerId.getOrDefault(p.playerId(), 0)))
                .toList();
        int fromIdx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).playerId().equals(fromPlayerId)) {
                fromIdx = i;
                break;
            }
        }
        if (fromIdx < 0) {
            return sorted;
        }
        List<PlayerSnapshot> ordered = new ArrayList<>(sorted.size());
        for (int offset = 0; offset < sorted.size(); offset++) {
            ordered.add(sorted.get((fromIdx + offset) % sorted.size()));
        }
        return ordered;
    }

    private static int roundDown(int value) {
        return (value / BID_INCREMENT) * BID_INCREMENT;
    }
}
