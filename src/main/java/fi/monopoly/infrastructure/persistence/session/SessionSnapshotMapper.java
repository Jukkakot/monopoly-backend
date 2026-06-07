package fi.monopoly.infrastructure.persistence.session;

import fi.monopoly.domain.decision.DecisionPayload;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.AuctionState;
import fi.monopoly.domain.session.DebtStateModel;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.domain.session.SeatState;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.TurnContinuationState;
import fi.monopoly.domain.session.TradeHistoryEntry;
import fi.monopoly.domain.session.TradeOfferState;
import fi.monopoly.domain.session.TradeSelectionState;
import fi.monopoly.domain.session.TradeState;
import fi.monopoly.domain.turn.TurnState;

import java.util.List;

public final class SessionSnapshotMapper {
    public SessionSnapshot toSnapshot(SessionState sessionState) {
        return new SessionSnapshot(
                SessionSnapshot.CURRENT_SCHEMA_VERSION,
                sessionState.sessionId(),
                sessionState.version(),
                sessionState.status(),
                sessionState.seats().stream().map(this::toSeatSnapshot).toList(),
                sessionState.players().stream().map(this::toPlayerSnapshot).toList(),
                sessionState.properties().stream().map(this::toPropertySnapshot).toList(),
                toTurnSnapshot(sessionState.turn()),
                toPendingDecisionSnapshot(sessionState.pendingDecision()),
                toAuctionSnapshot(sessionState.auctionState()),
                toDebtSnapshot(sessionState.activeDebt()),
                toTradeSnapshot(sessionState.tradeState()),
                toTurnContinuationSnapshot(sessionState.turnContinuationState()),
                sessionState.winnerPlayerId()
        );
    }

    public SessionState fromSnapshot(SessionSnapshot snapshot) {
        validate(snapshot);
        return new SessionState(
                snapshot.sessionId(),
                snapshot.version(),
                snapshot.status(),
                snapshot.seats().stream().map(this::fromSeatSnapshot).toList(),
                snapshot.players().stream().map(this::fromPlayerSnapshot).toList(),
                snapshot.properties().stream().map(this::fromPropertySnapshot).toList(),
                fromTurnSnapshot(snapshot.turn()),
                fromPendingDecisionSnapshot(snapshot.pendingDecision()),
                fromAuctionSnapshot(snapshot.auctionState()),
                fromDebtSnapshot(snapshot.activeDebt()),
                fromTradeSnapshot(snapshot.tradeState()),
                fromTurnContinuationSnapshot(snapshot.turnContinuation()),
                snapshot.winnerPlayerId()
        );
    }

    public void validate(SessionSnapshot snapshot) {
        if (snapshot.snapshotSchemaVersion() != SessionSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported snapshot schema version: " + snapshot.snapshotSchemaVersion());
        }
    }

    private SessionSnapshot.SeatSnapshot toSeatSnapshot(SeatState seatState) {
        return new SessionSnapshot.SeatSnapshot(
                seatState.seatId(),
                seatState.seatIndex(),
                seatState.playerId(),
                seatState.seatKind(),
                seatState.controlMode(),
                seatState.displayName(),
                seatState.controllerProfileId(),
                seatState.tokenColorHex()
        );
    }

    private SeatState fromSeatSnapshot(SessionSnapshot.SeatSnapshot seatSnapshot) {
        return new SeatState(
                seatSnapshot.seatId(),
                seatSnapshot.seatIndex(),
                seatSnapshot.playerId(),
                seatSnapshot.seatKind(),
                seatSnapshot.controlMode(),
                seatSnapshot.displayName(),
                seatSnapshot.controllerProfileId(),
                seatSnapshot.tokenColorHex()
        );
    }

    private SessionSnapshot.PlayerSnapshot toPlayerSnapshot(PlayerSnapshot playerSnapshot) {
        return new SessionSnapshot.PlayerSnapshot(
                playerSnapshot.playerId(),
                playerSnapshot.seatId(),
                playerSnapshot.name(),
                playerSnapshot.cash(),
                playerSnapshot.boardIndex(),
                playerSnapshot.bankrupt(),
                playerSnapshot.eliminated(),
                playerSnapshot.inJail(),
                playerSnapshot.jailRoundsRemaining(),
                playerSnapshot.getOutOfJailCards(),
                playerSnapshot.ownedPropertyIds()
        );
    }

    private PlayerSnapshot fromPlayerSnapshot(SessionSnapshot.PlayerSnapshot playerSnapshot) {
        return new PlayerSnapshot(
                playerSnapshot.playerId(),
                playerSnapshot.seatId(),
                playerSnapshot.name(),
                playerSnapshot.cash(),
                playerSnapshot.boardIndex(),
                playerSnapshot.bankrupt(),
                playerSnapshot.eliminated(),
                playerSnapshot.inJail(),
                playerSnapshot.jailRoundsRemaining(),
                playerSnapshot.getOutOfJailCards(),
                playerSnapshot.ownedPropertyIds()
        );
    }

    private SessionSnapshot.PropertySnapshot toPropertySnapshot(PropertyStateSnapshot propertyStateSnapshot) {
        return new SessionSnapshot.PropertySnapshot(
                propertyStateSnapshot.propertyId(),
                propertyStateSnapshot.ownerPlayerId(),
                propertyStateSnapshot.mortgaged(),
                propertyStateSnapshot.houseCount(),
                propertyStateSnapshot.hotelCount()
        );
    }

    private PropertyStateSnapshot fromPropertySnapshot(SessionSnapshot.PropertySnapshot propertySnapshot) {
        return new PropertyStateSnapshot(
                propertySnapshot.propertyId(),
                propertySnapshot.ownerPlayerId(),
                propertySnapshot.mortgaged(),
                propertySnapshot.houseCount(),
                propertySnapshot.hotelCount()
        );
    }

    private SessionSnapshot.TurnSnapshot toTurnSnapshot(TurnState turnState) {
        if (turnState == null) {
            return null;
        }
        return new SessionSnapshot.TurnSnapshot(
                turnState.activePlayerId(),
                turnState.phase(),
                turnState.canRoll(),
                turnState.canEndTurn()
        );
    }

    private TurnState fromTurnSnapshot(SessionSnapshot.TurnSnapshot turnSnapshot) {
        if (turnSnapshot == null) {
            return null;
        }
        return new TurnState(
                turnSnapshot.activePlayerId(),
                turnSnapshot.phase(),
                turnSnapshot.canRoll(),
                turnSnapshot.canEndTurn()
        );
    }

    private SessionSnapshot.PendingDecisionSnapshot toPendingDecisionSnapshot(PendingDecision pendingDecision) {
        if (pendingDecision == null) {
            return null;
        }
        return new SessionSnapshot.PendingDecisionSnapshot(
                pendingDecision.decisionId(),
                pendingDecision.decisionType(),
                pendingDecision.actorPlayerId(),
                pendingDecision.allowedActions(),
                pendingDecision.summaryText(),
                toPropertyPurchasePayloadSnapshot(pendingDecision.payload())
        );
    }

    private PendingDecision fromPendingDecisionSnapshot(SessionSnapshot.PendingDecisionSnapshot pendingDecisionSnapshot) {
        if (pendingDecisionSnapshot == null) {
            return null;
        }
        return new PendingDecision(
                pendingDecisionSnapshot.decisionId(),
                pendingDecisionSnapshot.decisionType(),
                pendingDecisionSnapshot.actorPlayerId(),
                pendingDecisionSnapshot.allowedActions(),
                pendingDecisionSnapshot.summaryText(),
                fromPropertyPurchasePayloadSnapshot(pendingDecisionSnapshot.propertyPurchasePayload())
        );
    }

    private SessionSnapshot.PropertyPurchasePayloadSnapshot toPropertyPurchasePayloadSnapshot(DecisionPayload payload) {
        if (payload == null) {
            return null;
        }
        if (!(payload instanceof PropertyPurchaseDecisionPayload propertyPurchaseDecisionPayload)) {
            throw new IllegalArgumentException("Unsupported pending decision payload type: " + payload.getClass().getName());
        }
        return new SessionSnapshot.PropertyPurchasePayloadSnapshot(
                propertyPurchaseDecisionPayload.propertyId(),
                propertyPurchaseDecisionPayload.propertyDisplayName(),
                propertyPurchaseDecisionPayload.price()
        );
    }

    private PropertyPurchaseDecisionPayload fromPropertyPurchasePayloadSnapshot(SessionSnapshot.PropertyPurchasePayloadSnapshot payloadSnapshot) {
        if (payloadSnapshot == null) {
            return null;
        }
        return new PropertyPurchaseDecisionPayload(
                payloadSnapshot.propertyId(),
                payloadSnapshot.propertyDisplayName(),
                payloadSnapshot.price()
        );
    }

    private SessionSnapshot.AuctionSnapshot toAuctionSnapshot(AuctionState auctionState) {
        if (auctionState == null) {
            return null;
        }
        return new SessionSnapshot.AuctionSnapshot(
                auctionState.auctionId(),
                auctionState.propertyId(),
                auctionState.triggeringPlayerId(),
                auctionState.currentActorPlayerId(),
                auctionState.leadingPlayerId(),
                auctionState.currentBid(),
                auctionState.minimumNextBid(),
                auctionState.passedPlayerIds(),
                auctionState.eligiblePlayerIds(),
                auctionState.status(),
                auctionState.winningBid(),
                auctionState.winningPlayerId()
        );
    }

    private AuctionState fromAuctionSnapshot(SessionSnapshot.AuctionSnapshot auctionSnapshot) {
        if (auctionSnapshot == null) {
            return null;
        }
        return new AuctionState(
                auctionSnapshot.auctionId(),
                auctionSnapshot.propertyId(),
                auctionSnapshot.triggeringPlayerId(),
                auctionSnapshot.currentActorPlayerId(),
                auctionSnapshot.leadingPlayerId(),
                auctionSnapshot.currentBid(),
                auctionSnapshot.minimumNextBid(),
                auctionSnapshot.passedPlayerIds(),
                auctionSnapshot.eligiblePlayerIds(),
                auctionSnapshot.status(),
                auctionSnapshot.winningBid(),
                auctionSnapshot.winningPlayerId()
        );
    }

    private SessionSnapshot.DebtSnapshot toDebtSnapshot(DebtStateModel debtStateModel) {
        if (debtStateModel == null) {
            return null;
        }
        return new SessionSnapshot.DebtSnapshot(
                debtStateModel.debtId(),
                debtStateModel.debtorPlayerId(),
                debtStateModel.creditorType(),
                debtStateModel.creditorPlayerId(),
                debtStateModel.amountRemaining(),
                debtStateModel.reason(),
                debtStateModel.bankruptcyRisk(),
                debtStateModel.currentCash(),
                debtStateModel.estimatedLiquidationValue(),
                debtStateModel.allowedActions()
        );
    }

    private DebtStateModel fromDebtSnapshot(SessionSnapshot.DebtSnapshot debtSnapshot) {
        if (debtSnapshot == null) {
            return null;
        }
        return new DebtStateModel(
                debtSnapshot.debtId(),
                debtSnapshot.debtorPlayerId(),
                debtSnapshot.creditorType(),
                debtSnapshot.creditorPlayerId(),
                debtSnapshot.amountRemaining(),
                debtSnapshot.reason(),
                debtSnapshot.bankruptcyRisk(),
                debtSnapshot.currentCash(),
                debtSnapshot.estimatedLiquidationValue(),
                debtSnapshot.allowedActions()
        );
    }

    private SessionSnapshot.TradeSnapshot toTradeSnapshot(TradeState tradeState) {
        if (tradeState == null) {
            return null;
        }
        return new SessionSnapshot.TradeSnapshot(
                tradeState.tradeId(),
                tradeState.initiatorPlayerId(),
                tradeState.recipientPlayerId(),
                tradeState.status(),
                toTradeOfferSnapshot(tradeState.currentOffer()),
                tradeState.editingPlayerId(),
                tradeState.editingOfferedSide(),
                tradeState.decisionRequiredFromPlayerId(),
                tradeState.openedByPlayerId(),
                tradeState.history().stream().map(this::toTradeHistoryEntrySnapshot).toList()
        );
    }

    private TradeState fromTradeSnapshot(SessionSnapshot.TradeSnapshot tradeSnapshot) {
        if (tradeSnapshot == null) {
            return null;
        }
        return new TradeState(
                tradeSnapshot.tradeId(),
                tradeSnapshot.initiatorPlayerId(),
                tradeSnapshot.recipientPlayerId(),
                tradeSnapshot.status(),
                fromTradeOfferSnapshot(tradeSnapshot.currentOffer()),
                tradeSnapshot.editingPlayerId(),
                tradeSnapshot.editingOfferedSide(),
                tradeSnapshot.decisionRequiredFromPlayerId(),
                tradeSnapshot.openedByPlayerId(),
                tradeSnapshot.history().stream().map(this::fromTradeHistoryEntrySnapshot).toList()
        );
    }

    private SessionSnapshot.TradeOfferSnapshot toTradeOfferSnapshot(TradeOfferState tradeOfferState) {
        if (tradeOfferState == null) {
            return null;
        }
        return new SessionSnapshot.TradeOfferSnapshot(
                tradeOfferState.proposerPlayerId(),
                tradeOfferState.recipientPlayerId(),
                toTradeSelectionSnapshot(tradeOfferState.offeredToRecipient()),
                toTradeSelectionSnapshot(tradeOfferState.requestedFromRecipient())
        );
    }

    private TradeOfferState fromTradeOfferSnapshot(SessionSnapshot.TradeOfferSnapshot tradeOfferSnapshot) {
        if (tradeOfferSnapshot == null) {
            return null;
        }
        return new TradeOfferState(
                tradeOfferSnapshot.proposerPlayerId(),
                tradeOfferSnapshot.recipientPlayerId(),
                fromTradeSelectionSnapshot(tradeOfferSnapshot.offeredToRecipient()),
                fromTradeSelectionSnapshot(tradeOfferSnapshot.requestedFromRecipient())
        );
    }

    private SessionSnapshot.TradeSelectionSnapshot toTradeSelectionSnapshot(TradeSelectionState tradeSelectionState) {
        if (tradeSelectionState == null) {
            return null;
        }
        return new SessionSnapshot.TradeSelectionSnapshot(
                tradeSelectionState.moneyAmount(),
                tradeSelectionState.propertyIds(),
                tradeSelectionState.jailCardCount()
        );
    }

    private TradeSelectionState fromTradeSelectionSnapshot(SessionSnapshot.TradeSelectionSnapshot tradeSelectionSnapshot) {
        if (tradeSelectionSnapshot == null) {
            return TradeSelectionState.NONE;
        }
        return new TradeSelectionState(
                tradeSelectionSnapshot.moneyAmount(),
                tradeSelectionSnapshot.propertyIds(),
                tradeSelectionSnapshot.jailCardCount()
        );
    }

    private SessionSnapshot.TradeHistoryEntrySnapshot toTradeHistoryEntrySnapshot(TradeHistoryEntry tradeHistoryEntry) {
        return new SessionSnapshot.TradeHistoryEntrySnapshot(
                tradeHistoryEntry.actorPlayerId(),
                tradeHistoryEntry.actionType(),
                tradeHistoryEntry.summary()
        );
    }

    private TradeHistoryEntry fromTradeHistoryEntrySnapshot(SessionSnapshot.TradeHistoryEntrySnapshot tradeHistoryEntrySnapshot) {
        return new TradeHistoryEntry(
                tradeHistoryEntrySnapshot.actorPlayerId(),
                tradeHistoryEntrySnapshot.actionType(),
                tradeHistoryEntrySnapshot.summary()
        );
    }

    private SessionSnapshot.TurnContinuationSnapshot toTurnContinuationSnapshot(TurnContinuationState turnContinuationState) {
        if (turnContinuationState == null) {
            return null;
        }
        return new SessionSnapshot.TurnContinuationSnapshot(
                turnContinuationState.continuationId(),
                turnContinuationState.activePlayerId(),
                turnContinuationState.continuationType(),
                turnContinuationState.completionAction(),
                turnContinuationState.propertyId(),
                turnContinuationState.reason()
        );
    }

    private TurnContinuationState fromTurnContinuationSnapshot(SessionSnapshot.TurnContinuationSnapshot turnContinuationSnapshot) {
        if (turnContinuationSnapshot == null) {
            return null;
        }
        return new TurnContinuationState(
                turnContinuationSnapshot.continuationId(),
                turnContinuationSnapshot.activePlayerId(),
                turnContinuationSnapshot.continuationType(),
                turnContinuationSnapshot.completionAction(),
                turnContinuationSnapshot.propertyId(),
                turnContinuationSnapshot.reason()
        );
    }
}
