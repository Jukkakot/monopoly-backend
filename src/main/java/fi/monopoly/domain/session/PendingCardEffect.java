package fi.monopoly.domain.session;

import java.util.List;

/** Stores a drawn card's effect parameters while the player acknowledges the card popup. */
public record PendingCardEffect(
        String playerId,
        String cardType,
        List<String> values,
        boolean isDoubles,
        int consecutiveDoubles,
        int diceTotal
) {
    public PendingCardEffect {
        values = List.copyOf(values);
    }
}
