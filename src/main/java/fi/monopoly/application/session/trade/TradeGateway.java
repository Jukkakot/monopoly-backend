package fi.monopoly.application.session.trade;

import fi.monopoly.domain.session.TradeOfferState;

public interface TradeGateway {
    boolean playerExists(String playerId);

    boolean isValidOffer(TradeOfferState offerState);

    boolean applyOffer(TradeOfferState offerState);
}
