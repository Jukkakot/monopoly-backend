package fi.monopoly.domain.session;

import java.util.List;
import java.util.Set;

public record AuctionState(
        String auctionId,
        String propertyId,
        String triggeringPlayerId,
        String currentActorPlayerId,
        String leadingPlayerId,
        int currentBid,
        int minimumNextBid,
        Set<String> passedPlayerIds,
        List<String> eligiblePlayerIds,
        AuctionStatus status,
        int winningBid,
        String winningPlayerId
) {
    public AuctionState {
        passedPlayerIds = Set.copyOf(passedPlayerIds);
        eligiblePlayerIds = List.copyOf(eligiblePlayerIds);
    }
}
