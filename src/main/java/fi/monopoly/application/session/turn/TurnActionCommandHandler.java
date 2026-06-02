package fi.monopoly.application.session.turn;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.DomainEvent;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class TurnActionCommandHandler {
    private final String sessionId;
    private final Supplier<SessionState> currentStateSupplier;
    private final TurnActionGateway gateway;

    public boolean supports(SessionCommand command) {
        return command instanceof RollDiceCommand
                || command instanceof EndTurnCommand
                || command instanceof BuyBuildingRoundCommand
                || command instanceof SellBuildingRoundCommand
                || command instanceof ToggleMortgageCommand
                || command instanceof UseGetOutOfJailCardCommand
                || command instanceof PayJailFineCommand
                || command instanceof AcknowledgeCardCommand;
    }

    public CommandResult handle(SessionCommand command) {
        if (command instanceof RollDiceCommand rollDiceCommand) {
            return handleRollDice(rollDiceCommand);
        }
        if (command instanceof EndTurnCommand endTurnCommand) {
            return handleEndTurn(endTurnCommand);
        }
        if (command instanceof BuyBuildingRoundCommand buyBuildingRoundCommand) {
            return handleBuyBuildingRound(buyBuildingRoundCommand);
        }
        if (command instanceof SellBuildingRoundCommand sellBuildingRoundCommand) {
            return handleSellBuildingRound(sellBuildingRoundCommand);
        }
        if (command instanceof ToggleMortgageCommand toggleMortgageCommand) {
            return handleToggleMortgage(toggleMortgageCommand);
        }
        if (command instanceof UseGetOutOfJailCardCommand useCardCommand) {
            return handleUseGetOutOfJailCard(useCardCommand);
        }
        if (command instanceof PayJailFineCommand payFineCommand) {
            return handlePayJailFine(payFineCommand);
        }
        if (command instanceof AcknowledgeCardCommand ackCommand) {
            return handleAcknowledgeCard(ackCommand);
        }
        return rejected("UNSUPPORTED_TURN_ACTION", "Turn action command is not supported");
    }

    private CommandResult handleRollDice(RollDiceCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can roll dice");
        }
        TurnState turn = currentStateSupplier.get().turn();
        if (isTurnActionBlocked(turn.phase())) {
            return rejected("ROLL_NOT_ALLOWED", "Dice can only be rolled during the rolling phase");
        }
        if (!turn.canRoll()) {
            return rejected("ROLL_NOT_ALLOWED", "Dice roll is not allowed at this point");
        }
        return gateway.rollDice()
                ? accepted("DiceRolled", command.actorPlayerId())
                : rejected("ROLL_FAILED", "Dice roll could not be started");
    }

    private CommandResult handleEndTurn(EndTurnCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can end the turn");
        }
        TurnState turn = currentStateSupplier.get().turn();
        if (isTurnActionBlocked(turn.phase()) || turn.phase() == TurnPhase.WAITING_FOR_ROLL) {
            return rejected("END_TURN_NOT_ALLOWED", "Turn can only end during the end-turn phase");
        }
        if (!turn.canEndTurn()) {
            return rejected("END_TURN_NOT_ALLOWED", "Turn cannot be ended at this point");
        }
        return gateway.endTurn()
                ? accepted("TurnEnded", command.actorPlayerId())
                : rejected("END_TURN_FAILED", "Turn could not be ended");
    }

    private CommandResult handleBuyBuildingRound(BuyBuildingRoundCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can buy buildings");
        }
        if (isTurnActionBlocked(currentStateSupplier.get().turn().phase())) {
            return rejected("BUILD_ROUND_FAILED", "Buildings cannot be bought in the current phase");
        }
        if (!gateway.buyBuildingRound(command.propertyId())) {
            return rejected("BUILD_ROUND_FAILED", "Building round purchase failed");
        }
        return accepted("BuildingRoundBought", command.propertyId());
    }

    private CommandResult handleSellBuildingRound(SellBuildingRoundCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can sell buildings");
        }
        if (isTurnActionBlocked(currentStateSupplier.get().turn().phase())) {
            return rejected("SELL_ROUND_FAILED", "Buildings cannot be sold in the current phase");
        }
        if (!gateway.sellBuildingRound(command.propertyId())) {
            return rejected("SELL_ROUND_FAILED", "Building round sale failed");
        }
        return accepted("BuildingRoundSold", command.propertyId());
    }

    private CommandResult handleToggleMortgage(ToggleMortgageCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can change mortgages");
        }
        TurnPhase phase = currentStateSupplier.get().turn().phase();
        if (isTurnActionBlocked(phase) || phase == TurnPhase.WAITING_FOR_ROLL) {
            return rejected("MORTGAGE_TOGGLE_FAILED", "Mortgage cannot be changed in the current phase");
        }
        // During a purchase decision the player may only MORTGAGE (not unmortgage) to raise cash.
        if (phase == TurnPhase.WAITING_FOR_DECISION) {
            SessionState earlyState = currentStateSupplier.get();
            PropertyStateSnapshot earlyProp = findPropertyOwnedBy(earlyState, command.propertyId(), command.actorPlayerId());
            if (earlyProp != null && earlyProp.mortgaged()) {
                return rejected("MORTGAGE_TOGGLE_FAILED", "Cannot unmortgage during a purchase decision");
            }
        }
        SessionState state = currentStateSupplier.get();
        PropertyStateSnapshot property = findPropertyOwnedBy(state, command.propertyId(), command.actorPlayerId());
        if (property == null) {
            return rejected("PROPERTY_NOT_OWNED", "Active player does not own the property");
        }
        if (property.mortgaged()) {
            PlayerSnapshot player = findPlayer(state, command.actorPlayerId());
            if (player != null) {
                int unmortgageCost = unmortgageCost(command.propertyId());
                if (player.cash() < unmortgageCost) {
                    return rejected("INSUFFICIENT_FUNDS",
                            "Not enough cash to unmortgage: need " + unmortgageCost + ", have " + player.cash());
                }
            }
        } else {
            // Can only mortgage a street if no buildings exist anywhere in the color group
            SpotType st = SpotType.valueOf(command.propertyId());
            if (st.streetType.placeType == PlaceType.STREET) {
                boolean groupHasBuildings = state.properties().stream()
                        .filter(p -> SpotType.valueOf(p.propertyId()).streetType == st.streetType)
                        .anyMatch(p -> p.houseCount() > 0 || p.hotelCount() > 0);
                if (groupHasBuildings) {
                    return rejected("BUILDINGS_PRESENT",
                            "Cannot mortgage a street property while the color group has buildings");
                }
            }
        }
        if (!gateway.toggleMortgage(command.propertyId())) {
            return rejected("MORTGAGE_TOGGLE_FAILED", "Mortgage action failed");
        }
        return accepted("MortgageToggled", command.propertyId());
    }

    private CommandResult handleUseGetOutOfJailCard(UseGetOutOfJailCardCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can use a get-out-of-jail card");
        }
        SessionState state = currentStateSupplier.get();
        if (state.turn().phase() != TurnPhase.WAITING_FOR_ROLL) {
            return rejected("CARD_NOT_ALLOWED", "Card can only be used during the rolling phase");
        }
        PlayerSnapshot player = findPlayer(state, command.actorPlayerId());
        if (player == null || !player.inJail()) {
            return rejected("NOT_IN_JAIL", "Player is not in jail");
        }
        if (player.getOutOfJailCards() <= 0) {
            return rejected("NO_CARD", "Player has no get-out-of-jail cards");
        }
        return gateway.useGetOutOfJailCard()
                ? accepted("GetOutOfJailCardUsed", command.actorPlayerId())
                : rejected("CARD_USE_FAILED", "Get-out-of-jail card could not be used");
    }

    private CommandResult handlePayJailFine(PayJailFineCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can pay the jail fine");
        }
        SessionState state = currentStateSupplier.get();
        if (state.turn().phase() != TurnPhase.WAITING_FOR_ROLL) {
            return rejected("FINE_NOT_ALLOWED", "Jail fine can only be paid during the rolling phase");
        }
        PlayerSnapshot player = findPlayer(state, command.actorPlayerId());
        if (player == null || !player.inJail()) {
            return rejected("NOT_IN_JAIL", "Player is not in jail");
        }
        if (player.cash() < 50) {
            return rejected("INSUFFICIENT_FUNDS", "Not enough cash to pay the jail fine (need €50)");
        }
        return gateway.payJailFine()
                ? accepted("JailFinePaid", command.actorPlayerId())
                : rejected("FINE_PAYMENT_FAILED", "Jail fine payment failed");
    }

    private static PropertyStateSnapshot findPropertyOwnedBy(SessionState state, String propertyId, String actorPlayerId) {
        return state.properties().stream()
                .filter(p -> propertyId.equals(p.propertyId()) && actorPlayerId.equals(p.ownerPlayerId()))
                .findFirst()
                .orElse(null);
    }

    private static PlayerSnapshot findPlayer(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> playerId.equals(p.playerId()))
                .findFirst()
                .orElse(null);
    }

    private static int unmortgageCost(String propertyId) {
        try {
            int price = SpotType.valueOf(propertyId).getIntegerProperty("price");
            int mortgageValue = price / 2;
            return mortgageValue + (int) (mortgageValue * 0.1);
        } catch (IllegalArgumentException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean isCurrentActor(String commandSessionId, String actorPlayerId) {
        SessionState state = currentStateSupplier.get();
        return Objects.equals(sessionId, commandSessionId)
                && Objects.equals(state.turn().activePlayerId(), actorPlayerId);
    }

    private CommandResult handleAcknowledgeCard(AcknowledgeCardCommand command) {
        if (!isCurrentActor(command.sessionId(), command.actorPlayerId())) {
            return rejected("WRONG_TURN_ACTOR", "Only the active player can acknowledge the card");
        }
        SessionState state = currentStateSupplier.get();
        if (state.turn().phase() != TurnPhase.WAITING_FOR_CARD_ACK) {
            return rejected("ACK_NOT_ALLOWED", "Card acknowledgement is only valid during WAITING_FOR_CARD_ACK phase");
        }
        return gateway.acknowledgeCard()
                ? accepted("CardAcknowledged", command.actorPlayerId())
                : rejected("ACK_FAILED", "Card acknowledgement failed");
    }

    private boolean isTurnActionBlocked(TurnPhase phase) {
        return phase == TurnPhase.WAITING_FOR_CARD_ACK
                || phase == TurnPhase.WAITING_FOR_AUCTION
                || phase == TurnPhase.RESOLVING_DEBT
                || phase == TurnPhase.GAME_OVER;
    }

    private CommandResult accepted(String eventType, String detail) {
        return new CommandResult(true, currentStateSupplier.get(), List.of(new DomainEvent(eventType, detail, null)), List.of(), List.of());
    }

    private CommandResult rejected(String code, String message) {
        return new CommandResult(false, currentStateSupplier.get(), List.of(), List.of(new CommandRejection(code, message)), List.of());
    }
}
