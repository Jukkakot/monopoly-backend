package fi.monopoly.application.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.session.auction.AuctionCommandHandler;
import fi.monopoly.application.session.auction.AuctionGateway;
import fi.monopoly.application.session.debt.DebtRemediationCommandHandler;
import fi.monopoly.application.session.debt.DebtRemediationGateway;
import fi.monopoly.application.session.purchase.PropertyPurchaseCommandHandler;
import fi.monopoly.application.session.purchase.PropertyPurchaseGateway;
import fi.monopoly.application.session.trade.TradeCommandHandler;
import fi.monopoly.application.session.trade.TradeGateway;
import fi.monopoly.application.session.turn.TurnActionCommandHandler;
import fi.monopoly.application.session.turn.TurnActionGateway;
import fi.monopoly.application.session.turn.TurnContinuationGateway;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.session.*;

import java.util.List;
import java.util.function.Supplier;

/**
 * Application-layer entry point for command handling around a single Monopoly session.
 *
 * <p>This service exposes the projected authoritative {@link SessionState} via
 * {@link #currentState()}, routes incoming session commands to the relevant subsystem handlers,
 * and delegates flow-state overrides (auction, debt, trade, pending decision, turn continuation)
 * to an {@link OverlaySessionStateStore}.</p>
 */
public final class SessionApplicationService implements SessionCommandPort, SessionPresentationStatePort {

    private final String sessionId;
    private final OverlaySessionStateStore overlay;
    private AuctionCommandHandler auctionCommandHandler;
    private PropertyPurchaseCommandHandler propertyPurchaseCommandHandler;
    private DebtRemediationCommandHandler debtRemediationCommandHandler;
    private TradeCommandHandler tradeCommandHandler;
    private TurnActionCommandHandler turnActionCommandHandler;
    private TurnContinuationGateway turnContinuationGateway;

    public SessionApplicationService(String sessionId, Supplier<SessionState> stateSupplier) {
        this(sessionId, new OverlaySessionStateStore(stateSupplier));
    }

    public SessionApplicationService(String sessionId, OverlaySessionStateStore overlay) {
        this.sessionId = sessionId;
        this.overlay = overlay;
    }

    public SessionState currentState() {
        return overlay.get();
    }

    public void configureAuctionFlow(AuctionGateway gateway) {
        auctionCommandHandler = new AuctionCommandHandler(
                sessionId,
                this::currentState,
                this::setAuctionStateOverride,
                this::setTurnContinuationOverride,
                this::resumeContinuation,
                gateway
        );
    }

    public void configurePropertyPurchaseFlow(PropertyPurchaseGateway gateway) {
        if (auctionCommandHandler == null) {
            throw new IllegalStateException("Auction flow must be configured before property purchase flow");
        }
        propertyPurchaseCommandHandler = new PropertyPurchaseCommandHandler(
                sessionId,
                this::currentState,
                this::setPendingDecisionOverride,
                this::setAuctionStateOverride,
                this::setTurnContinuationOverride,
                this::resumeContinuation,
                gateway,
                auctionCommandHandler
        );
    }

    public void configureDebtRemediationFlow(DebtRemediationGateway debtRemediationGateway) {
        debtRemediationCommandHandler = new DebtRemediationCommandHandler(
                sessionId,
                this::currentState,
                this::setActiveDebtOverride,
                this::setTurnContinuationOverride,
                debtRemediationGateway
        );
    }

    public void configureTradeFlow(TradeGateway gateway) {
        tradeCommandHandler = new TradeCommandHandler(
                sessionId,
                this::currentState,
                this::setTradeStateOverride,
                gateway
        );
    }

    public void configureTurnActionFlow(TurnActionGateway gateway) {
        turnActionCommandHandler = new TurnActionCommandHandler(
                sessionId,
                this::currentState,
                gateway
        );
    }

    public void configureTurnContinuationFlow(TurnContinuationGateway gateway) {
        this.turnContinuationGateway = gateway;
    }

    public PendingDecision openPropertyPurchaseDecision(
            String playerId,
            String propertyId,
            String displayName,
            int price,
            String message,
            TurnContinuationState continuationState
    ) {
        if (propertyPurchaseCommandHandler == null) {
            throw new IllegalStateException("Property purchase flow has not been configured");
        }
        return propertyPurchaseCommandHandler.openDecision(playerId, propertyId, displayName, price, message, continuationState);
    }

    public void clearActiveDebtOverride() {
        overlay.setActiveDebt(null);
    }

    public boolean hasActiveAuction() {
        return currentState().auctionState() != null;
    }

    public boolean hasActiveTrade() {
        return currentState().tradeState() != null;
    }

    public boolean hasAuctionOverride() {
        return overlay.hasAuctionState();
    }

    public boolean hasTradeOverride() {
        return overlay.hasTradeState();
    }

    public boolean hasPendingDecisionOverride() {
        return overlay.hasPendingDecision();
    }

    public void restoreFrom(SessionState restoredState) {
        overlay.restoreFrom(restoredState);
    }

    public void setTurnContinuationOverride(TurnContinuationState turnContinuationState) {
        overlay.setTurnContinuation(turnContinuationState);
    }

    public void resumeContinuation(TurnContinuationState continuationState) {
        if (continuationState == null || turnContinuationGateway == null) {
            return;
        }
        turnContinuationGateway.resume(continuationState);
    }

    public CommandResult handleComputerAuctionAction(String actorPlayerId) {
        if (auctionCommandHandler == null) {
            return rejected("AUCTION_NOT_CONFIGURED", "Auction flow has not been configured");
        }
        return auctionCommandHandler.handleComputerAction(actorPlayerId);
    }

    public CommandResult handle(SessionCommand command) {
        return dispatch(command);
    }

    private CommandResult dispatch(SessionCommand command) {
        if (propertyPurchaseCommandHandler != null
                && (command instanceof BuyPropertyCommand || command instanceof DeclinePropertyCommand)) {
            return propertyPurchaseCommandHandler.handle(command);
        }
        if (auctionCommandHandler != null
                && (command instanceof PlaceAuctionBidCommand
                || command instanceof PassAuctionCommand
                || command instanceof FinishAuctionResolutionCommand)) {
            return auctionCommandHandler.handle(command);
        }
        if (debtRemediationCommandHandler != null
                && (command instanceof PayDebtCommand
                || command instanceof MortgagePropertyForDebtCommand
                || command instanceof SellBuildingForDebtCommand
                || command instanceof SellBuildingRoundsAcrossSetForDebtCommand
                || command instanceof DeclareBankruptcyCommand)) {
            return debtRemediationCommandHandler.handle(command);
        }
        if (tradeCommandHandler != null
                && (command instanceof OpenTradeCommand
                || command instanceof EditTradeOfferCommand
                || command instanceof SubmitTradeOfferCommand
                || command instanceof AcceptTradeCommand
                || command instanceof DeclineTradeCommand
                || command instanceof CounterTradeCommand
                || command instanceof CancelTradeCommand)) {
            return tradeCommandHandler.handle(command);
        }
        if (turnActionCommandHandler != null
                && (command instanceof RollDiceCommand
                || command instanceof EndTurnCommand
                || command instanceof BuyBuildingRoundCommand
                || command instanceof ToggleMortgageCommand)) {
            return turnActionCommandHandler.handle(command);
        }
        if (command instanceof RefreshSessionViewCommand refreshSessionViewCommand) {
            if (!sessionId.equals(refreshSessionViewCommand.sessionId())) {
                return rejected("WRONG_SESSION", "Command session does not match active session");
            }
            return accepted();
        }
        return rejected("UNSUPPORTED_COMMAND", "Command is not supported by the PR1 seam");
    }

    private CommandResult accepted() {
        return new CommandResult(true, currentState(), List.of(), List.of(), List.of());
    }

    private CommandResult rejected(String code, String message) {
        return new CommandResult(false, currentState(), List.of(), List.of(new CommandRejection(code, message)), List.of());
    }

    private void setPendingDecisionOverride(PendingDecision pendingDecision) {
        overlay.setPendingDecision(pendingDecision);
    }

    private void setAuctionStateOverride(AuctionState auctionState) {
        overlay.setAuctionState(auctionState);
    }

    public void setActiveDebtOverride(DebtStateModel activeDebt) {
        overlay.setActiveDebt(activeDebt);
    }

    private void setTradeStateOverride(TradeState tradeState) {
        overlay.setTradeState(tradeState);
    }
}
