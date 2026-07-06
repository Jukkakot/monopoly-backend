package fi.monopoly.application.session.debt;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.DomainEvent;
import fi.monopoly.application.session.auction.AuctionCommandHandler;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

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
    @Setter private AuctionCommandHandler auctionCommandHandler;
    @Setter private Consumer<List<String>> bankruptcyQueueSetter;

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
                // Check the debtor's LIVE cash, not the debt model's cached currentCash —
                // a trade accepted while the debt is open changes the debtor's balance
                // without refreshing the debt state. Paying against a stale high value
                // would drive the balance negative.
                PlayerSnapshot debtor = findPlayer(state, debt.debtorPlayerId());
                int liveCash = debtor != null ? debtor.cash() : 0;
                if (liveCash < debt.amountRemaining()) {
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
                activeDebtUpdater.accept(null);
                turnContinuationUpdater.accept(null);
                gateway.declareBankruptcy();
                startBankruptcyAuctionsIfNeeded();
                yield accepted("BankruptcyDeclared");
            }
            default -> unsupported();
        };
    }

    private void startBankruptcyAuctionsIfNeeded() {
        if (auctionCommandHandler == null) return;
        SessionState state = sessionStateSupplier.get();
        if (state.status() == SessionStatus.GAME_OVER) return;
        List<String> queue = state.bankruptcyAuctionQueue();
        if (queue == null || queue.isEmpty()) return;
        String firstPropId = queue.get(0);
        // Pop first so resolveAuction sees the remainder when this auction finishes.
        List<String> remaining = queue.size() > 1 ? queue.subList(1, queue.size()) : List.of();
        if (bankruptcyQueueSetter != null) bankruptcyQueueSetter.accept(remaining);
        auctionCommandHandler.startAuction(
                state.turn().activePlayerId(),
                firstPropId,
                resolveDisplayName(firstPropId),
                null
        );
    }

    private static String resolveDisplayName(String propertyId) {
        try {
            SpotType spotType = SpotType.valueOf(propertyId);
            String name = spotType.getStringProperty("name");
            return (name != null && !name.isBlank()) ? name : propertyId;
        } catch (IllegalArgumentException ignored) {
            return propertyId;
        }
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
        return DomainDebtRemediationGateway.estimatedLiquidationValue(state, playerId);
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
        actions.add(DebtAction.DECLARE_BANKRUPTCY);
        return actions;
    }

    private CommandResult accepted(String eventType) {
        SessionState state = sessionStateSupplier.get();
        if (state.activeDebt() == null && state.turn().phase() == TurnPhase.RESOLVING_DEBT) {
            state = state.toBuilder()
                    .turn(new TurnState(state.turn().activePlayerId(), TurnPhase.WAITING_FOR_END_TURN, false, true, state.turn().consecutiveDoubles(), state.turn().lastDice()))
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
