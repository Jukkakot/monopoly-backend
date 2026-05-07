package fi.monopoly.application.session.auction;

import fi.monopoly.application.command.FinishAuctionResolutionCommand;
import fi.monopoly.application.command.PassAuctionCommand;
import fi.monopoly.application.command.PlaceAuctionBidCommand;
import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.result.DomainEvent;
import fi.monopoly.domain.session.AuctionState;
import fi.monopoly.domain.session.AuctionStatus;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.TurnContinuationState;
import fi.monopoly.types.SpotType;
import fi.monopoly.utils.MonopolyUtils;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class AuctionCommandHandler {
    private final String sessionId;
    private final Supplier<SessionState> currentStateSupplier;
    private final Consumer<AuctionState> auctionStateSetter;
    private final Consumer<TurnContinuationState> turnContinuationSetter;
    private final Consumer<TurnContinuationState> turnContinuationResolver;
    private final AuctionGateway gateway;
    private ActiveAuctionContext activeContext;

    public AuctionState startAuction(
            String triggeringPlayerId,
            String propertyId,
            String propertyDisplayName,
            TurnContinuationState continuationState
    ) {
        List<String> eligibleBidderIds = gateway.eligibleBidderIds(triggeringPlayerId, propertyId);
        if (eligibleBidderIds.isEmpty()) {
            activeContext = null;
            auctionStateSetter.accept(null);
            return null;
        }
        AuctionState state = new AuctionState(
                "auction:" + propertyId + ":" + (triggeringPlayerId == null ? "bank" : triggeringPlayerId),
                propertyId,
                triggeringPlayerId,
                eligibleBidderIds.get(0),
                null,
                0,
                AuctionGateway.OPENING_BID,
                Set.of(),
                eligibleBidderIds,
                AuctionStatus.ACTIVE,
                0,
                null
        );
        activeContext = new ActiveAuctionContext(state.auctionId(), propertyDisplayName, continuationState);
        turnContinuationSetter.accept(continuationState);
        auctionStateSetter.accept(state);
        return state;
    }

    public boolean supports(SessionCommand command) {
        return command instanceof PlaceAuctionBidCommand
                || command instanceof PassAuctionCommand
                || command instanceof FinishAuctionResolutionCommand;
    }

    public CommandResult handle(SessionCommand command) {
        if (command instanceof PlaceAuctionBidCommand placeAuctionBidCommand) {
            return handleBid(placeAuctionBidCommand);
        }
        if (command instanceof PassAuctionCommand passAuctionCommand) {
            return handlePass(passAuctionCommand);
        }
        if (command instanceof FinishAuctionResolutionCommand finishAuctionResolutionCommand) {
            return handleFinish(finishAuctionResolutionCommand);
        }
        return reject("UNSUPPORTED_AUCTION_COMMAND", "Unsupported auction command");
    }

    public CommandResult handleComputerAction(String actorPlayerId) {
        AuctionState state = currentStateSupplier.get().auctionState();
        if (state == null || state.status() != AuctionStatus.ACTIVE) {
            return reject("NO_ACTIVE_AUCTION", "There is no active auction to resolve");
        }
        if (!Objects.equals(state.currentActorPlayerId(), actorPlayerId)) {
            return reject("WRONG_AUCTION_ACTOR", "Computer player is not the current auction actor");
        }
        int maxBid = gateway.maxBidFor(actorPlayerId, state.propertyId());
        if (maxBid < state.minimumNextBid()) {
            return handle(new PassAuctionCommand(sessionId, actorPlayerId, state.auctionId()));
        }
        int amount = gateway.nextBidAmount(actorPlayerId, state.propertyId(), state.currentBid());
        return handle(new PlaceAuctionBidCommand(sessionId, actorPlayerId, state.auctionId(), amount));
    }

    private CommandResult handleBid(PlaceAuctionBidCommand command) {
        AuctionState state = validateActiveAuction(command.sessionId(), command.auctionId());
        if (state == null) {
            return reject("INVALID_AUCTION", "Auction bid command is not valid in the current state");
        }
        if (state.status() != AuctionStatus.ACTIVE) {
            return reject("AUCTION_NOT_ACTIVE", "Auction is not accepting bids");
        }
        if (!Objects.equals(state.currentActorPlayerId(), command.actorPlayerId())) {
            return reject("WRONG_AUCTION_ACTOR", "Only the current auction actor can bid");
        }
        if (state.passedPlayerIds().contains(command.actorPlayerId())) {
            return reject("ACTOR_ALREADY_PASSED", "Passed players cannot re-enter the auction");
        }
        if (command.amount() < state.minimumNextBid()) {
            return reject("BID_TOO_LOW", "Bid is below the minimum next bid");
        }
        int maxBid = gateway.maxBidFor(command.actorPlayerId(), state.propertyId());
        if (command.amount() > maxBid) {
            return reject("BID_TOO_HIGH", "Bid exceeds what the player can currently afford");
        }

        AuctionState updated = new AuctionState(
                state.auctionId(),
                state.propertyId(),
                state.triggeringPlayerId(),
                nextEligibleActor(state.eligiblePlayerIds(), state.passedPlayerIds(), command.actorPlayerId()),
                command.actorPlayerId(),
                command.amount(),
                command.amount() + AuctionGateway.BID_INCREMENT,
                state.passedPlayerIds(),
                state.eligiblePlayerIds(),
                AuctionStatus.ACTIVE,
                0,
                null
        );
        if (remainingActiveBidderIds(updated).size() <= 1) {
            updated = new AuctionState(
                    updated.auctionId(),
                    updated.propertyId(),
                    updated.triggeringPlayerId(),
                    null,
                    updated.leadingPlayerId(),
                    updated.currentBid(),
                    updated.minimumNextBid(),
                    updated.passedPlayerIds(),
                    updated.eligiblePlayerIds(),
                    AuctionStatus.WON_PENDING_RESOLUTION,
                    updated.currentBid(),
                    updated.leadingPlayerId()
            );
        }
        auctionStateSetter.accept(updated);
        List<DomainEvent> events = new ArrayList<>();
        events.add(new DomainEvent("AuctionBidPlaced", command.actorPlayerId(), "M" + command.amount()));
        if (updated.status() == AuctionStatus.WON_PENDING_RESOLUTION) {
            events.add(new DomainEvent("AuctionWon", updated.winningPlayerId(), activePropertyDisplayName(state)));
        }
        return accepted(events);
    }

    private CommandResult handlePass(PassAuctionCommand command) {
        AuctionState state = validateActiveAuction(command.sessionId(), command.auctionId());
        if (state == null) {
            return reject("INVALID_AUCTION", "Auction pass command is not valid in the current state");
        }
        if (state.status() != AuctionStatus.ACTIVE) {
            return reject("AUCTION_NOT_ACTIVE", "Auction is not accepting passes");
        }
        if (!Objects.equals(state.currentActorPlayerId(), command.actorPlayerId())) {
            return reject("WRONG_AUCTION_ACTOR", "Only the current auction actor can pass");
        }
        if (state.passedPlayerIds().contains(command.actorPlayerId())) {
            return reject("ACTOR_ALREADY_PASSED", "Player has already passed this auction");
        }

        String displayName = activePropertyDisplayName(state);
        Set<String> passedPlayerIds = new LinkedHashSet<>(state.passedPlayerIds());
        passedPlayerIds.add(command.actorPlayerId());

        List<String> remainingActive = remainingActiveBidderIds(state.eligiblePlayerIds(), passedPlayerIds);
        if (remainingActive.isEmpty() && state.leadingPlayerId() == null) {
            ActiveAuctionContext resolvedContext = activeContext;
            activeContext = null;
            auctionStateSetter.accept(null);
            turnContinuationSetter.accept(null);
            turnContinuationResolver.accept(resolvedContext.continuationState());
            return new CommandResult(
                    true,
                    currentStateSupplier.get(),
                    List.of(
                            new DomainEvent("AuctionPassed", command.actorPlayerId(), displayName),
                            new DomainEvent("AuctionEndedWithoutWinner", command.actorPlayerId(), displayName)
                    ),
                    List.of(),
                    List.of()
            );
        }

        AuctionState updated;
        if (state.leadingPlayerId() != null && remainingActive.size() <= 1) {
            updated = new AuctionState(
                    state.auctionId(),
                    state.propertyId(),
                    state.triggeringPlayerId(),
                    null,
                    state.leadingPlayerId(),
                    state.currentBid(),
                    state.minimumNextBid(),
                    passedPlayerIds,
                    state.eligiblePlayerIds(),
                    AuctionStatus.WON_PENDING_RESOLUTION,
                    state.currentBid(),
                    state.leadingPlayerId()
            );
        } else {
            updated = new AuctionState(
                    state.auctionId(),
                    state.propertyId(),
                    state.triggeringPlayerId(),
                    nextEligibleActor(state.eligiblePlayerIds(), passedPlayerIds, command.actorPlayerId()),
                    state.leadingPlayerId(),
                    state.currentBid(),
                    state.minimumNextBid(),
                    passedPlayerIds,
                    state.eligiblePlayerIds(),
                    AuctionStatus.ACTIVE,
                    0,
                    null
            );
        }
        auctionStateSetter.accept(updated);
        List<DomainEvent> events = new ArrayList<>();
        events.add(new DomainEvent("AuctionPassed", command.actorPlayerId(), displayName));
        if (updated.status() == AuctionStatus.WON_PENDING_RESOLUTION) {
            events.add(new DomainEvent("AuctionWon", updated.winningPlayerId(), displayName));
        }
        return accepted(events);
    }

    private CommandResult handleFinish(FinishAuctionResolutionCommand command) {
        AuctionState state = validateActiveAuction(command.sessionId(), command.auctionId());
        if (state == null) {
            return reject("INVALID_AUCTION", "Auction resolution command is not valid in the current state");
        }
        if (state.status() != AuctionStatus.WON_PENDING_RESOLUTION) {
            return reject("AUCTION_NOT_READY", "Auction is not waiting for final resolution");
        }
        if (!gateway.transferWinningProperty(state.winningPlayerId(), state.propertyId(), state.winningBid())) {
            return reject("AUCTION_TRANSFER_FAILED", "Winning property transfer failed");
        }
        ActiveAuctionContext resolvedContext = activeContext;
        activeContext = null;
        auctionStateSetter.accept(null);
        turnContinuationSetter.accept(null);
        turnContinuationResolver.accept(resolvedContext.continuationState());
        return new CommandResult(
                true,
                currentStateSupplier.get(),
                List.of(new DomainEvent("AuctionResolved", state.winningPlayerId(), activePropertyDisplayName(state))),
                List.of(),
                List.of()
        );
    }

    private AuctionState validateActiveAuction(String commandSessionId, String auctionId) {
        if (!Objects.equals(sessionId, commandSessionId)) {
            return null;
        }
        SessionState currentState = currentStateSupplier.get();
        AuctionState state = currentState.auctionState();
        if (state == null) {
            return null;
        }
        if (activeContext == null) {
            activeContext = restoreContextFrom(currentState, state);
        }
        if (activeContext == null
                || !Objects.equals(state.auctionId(), auctionId)
                || !Objects.equals(activeContext.auctionId(), auctionId)) {
            return null;
        }
        return state;
    }

    private ActiveAuctionContext restoreContextFrom(SessionState currentState, AuctionState state) {
        return new ActiveAuctionContext(
                state.auctionId(),
                resolveDisplayName(state.propertyId()),
                currentState.turnContinuationState()
        );
    }

    private String activePropertyDisplayName(AuctionState state) {
        if (activeContext != null) {
            return activeContext.propertyDisplayName();
        }
        return resolveDisplayName(state.propertyId());
    }

    private static String resolveDisplayName(String propertyId) {
        try {
            SpotType spotType = SpotType.valueOf(propertyId);
            String name = spotType.getStringProperty("name");
            return name.isBlank() ? propertyId : MonopolyUtils.parseIllegalCharacters(name);
        } catch (IllegalArgumentException ignored) {
            return propertyId;
        }
    }

    private List<String> remainingActiveBidderIds(AuctionState state) {
        return remainingActiveBidderIds(state.eligiblePlayerIds(), state.passedPlayerIds());
    }

    private List<String> remainingActiveBidderIds(List<String> eligiblePlayerIds, Set<String> passedPlayerIds) {
        List<String> remaining = new ArrayList<>();
        for (String playerId : eligiblePlayerIds) {
            if (!passedPlayerIds.contains(playerId)) {
                remaining.add(playerId);
            }
        }
        return remaining;
    }

    private String nextEligibleActor(List<String> eligiblePlayerIds, Set<String> passedPlayerIds, String currentActorPlayerId) {
        if (eligiblePlayerIds.isEmpty()) {
            return null;
        }
        int currentIndex = eligiblePlayerIds.indexOf(currentActorPlayerId);
        for (int offset = 1; offset <= eligiblePlayerIds.size(); offset++) {
            String candidate = eligiblePlayerIds.get((currentIndex + offset) % eligiblePlayerIds.size());
            if (!passedPlayerIds.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private CommandResult accepted(List<DomainEvent> events) {
        return new CommandResult(true, currentStateSupplier.get(), events, List.of(), List.of());
    }

    private CommandResult reject(String code, String message) {
        return new CommandResult(false, currentStateSupplier.get(), List.of(), List.of(new CommandRejection(code, message)), List.of());
    }

    private record ActiveAuctionContext(
            String auctionId,
            String propertyDisplayName,
            TurnContinuationState continuationState
    ) {}
}
