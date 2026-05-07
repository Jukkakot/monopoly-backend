package fi.monopoly.server.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.monopoly.application.command.*;
import fi.monopoly.domain.session.TradeEditPatch;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

/**
 * Maps JSON (with a {@code "type"} discriminator field) to typed {@link SessionCommand} instances
 * without requiring Jackson annotations on domain types.
 */
@RequiredArgsConstructor
public final class SessionCommandMapper {

    private final ObjectMapper objectMapper;

    public SessionCommand fromJson(byte[] json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        String type = node.path("type").asText();
        String sessionId = node.path("sessionId").asText();
        return switch (type) {
            case "RollDice" -> new RollDiceCommand(
                    sessionId,
                    node.path("actorPlayerId").asText());
            case "EndTurn" -> new EndTurnCommand(
                    sessionId,
                    node.path("actorPlayerId").asText());
            case "BuyProperty" -> new BuyPropertyCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("decisionId").asText(),
                    node.path("propertyId").asText());
            case "DeclineProperty" -> new DeclinePropertyCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("decisionId").asText(),
                    node.path("propertyId").asText());
            case "PayDebt" -> new PayDebtCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("debtId").asText());
            case "MortgagePropertyForDebt" -> new MortgagePropertyForDebtCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("debtId").asText(),
                    node.path("propertyId").asText());
            case "SellBuildingForDebt" -> new SellBuildingForDebtCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("debtId").asText(),
                    node.path("propertyId").asText(),
                    node.path("count").asInt());
            case "DeclareBankruptcy" -> new DeclareBankruptcyCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("debtId").asText());
            case "SellBuildingRoundsAcrossSetForDebt" -> new SellBuildingRoundsAcrossSetForDebtCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("debtId").asText(),
                    node.path("propertyId").asText(),
                    node.path("rounds").asInt());
            case "PassAuction" -> new PassAuctionCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("auctionId").asText());
            case "PlaceAuctionBid" -> new PlaceAuctionBidCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("auctionId").asText(),
                    node.path("amount").asInt());
            case "FinishAuctionResolution" -> new FinishAuctionResolutionCommand(
                    sessionId,
                    node.path("auctionId").asText());
            case "AcceptTrade" -> new AcceptTradeCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("tradeId").asText());
            case "EditTradeOffer" -> new EditTradeOfferCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("tradeId").asText(),
                    objectMapper.treeToValue(node.path("patch"), TradeEditPatch.class));
            case "OpenTrade" -> new OpenTradeCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("recipientPlayerId").asText());
            case "SubmitTradeOffer" -> new SubmitTradeOfferCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("tradeId").asText());
            case "CancelTrade" -> new CancelTradeCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("tradeId").asText());
            case "CounterTrade" -> new CounterTradeCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("tradeId").asText());
            case "DeclineTrade" -> new DeclineTradeCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("tradeId").asText());
            case "BuyBuildingRound" -> new BuyBuildingRoundCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("propertyId").asText());
            case "ToggleMortgage" -> new ToggleMortgageCommand(
                    sessionId,
                    node.path("actorPlayerId").asText(),
                    node.path("propertyId").asText());
            case "RefreshSessionView" -> new RefreshSessionViewCommand(sessionId);
            default -> throw new IllegalArgumentException("Unknown command type: " + type);
        };
    }
}
