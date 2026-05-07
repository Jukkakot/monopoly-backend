package fi.monopoly.domain.session;

import java.util.List;

public record PlayerSnapshot(
        String playerId,
        String seatId,
        String name,
        int cash,
        int boardIndex,
        boolean bankrupt,
        boolean eliminated,
        boolean inJail,
        int jailRoundsRemaining,
        int getOutOfJailCards,
        List<String> ownedPropertyIds
) {
    public PlayerSnapshot {
        ownedPropertyIds = List.copyOf(ownedPropertyIds);
    }
}
