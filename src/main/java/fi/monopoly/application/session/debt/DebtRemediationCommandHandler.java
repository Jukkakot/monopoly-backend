package fi.monopoly.application.session.debt;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.DomainEvent;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class DebtRemediationCommandHandler {
    private final String sessionId;
    private final Supplier<SessionState> sessionStateSupplier;
    private final Consumer<DebtStateModel> activeDebtUpdater;
    private final Consumer<TurnContinuationState> turnContinuationUpdater;
    private final DebtRemediationGateway gateway;

    public CommandResult handle(SessionCommand command) {
        SessionState state = sessionStateSupplier.get();
        DebtStateModel debt = state.activeDebt();
        if (debt == null) {
            return rejected("NO_ACTIVE_DEBT", "No active debt to remediate");
        }

        return switch (command) {
            case PayDebtCommand cmd -> {
                if (!validBase(cmd.sessionId(), cmd.actorPlayerId(), cmd.debtId(), debt)) {
                    yield rejected("INVALID_DEBT_ACTION", "Debt action does not match the active debt");
                }
                if (debt.currentCash() < debt.amountRemaining()) {
                    yield rejected("DEBT_NOT_PAYABLE", "Current cash does not cover the debt");
                }
                activeDebtUpdater.accept(null);
                turnContinuationUpdater.accept(null);
                gateway.payDebtNow();
                yield accepted("DebtResolved");
            }
            case MortgagePropertyForDebtCommand cmd -> {
                if (!validBase(cmd.sessionId(), cmd.actorPlayerId(), cmd.debtId(), debt)) {
                    yield rejected("INVALID_DEBT_ACTION", "Debt action does not match the active debt");
                }
                if (!gateway.canMortgage(cmd.propertyId(), debt.debtorPlayerId())) {
                    yield rejected("INVALID_MORTGAGE", "Property cannot be mortgaged for the active debt");
                }
                if (!gateway.mortgageProperty(cmd.propertyId())) {
                    yield rejected("MORTGAGE_FAILED", "Property mortgage failed");
                }
                refreshDebtState(debt);
                yield accepted("PropertyMortgaged");
            }
            case SellBuildingForDebtCommand cmd -> {
                if (!validBase(cmd.sessionId(), cmd.actorPlayerId(), cmd.debtId(), debt)) {
                    yield rejected("INVALID_DEBT_ACTION", "Debt action does not match the active debt");
                }
                if (!gateway.canSellBuildings(cmd.propertyId(), cmd.count(), debt.debtorPlayerId())) {
                    yield rejected("INVALID_BUILDING_SALE", "Buildings cannot be sold for the active debt");
                }
                if (!gateway.sellBuildings(cmd.propertyId(), cmd.count())) {
                    yield rejected("BUILDING_SALE_FAILED", "Building sale failed");
                }
                refreshDebtState(debt);
                yield accepted("BuildingSold");
            }
            case SellBuildingRoundsAcrossSetForDebtCommand cmd -> {
                if (!validBase(cmd.sessionId(), cmd.actorPlayerId(), cmd.debtId(), debt)) {
                    yield rejected("INVALID_DEBT_ACTION", "Debt action does not match the active debt");
                }
                if (!gateway.canSellBuildingRoundsAcrossSet(cmd.propertyId(), cmd.rounds(), debt.debtorPlayerId())) {
                    yield rejected("INVALID_SET_BUILDING_SALE", "Building rounds cannot be sold for the active debt");
                }
                if (!gateway.sellBuildingRoundsAcrossSet(cmd.propertyId(), cmd.rounds())) {
                    yield rejected("SET_BUILDING_SALE_FAILED", "Building round sale failed");
                }
                refreshDebtState(debt);
                yield accepted("BuildingRoundsSold");
            }
            case DeclareBankruptcyCommand cmd -> {
                if (!validBase(cmd.sessionId(), cmd.actorPlayerId(), cmd.debtId(), debt)) {
                    yield rejected("INVALID_DEBT_ACTION", "Debt action does not match the active debt");
                }
                if (!debt.bankruptcyRisk()) {
                    yield rejected("BANKRUPTCY_NOT_ALLOWED", "Debt can still be covered by available assets");
                }
                activeDebtUpdater.accept(null);
                turnContinuationUpdater.accept(null);
                gateway.declareBankruptcy();
                yield accepted("BankruptcyDeclared");
            }
            default -> unsupported();
        };
    }

    private boolean validBase(String commandSessionId, String actorPlayerId, String debtId, DebtStateModel debt) {
        return sessionId.equals(commandSessionId)
                && debt.debtorPlayerId().equals(actorPlayerId)
                && debt.debtId().equals(debtId);
    }

    private void refreshDebtState(DebtStateModel debt) {
        SessionState state = sessionStateSupplier.get();
        PlayerSnapshot debtor = findPlayer(state, debt.debtorPlayerId());
        if (debtor == null) {
            activeDebtUpdater.accept(null);
            return;
        }
        int currentCash = debtor.cash();
        int liquidationValue = estimatedLiquidationValue(state, debt.debtorPlayerId());
        boolean bankruptcyRisk = currentCash + liquidationValue < debt.amountRemaining();
        activeDebtUpdater.accept(new DebtStateModel(
                debt.debtId(),
                debt.debtorPlayerId(),
                debt.creditorType(),
                debt.creditorPlayerId(),
                debt.amountRemaining(),
                debt.reason(),
                bankruptcyRisk,
                currentCash,
                liquidationValue,
                allowedActions(bankruptcyRisk)
        ));
    }

    private static int estimatedLiquidationValue(SessionState state, String playerId) {
        int total = 0;
        for (PropertyStateSnapshot prop : state.properties()) {
            if (!playerId.equals(prop.ownerPlayerId())) {
                continue;
            }
            if (!prop.mortgaged()) {
                try {
                    int price = SpotType.valueOf(prop.propertyId()).getIntegerProperty("price");
                    total += price / 2;
                } catch (IllegalArgumentException ignored) {
                }
            }
            int buildings = prop.houseCount() + prop.hotelCount();
            if (buildings > 0) {
                try {
                    int housePrice = SpotType.valueOf(prop.propertyId()).getIntegerProperty("housePrice");
                    total += buildings * housePrice / 2;
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return total;
    }

    private static PlayerSnapshot findPlayer(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> playerId.equals(p.playerId()))
                .findFirst()
                .orElse(null);
    }

    private List<DebtAction> allowedActions(boolean bankruptcyRisk) {
        List<DebtAction> actions = new ArrayList<>(List.of(
                DebtAction.PAY_DEBT_NOW,
                DebtAction.MORTGAGE_PROPERTY,
                DebtAction.SELL_BUILDING,
                DebtAction.SELL_BUILDING_ROUNDS_ACROSS_SET
        ));
        if (bankruptcyRisk) {
            actions.add(DebtAction.DECLARE_BANKRUPTCY);
        }
        return actions;
    }

    private CommandResult accepted(String eventType) {
        SessionState state = sessionStateSupplier.get();
        if (state.activeDebt() == null && state.turn().phase() == TurnPhase.RESOLVING_DEBT) {
            state = state.toBuilder()
                    .turn(new TurnState(state.turn().activePlayerId(), TurnPhase.WAITING_FOR_END_TURN, false, true))
                    .activeDebt(null)
                    .turnContinuationState(null)
                    .build();
        }
        return new CommandResult(true, state, List.of(new DomainEvent(eventType, state.turn().activePlayerId(), eventType)), List.of(), List.of());
    }

    private CommandResult rejected(String code, String message) {
        return new CommandResult(false, sessionStateSupplier.get(), List.of(), List.of(new CommandRejection(code, message)), List.of());
    }

    private CommandResult unsupported() {
        return rejected("UNSUPPORTED_COMMAND", "Command is not supported by debt remediation");
    }
}
