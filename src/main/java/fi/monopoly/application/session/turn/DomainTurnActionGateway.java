package fi.monopoly.application.session.turn;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.application.session.purchase.PropertyPurchaseFlow;
import fi.monopoly.domain.session.*;
import static fi.monopoly.domain.session.GameEventHelper.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.CardType;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;
import lombok.extern.slf4j.Slf4j;

import fi.monopoly.utils.RandomSource;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure domain implementation of {@link TurnActionGateway} — no Processing runtime objects.
 *
 * <p>Handles the full dice-roll-and-move cycle, landing-spot effects, end-turn, building
 * purchases, and mortgage toggling. All changes are persisted via {@link SessionStateStore}.</p>
 *
 * <h2>Landing effects implemented</h2>
 * <ul>
 *   <li>Movement + GO bonus (€200)</li>
 *   <li>GO_TO_JAIL — player is jailed immediately</li>
 *   <li>Unowned property — property purchase decision opened via {@link PropertyPurchaseFlow}</li>
 *   <li>Owned property — rent paid immediately if cash allows; otherwise {@link DebtStateModel}
 *       is opened and turn phase moves to {@code RESOLVING_DEBT}</li>
 *   <li>Mortgaged property — no rent, player continues</li>
 *   <li>Tax spots — direct deduction or debt if insufficient cash</li>
 *   <li>Chance/Community cards — full deck with MONEY, MOVE, MOVE_NEAREST, MOVE_BACK_3,
 *       GO_JAIL, OUT_OF_JAIL, REPAIR_PROPERTIES, and ALL_PLAYERS_MONEY card types</li>
 *   <li>Doubles — player re-rolls; third consecutive doubles → jail</li>
 *   <li>In-jail turn — doubles escape jail; last forced round deducts €50 fine</li>
 * </ul>
 */
@Slf4j
public final class DomainTurnActionGateway implements TurnActionGateway {

    private static final int BOARD_SIZE = SpotType.SPOT_TYPES.size(); // 40
    private static final int JAIL_INDEX = SpotType.SPOT_TYPES.indexOf(SpotType.JAIL); // 10
    private static final int MAX_CONSECUTIVE_DOUBLES = 3;
    private static final int GO_REWARD = 200;
    private static final int GET_OUT_OF_JAIL_FEE = 50;
    private static final int INITIAL_JAIL_ROUNDS = 3;
    static final int BANK_HOUSE_SUPPLY = 32;
    static final int BANK_HOTEL_SUPPLY = 12;

    private final SessionStateStore store;
    private final PropertyPurchaseFlow propertyPurchaseFlow;
    private final RandomSource randomSource;
    private TurnContinuationState pendingPostPurchaseContinuation;

    public DomainTurnActionGateway(SessionStateStore store, PropertyPurchaseFlow propertyPurchaseFlow) {
        this(store, propertyPurchaseFlow, RandomSource.threadLocal());
    }

    public DomainTurnActionGateway(SessionStateStore store, PropertyPurchaseFlow propertyPurchaseFlow, RandomSource randomSource) {
        this.store = store;
        this.propertyPurchaseFlow = propertyPurchaseFlow;
        this.randomSource = randomSource;
    }

    // -------------------------------------------------------------------------
    // TurnActionGateway
    // -------------------------------------------------------------------------

    @Override
    public boolean rollDice() {
        SessionState state = store.get();
        String activePlayerId = state.turn().activePlayerId();
        if (activePlayerId == null) return false;

        PlayerSnapshot activePlayer = findPlayer(state, activePlayerId);
        if (activePlayer == null) return false;

        int[] diceOverride = state.nextDiceOverride();
        int die1 = (diceOverride != null && diceOverride.length >= 2)
                ? Math.max(1, Math.min(6, diceOverride[0])) : 1 + randomSource.nextInt(6);
        int die2 = (diceOverride != null && diceOverride.length >= 2)
                ? Math.max(1, Math.min(6, diceOverride[1])) : 1 + randomSource.nextInt(6);
        int total = die1 + die2;
        boolean isDoubles = die1 == die2;
        int newConsecutive = isDoubles ? state.turn().consecutiveDoubles() + 1 : 0;

        log.debug("rollDice player={} die1={} die2={} total={} doubles={} consecutive={}",
                activePlayer.name(), die1, die2, total, isDoubles, newConsecutive);

        final String d1s = String.valueOf(die1), d2s = String.valueOf(die2);
        final String fromIdxStr = String.valueOf(activePlayer.boardIndex());

        // Third consecutive doubles → jail without collecting GO
        if (newConsecutive >= MAX_CONSECUTIVE_DOUBLES) {
            log.info("Player {} sent to jail for {} consecutive doubles", activePlayer.name(), newConsecutive);
            // Snapshot 1: show the dice (player stays in place)
            store.update(s -> appendEvents(
                    s.toBuilder().nextDiceOverride(null).turn(withDice(withConsecutive(s.turn(), newConsecutive), die1, die2)).build(),
                    ev("DICE_ROLLED", activePlayerId, Map.of("d1", d1s, "d2", d2s))));
            // Snapshot 2: send to jail
            store.update(s -> appendEvents(
                    applyGoToJail(s, activePlayerId),
                    ev("WENT_TO_JAIL", activePlayerId, Map.of("from", fromIdxStr))));
            return true;
        }

        // In-jail: special handling
        if (activePlayer.inJail()) {
            handleInJailRoll(activePlayer, die1, die2, total, isDoubles);
            return true;
        }

        // Normal movement
        int rawNew = activePlayer.boardIndex() + total;
        int newIndex = rawNew % BOARD_SIZE;
        boolean passedGo = rawNew >= BOARD_SIZE;
        SpotType landedSpot = SpotType.SPOT_TYPES.get(newIndex);
        final String toIdxStr = String.valueOf(newIndex);

        if (landedSpot == SpotType.GO_TO_JAIL) {
            log.info("Player {} landed on Go To Jail", activePlayer.name());
            // Snapshot 1: player arrives at spot 30
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .nextDiceOverride(null)
                            .players(updatePosition(s.players(), activePlayerId, newIndex, 0))
                            .turn(withDice(s.turn(), die1, die2))
                            .build(),
                    ev("DICE_ROLLED", activePlayerId, Map.of("d1", d1s, "d2", d2s)),
                    ev("PLAYER_MOVED", activePlayerId, Map.of("from", fromIdxStr, "to", toIdxStr))));
            // Snapshot 2: player is jailed
            store.update(s -> appendEvents(applyGoToJail(s, activePlayerId),
                    ev("WENT_TO_JAIL", activePlayerId, Map.of("from", toIdxStr))));
            return true;
        }

        // Normal movement — snapshot 1: move player (no GO bonus yet)
        final int newConsecutiveFinal = newConsecutive;
        store.update(s -> appendEvents(
                s.toBuilder()
                        .nextDiceOverride(null)
                        .players(updatePosition(s.players(), activePlayerId, newIndex, 0))
                        .turn(withDice(withConsecutive(s.turn(), newConsecutiveFinal), die1, die2))
                        .build(),
                ev("DICE_ROLLED", activePlayerId, Map.of("d1", d1s, "d2", d2s)),
                ev("PLAYER_MOVED", activePlayerId, Map.of("from", fromIdxStr, "to", toIdxStr))));
        // Snapshot 2: GO bonus (separate so frontend can show €200 after animation)
        if (passedGo) {
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(updateCash(s.players(), activePlayerId, GO_REWARD))
                            .build(),
                    ev("PASSED_GO", activePlayerId)));
        }

        applyLandingEffects(activePlayerId, landedSpot, isDoubles, newConsecutive, total);
        return true;
    }

    public void pauseAfterPropertyPurchase(TurnContinuationState continuation) {
        log.debug("pauseAfterPropertyPurchase continuation={} action={}", continuation.continuationId(), continuation.completionAction());
        if (continuation.completionAction() == TurnContinuationAction.APPLY_TURN_FOLLOW_UP) {
            // Doubles: skip WAITING_FOR_END_TURN and go directly to WAITING_FOR_ROLL.
            // The client never needs to send EndTurn — this eliminates the entire
            // frontend/backend sync problem with doubles auto-advance.
            log.debug("pauseAfterPropertyPurchase doubles shortcut: consecutiveDoubles={} -> WAITING_FOR_ROLL directly", store.get().turn().consecutiveDoubles());
            store.update(s -> s.toBuilder()
                    .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_ROLL, true, false, s.turn().consecutiveDoubles(), s.turn().lastDice()))
                    .lastCardKey(null)
                    .lastCardMessage(null)
                    .build());
            return;
        }
        pendingPostPurchaseContinuation = continuation;
        store.update(s -> {
            log.trace("pauseAfterPropertyPurchase store.update: consecutiveDoubles={} -> WAITING_FOR_END_TURN", s.turn().consecutiveDoubles());
            return s.toBuilder()
                    .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_END_TURN, false, true, s.turn().consecutiveDoubles(), s.turn().lastDice()))
                    .build();
        });
    }

    @Override
    public boolean endTurn() {
        TurnContinuationState pending = pendingPostPurchaseContinuation;
        log.debug("endTurn pending={}", pending != null ? pending.continuationId() + " action=" + pending.completionAction() : "null");
        pendingPostPurchaseContinuation = null;
        if (pending != null) {
            switch (pending.completionAction()) {
                case APPLY_TURN_FOLLOW_UP -> store.update(s -> {
                    log.debug("endTurn APPLY_TURN_FOLLOW_UP: consecutiveDoubles={} -> WAITING_FOR_ROLL canRoll=true", s.turn().consecutiveDoubles());
                    return s.toBuilder()
                            .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_ROLL, true, false, s.turn().consecutiveDoubles(), s.turn().lastDice()))
                            .lastCardKey(null)
                            .lastCardMessage(null)
                            .build();
                });
                case END_TURN_WITH_SWITCH -> store.update(s -> {
                    String next = DomainTurnContinuationGateway.nextActivePlayerId(s, s.turn().activePlayerId());
                    if (next == null) return s;
                    log.trace("endTurn END_TURN_WITH_SWITCH: {} -> next={}", s.turn().activePlayerId(), next);
                    return s.toBuilder()
                            .turn(new TurnState(next, TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                            .lastCardKey(null)
                            .lastCardMessage(null)
                            .build();
                });
                default -> log.trace("endTurn pending.completionAction={} fell through to normal end-turn", pending.completionAction());
            }
            return true;
        }
        store.update(state -> {
            String next = DomainTurnContinuationGateway.nextActivePlayerId(state, state.turn().activePlayerId());
            if (next == null) return state;
            log.trace("endTurn normal switch: {} -> next={}", state.turn().activePlayerId(), next);
            return state.toBuilder()
                    .turn(new TurnState(next, TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                    .lastCardKey(null)
                    .lastCardMessage(null)
                    .build();
        });
        return true;
    }

    @Override
    public boolean buyBuildingRound(String propertyId) {
        SpotType spotType;
        try {
            spotType = SpotType.valueOf(propertyId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (spotType.streetType.placeType != PlaceType.STREET) return false;

        SessionState state = store.get();
        String activePlayerId = state.turn().activePlayerId();
        if (activePlayerId == null) return false;

        List<SpotType> colorSet = spotsOfType(spotType.streetType);

        if (!colorSet.stream().allMatch(s -> activePlayerId.equals(ownerOf(state, s.name())))) {
            return false;
        }
        if (colorSet.stream().anyMatch(s -> {
            PropertyStateSnapshot p = findProperty(state, s.name());
            return p != null && p.mortgaged();
        })) {
            return false;
        }

        PropertyStateSnapshot targetProp = findProperty(state, propertyId);
        if (targetProp == null || targetProp.hotelCount() > 0) return false;

        // Even building rule: this property must not be above the set minimum level
        int targetLevel = targetProp.houseCount();
        int minLevelInSet = colorSet.stream()
                .mapToInt(s -> {
                    PropertyStateSnapshot p = findProperty(state, s.name());
                    return (p == null) ? 0 : (p.hotelCount() > 0 ? 5 : p.houseCount());
                })
                .min().orElse(0);
        if (targetLevel > minLevelInSet) return false;

        int housePrice = spotType.getIntegerProperty("housePrice");
        if (housePrice <= 0) return false;

        PlayerSnapshot player = findPlayer(state, activePlayerId);
        if (player == null || player.cash() < housePrice) return false;

        boolean becomesHotel = targetProp.houseCount() + 1 >= 5;

        // Bank supply check
        int totalHouses = state.properties().stream().mapToInt(PropertyStateSnapshot::houseCount).sum();
        int totalHotels = state.properties().stream().mapToInt(PropertyStateSnapshot::hotelCount).sum();
        if (becomesHotel) {
            if (totalHotels >= BANK_HOTEL_SUPPLY) return false;
        } else {
            if (totalHouses >= BANK_HOUSE_SUPPLY) return false;
        }

        int newHouseCount = targetProp.houseCount() + 1;
        final String activePlayerIdFinal = activePlayerId;
        store.update(s -> {
            List<PropertyStateSnapshot> updatedProps = s.properties().stream()
                    .map(p -> p.propertyId().equals(propertyId)
                            ? new PropertyStateSnapshot(p.propertyId(), p.ownerPlayerId(), p.mortgaged(),
                                    becomesHotel ? 0 : newHouseCount, becomesHotel ? 1 : 0)
                            : p)
                    .toList();
            SessionState updated = s.toBuilder()
                    .properties(updatedProps)
                    .players(updateCash(s.players(), activePlayerIdFinal, -housePrice))
                    .build();
            return appendEvents(updated,
                    ev(becomesHotel ? "BUILT_HOTEL" : "BUILT_HOUSE", activePlayerIdFinal,
                            Map.of("property", propertyId)),
                    evMoney(activePlayerIdFinal, "", housePrice, "rakennus"));
        });
        return true;
    }

    @Override
    public boolean sellBuildingRound(String propertyId) {
        SpotType spotType;
        try {
            spotType = SpotType.valueOf(propertyId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (spotType.streetType.placeType != PlaceType.STREET) return false;

        SessionState state = store.get();
        String activePlayerId = state.turn().activePlayerId();
        if (activePlayerId == null) return false;

        PropertyStateSnapshot targetProp = findProperty(state, propertyId);
        if (targetProp == null) return false;
        if (!activePlayerId.equals(targetProp.ownerPlayerId())) return false;
        if (targetProp.houseCount() == 0 && targetProp.hotelCount() == 0) return false;

        List<SpotType> colorSet = spotsOfType(spotType.streetType);

        // Even building rule: this property must be at the MAX level in the group
        int targetLevel = targetProp.hotelCount() > 0 ? 5 : targetProp.houseCount();
        int maxLevelInSet = colorSet.stream()
                .mapToInt(s -> {
                    PropertyStateSnapshot p = findProperty(state, s.name());
                    return (p == null) ? 0 : (p.hotelCount() > 0 ? 5 : p.houseCount());
                })
                .max().orElse(0);
        if (targetLevel < maxLevelInSet) return false;

        int housePrice = spotType.getIntegerProperty("housePrice");
        if (housePrice <= 0) return false;

        int saleProceeds = housePrice / 2;
        boolean sellingHotel = targetProp.hotelCount() > 0;

        final String activePlayerIdFinal = activePlayerId;
        if (sellingHotel) {
            // Hotel → 4 houses (need bank house supply)
            int totalHouses = state.properties().stream().mapToInt(PropertyStateSnapshot::houseCount).sum();
            if (totalHouses + 4 > BANK_HOUSE_SUPPLY) {
                // Not enough houses in bank — hotel can still be sold but becomes 0 (rare rule variant)
                store.update(s -> {
                    List<PropertyStateSnapshot> updatedProps = s.properties().stream()
                            .map(p -> p.propertyId().equals(propertyId)
                                    ? new PropertyStateSnapshot(p.propertyId(), p.ownerPlayerId(), p.mortgaged(), 0, 0)
                                    : p)
                            .toList();
                    return appendEvents(
                            s.toBuilder().properties(updatedProps)
                                    .players(updateCash(s.players(), activePlayerIdFinal, saleProceeds)).build(),
                            ev("SOLD_HOTEL", activePlayerIdFinal, Map.of("property", propertyId)),
                            evMoney("", activePlayerIdFinal, saleProceeds, "myynti"));
                });
            } else {
                store.update(s -> {
                    List<PropertyStateSnapshot> updatedProps = s.properties().stream()
                            .map(p -> p.propertyId().equals(propertyId)
                                    ? new PropertyStateSnapshot(p.propertyId(), p.ownerPlayerId(), p.mortgaged(), 4, 0)
                                    : p)
                            .toList();
                    return appendEvents(
                            s.toBuilder().properties(updatedProps)
                                    .players(updateCash(s.players(), activePlayerIdFinal, saleProceeds)).build(),
                            ev("SOLD_HOTEL", activePlayerIdFinal, Map.of("property", propertyId)),
                            evMoney("", activePlayerIdFinal, saleProceeds, "myynti"));
                });
            }
        } else {
            store.update(s -> {
                List<PropertyStateSnapshot> updatedProps = s.properties().stream()
                        .map(p -> p.propertyId().equals(propertyId)
                                ? new PropertyStateSnapshot(p.propertyId(), p.ownerPlayerId(), p.mortgaged(),
                                        p.houseCount() - 1, 0)
                                : p)
                        .toList();
                return appendEvents(
                        s.toBuilder().properties(updatedProps)
                                .players(updateCash(s.players(), activePlayerIdFinal, saleProceeds)).build(),
                        ev("SOLD_HOUSE", activePlayerIdFinal, Map.of("property", propertyId)),
                        evMoney("", activePlayerIdFinal, saleProceeds, "myynti"));
            });
        }
        return true;
    }

    @Override
    public boolean toggleMortgage(String actorPlayerId, String propertyId) {
        try {
            SpotType.valueOf(propertyId);
        } catch (IllegalArgumentException e) {
            return false;
        }

        store.update(state -> {
            PropertyStateSnapshot property = findProperty(state, propertyId);
            if (property == null || !actorPlayerId.equals(property.ownerPlayerId())) return state;

            int mortgageValue = SpotType.valueOf(propertyId).getIntegerProperty("price") / 2;

            if (property.mortgaged()) {
                int unmortgageCost = mortgageValue + (int) (mortgageValue * 0.1);
                PlayerSnapshot player = findPlayer(state, actorPlayerId);
                if (player == null || player.cash() < unmortgageCost) return state;
                return appendEvents(
                        state.toBuilder()
                                .properties(replaceProperty(state.properties(), propertyId,
                                        new PropertyStateSnapshot(property.propertyId(), property.ownerPlayerId(),
                                                false, property.houseCount(), property.hotelCount())))
                                .players(updateCash(state.players(), actorPlayerId, -unmortgageCost))
                                .build(),
                        ev("REDEEMED", actorPlayerId, Map.of("property", propertyId)),
                        evMoney(actorPlayerId, "", unmortgageCost, "lunastus"));
            } else {
                return appendEvents(
                        state.toBuilder()
                                .properties(replaceProperty(state.properties(), propertyId,
                                        new PropertyStateSnapshot(property.propertyId(), property.ownerPlayerId(),
                                                true, property.houseCount(), property.hotelCount())))
                                .players(updateCash(state.players(), actorPlayerId, mortgageValue))
                                .build(),
                        ev("MORTGAGED", actorPlayerId, Map.of("property", propertyId)),
                        evMoney("", actorPlayerId, mortgageValue, "kiinnitys"));
            }
        });
        return true;
    }

    @Override
    public boolean useGetOutOfJailCard() {
        SessionState state = store.get();
        String activePlayerId = state.turn().activePlayerId();
        if (activePlayerId == null) return false;

        PlayerSnapshot activePlayer = findPlayer(state, activePlayerId);
        if (activePlayer == null || !activePlayer.inJail() || activePlayer.getOutOfJailCards() <= 0) return false;

        store.update(s -> {
            List<PlayerSnapshot> updated = s.players().stream()
                    .map(p -> activePlayerId.equals(p.playerId())
                            ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash(),
                                    p.boardIndex(), p.bankrupt(), p.eliminated(), false, 0,
                                    p.getOutOfJailCards() - 1, p.ownedPropertyIds())
                            : p)
                    .toList();
            return appendEvents(
                    s.toBuilder()
                            .players(updated)
                            .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                            .build(),
                    ev("RELEASED_FROM_JAIL", activePlayerId));
        });
        return true;
    }

    @Override
    public boolean payJailFine() {
        SessionState state = store.get();
        String activePlayerId = state.turn().activePlayerId();
        if (activePlayerId == null) return false;

        PlayerSnapshot activePlayer = findPlayer(state, activePlayerId);
        if (activePlayer == null || !activePlayer.inJail()) return false;
        if (activePlayer.cash() < GET_OUT_OF_JAIL_FEE) return false;

        // Snapshot 1: deduct fine
        store.update(s -> appendEvents(
                s.toBuilder()
                        .players(updateCash(s.players(), activePlayerId, -GET_OUT_OF_JAIL_FEE))
                        .build(),
                evMoney(activePlayerId, "", GET_OUT_OF_JAIL_FEE, "vankilamaksu")));
        // Snapshot 2: release from jail
        store.update(s -> appendEvents(
                s.toBuilder()
                        .players(clearJailFlag(s.players(), activePlayerId))
                        .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                        .build(),
                ev("RELEASED_FROM_JAIL", activePlayerId)));
        return true;
    }

    @Override
    public boolean acknowledgeCard() {
        SessionState state = store.get();
        PendingCardEffect pending = state.pendingCardEffect();
        if (pending == null) return false;

        CardType cardType;
        try {
            cardType = CardType.valueOf(pending.cardType());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown card type in pending effect: {}", pending.cardType());
            store.update(s -> s.toBuilder()
                    .pendingCardEffect(null)
                    .turn(postMovePhase(s.turn(), pending.isDoubles(), pending.consecutiveDoubles()))
                    .build());
            return true;
        }

        store.update(s -> s.toBuilder().pendingCardEffect(null).build());
        applyCardEffect(pending.playerId(), cardType, pending.values(),
                pending.isDoubles(), pending.consecutiveDoubles(), pending.diceTotal());
        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers: jail
    // -------------------------------------------------------------------------

    private void handleInJailRoll(PlayerSnapshot activePlayer, int die1, int die2, int total, boolean isDoubles) {
        String playerId = activePlayer.playerId();
        int roundsLeft = activePlayer.jailRoundsRemaining();
        final String d1s = String.valueOf(die1), d2s = String.valueOf(die2);

        if (isDoubles) {
            log.info("Player {} rolled doubles and escapes jail", activePlayer.name());
            int rawNew = JAIL_INDEX + total;
            int newIndex = rawNew % BOARD_SIZE;
            boolean passedGo = rawNew >= BOARD_SIZE;
            SpotType landedSpot = SpotType.SPOT_TYPES.get(newIndex);
            final String toStr = String.valueOf(newIndex);
            final String jailIdxStr = String.valueOf(JAIL_INDEX);

            // Snapshot 1: release from jail (still at jail square)
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(clearJailFlag(s.players(), playerId))
                            .turn(withDice(withConsecutive(s.turn(), 0), die1, die2))
                            .build(),
                    ev("DICE_ROLLED", playerId, Map.of("d1", d1s, "d2", d2s)),
                    ev("RELEASED_FROM_JAIL", playerId)));
            // Snapshot 2: move player
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(movePlayer(s.players(), playerId, newIndex))
                            .build(),
                    ev("PLAYER_MOVED", playerId, Map.of("from", jailIdxStr, "to", toStr))));
            // Snapshot 3: GO bonus if applicable
            if (passedGo) {
                store.update(s -> appendEvents(
                        s.toBuilder()
                                .players(updateCash(s.players(), playerId, GO_REWARD))
                                .build(),
                        ev("PASSED_GO", playerId)));
            }

            if (landedSpot == SpotType.GO_TO_JAIL) {
                store.update(s -> appendEvents(applyGoToJail(s, playerId),
                        ev("WENT_TO_JAIL", playerId, Map.of("from", toStr))));
            } else {
                // No re-roll bonus after jail escape even if doubles
                applyLandingEffects(playerId, landedSpot, false, 0, total);
            }
        } else if (roundsLeft <= 1 && activePlayer.cash() >= GET_OUT_OF_JAIL_FEE) {
            log.info("Player {} must pay jail fine (last round), total={}", activePlayer.name(), total);
            int rawNew = JAIL_INDEX + total;
            int newIndex = rawNew % BOARD_SIZE;
            boolean passedGo = rawNew >= BOARD_SIZE;
            SpotType landedSpot = SpotType.SPOT_TYPES.get(newIndex);
            final String toStr = String.valueOf(newIndex);
            final String jailIdxStr = String.valueOf(JAIL_INDEX);

            // Snapshot 1: pay fine and release (still at jail square)
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(clearJailFlagWithFine(s.players(), playerId))
                            .turn(withDice(withConsecutive(s.turn(), 0), die1, die2))
                            .build(),
                    ev("DICE_ROLLED", playerId, Map.of("d1", d1s, "d2", d2s)),
                    ev("RELEASED_FROM_JAIL", playerId)));
            // Snapshot 2: move player
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(movePlayer(s.players(), playerId, newIndex))
                            .build(),
                    ev("PLAYER_MOVED", playerId, Map.of("from", jailIdxStr, "to", toStr))));
            // Snapshot 3: GO bonus if applicable
            if (passedGo) {
                store.update(s -> appendEvents(
                        s.toBuilder()
                                .players(updateCash(s.players(), playerId, GO_REWARD))
                                .build(),
                        ev("PASSED_GO", playerId)));
            }

            if (landedSpot == SpotType.GO_TO_JAIL) {
                store.update(s -> appendEvents(applyGoToJail(s, playerId),
                        ev("WENT_TO_JAIL", playerId, Map.of("from", toStr))));
            } else {
                applyLandingEffects(playerId, landedSpot, false, 0, total);
            }
        } else if (roundsLeft <= 1) {
            // Final forced round, no doubles, and the player cannot afford the €50 fine.
            // On the last round the fine is mandatory (real Monopoly), so release the
            // player from jail and open a bank debt for the fee. The debt subsystem then
            // forces a mortgage/sale — or bankruptcy — rather than leaving the player
            // stuck in jail forever (the old code clamped rounds at 1 and required cash
            // for release, so a broke player was deadlocked while the UI kept promising
            // "released next turn"). The player forfeits this turn's move; once the debt
            // is settled the turn ends and they are out of jail for their next turn.
            log.info("Player {} cannot afford the €{} jail fine on the last round — releasing into a bank debt",
                    activePlayer.name(), GET_OUT_OF_JAIL_FEE);
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(clearJailFlag(s.players(), playerId))
                            .turn(withDice(withConsecutive(s.turn(), 0), die1, die2))
                            .build(),
                    ev("DICE_ROLLED", playerId, Map.of("d1", d1s, "d2", d2s)),
                    ev("RELEASED_FROM_JAIL", playerId)));
            openDebt(store.get(), playerId, null, GET_OUT_OF_JAIL_FEE, false, 0, "vankilamaksu");
        } else {
            // Genuinely stays in jail — more than one round remains. Decrement toward the
            // final forced round (roundsLeft is > 1 here, so this never clamps).
            int newRounds = roundsLeft - 1;
            log.debug("Player {} stays in jail, roundsLeft → {}", activePlayer.name(), newRounds);
            store.update(s -> {
                List<PlayerSnapshot> updated = s.players().stream()
                        .map(p -> playerId.equals(p.playerId())
                                ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash(),
                                        p.boardIndex(), p.bankrupt(), p.eliminated(), true,
                                        newRounds, p.getOutOfJailCards(), p.ownedPropertyIds())
                                : p)
                        .toList();
                return appendEvents(
                        s.toBuilder()
                                .players(updated)
                                .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_END_TURN, false, true, 0, new int[]{die1, die2}))
                                .build(),
                        ev("DICE_ROLLED", playerId, Map.of("d1", d1s, "d2", d2s)));
            });
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers: landing effects
    // -------------------------------------------------------------------------

    /** Rent override for the Chance "advance to nearest…" cards, whose text promises
     *  double railroad rent / ten times the dice throw on utilities. */
    private enum CardRentRule { NORMAL, DOUBLE, DICE_TIMES_TEN }

    private void applyLandingEffects(String playerId, SpotType landedSpot, boolean isDoubles, int consecutiveDoubles, int diceTotal) {
        applyLandingEffects(playerId, landedSpot, isDoubles, consecutiveDoubles, diceTotal, CardRentRule.NORMAL);
    }

    private void applyLandingEffects(String playerId, SpotType landedSpot, boolean isDoubles,
                                     int consecutiveDoubles, int diceTotal, CardRentRule rentRule) {
        PlaceType placeType = landedSpot.streetType.placeType;

        if (placeType == PlaceType.STREET || placeType == PlaceType.RAILROAD || placeType == PlaceType.UTILITY) {
            SessionState current = store.get();
            PropertyStateSnapshot property = findProperty(current, landedSpot.name());
            String ownerId = property != null ? property.ownerPlayerId() : null;

            if (ownerId == null) {
                openPropertyDecision(current, playerId, landedSpot, isDoubles, consecutiveDoubles);
                return;
            }
            if (ownerId.equals(playerId) || property.mortgaged()) {
                store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
                return;
            }
            int rent = calculateRent(current, landedSpot, property, ownerId, diceTotal);
            // The card text is the promise made to the player — honor it.
            if (rentRule == CardRentRule.DOUBLE) rent *= 2;
            else if (rentRule == CardRentRule.DICE_TIMES_TEN) rent = diceTotal * 10;
            applyRentOrDebt(current, playerId, ownerId, rent, isDoubles, consecutiveDoubles,
                    landedSpot.name());
            return;
        }

        if (placeType == PlaceType.TAX) {
            SessionState current = store.get();
            int tax = landedSpot.getIntegerProperty("price");
            applyRentOrDebt(current, playerId, null, tax, isDoubles, consecutiveDoubles, "Tax");
            return;
        }

        if (placeType == PlaceType.PICK_CARD) {
            applyPickCard(playerId, landedSpot, isDoubles, consecutiveDoubles, diceTotal);
            return;
        }

        // GO_TO_JAIL corner — jail the player regardless of how they arrived
        if (landedSpot == SpotType.GO_TO_JAIL) {
            log.debug("applyLandingEffects GO_TO_JAIL player={}", playerId);
            store.update(s -> appendEvents(applyGoToJail(s, playerId),
                    ev("WENT_TO_JAIL", playerId)));
            return;
        }

        // CORNER (GO / JAIL visiting / FREE_PARKING) → advance turn
        store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
    }

    // -------------------------------------------------------------------------
    // Private helpers: card effects
    // -------------------------------------------------------------------------

    private void applyPickCard(String playerId, SpotType landedSpot, boolean isDoubles, int consecutiveDoubles, int diceTotal) {
        boolean isChance = landedSpot.streetType == StreetType.CHANCE;
        String bundleName = isChance ? "chance" : "community";

        ensureDecksInitialized();

        SessionState state = store.get();
        List<String> deck = isChance ? state.chanceDeck() : state.communityDeck();
        if (deck == null || deck.isEmpty()) {
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }

        // Draw top card, rotate to bottom (standard Monopoly draw cycle)
        String cardKey = deck.get(0);
        List<String> rotatedDeck = new ArrayList<>(deck.subList(1, deck.size()));
        rotatedDeck.add(cardKey);
        final List<String> finalDeck = List.copyOf(rotatedDeck);

        if (isChance) {
            store.update(s -> s.toBuilder().chanceDeck(finalDeck).build());
        } else {
            store.update(s -> s.toBuilder().communityDeck(finalDeck).build());
        }

        String[] parts = cardKey.split(":", 2);
        CardType cardType;
        try {
            cardType = CardType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown card type in key: {}", cardKey);
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }
        List<String> values = CardDeckLoader.cardValues(bundleName, cardKey);
        String cardText = CardDeckLoader.cardText(bundleName, cardKey);
        PlayerSnapshot cardPlayer = findPlayer(state, playerId);
        log.info("Player {} drew {} card [{}] values={}", cardPlayer != null ? cardPlayer.name() : playerId, bundleName, cardKey, values);

        final String fullCardKey = bundleName + ":" + cardKey;
        final PendingCardEffect pending = new PendingCardEffect(playerId, cardType.name(), values, isDoubles, consecutiveDoubles, diceTotal);
        store.update(s -> appendEvents(
                s.toBuilder()
                        .lastCardMessage(cardText)
                        .lastCardKey(fullCardKey)
                        .pendingCardEffect(pending)
                        .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_CARD_ACK,
                                false, false, consecutiveDoubles, s.turn().lastDice()))
                        .build(),
                ev("DREW_CARD", playerId, Map.of("card", fullCardKey))));
    }

    private void ensureDecksInitialized() {
        store.update(s -> {
            if (s.chanceDeck() != null && s.communityDeck() != null) return s;
            Random rng = randomSource.toJavaRandom();
            List<String> chance = s.chanceDeck() != null ? s.chanceDeck() : CardDeckLoader.buildDeck("chance", rng);
            List<String> community = s.communityDeck() != null ? s.communityDeck() : CardDeckLoader.buildDeck("community", rng);
            return s.toBuilder().chanceDeck(chance).communityDeck(community).build();
        });
    }

    private void applyCardEffect(String playerId, CardType cardType, List<String> values,
                                  boolean isDoubles, int consecutiveDoubles, int diceTotal) {
        switch (cardType) {
            case MONEY -> {
                int amount = values.isEmpty() ? 0 : parseIntSafe(values.get(0));
                applyCardMoney(playerId, amount, isDoubles, consecutiveDoubles);
            }
            case MOVE -> {
                String target = values.isEmpty() ? null : values.get(0).trim();
                applyCardMove(playerId, target, isDoubles, consecutiveDoubles, diceTotal);
            }
            case MOVE_NEAREST -> {
                String typeStr = values.isEmpty() ? null : values.get(0).trim();
                applyCardMoveNearest(playerId, typeStr, isDoubles, consecutiveDoubles, diceTotal);
            }
            case MOVE_BACK_3 -> applyCardMoveBack(playerId, 3, isDoubles, consecutiveDoubles, diceTotal);
            case GO_JAIL -> store.update(s -> appendEvents(applyGoToJail(s, playerId),
                    ev("WENT_TO_JAIL", playerId)));
            case OUT_OF_JAIL -> {
                store.update(s -> {
                    List<PlayerSnapshot> updated = s.players().stream()
                            .map(p -> playerId.equals(p.playerId())
                                    ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash(),
                                            p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                            p.jailRoundsRemaining(), p.getOutOfJailCards() + 1, p.ownedPropertyIds())
                                    : p)
                            .toList();
                    return s.toBuilder()
                            .players(updated)
                            .turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles))
                            .build();
                });
            }
            case REPAIR_PROPERTIES -> {
                int houseCost = values.size() > 0 ? parseIntSafe(values.get(0)) : 0;
                int hotelCost = values.size() > 1 ? parseIntSafe(values.get(1)) : 0;
                applyCardRepair(playerId, houseCost, hotelCost, isDoubles, consecutiveDoubles);
            }
            case ALL_PLAYERS_MONEY -> {
                int amountPerPlayer = values.isEmpty() ? 0 : parseIntSafe(values.get(0));
                applyCardAllPlayersMoney(playerId, amountPerPlayer, isDoubles, consecutiveDoubles);
            }
        }
    }

    private void applyCardMoney(String playerId, int amount, boolean isDoubles, int consecutiveDoubles) {
        log.debug("applyCardMoney player={} amount={}", playerId, amount);
        if (amount >= 0) {
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(updateCash(s.players(), playerId, amount))
                            .turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles))
                            .build(),
                    evMoney("", playerId, amount, "kortti")));
        } else {
            SessionState current = store.get();
            applyRentOrDebt(current, playerId, null, -amount, isDoubles, consecutiveDoubles, "Card payment");
        }
    }

    private void applyCardMove(String playerId, String target, boolean isDoubles, int consecutiveDoubles, int diceTotal) {
        if (target == null) {
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }
        SpotType targetSpot;
        try {
            targetSpot = SpotType.valueOf(target.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown MOVE card target: {}", target);
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }

        int targetIndex = SpotType.SPOT_TYPES.indexOf(targetSpot);
        SessionState current = store.get();
        PlayerSnapshot player = findPlayer(current, playerId);
        if (player == null) return;

        // Player always moves forward; if target is behind or is GO, they pass/land on GO
        boolean passedGo = targetIndex < player.boardIndex() || targetSpot == SpotType.GO_SPOT;
        log.debug("applyCardMove player={} target={} targetIndex={} fromIndex={} passedGo={}",
                player.name(), targetSpot, targetIndex, player.boardIndex(), passedGo);
        final String fromStr = String.valueOf(player.boardIndex());
        final String toStr = String.valueOf(targetIndex);

        // Snapshot 1: move player (no GO bonus yet)
        store.update(s -> appendEvents(
                s.toBuilder()
                        .players(movePlayer(s.players(), playerId, targetIndex))
                        .build(),
                ev("PLAYER_MOVED", playerId, Map.of("from", fromStr, "to", toStr))));
        // Snapshot 2: GO bonus if applicable
        if (passedGo) {
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(updateCash(s.players(), playerId, GO_REWARD))
                            .build(),
                    ev("PASSED_GO", playerId)));
        }
        applyLandingEffects(playerId, targetSpot, isDoubles, consecutiveDoubles, diceTotal);
    }

    private void applyCardMoveNearest(String playerId, String typeStr, boolean isDoubles, int consecutiveDoubles, int diceTotal) {
        PlaceType targetType = "RAILROAD".equals(typeStr) ? PlaceType.RAILROAD : PlaceType.UTILITY;

        SessionState current = store.get();
        PlayerSnapshot player = findPlayer(current, playerId);
        if (player == null) return;

        int currentIndex = player.boardIndex();
        SpotType nearestSpot = null;
        int nearestIndex = -1;

        for (int offset = 1; offset <= BOARD_SIZE; offset++) {
            int checkIndex = (currentIndex + offset) % BOARD_SIZE;
            SpotType spot = SpotType.SPOT_TYPES.get(checkIndex);
            if (spot.streetType.placeType == targetType) {
                nearestSpot = spot;
                nearestIndex = checkIndex;
                break;
            }
        }

        if (nearestSpot == null) {
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }

        // Nearest spot is always ahead or wraps — passes GO only if wrapped
        boolean passedGo = nearestIndex < currentIndex;
        log.debug("applyCardMoveNearest player={} type={} nearest={} nearestIndex={} fromIndex={} passedGo={}",
                player.name(), typeStr, nearestSpot, nearestIndex, currentIndex, passedGo);

        final int finalNearestIndex = nearestIndex;
        final SpotType finalNearestSpot = nearestSpot;
        final String fromStr = String.valueOf(currentIndex);
        final String toStr = String.valueOf(nearestIndex);

        // Snapshot 1: move player (no GO bonus yet)
        store.update(s -> appendEvents(
                s.toBuilder()
                        .players(movePlayer(s.players(), playerId, finalNearestIndex))
                        .build(),
                ev("PLAYER_MOVED", playerId, Map.of("from", fromStr, "to", toStr))));
        // Snapshot 2: GO bonus if applicable
        if (passedGo) {
            store.update(s -> appendEvents(
                    s.toBuilder()
                            .players(updateCash(s.players(), playerId, GO_REWARD))
                            .build(),
                    ev("PASSED_GO", playerId)));
        }
        // Card text promise: railroad → pay twice the rental; utility → pay 10× the dice throw.
        CardRentRule rentRule = targetType == PlaceType.RAILROAD
                ? CardRentRule.DOUBLE : CardRentRule.DICE_TIMES_TEN;
        applyLandingEffects(playerId, finalNearestSpot, isDoubles, consecutiveDoubles, diceTotal, rentRule);
    }

    private void applyCardMoveBack(String playerId, int spaces, boolean isDoubles, int consecutiveDoubles, int diceTotal) {
        SessionState current = store.get();
        PlayerSnapshot player = findPlayer(current, playerId);
        if (player == null) return;

        int newIndex = (player.boardIndex() - spaces + BOARD_SIZE) % BOARD_SIZE;
        SpotType landedSpot = SpotType.SPOT_TYPES.get(newIndex);
        log.debug("applyCardMoveBack player={} spaces={} fromIndex={} toIndex={} landedSpot={}",
                player.name(), spaces, player.boardIndex(), newIndex, landedSpot);
        final String fromStr = String.valueOf(player.boardIndex());
        final String toStr = String.valueOf(newIndex);

        // Moving backward does not cross GO — no GO bonus
        store.update(s -> appendEvents(
                s.toBuilder()
                        .players(movePlayer(s.players(), playerId, newIndex))
                        .build(),
                ev("PLAYER_MOVED", playerId, Map.of("from", fromStr, "to", toStr))));
        applyLandingEffects(playerId, landedSpot, isDoubles, consecutiveDoubles, diceTotal);
    }

    private void applyCardRepair(String playerId, int houseCost, int hotelCost, boolean isDoubles, int consecutiveDoubles) {
        SessionState current = store.get();
        int houses = current.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()))
                .mapToInt(PropertyStateSnapshot::houseCount)
                .sum();
        int hotels = current.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()))
                .mapToInt(PropertyStateSnapshot::hotelCount)
                .sum();
        int totalCost = houses * houseCost + hotels * hotelCost;

        if (totalCost == 0) {
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }
        applyRentOrDebt(current, playerId, null, totalCost, isDoubles, consecutiveDoubles, "Card repairs");
    }

    private void applyCardAllPlayersMoney(String playerId, int amountPerPlayer, boolean isDoubles, int consecutiveDoubles) {
        SessionState current = store.get();
        List<PlayerSnapshot> others = current.players().stream()
                .filter(p -> !playerId.equals(p.playerId()) && !p.eliminated())
                .toList();

        if (others.isEmpty() || amountPerPlayer == 0) {
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }

        if (amountPerPlayer < 0) {
            // Active player pays |amountPerPlayer| to each other player
            int totalOwed = Math.abs(amountPerPlayer) * others.size();
            PlayerSnapshot active = findPlayer(current, playerId);
            if (active != null && active.cash() < totalOwed) {
                // Can't fully afford: distribute whatever cash the active player has
                // proportionally (capped per receiver), then enter debt for the remainder.
                // This ensures other players receive money rather than the bank.
                final int available = active != null ? active.cash() : 0;
                final int perPlayerPartial = others.isEmpty() ? 0 : available / others.size();
                final int totalPartial = perPlayerPartial * others.size();
                if (totalPartial > 0) {
                    final List<String> recipientIds = others.stream().map(PlayerSnapshot::playerId).toList();
                    store.update(s -> {
                        final int perP = perPlayerPartial;
                        List<PlayerSnapshot> updated = s.players().stream()
                                .map(p -> {
                                    if (playerId.equals(p.playerId())) {
                                        return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() - totalPartial,
                                                p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                                p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
                                    }
                                    if (!p.eliminated()) {
                                        return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() + perP,
                                                p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                                p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
                                    }
                                    return p;
                                })
                                .toList();
                        GameEventEntry[] flows = recipientIds.stream()
                                .map(rid -> evMoney(playerId, rid, perP, "kortti"))
                                .toArray(GameEventEntry[]::new);
                        return appendEvents(s.toBuilder().players(updated).build(), flows);
                    });
                }
                SessionState afterPartial = store.get();
                // Open debt for the remaining amount with no single creditor (treated as card penalty)
                int remaining = totalOwed - totalPartial;
                applyRentOrDebt(afterPartial, playerId, null, remaining, isDoubles, consecutiveDoubles, "Card payment");
                return;
            }
            // Can afford: deduct from active, distribute to others
            final int payment = Math.abs(amountPerPlayer);
            final int total = totalOwed;
            final List<String> recipientIds = others.stream().map(PlayerSnapshot::playerId).toList();
            store.update(s -> {
                List<PlayerSnapshot> updated = s.players().stream()
                        .map(p -> {
                            if (playerId.equals(p.playerId())) {
                                return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() - total,
                                        p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                        p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
                            }
                            if (!p.eliminated()) {
                                return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() + payment,
                                        p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                        p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
                            }
                            return p;
                        })
                        .toList();
                GameEventEntry[] flows = recipientIds.stream()
                        .map(rid -> evMoney(playerId, rid, payment, "kortti"))
                        .toArray(GameEventEntry[]::new);
                return appendEvents(s.toBuilder()
                        .players(updated)
                        .turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles))
                        .build(), flows);
            });
        } else {
            // amountPerPlayer > 0: others pay active player
            // Separate payers who can afford from those who need debt resolution
            List<String> canPayIds = others.stream()
                    .filter(p -> p.cash() >= amountPerPlayer)
                    .map(PlayerSnapshot::playerId)
                    .toList();
            List<String> owesDebtIds = others.stream()
                    .filter(p -> p.cash() < amountPerPlayer)
                    .map(PlayerSnapshot::playerId)
                    .toList();
            final int totalFromCanPay = amountPerPlayer * canPayIds.size();

            // Build the pending group debt queue (first becomes active debt, rest are queued)
            List<PendingGroupDebt> pendingQueue = owesDebtIds.stream()
                    .map(did -> new PendingGroupDebt(did, playerId, amountPerPlayer, isDoubles, consecutiveDoubles))
                    .toList();

            store.update(s -> {
                // Collect from those who can pay immediately
                GameEventEntry[] flows = canPayIds.stream()
                        .map(cid -> evMoney(cid, playerId, amountPerPlayer, "kortti"))
                        .toArray(GameEventEntry[]::new);
                List<PlayerSnapshot> updated = s.players().stream()
                        .map(p -> {
                            if (playerId.equals(p.playerId()))
                                return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() + totalFromCanPay,
                                        p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                        p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
                            if (canPayIds.contains(p.playerId()))
                                return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() - amountPerPlayer,
                                        p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                        p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
                            return p;
                        })
                        .toList();

                if (pendingQueue.isEmpty()) {
                    // All paid — advance turn
                    return appendEvents(s.toBuilder()
                            .players(updated)
                            .turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles))
                            .build(), flows);
                }

                // Open debt for first debtor, queue the rest
                PendingGroupDebt first = pendingQueue.get(0);
                List<PendingGroupDebt> remaining = pendingQueue.size() > 1
                        ? pendingQueue.subList(1, pendingQueue.size()) : List.of();
                SessionState intermediate = s.toBuilder().players(updated).build();
                int firstLiquidation = estimateLiquidation(intermediate, first.debtorPlayerId());
                PlayerSnapshot firstDebtor = findPlayer(intermediate, first.debtorPlayerId());
                int firstCash = firstDebtor != null ? firstDebtor.cash() : 0;

                DebtStateModel debt = new DebtStateModel(
                        "debt:group:" + first.debtorPlayerId() + ":" + System.currentTimeMillis(),
                        first.debtorPlayerId(), DebtCreditorType.PLAYER, playerId,
                        amountPerPlayer, "Card payment", false, firstCash, firstLiquidation,
                        new ArrayList<>(List.of(DebtAction.PAY_DEBT_NOW, DebtAction.MORTGAGE_PROPERTY,
                                DebtAction.SELL_BUILDING, DebtAction.SELL_BUILDING_ROUNDS_ACROSS_SET,
                                DebtAction.DECLARE_BANKRUPTCY)));
                TurnContinuationState continuation = new TurnContinuationState(
                        "cont:group:" + playerId + ":afterGroupDebt",
                        playerId,  // card holder resumes after all debts
                        TurnContinuationType.RESUME_AFTER_DEBT,
                        isDoubles ? TurnContinuationAction.APPLY_TURN_FOLLOW_UP : TurnContinuationAction.END_TURN_WITH_SWITCH,
                        null, "Card payment");
                return appendEvents(s.toBuilder()
                        .players(updated)
                        .activeDebt(debt)
                        .pendingGroupDebts(remaining)
                        .turnContinuationState(continuation)
                        .turn(new TurnState(first.debtorPlayerId(), TurnPhase.RESOLVING_DEBT, false, false, consecutiveDoubles, s.turn().lastDice()))
                        .build(), flows);
            });
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void openPropertyDecision(SessionState state, String playerId, SpotType spot,
                                       boolean isDoubles, int consecutiveDoubles) {
        int price = spot.getIntegerProperty("price");
        String displayName = spot.getStringProperty("name");
        TurnContinuationAction completionAction = isDoubles
                ? TurnContinuationAction.APPLY_TURN_FOLLOW_UP
                : TurnContinuationAction.END_TURN_WITH_SWITCH;
        log.debug("openPropertyDecision player={} spot={} isDoubles={} consecutiveDoubles={} -> completionAction={}",
                playerId, spot, isDoubles, consecutiveDoubles, completionAction);
        TurnContinuationState continuation = new TurnContinuationState(
                "cont:" + playerId + ":purchase:" + spot.name(),
                playerId,
                TurnContinuationType.RESUME_AFTER_AUCTION,
                completionAction,
                spot.name(), null
        );
        // Set the pending decision in the overlay FIRST, then bump the base store so the
        // published snapshot is consistent (phase WAITING_FOR_DECISION *with* the decision).
        // Doing the base update first would publish an intermediate snapshot whose phase is
        // set but whose pendingDecision is still null; the later overlay-only change does not
        // bump the version, so the SSE sendIfNewer guard drops the consistent follow-up and
        // the client stays stuck on the decision-less snapshot until a manual refresh.
        propertyPurchaseFlow.begin(playerId, spot.name(), displayName, price,
                displayName + " €" + price, continuation);
        store.update(s -> s.toBuilder()
                .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.WAITING_FOR_DECISION, false, false, consecutiveDoubles, s.turn().lastDice()))
                .build());
    }

    private void applyRentOrDebt(SessionState state, String debtorId, String creditorId, int amount,
                                  boolean isDoubles, int consecutiveDoubles, String reason) {
        log.debug("applyRentOrDebt debtor={} creditor={} amount={} reason={}", debtorId, creditorId, amount, reason);
        if (amount <= 0) {
            store.update(s -> s.toBuilder().turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles)).build());
            return;
        }
        PlayerSnapshot debtor = findPlayer(state, debtorId);
        if (debtor == null) return;

        if (debtor.cash() >= amount) {
            if (creditorId != null) {
                // Snapshot 1: debtor pays (PAID_RENT event)
                store.update(s -> appendEvents(
                        s.toBuilder()
                                .players(updateCash(s.players(), debtorId, -amount))
                                .build(),
                        ev("PAID_RENT", List.of(debtorId, creditorId),
                                Map.of("amount", String.valueOf(amount)))));
                // Snapshot 2: creditor receives + turn phase advances
                store.update(s -> s.toBuilder()
                        .players(updateCash(s.players(), creditorId, amount))
                        .turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles))
                        .build());
            } else {
                store.update(s -> appendEvents(
                        s.toBuilder()
                                .players(updateCash(s.players(), debtorId, -amount))
                                .turn(postMovePhase(s.turn(), isDoubles, consecutiveDoubles))
                                .build(),
                        evMoney(debtorId, "", amount, toFinReason(reason))));
            }
        } else {
            openDebt(state, debtorId, creditorId, amount, isDoubles, consecutiveDoubles, reason);
        }
    }

    private void openDebt(SessionState state, String debtorId, String creditorId, int amount,
                           boolean isDoubles, int consecutiveDoubles, String reason) {
        int estimatedLiquidation = estimateLiquidation(state, debtorId);
        PlayerSnapshot debtor = findPlayer(state, debtorId);
        int currentCash = debtor != null ? debtor.cash() : 0;
        boolean bankruptcyRisk = currentCash + estimatedLiquidation < amount;

        List<DebtAction> actions = new ArrayList<>(List.of(
                DebtAction.PAY_DEBT_NOW, DebtAction.MORTGAGE_PROPERTY,
                DebtAction.SELL_BUILDING, DebtAction.SELL_BUILDING_ROUNDS_ACROSS_SET,
                DebtAction.DECLARE_BANKRUPTCY));

        DebtStateModel debt = new DebtStateModel(
                "debt:" + debtorId + ":" + System.currentTimeMillis(),
                debtorId,
                creditorId != null ? DebtCreditorType.PLAYER : DebtCreditorType.BANK,
                creditorId,
                amount, reason, bankruptcyRisk, currentCash, estimatedLiquidation, actions
        );
        TurnContinuationState continuation = new TurnContinuationState(
                "cont:" + debtorId + ":afterDebt",
                debtorId,
                TurnContinuationType.RESUME_AFTER_DEBT,
                isDoubles ? TurnContinuationAction.APPLY_TURN_FOLLOW_UP : TurnContinuationAction.END_TURN_WITH_SWITCH,
                null, reason
        );
        store.update(s -> s.toBuilder()
                .activeDebt(debt)
                .turnContinuationState(continuation)
                .turn(new TurnState(s.turn().activePlayerId(), TurnPhase.RESOLVING_DEBT, false, false, consecutiveDoubles, s.turn().lastDice()))
                .build());
    }

    // -------------------------------------------------------------------------
    // Private helpers: state transitions
    // -------------------------------------------------------------------------

    private static SessionState applyGoToJail(SessionState state, String playerId) {
        List<PlayerSnapshot> updated = state.players().stream()
                .map(p -> playerId.equals(p.playerId())
                        ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash(),
                                JAIL_INDEX, p.bankrupt(), p.eliminated(), true, INITIAL_JAIL_ROUNDS,
                                p.getOutOfJailCards(), p.ownedPropertyIds())
                        : p)
                .toList();
        return state.toBuilder()
                .players(updated)
                .turn(new TurnState(state.turn().activePlayerId(), TurnPhase.WAITING_FOR_END_TURN, false, true, 0, state.turn().lastDice()))
                .build();
    }

    private static TurnState postMovePhase(TurnState current, boolean isDoubles, int consecutiveDoubles) {
        if (isDoubles) {
            return new TurnState(current.activePlayerId(), TurnPhase.WAITING_FOR_ROLL, true, false, consecutiveDoubles, current.lastDice());
        }
        return new TurnState(current.activePlayerId(), TurnPhase.WAITING_FOR_END_TURN, false, true, 0, current.lastDice());
    }

    private static TurnState withConsecutive(TurnState current, int consecutiveDoubles) {
        return new TurnState(current.activePlayerId(), current.phase(), current.canRoll(), current.canEndTurn(), consecutiveDoubles, current.lastDice());
    }

    private static TurnState withDice(TurnState current, int d1, int d2) {
        return new TurnState(current.activePlayerId(), current.phase(), current.canRoll(), current.canEndTurn(), current.consecutiveDoubles(), new int[]{d1, d2});
    }

    // -------------------------------------------------------------------------
    // Private helpers: player list mutation
    // -------------------------------------------------------------------------

    private static List<PlayerSnapshot> updatePosition(List<PlayerSnapshot> players, String playerId, int newIndex, int cashDelta) {
        return players.stream()
                .map(p -> playerId.equals(p.playerId())
                        ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() + cashDelta,
                                newIndex, p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds())
                        : p)
                .toList();
    }

    private static List<PlayerSnapshot> movePlayer(List<PlayerSnapshot> players, String playerId, int newIndex) {
        return players.stream()
                .map(p -> playerId.equals(p.playerId())
                        ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash(),
                                newIndex, p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds())
                        : p)
                .toList();
    }

    private static List<PlayerSnapshot> clearJailFlag(List<PlayerSnapshot> players, String playerId) {
        return players.stream()
                .map(p -> playerId.equals(p.playerId())
                        ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash(),
                                p.boardIndex(), p.bankrupt(), p.eliminated(), false, 0,
                                p.getOutOfJailCards(), p.ownedPropertyIds())
                        : p)
                .toList();
    }

    /** Caller must verify the player can afford the fee — handleInJailRoll keeps a broke
     *  player jailed instead, so the fee is never partially waived. */
    private static List<PlayerSnapshot> clearJailFlagWithFine(List<PlayerSnapshot> players, String playerId) {
        return players.stream()
                .map(p -> playerId.equals(p.playerId())
                        ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(),
                                p.cash() - GET_OUT_OF_JAIL_FEE,
                                p.boardIndex(), p.bankrupt(), p.eliminated(), false, 0,
                                p.getOutOfJailCards(), p.ownedPropertyIds())
                        : p)
                .toList();
    }

    static List<PlayerSnapshot> updateCash(List<PlayerSnapshot> players, String playerId, int delta) {
        return players.stream()
                .map(p -> playerId.equals(p.playerId())
                        ? new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() + delta,
                                p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds())
                        : p)
                .toList();
    }

    static List<PropertyStateSnapshot> replaceProperty(List<PropertyStateSnapshot> properties, String propertyId,
                                                        PropertyStateSnapshot replacement) {
        return properties.stream()
                .map(p -> p.propertyId().equals(propertyId) ? replacement : p)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers: rent calculation
    // -------------------------------------------------------------------------

    private static int calculateRent(SessionState state, SpotType landedSpot, PropertyStateSnapshot property,
                                      String ownerId, int diceTotal) {
        PlaceType placeType = landedSpot.streetType.placeType;

        if (placeType == PlaceType.STREET) {
            int[] rents = parseRents(landedSpot.getStringProperty("rents"));
            if (rents.length < 6) return 0;
            if (property.hotelCount() > 0) return rents[5];
            if (property.houseCount() > 0) return rents[Math.min(property.houseCount(), 4)];
            boolean monopoly = spotsOfType(landedSpot.streetType).stream()
                    .allMatch(s -> ownerId.equals(ownerOf(state, s.name())));
            return monopoly ? rents[0] * 2 : rents[0];
        }

        if (placeType == PlaceType.RAILROAD) {
            int[] rents = parseRents(landedSpot.getStringProperty("rents"));
            long owned = spotsOfType(landedSpot.streetType).stream()
                    .filter(s -> ownerId.equals(ownerOf(state, s.name())))
                    .count();
            int idx = (int) Math.min(owned, rents.length - 1);
            return idx > 0 ? rents[idx] : 0;
        }

        if (placeType == PlaceType.UTILITY) {
            long owned = spotsOfType(landedSpot.streetType).stream()
                    .filter(s -> ownerId.equals(ownerOf(state, s.name())))
                    .count();
            int multiplier = owned >= 2 ? 10 : 4;
            return diceTotal * multiplier;
        }

        return 0;
    }

    private static int estimateLiquidation(SessionState state, String playerId) {
        // Delegates to the debt gateway's estimator so the bankruptcyRisk flag is computed the
        // same way at debt opening and after each remediation step. The old copy ignored
        // buildings entirely, flagging hotel owners as bankruptcy risks they weren't.
        return fi.monopoly.application.session.debt.DomainDebtRemediationGateway
                .estimatedLiquidationValue(state, playerId);
    }

    private static int[] parseRents(String rentsStr) {
        if (rentsStr == null || rentsStr.isBlank()) return new int[0];
        String[] parts = rentsStr.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static List<SpotType> spotsOfType(StreetType streetType) {
        return SpotType.SPOT_TYPES.stream()
                .filter(s -> s.streetType == streetType && s.isProperty)
                .toList();
    }

    private static String ownerOf(SessionState state, String propertyId) {
        return state.properties().stream()
                .filter(p -> propertyId.equals(p.propertyId()))
                .findFirst()
                .map(PropertyStateSnapshot::ownerPlayerId)
                .orElse(null);
    }

    private static PlayerSnapshot findPlayer(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> playerId.equals(p.playerId()))
                .findFirst().orElse(null);
    }

    private static PropertyStateSnapshot findProperty(SessionState state, String propertyId) {
        return state.properties().stream()
                .filter(p -> propertyId.equals(p.propertyId()))
                .findFirst().orElse(null);
    }

    private static String toFinReason(String reason) {
        if ("Tax".equals(reason)) return "vero";
        if (reason != null && reason.startsWith("Card")) return "kortti";
        return "maksu";
    }
}
