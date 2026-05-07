package fi.monopoly.application.command;

public record PassAuctionCommand(
        String sessionId,
        String actorPlayerId,
        String auctionId
) implements SessionCommand {
}
