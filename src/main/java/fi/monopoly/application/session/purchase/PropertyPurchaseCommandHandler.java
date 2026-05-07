package fi.monopoly.application.session.purchase;

import fi.monopoly.application.command.BuyPropertyCommand;
import fi.monopoly.application.command.DeclinePropertyCommand;
import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.DomainEvent;
import fi.monopoly.application.session.auction.AuctionCommandHandler;
import fi.monopoly.domain.decision.DecisionAction;
import fi.monopoly.domain.decision.DecisionType;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.AuctionState;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.TurnContinuationState;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class PropertyPurchaseCommandHandler {
    private final String sessionId;
    private final Supplier<SessionState> currentStateSupplier;
    private final Consumer<PendingDecision> pendingDecisionSetter;
    private final Consumer<AuctionState> auctionStateSetter;
    private final Consumer<TurnContinuationState> turnContinuationSetter;
    private final Consumer<TurnContinuationState> turnContinuationResolver;
    private final PropertyPurchaseGateway gateway;
    private final AuctionCommandHandler auctionCommandHandler;
    private PropertyPurchaseContext activeContext;

    public PendingDecision openDecision(
            String playerId,
            String propertyId,
            String displayName,
            int price,
            String message,
            TurnContinuationState continuationState
    ) {
        PendingDecision decision = new PendingDecision(
                "property-purchase:" + playerId + ":" + propertyId,
                DecisionType.PROPERTY_PURCHASE,
                playerId,
                List.of(DecisionAction.BUY_PROPERTY, DecisionAction.DECLINE_PROPERTY),
                message,
                new PropertyPurchaseDecisionPayload(propertyId, displayName, price)
        );
        activeContext = new PropertyPurchaseContext(decision.decisionId(), playerId, propertyId, displayName, continuationState);
        auctionStateSetter.accept(null);
        turnContinuationSetter.accept(continuationState);
        pendingDecisionSetter.accept(decision);
        return decision;
    }

    public boolean supports(SessionCommand command) {
        return command instanceof BuyPropertyCommand || command instanceof DeclinePropertyCommand;
    }

    public CommandResult handle(SessionCommand command) {
        if (command instanceof BuyPropertyCommand buyPropertyCommand) {
            return handleBuy(buyPropertyCommand);
        }
        if (command instanceof DeclinePropertyCommand declinePropertyCommand) {
            return handleDecline(declinePropertyCommand);
        }
        return reject("UNSUPPORTED_COMMAND", "Unsupported property purchase command");
    }

    private CommandResult handleBuy(BuyPropertyCommand command) {
        PropertyPurchaseContext context = validate(command.sessionId(), command.actorPlayerId(), command.decisionId(), command.propertyId());
        if (context == null) {
            return reject("INVALID_PROPERTY_PURCHASE", "Property purchase command is not valid in the current state");
        }
        if (!gateway.buyProperty(context.playerId(), context.propertyId())) {
            pendingDecisionSetter.accept(null);
            activeContext = null;
            if (!isAlreadyOwned(context.propertyId())) {
                auctionCommandHandler.startAuction(
                        context.playerId(),
                        context.propertyId(),
                        context.displayName(),
                        context.continuationState()
                );
                return new CommandResult(
                        true,
                        currentStateSupplier.get(),
                        List.of(
                                new DomainEvent("PropertyPurchaseFailed", context.playerId(), context.displayName()),
                                new DomainEvent("AuctionStarted", context.playerId(), context.displayName())
                        ),
                        List.of(),
                        List.of()
                );
            }
            auctionStateSetter.accept(null);
            turnContinuationSetter.accept(null);
            turnContinuationResolver.accept(context.continuationState());
            return new CommandResult(
                    true,
                    currentStateSupplier.get(),
                    List.of(new DomainEvent("PropertyPurchaseExpired", context.playerId(), context.displayName())),
                    List.of(),
                    List.of()
            );
        }
        TurnContinuationState continuationState = context.continuationState();
        pendingDecisionSetter.accept(null);
        auctionStateSetter.accept(null);
        turnContinuationSetter.accept(null);
        activeContext = null;
        turnContinuationResolver.accept(continuationState);
        return new CommandResult(
                true,
                currentStateSupplier.get(),
                List.of(new DomainEvent("PropertyBought", context.playerId(), context.displayName())),
                List.of(),
                List.of()
        );
    }

    private CommandResult handleDecline(DeclinePropertyCommand command) {
        PropertyPurchaseContext context = validate(command.sessionId(), command.actorPlayerId(), command.decisionId(), command.propertyId());
        if (context == null) {
            return reject("INVALID_PROPERTY_PURCHASE", "Property decline command is not valid in the current state");
        }
        pendingDecisionSetter.accept(null);
        auctionCommandHandler.startAuction(
                context.playerId(),
                context.propertyId(),
                context.displayName(),
                context.continuationState()
        );
        activeContext = null;
        return new CommandResult(
                true,
                currentStateSupplier.get(),
                List.of(
                        new DomainEvent("PropertyDeclined", context.playerId(), context.displayName()),
                        new DomainEvent("AuctionStarted", context.playerId(), context.displayName())
                ),
                List.of(),
                List.of()
        );
    }

    private PropertyPurchaseContext validate(String commandSessionId, String actorPlayerId, String decisionId, String propertyId) {
        if (!Objects.equals(sessionId, commandSessionId)) {
            return null;
        }
        SessionState currentState = currentStateSupplier.get();
        PendingDecision pendingDecision = currentState.pendingDecision();
        if (pendingDecision == null || pendingDecision.decisionType() != DecisionType.PROPERTY_PURCHASE) {
            return null;
        }
        if (activeContext == null) {
            activeContext = restoreContextFrom(currentState, pendingDecision);
        }
        if (activeContext == null) {
            return null;
        }
        if (!Objects.equals(pendingDecision.actorPlayerId(), actorPlayerId)
                || !Objects.equals(pendingDecision.decisionId(), decisionId)) {
            return null;
        }
        if (!(pendingDecision.payload() instanceof PropertyPurchaseDecisionPayload payload)
                || !Objects.equals(payload.propertyId(), propertyId)) {
            return null;
        }
        if (!Objects.equals(activeContext.decisionId(), decisionId)
                || !Objects.equals(activeContext.propertyId(), propertyId)
                || !Objects.equals(activeContext.playerId(), actorPlayerId)) {
            return null;
        }
        return activeContext;
    }

    private PropertyPurchaseContext restoreContextFrom(SessionState currentState, PendingDecision pendingDecision) {
        if (!(pendingDecision.payload() instanceof PropertyPurchaseDecisionPayload payload)) {
            return null;
        }
        return new PropertyPurchaseContext(
                pendingDecision.decisionId(),
                pendingDecision.actorPlayerId(),
                payload.propertyId(),
                payload.propertyDisplayName(),
                currentState.turnContinuationState()
        );
    }

    private boolean isAlreadyOwned(String propertyId) {
        return currentStateSupplier.get().properties().stream()
                .anyMatch(p -> propertyId.equals(p.propertyId()) && p.ownerPlayerId() != null);
    }

    private CommandResult reject(String code, String message) {
        return new CommandResult(false, currentStateSupplier.get(), List.of(), List.of(new CommandRejection(code, message)), List.of());
    }

    private record PropertyPurchaseContext(
            String decisionId,
            String playerId,
            String propertyId,
            String displayName,
            TurnContinuationState continuationState
    ) {}
}
