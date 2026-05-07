package fi.monopoly.application.session.trade;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.DomainEvent;
import fi.monopoly.domain.session.*;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class TradeCommandHandler {
    private final String sessionId;
    private final Supplier<SessionState> currentStateSupplier;
    private final Consumer<TradeState> tradeStateSetter;
    private final TradeGateway gateway;

    public boolean supports(SessionCommand command) {
        return command instanceof OpenTradeCommand
                || command instanceof EditTradeOfferCommand
                || command instanceof SubmitTradeOfferCommand
                || command instanceof AcceptTradeCommand
                || command instanceof DeclineTradeCommand
                || command instanceof CounterTradeCommand
                || command instanceof CancelTradeCommand;
    }

    public CommandResult handle(SessionCommand command) {
        if (command instanceof OpenTradeCommand openTradeCommand) {
            return handleOpen(openTradeCommand);
        }
        if (command instanceof EditTradeOfferCommand editTradeOfferCommand) {
            return handleEdit(editTradeOfferCommand);
        }
        if (command instanceof SubmitTradeOfferCommand submitTradeOfferCommand) {
            return handleSubmit(submitTradeOfferCommand);
        }
        if (command instanceof AcceptTradeCommand acceptTradeCommand) {
            return handleAccept(acceptTradeCommand);
        }
        if (command instanceof DeclineTradeCommand declineTradeCommand) {
            return handleDecline(declineTradeCommand);
        }
        if (command instanceof CounterTradeCommand counterTradeCommand) {
            return handleCounter(counterTradeCommand);
        }
        if (command instanceof CancelTradeCommand cancelTradeCommand) {
            return handleCancel(cancelTradeCommand);
        }
        return reject("UNSUPPORTED_TRADE_COMMAND", "Unsupported trade command");
    }

    private CommandResult handleOpen(OpenTradeCommand command) {
        if (!Objects.equals(sessionId, command.sessionId())) {
            return reject("WRONG_SESSION", "Command session does not match active session");
        }
        if (currentStateSupplier.get().tradeState() != null) {
            return reject("TRADE_ALREADY_ACTIVE", "Another trade is already active");
        }
        if (Objects.equals(command.actorPlayerId(), command.recipientPlayerId())) {
            return reject("INVALID_TRADE_TARGET", "Trade recipient must be another player");
        }
        if (!gateway.playerExists(command.actorPlayerId()) || !gateway.playerExists(command.recipientPlayerId())) {
            return reject("UNKNOWN_TRADE_PLAYER", "Trade participants are not available");
        }
        TradeState tradeState = new TradeState(
                "trade:" + command.actorPlayerId() + ":" + command.recipientPlayerId() + ":" + System.nanoTime(),
                command.actorPlayerId(),
                command.recipientPlayerId(),
                TradeStatus.EDITING,
                new TradeOfferState(
                        command.actorPlayerId(),
                        command.recipientPlayerId(),
                        TradeSelectionState.NONE,
                        TradeSelectionState.NONE
                ),
                command.actorPlayerId(),
                true,
                null,
                command.actorPlayerId(),
                List.of(new TradeHistoryEntry(command.actorPlayerId(), "OPENED", "Trade opened"))
        );
        tradeStateSetter.accept(tradeState);
        return accepted(List.of(new DomainEvent("TradeOpened", command.actorPlayerId(), command.recipientPlayerId())));
    }

    private CommandResult handleEdit(EditTradeOfferCommand command) {
        TradeState state = validateEditableTrade(command.sessionId(), command.tradeId(), command.actorPlayerId());
        if (state == null) {
            return reject("INVALID_TRADE_EDIT", "Trade edit command is not valid in the current state");
        }
        TradeOfferState updatedOffer = applyPatch(state.currentOffer(), command.patch());
        boolean nextEditingOfferedSide = command.patch().offeredSide() != null ? command.patch().offeredSide() : state.editingOfferedSide();
        TradeState updated = new TradeState(
                state.tradeId(),
                state.initiatorPlayerId(),
                state.recipientPlayerId(),
                state.status(),
                updatedOffer,
                command.actorPlayerId(),
                nextEditingOfferedSide,
                state.decisionRequiredFromPlayerId(),
                state.openedByPlayerId(),
                appendHistory(state, new TradeHistoryEntry(command.actorPlayerId(), "EDITED", "Trade updated"))
        );
        tradeStateSetter.accept(updated);
        return accepted(List.of(new DomainEvent("TradeOfferUpdated", command.actorPlayerId(), updatedOffer.toString())));
    }

    private CommandResult handleSubmit(SubmitTradeOfferCommand command) {
        TradeState state = validateExistingTrade(command.sessionId(), command.tradeId());
        if (state == null) {
            return reject("INVALID_TRADE", "Trade submit command is not valid in the current state");
        }
        if (!Objects.equals(state.editingPlayerId(), command.actorPlayerId())) {
            return reject("WRONG_TRADE_EDITOR", "Only the current trade editor can submit the offer");
        }
        if (state.currentOffer().isEmpty()) {
            return reject("EMPTY_TRADE", "Trade offer must include something on at least one side");
        }
        if (!gateway.isValidOffer(state.currentOffer())) {
            return reject("INVALID_TRADE_OFFER", "Trade offer is no longer valid");
        }
        String responderId = state.currentOffer().recipientPlayerId();
        TradeState updated = new TradeState(
                state.tradeId(),
                state.initiatorPlayerId(),
                state.recipientPlayerId(),
                TradeStatus.SUBMITTED,
                state.currentOffer(),
                responderId,
                false,
                responderId,
                state.openedByPlayerId(),
                appendHistory(state, new TradeHistoryEntry(command.actorPlayerId(), "SUBMITTED", "Trade submitted"))
        );
        tradeStateSetter.accept(updated);
        return accepted(List.of(new DomainEvent("TradeOfferSubmitted", command.actorPlayerId(), responderId)));
    }

    private CommandResult handleAccept(AcceptTradeCommand command) {
        TradeState state = validateRespondableTrade(command.sessionId(), command.tradeId(), command.actorPlayerId());
        if (state == null) {
            return reject("INVALID_TRADE_RESPONSE", "Trade accept command is not valid in the current state");
        }
        if (!gateway.isValidOffer(state.currentOffer())) {
            return reject("INVALID_TRADE_OFFER", "Trade offer is no longer valid");
        }
        TradeState applyingState = new TradeState(
                state.tradeId(),
                state.initiatorPlayerId(),
                state.recipientPlayerId(),
                TradeStatus.ACCEPTED_PENDING_APPLY,
                state.currentOffer(),
                state.editingPlayerId(),
                state.editingOfferedSide(),
                state.decisionRequiredFromPlayerId(),
                state.openedByPlayerId(),
                appendHistory(state, new TradeHistoryEntry(command.actorPlayerId(), "ACCEPTED", "Trade accepted"))
        );
        tradeStateSetter.accept(applyingState);
        if (!gateway.applyOffer(state.currentOffer())) {
            tradeStateSetter.accept(state);
            return reject("TRADE_APPLY_FAILED", "Trade could not be applied");
        }
        tradeStateSetter.accept(null);
        return accepted(List.of(new DomainEvent("TradeAccepted", command.actorPlayerId(), state.tradeId())));
    }

    private CommandResult handleDecline(DeclineTradeCommand command) {
        TradeState state = validateRespondableTrade(command.sessionId(), command.tradeId(), command.actorPlayerId());
        if (state == null) {
            return reject("INVALID_TRADE_RESPONSE", "Trade decline command is not valid in the current state");
        }
        tradeStateSetter.accept(null);
        return accepted(List.of(new DomainEvent("TradeDeclined", command.actorPlayerId(), state.tradeId())));
    }

    private CommandResult handleCounter(CounterTradeCommand command) {
        TradeState state = validateRespondableTrade(command.sessionId(), command.tradeId(), command.actorPlayerId());
        if (state == null) {
            return reject("INVALID_TRADE_RESPONSE", "Trade counter command is not valid in the current state");
        }
        if (state.currentOffer().isEmpty() || !gateway.isValidOffer(state.currentOffer())) {
            return reject("INVALID_TRADE_OFFER", "Trade counter offer is not valid");
        }
        String nextResponder = state.currentOffer().recipientPlayerId();
        TradeState updated = new TradeState(
                state.tradeId(),
                state.initiatorPlayerId(),
                state.recipientPlayerId(),
                TradeStatus.COUNTERED,
                state.currentOffer(),
                nextResponder,
                false,
                nextResponder,
                state.openedByPlayerId(),
                appendHistory(state, new TradeHistoryEntry(command.actorPlayerId(), "COUNTERED", "Trade countered"))
        );
        tradeStateSetter.accept(updated);
        return accepted(List.of(new DomainEvent("TradeCountered", command.actorPlayerId(), nextResponder)));
    }

    private CommandResult handleCancel(CancelTradeCommand command) {
        TradeState state = validateExistingTrade(command.sessionId(), command.tradeId());
        if (state == null) {
            return reject("INVALID_TRADE", "Trade cancel command is not valid in the current state");
        }
        if (!Objects.equals(state.editingPlayerId(), command.actorPlayerId())) {
            return reject("WRONG_TRADE_EDITOR", "Only the current trade editor can cancel the trade");
        }
        tradeStateSetter.accept(null);
        return accepted(List.of(new DomainEvent("TradeCancelled", command.actorPlayerId(), state.tradeId())));
    }

    private TradeState validateEditableTrade(String commandSessionId, String tradeId, String actorPlayerId) {
        TradeState state = validateExistingTrade(commandSessionId, tradeId);
        if (state == null) {
            return null;
        }
        if (state.status() == TradeStatus.EDITING && Objects.equals(state.editingPlayerId(), actorPlayerId)) {
            return state;
        }
        if ((state.status() == TradeStatus.SUBMITTED || state.status() == TradeStatus.COUNTERED)
                && Objects.equals(state.decisionRequiredFromPlayerId(), actorPlayerId)) {
            return state;
        }
        return null;
    }

    private TradeState validateRespondableTrade(String commandSessionId, String tradeId, String actorPlayerId) {
        TradeState state = validateExistingTrade(commandSessionId, tradeId);
        if (state == null) {
            return null;
        }
        if (state.status() != TradeStatus.SUBMITTED && state.status() != TradeStatus.COUNTERED) {
            return null;
        }
        if (!Objects.equals(state.decisionRequiredFromPlayerId(), actorPlayerId)) {
            return null;
        }
        return state;
    }

    private TradeState validateExistingTrade(String commandSessionId, String tradeId) {
        if (!Objects.equals(sessionId, commandSessionId)) {
            return null;
        }
        TradeState state = currentStateSupplier.get().tradeState();
        if (state == null || !Objects.equals(state.tradeId(), tradeId)) {
            return null;
        }
        return state;
    }

    private TradeOfferState applyPatch(TradeOfferState currentOffer, TradeEditPatch patch) {
        TradeOfferState offer = Boolean.TRUE.equals(patch.reversePerspective()) ? currentOffer.reversePerspective() : currentOffer;
        boolean offeredSide = patch.offeredSide() == null || patch.offeredSide();
        TradeSelectionState targetSelection = offeredSide ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        TradeSelectionState updatedSelection = new TradeSelectionState(
                patch.replaceMoneyAmount() != null ? Math.max(0, patch.replaceMoneyAmount()) : targetSelection.moneyAmount(),
                updatePropertyIds(targetSelection.propertyIds(), patch.propertyIdsToAdd(), patch.propertyIdsToRemove()),
                Boolean.TRUE.equals(patch.toggleJailCard()) ? (targetSelection.jailCardCount() > 0 ? 0 : 1) : targetSelection.jailCardCount()
        );
        return offeredSide
                ? new TradeOfferState(offer.proposerPlayerId(), offer.recipientPlayerId(), updatedSelection, offer.requestedFromRecipient())
                : new TradeOfferState(offer.proposerPlayerId(), offer.recipientPlayerId(), offer.offeredToRecipient(), updatedSelection);
    }

    private List<String> updatePropertyIds(List<String> currentIds, List<String> idsToAdd, List<String> idsToRemove) {
        List<String> updated = new ArrayList<>(currentIds);
        for (String propertyId : idsToAdd) {
            if (propertyId != null && !updated.contains(propertyId)) {
                updated.add(propertyId);
            }
        }
        updated.removeIf(idsToRemove::contains);
        return updated;
    }

    private List<TradeHistoryEntry> appendHistory(TradeState state, TradeHistoryEntry entry) {
        List<TradeHistoryEntry> history = new ArrayList<>(state.history());
        history.add(entry);
        return history;
    }

    private CommandResult accepted(List<DomainEvent> events) {
        return new CommandResult(true, currentStateSupplier.get(), events, List.of(), List.of());
    }

    private CommandResult reject(String code, String message) {
        return new CommandResult(false, currentStateSupplier.get(), List.of(), List.of(new CommandRejection(code, message)), List.of());
    }
}
