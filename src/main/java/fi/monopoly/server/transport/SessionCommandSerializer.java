package fi.monopoly.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.monopoly.application.command.*;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

/**
 * Serializes typed {@link SessionCommand} instances to JSON with a {@code "type"} discriminator
 * field, complementing {@link SessionCommandMapper} which does the reverse.
 */
@RequiredArgsConstructor
public final class SessionCommandSerializer {

    private final ObjectMapper objectMapper;

    public String toJson(SessionCommand command) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sessionId", command.sessionId());

        switch (command) {
            case RollDiceCommand c -> {
                node.put("type", "RollDice");
                node.put("actorPlayerId", c.actorPlayerId());
            }
            case EndTurnCommand c -> {
                node.put("type", "EndTurn");
                node.put("actorPlayerId", c.actorPlayerId());
            }
            case BuyPropertyCommand c -> {
                node.put("type", "BuyProperty");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("decisionId", c.decisionId());
                node.put("propertyId", c.propertyId());
            }
            case DeclinePropertyCommand c -> {
                node.put("type", "DeclineProperty");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("decisionId", c.decisionId());
                node.put("propertyId", c.propertyId());
            }
            case PayDebtCommand c -> {
                node.put("type", "PayDebt");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("debtId", c.debtId());
            }
            case MortgagePropertyForDebtCommand c -> {
                node.put("type", "MortgagePropertyForDebt");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("debtId", c.debtId());
                node.put("propertyId", c.propertyId());
            }
            case SellBuildingForDebtCommand c -> {
                node.put("type", "SellBuildingForDebt");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("debtId", c.debtId());
                node.put("propertyId", c.propertyId());
                node.put("count", c.count());
            }
            case DeclareBankruptcyCommand c -> {
                node.put("type", "DeclareBankruptcy");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("debtId", c.debtId());
            }
            case SellBuildingRoundsAcrossSetForDebtCommand c -> {
                node.put("type", "SellBuildingRoundsAcrossSetForDebt");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("debtId", c.debtId());
                node.put("propertyId", c.propertyId());
                node.put("rounds", c.rounds());
            }
            case PassAuctionCommand c -> {
                node.put("type", "PassAuction");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("auctionId", c.auctionId());
            }
            case PlaceAuctionBidCommand c -> {
                node.put("type", "PlaceAuctionBid");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("auctionId", c.auctionId());
                node.put("amount", c.amount());
            }
            case FinishAuctionResolutionCommand c -> {
                node.put("type", "FinishAuctionResolution");
                node.put("auctionId", c.auctionId());
            }
            case AcceptTradeCommand c -> {
                node.put("type", "AcceptTrade");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("tradeId", c.tradeId());
            }
            case EditTradeOfferCommand c -> {
                node.put("type", "EditTradeOffer");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("tradeId", c.tradeId());
                node.set("patch", objectMapper.valueToTree(c.patch()));
            }
            case OpenTradeCommand c -> {
                node.put("type", "OpenTrade");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("recipientPlayerId", c.recipientPlayerId());
            }
            case SubmitTradeOfferCommand c -> {
                node.put("type", "SubmitTradeOffer");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("tradeId", c.tradeId());
            }
            case CancelTradeCommand c -> {
                node.put("type", "CancelTrade");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("tradeId", c.tradeId());
            }
            case CounterTradeCommand c -> {
                node.put("type", "CounterTrade");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("tradeId", c.tradeId());
            }
            case DeclineTradeCommand c -> {
                node.put("type", "DeclineTrade");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("tradeId", c.tradeId());
            }
            case BuyBuildingRoundCommand c -> {
                node.put("type", "BuyBuildingRound");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("propertyId", c.propertyId());
            }
            case ToggleMortgageCommand c -> {
                node.put("type", "ToggleMortgage");
                node.put("actorPlayerId", c.actorPlayerId());
                node.put("propertyId", c.propertyId());
            }
            case RefreshSessionViewCommand ignored -> node.put("type", "RefreshSessionView");
            default -> throw new IllegalArgumentException(
                    "Unknown SessionCommand type: " + command.getClass().getName());
        }
        return objectMapper.writeValueAsString(node);
    }
}
