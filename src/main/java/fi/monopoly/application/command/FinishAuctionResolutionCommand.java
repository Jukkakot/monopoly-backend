package fi.monopoly.application.command;

public record FinishAuctionResolutionCommand(
        String sessionId,
        String auctionId
) implements SessionCommand {
}
