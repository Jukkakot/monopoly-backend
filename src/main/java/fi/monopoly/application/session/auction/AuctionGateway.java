package fi.monopoly.application.session.auction;

import java.util.List;

public interface AuctionGateway {
    int OPENING_BID = 10;
    int BID_INCREMENT = 10;

    List<String> eligibleBidderIds(String triggeringPlayerId, String propertyId);

    int maxBidFor(String bidderId, String propertyId);

    int nextBidAmount(String bidderId, String propertyId, int currentBid);

    boolean transferWinningProperty(String winnerId, String propertyId, int amount);
}
