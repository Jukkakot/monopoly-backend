package fi.monopoly.application.session.trade;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.domain.session.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

import static fi.monopoly.domain.session.GameEventHelper.*;

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
                        boolean isProposer = proposerId.equals(p.playerId());
                        boolean isRecipient = recipientId.equals(p.playerId());
                        // Both parties must ALWAYS be rebuilt: in a pure property-for-property
                        // swap the cash and jail-card deltas are zero, but ownedPropertyIds
                        // still changes — the old early-return left the players' owned lists
                        // stale (wrong net worth, player list and game-over rankings).
                        if (!isProposer && !isRecipient) return p;

                        int cashDelta = isProposer
                                ? requested.moneyAmount() - offered.moneyAmount()
                                : offered.moneyAmount() - requested.moneyAmount();
                        int jailDelta = isProposer
                                ? requested.jailCardCount() - offered.jailCardCount()
                                : offered.jailCardCount() - requested.jailCardCount();

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

    @Override
    public void logTradeAccepted(String initiatorId, String recipientId, TradeOfferState offer) {
        var offered   = offer.offeredToRecipient();
        var requested = offer.requestedFromRecipient();
        var data = new java.util.HashMap<String, String>();
        if (offered.moneyAmount()   > 0) data.put("offeredMoney",    String.valueOf(offered.moneyAmount()));
        if (requested.moneyAmount() > 0) data.put("requestedMoney",  String.valueOf(requested.moneyAmount()));
        if (!offered.propertyIds().isEmpty())   data.put("offeredProps",   String.join(",", offered.propertyIds()));
        if (!requested.propertyIds().isEmpty()) data.put("requestedProps", String.join(",", requested.propertyIds()));
        store.update(state -> {
            List<fi.monopoly.domain.session.GameEventEntry> events = new java.util.ArrayList<>();
            events.add(ev("TRADE_ACCEPTED", List.of(initiatorId, recipientId), Map.copyOf(data)));
            if (offered.moneyAmount() > 0)
                events.add(evMoney(initiatorId, recipientId, offered.moneyAmount(), "kauppa"));
            if (requested.moneyAmount() > 0)
                events.add(evMoney(recipientId, initiatorId, requested.moneyAmount(), "kauppa"));
            return appendEvents(state, events.toArray(fi.monopoly.domain.session.GameEventEntry[]::new));
        });
    }

    @Override
    public void logTradeDeclined(String initiatorId, String recipientId) {
        store.update(state -> appendEvents(state,
                ev("TRADE_DECLINED", List.of(initiatorId, recipientId), Map.of())));
    }

    @Override
    public void logTradeCancelled(String initiatorId, String recipientId) {
        store.update(state -> appendEvents(state,
                ev("TRADE_CANCELLED", List.of(initiatorId, recipientId), Map.of())));
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
