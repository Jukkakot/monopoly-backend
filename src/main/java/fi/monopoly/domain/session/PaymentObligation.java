package fi.monopoly.domain.session;

public record PaymentObligation(
        String debtorPlayerId,
        DebtCreditorType creditorType,
        String creditorPlayerId,
        int amount,
        String reason
) {
}
