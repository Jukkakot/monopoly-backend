package fi.monopoly.domain.session;

public record PendingGroupDebt(
        String debtorPlayerId,
        String creditorPlayerId,
        int amount,
        boolean isDoubles,
        int consecutiveDoubles
) {}
