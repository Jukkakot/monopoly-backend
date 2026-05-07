package fi.monopoly.application.session.trade;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.domain.session.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Pure domain implementation of {@link TradeGateway} — no Processing runtime objects.
 *
 * <p>Validation mirrors {@link fi.monopoly.components.trade.TradeOffer#isValid()}:
 * both players must be active, the offer must be non-empty, and each side must
 * have sufficient cash, own every listed property with no buildings, and hold
 * enough jail cards.</p>
 *
 * <p>Application mirrors {@link fi.monopoly.components.trade.TradeOffer#apply()}:
 * net cash transfers, property-ownership reassignment, and jail-card transfers
 * are applied atomically to the {@link SessionStateStore}.</p>
 */
@Slf4j
@RequiredArgsConstructor
public final class DomainTradeGateway implements TradeGateway {

    private final SessionStateStore store;

    @Override
    public boolean playerExists(String playerId) {
        return store.get().players().stream()
                .anyMatch(p -> playerId.equals(p.playerId()) && !p.eliminated());
    }

    @Override
    public boolean isValidOffer(TradeOfferState offerState) {
        SessionState state = store.get();
        if (offerState.isEmpty()) return false;
        if (offerState.proposerPlayerId().equals(offerState.recipientPlayerId())) return false;

        PlayerSnapshot proposer = findPlayer(state, offerState.proposerPlayerId());
        PlayerSnapshot recipient = findPlayer(state, offerState.recipientPlayerId());
        if (proposer == null || recipient == null) return false;
        if (proposer.eliminated() || recipient.eliminated()) return false;

        return canSendSelection(state, proposer, offerState.offeredToRecipient())
                && canSendSelection(state, recipient, offerState.requestedFromRecipient());
    }

    @Override
    public boolean applyOffer(TradeOfferState offerState) {
        if (!isValidOffer(offerState)) return false;

        String proposerId = offerState.proposerPlayerId();
        String recipientId = offerState.recipientPlayerId();
        TradeSelectionState offered = offerState.offeredToRecipient();
        TradeSelectionState requested = offerState.requestedFromRecipient();

        log.debug("applyOffer proposer={} recipient={} offeredMoney={} requestedMoney={} offeredProps={} requestedProps={}",
                proposerId, recipientId, offered.moneyAmount(), requested.moneyAmount(),
                offered.propertyIds(), requested.propertyIds());

        store.update(state -> {
            List<PropertyStateSnapshot> updatedProps = state.properties().stream()
                    .map(p -> {
                        if (offered.propertyIds().contains(p.propertyId())) {
                            return new PropertyStateSnapshot(p.propertyId(), recipientId, p.mortgaged(), p.houseCount(), p.hotelCount());
                        }
                        if (requested.propertyIds().contains(p.propertyId())) {
                            return new PropertyStateSnapshot(p.propertyId(), proposerId, p.mortgaged(), p.houseCount(), p.hotelCount());
                        }
                        return p;
                    })
                    .toList();

            List<PlayerSnapshot> updatedPlayers = state.players().stream()
                    .map(p -> {
                        int cashDelta = 0;
                        int jailDelta = 0;
                        if (proposerId.equals(p.playerId())) {
                            cashDelta = requested.moneyAmount() - offered.moneyAmount();
                            jailDelta = requested.jailCardCount() - offered.jailCardCount();
                        } else if (recipientId.equals(p.playerId())) {
                            cashDelta = offered.moneyAmount() - requested.moneyAmount();
                            jailDelta = offered.jailCardCount() - requested.jailCardCount();
                        }
                        if (cashDelta == 0 && jailDelta == 0) return p;

                        List<String> ownedIds = updatedProps.stream()
                                .filter(prop -> p.playerId().equals(prop.ownerPlayerId()))
                                .map(PropertyStateSnapshot::propertyId)
                                .toList();
                        return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(),
                                p.cash() + cashDelta, p.boardIndex(),
                                p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards() + jailDelta, ownedIds);
                    })
                    .toList();

            return state.toBuilder()
                    .properties(updatedProps)
                    .players(updatedPlayers)
                    .build();
        });
        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static boolean canSendSelection(SessionState state, PlayerSnapshot player, TradeSelectionState selection) {
        if (selection.moneyAmount() < 0) return false;
        if (selection.moneyAmount() > player.cash()) return false;
        if (selection.jailCardCount() > player.getOutOfJailCards()) return false;
        for (String propertyId : selection.propertyIds()) {
            PropertyStateSnapshot prop = findProperty(state, propertyId);
            if (prop == null || !player.playerId().equals(prop.ownerPlayerId())) return false;
            if (prop.houseCount() > 0 || prop.hotelCount() > 0) return false;
        }
        return true;
    }

    private static PlayerSnapshot findPlayer(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> playerId.equals(p.playerId()))
                .findFirst().orElse(null);
    }

    private static PropertyStateSnapshot findProperty(SessionState state, String propertyId) {
        return state.properties().stream()
                .filter(p -> propertyId.equals(p.propertyId()))
                .findFirst().orElse(null);
    }
}
