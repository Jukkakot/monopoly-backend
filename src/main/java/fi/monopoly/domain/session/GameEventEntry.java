package fi.monopoly.domain.session;

import java.util.List;
import java.util.Map;

/**
 * An immutable record of a single game event, persisted in {@link SessionState#eventLog()}.
 *
 * <p>Events are appended by domain gateways and sent to clients as part of every
 * {@link fi.monopoly.client.session.ClientSessionSnapshot}. The client uses them to
 * populate the event log UI without re-deriving state from snapshot diffs.</p>
 *
 * <p>Known {@code type} values:
 * DICE_ROLLED, PLAYER_MOVED, PASSED_GO, WENT_TO_JAIL, RELEASED_FROM_JAIL,
 * DREW_CARD, PAID_RENT, BOUGHT_PROPERTY, BUILT_HOUSE, BUILT_HOTEL,
 * SOLD_HOUSE, SOLD_HOTEL, MORTGAGED, REDEEMED, WENT_BANKRUPT</p>
 */
public record GameEventEntry(
        long id,
        long timestamp,
        String type,
        List<String> playerIds,
        Map<String, String> data
) {}
