package fi.monopoly.domain.session;

import java.util.List;

public record TradeState(
        String tradeId,
        String initiatorPlayerId,
        String recipientPlayerId,
        TradeStatus status,
        TradeOfferState currentOffer,
        String editingPlayerId,
        boolean editingOfferedSide,
        String decisionRequiredFromPlayerId,
        String openedByPlayerId,
        List<TradeHistoryEntry> history
) {
    public TradeState {
        history = List.copyOf(history == null ? List.of() : history);
    }
}
