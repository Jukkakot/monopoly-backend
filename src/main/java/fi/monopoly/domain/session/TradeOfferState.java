package fi.monopoly.domain.session;

public record TradeOfferState(
        String proposerPlayerId,
        String recipientPlayerId,
        TradeSelectionState offeredToRecipient,
        TradeSelectionState requestedFromRecipient
) {
    public boolean isEmpty() {
        return offeredToRecipient.isEmpty() && requestedFromRecipient.isEmpty();
    }

    public TradeOfferState reversePerspective() {
        return new TradeOfferState(
                recipientPlayerId,
                proposerPlayerId,
                requestedFromRecipient,
                offeredToRecipient
        );
    }
}
