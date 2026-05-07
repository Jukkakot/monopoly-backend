package fi.monopoly.domain.session;

import java.util.List;

public record DebtStateModel(
        String debtId,
        String debtorPlayerId,
        DebtCreditorType creditorType,
        String creditorPlayerId,
        int amountRemaining,
        String reason,
        boolean bankruptcyRisk,
        int currentCash,
        int estimatedLiquidationValue,
        List<DebtAction> allowedActions
) {
    public DebtStateModel {
        allowedActions = List.copyOf(allowedActions);
    }
}
