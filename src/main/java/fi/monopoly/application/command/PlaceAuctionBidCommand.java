package fi.monopoly.application.command;

public record PlaceAuctionBidCommand(
        String sessionId,
        String actorPlayerId,
        String auctionId,
        int amount
) implements SessionCommand {
}
