package fi.monopoly.infrastructure.persistence.session;

import fi.monopoly.domain.decision.DecisionAction;
import fi.monopoly.domain.decision.DecisionType;
import fi.monopoly.domain.session.AuctionStatus;
import fi.monopoly.domain.session.ControlMode;
import fi.monopoly.domain.session.DebtAction;
import fi.monopoly.domain.session.DebtCreditorType;
import fi.monopoly.domain.session.SeatKind;
import fi.monopoly.domain.session.SessionStatus;
import fi.monopoly.domain.session.TurnContinuationAction;
import fi.monopoly.domain.session.TurnContinuationType;
import fi.monopoly.domain.session.TradeStatus;
import fi.monopoly.domain.turn.TurnPhase;

import java.util.List;
import java.util.Set;

public record SessionSnapshot(
        int snapshotSchemaVersion,
        String sessionId,
        long version,
        SessionStatus status,
        List<SeatSnapshot> seats,
        List<PlayerSnapshot> players,
        List<PropertySnapshot> properties,
        TurnSnapshot turn,
        PendingDecisionSnapshot pendingDecision,
        AuctionSnapshot auctionState,
        DebtSnapshot activeDebt,
        TradeSnapshot tradeState,
        TurnContinuationSnapshot turnContinuation,
        String winnerPlayerId
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public SessionSnapshot {
        seats = List.copyOf(seats == null ? List.of() : seats);
        players = List.copyOf(players == null ? List.of() : players);
        properties = List.copyOf(properties == null ? List.of() : properties);
    }

    public record SeatSnapshot(
            String seatId,
            int seatIndex,
            String playerId,
            SeatKind seatKind,
            ControlMode controlMode,
            String displayName,
            String controllerProfileId,
            String tokenColorHex
    ) {
    }

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
            ownedPropertyIds = List.copyOf(ownedPropertyIds == null ? List.of() : ownedPropertyIds);
        }
    }

    public record PropertySnapshot(
            String propertyId,
            String ownerPlayerId,
            boolean mortgaged,
            int houseCount,
            int hotelCount
    ) {
    }

    public record TurnSnapshot(
            String activePlayerId,
            TurnPhase phase,
            boolean canRoll,
            boolean canEndTurn
    ) {
    }

    public record PendingDecisionSnapshot(
            String decisionId,
            DecisionType decisionType,
            String actorPlayerId,
            List<DecisionAction> allowedActions,
            String summaryText,
            PropertyPurchasePayloadSnapshot propertyPurchasePayload
    ) {
        public PendingDecisionSnapshot {
            allowedActions = List.copyOf(allowedActions == null ? List.of() : allowedActions);
        }
    }

    public record PropertyPurchasePayloadSnapshot(
            String propertyId,
            String propertyDisplayName,
            int price
    ) {
    }

    public record AuctionSnapshot(
            String auctionId,
            String propertyId,
            String triggeringPlayerId,
            String currentActorPlayerId,
            String leadingPlayerId,
            int currentBid,
            int minimumNextBid,
            Set<String> passedPlayerIds,
            List<String> eligiblePlayerIds,
            AuctionStatus status,
            int winningBid,
            String winningPlayerId
    ) {
        public AuctionSnapshot {
            passedPlayerIds = Set.copyOf(passedPlayerIds == null ? Set.of() : passedPlayerIds);
            eligiblePlayerIds = List.copyOf(eligiblePlayerIds == null ? List.of() : eligiblePlayerIds);
        }
    }

    public record DebtSnapshot(
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
        public DebtSnapshot {
            allowedActions = List.copyOf(allowedActions == null ? List.of() : allowedActions);
        }
    }

    public record TradeSnapshot(
            String tradeId,
            String initiatorPlayerId,
            String recipientPlayerId,
            TradeStatus status,
            TradeOfferSnapshot currentOffer,
            String editingPlayerId,
            boolean editingOfferedSide,
            String decisionRequiredFromPlayerId,
            String openedByPlayerId,
            List<TradeHistoryEntrySnapshot> history
    ) {
        public TradeSnapshot {
            history = List.copyOf(history == null ? List.of() : history);
        }
    }

    public record TradeOfferSnapshot(
            String proposerPlayerId,
            String recipientPlayerId,
            TradeSelectionSnapshot offeredToRecipient,
            TradeSelectionSnapshot requestedFromRecipient
    ) {
    }

    public record TradeSelectionSnapshot(
            int moneyAmount,
            List<String> propertyIds,
            int jailCardCount
    ) {
        public TradeSelectionSnapshot {
            propertyIds = List.copyOf(propertyIds == null ? List.of() : propertyIds);
        }
    }

    public record TradeHistoryEntrySnapshot(
            String actorPlayerId,
            String actionType,
            String summary
    ) {
    }

    public record TurnContinuationSnapshot(
            String continuationId,
            String activePlayerId,
            TurnContinuationType continuationType,
            TurnContinuationAction completionAction,
            String propertyId,
            String reason
    ) {
    }
}
