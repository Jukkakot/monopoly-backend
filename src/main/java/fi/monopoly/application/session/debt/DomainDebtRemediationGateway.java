package fi.monopoly.application.session.debt;

import fi.monopoly.application.session.SessionStateStore;
import fi.monopoly.application.session.turn.DomainTurnContinuationGateway;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure domain implementation of {@link DebtRemediationGateway} — no Processing runtime objects.
 *
 * <p>All mutations are applied directly to the {@link SessionStateStore}. The gateway is designed
 * to work alongside {@link fi.monopoly.application.session.turn.DomainTurnActionGateway}, which
 * opens {@link DebtStateModel} entries in the store when a player cannot pay rent or tax.</p>
 *
 * <h2>Even-building rule for selling</h2>
 * <p>When selling {@code count} buildings from a property at level {@code L} with the rest of the
 * color set at max level {@code M}, the sell is valid iff {@code L >= count} and
 * {@code L - count >= M - 1} (the property cannot drop more than one level below the set max).</p>
 *
 * <h2>Turn continuation after debt resolution</h2>
 * <p>{@link #payDebtNow()} reads the {@link TurnContinuationState} from the store and applies it
 * directly, mirroring {@link DomainTurnContinuationGateway#resume}. This ensures that after a
 * doubles-triggered debt, the debtor can re-roll.</p>
 */
@Slf4j
@RequiredArgsConstructor
public final class DomainDebtRemediationGateway implements DebtRemediationGateway {

    private static final int UNMORTGAGE_SURCHARGE_PERCENT = 10;

    private final SessionStateStore store;

    // -------------------------------------------------------------------------
    // canXxx — validation queries (no store mutation)
    // -------------------------------------------------------------------------

    @Override
    public boolean canMortgage(String propertyId, String debtorPlayerId) {
        SessionState state = store.get();
        PropertyStateSnapshot prop = findProperty(state, propertyId);
        if (prop == null || prop.mortgaged() || !debtorPlayerId.equals(prop.ownerPlayerId())) {
            return false;
        }
        SpotType spotType = spotTypeOf(propertyId);
        if (spotType == null) return false;

        // Street: no buildings anywhere in the color set before mortgaging
        if (spotType.streetType.placeType == PlaceType.STREET) {
            return spotsOfType(spotType.streetType).stream()
                    .noneMatch(s -> {
                        PropertyStateSnapshot p = findProperty(state, s.name());
                        return p != null && (p.houseCount() > 0 || p.hotelCount() > 0);
                    });
        }
        return true;
    }

    @Override
    public boolean canSellBuildings(String propertyId, int count, String debtorPlayerId) {
        if (count <= 0) return false;
        SessionState state = store.get();
        PropertyStateSnapshot prop = findProperty(state, propertyId);
        if (prop == null || !debtorPlayerId.equals(prop.ownerPlayerId())) return false;

        SpotType spotType = spotTypeOf(propertyId);
        if (spotType == null || spotType.streetType.placeType != PlaceType.STREET) return false;

        int level = buildingLevel(prop);
        if (level < count) return false;

        // Even-selling rule: L - count >= maxRest - 1
        int maxRest = spotsOfType(spotType.streetType).stream()
                .filter(s -> !s.name().equals(propertyId))
                .mapToInt(s -> {
                    PropertyStateSnapshot p = findProperty(state, s.name());
                    return p != null ? buildingLevel(p) : 0;
                })
                .max().orElse(0);
        return level - count >= maxRest - 1;
    }

    @Override
    public boolean canSellBuildingRoundsAcrossSet(String propertyId, int rounds, String debtorPlayerId) {
        if (rounds <= 0) return false;
        SessionState state = store.get();
        PropertyStateSnapshot prop = findProperty(state, propertyId);
        if (prop == null || !debtorPlayerId.equals(prop.ownerPlayerId())) return false;

        SpotType spotType = spotTypeOf(propertyId);
        if (spotType == null || spotType.streetType.placeType != PlaceType.STREET) return false;

        // All properties in set must be owned by debtor and have at least `rounds` buildings
        return spotsOfType(spotType.streetType).stream().allMatch(s -> {
            PropertyStateSnapshot p = findProperty(state, s.name());
            return p != null && debtorPlayerId.equals(p.ownerPlayerId()) && buildingLevel(p) >= rounds;
        });
    }

    // -------------------------------------------------------------------------
    // Mutation operations
    // -------------------------------------------------------------------------

    @Override
    public boolean mortgageProperty(String propertyId) {
        SessionState state = store.get();
        PropertyStateSnapshot prop = findProperty(state, propertyId);
        if (prop == null || prop.mortgaged()) return false;

        String debtorId = activeDebtorId(state);
        if (debtorId == null || !debtorId.equals(prop.ownerPlayerId())) return false;

        SpotType spotType = spotTypeOf(propertyId);
        if (spotType == null) return false;

        int mortgageValue = spotType.getIntegerProperty("price") / 2;
        log.debug("mortgageProperty player={} property={} value={}", debtorId, propertyId, mortgageValue);

        store.update(s -> s.toBuilder()
                .properties(replaceProperty(s.properties(), propertyId,
                        new PropertyStateSnapshot(prop.propertyId(), prop.ownerPlayerId(), true,
                                prop.houseCount(), prop.hotelCount())))
                .players(addCash(s.players(), debtorId, mortgageValue))
                .build());
        return true;
    }

    @Override
    public boolean sellBuildings(String propertyId, int count) {
        SessionState state = store.get();
        PropertyStateSnapshot prop = findProperty(state, propertyId);
        if (prop == null) return false;

        String debtorId = activeDebtorId(state);
        if (debtorId == null || !debtorId.equals(prop.ownerPlayerId())) return false;

        SpotType spotType = spotTypeOf(propertyId);
        if (spotType == null) return false;

        int housePrice = spotType.getIntegerProperty("housePrice");
        if (housePrice <= 0) return false;

        int currentLevel = buildingLevel(prop);
        if (currentLevel < count) return false;

        int newLevel = currentLevel - count;
        int proceeds = count * (housePrice / 2);
        log.debug("sellBuildings player={} property={} count={} proceeds={}", debtorId, propertyId, count, proceeds);

        store.update(s -> s.toBuilder()
                .properties(replaceProperty(s.properties(), propertyId, withBuildingLevel(prop, newLevel)))
                .players(addCash(s.players(), debtorId, proceeds))
                .build());
        return true;
    }

    @Override
    public boolean sellBuildingRoundsAcrossSet(String propertyId, int rounds) {
        SessionState state = store.get();
        PropertyStateSnapshot prop = findProperty(state, propertyId);
        if (prop == null) return false;

        String debtorId = activeDebtorId(state);
        if (debtorId == null || !debtorId.equals(prop.ownerPlayerId())) return false;

        SpotType spotType = spotTypeOf(propertyId);
        if (spotType == null || spotType.streetType.placeType != PlaceType.STREET) return false;

        int housePrice = spotType.getIntegerProperty("housePrice");
        if (housePrice <= 0) return false;

        List<SpotType> colorSet = spotsOfType(spotType.streetType);

        store.update(s -> {
            int[] totalSold = {0};
            List<PropertyStateSnapshot> updatedProps = s.properties().stream()
                    .map(p -> {
                        boolean inSet = colorSet.stream().anyMatch(cs -> cs.name().equals(p.propertyId()));
                        if (!inSet || !debtorId.equals(p.ownerPlayerId())) return p;
                        int origLevel = buildingLevel(p);
                        int newLevel = Math.max(0, origLevel - rounds);
                        totalSold[0] += origLevel - newLevel;
                        return withBuildingLevel(p, newLevel);
                    })
                    .toList();
            int proceeds = totalSold[0] * (housePrice / 2);
            log.debug("sellBuildingRoundsAcrossSet player={} set={} rounds={} sold={} proceeds={}",
                    debtorId, spotType.streetType, rounds, totalSold[0], proceeds);
            return s.toBuilder()
                    .properties(updatedProps)
                    .players(addCash(s.players(), debtorId, proceeds))
                    .build();
        });
        return true;
    }

    @Override
    public void payDebtNow() {
        store.update(state -> {
            DebtStateModel debt = state.activeDebt();
            if (debt == null) return state;

            String debtorId = debt.debtorPlayerId();
            String creditorId = debt.creditorPlayerId();
            int amount = debt.amountRemaining();
            TurnContinuationState continuation = state.turnContinuationState();
            log.debug("payDebtNow debtor={} creditor={} amount={}", debtorId, creditorId, amount);

            List<PlayerSnapshot> updatedPlayers = state.players().stream()
                    .map(p -> {
                        if (debtorId.equals(p.playerId())) return withCashDelta(p, -amount);
                        if (creditorId != null && creditorId.equals(p.playerId())) return withCashDelta(p, amount);
                        return p;
                    })
                    .toList();

            return state.toBuilder()
                    .players(updatedPlayers)
                    .activeDebt(null)
                    .turnContinuationState(null)
                    .turn(resolveTurn(state, updatedPlayers, continuation))
                    .build();
        });
    }

    @Override
    public void declareBankruptcy() {
        store.update(state -> {
            DebtStateModel debt = state.activeDebt();
            String debtorId = debt != null ? debt.debtorPlayerId() : state.turn().activePlayerId();
            String creditorId = debt != null ? debt.creditorPlayerId() : null;
            if (debtorId == null) return state;
            log.info("declareBankruptcy debtor={} creditor={}", debtorId, creditorId);

            // Transfer all debtor's properties to creditor (or to bank if creditorId == null)
            List<PropertyStateSnapshot> updatedProps = state.properties().stream()
                    .map(p -> {
                        if (!debtorId.equals(p.ownerPlayerId())) return p;
                        // Buildings are forfeit; reset to unbuilt
                        return new PropertyStateSnapshot(p.propertyId(), creditorId, false, 0, 0);
                    })
                    .toList();

            int debtorCash = state.players().stream()
                    .filter(p -> debtorId.equals(p.playerId()))
                    .mapToInt(PlayerSnapshot::cash)
                    .findFirst().orElse(0);

            // Compute updated ownedPropertyIds for all players from the new property list
            List<PlayerSnapshot> updatedPlayers = state.players().stream()
                    .map(p -> {
                        if (debtorId.equals(p.playerId())) {
                            return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), 0,
                                    p.boardIndex(), true, true, false, 0, 0, List.of());
                        }
                        boolean isCreditor = creditorId != null && creditorId.equals(p.playerId());
                        int cashGain = isCreditor ? debtorCash : 0;
                        List<String> ownedIds = updatedProps.stream()
                                .filter(prop -> p.playerId().equals(prop.ownerPlayerId()))
                                .map(PropertyStateSnapshot::propertyId)
                                .toList();
                        return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() + cashGain,
                                p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                p.jailRoundsRemaining(), p.getOutOfJailCards(), ownedIds);
                    })
                    .toList();

            // Check for game-over (only one non-eliminated player left)
            List<PlayerSnapshot> stillActive = updatedPlayers.stream()
                    .filter(p -> !p.eliminated() && !p.bankrupt())
                    .toList();
            String winner = stillActive.size() == 1 ? stillActive.get(0).playerId() : null;

            // Advance to the next active player's turn
            String nextPlayer = nextActivePlayerId(updatedPlayers, state.seats(), debtorId);
            TurnState nextTurn = nextPlayer != null
                    ? new TurnState(nextPlayer, TurnPhase.WAITING_FOR_ROLL, true, false, 0)
                    : new TurnState(state.turn().activePlayerId(), TurnPhase.WAITING_FOR_END_TURN, false, true, 0);

            SessionState.SessionStateBuilder builder = state.toBuilder()
                    .properties(updatedProps)
                    .players(updatedPlayers)
                    .activeDebt(null)
                    .turnContinuationState(null)
                    .turn(nextTurn);
            if (winner != null) {
                builder.winnerPlayerId(winner).status(SessionStatus.GAME_OVER);
            }
            return builder.build();
        });
    }

    // -------------------------------------------------------------------------
    // Private: turn resolution after debt payment
    // -------------------------------------------------------------------------

    private TurnState resolveTurn(SessionState state, List<PlayerSnapshot> updatedPlayers,
                                   TurnContinuationState continuation) {
        String activeId = state.turn().activePlayerId();
        if (continuation == null) {
            return new TurnState(activeId, TurnPhase.WAITING_FOR_END_TURN, false, true, 0);
        }
        return switch (continuation.completionAction()) {
            case APPLY_TURN_FOLLOW_UP ->
                    new TurnState(activeId, TurnPhase.WAITING_FOR_ROLL, true, false, state.turn().consecutiveDoubles());
            case END_TURN_WITH_SWITCH -> {
                String next = nextActivePlayerId(updatedPlayers, state.seats(), activeId);
                yield next != null
                        ? new TurnState(next, TurnPhase.WAITING_FOR_ROLL, true, false, 0)
                        : new TurnState(activeId, TurnPhase.WAITING_FOR_END_TURN, false, true, 0);
            }
            case END_TURN_WITHOUT_SWITCH ->
                    new TurnState(activeId, TurnPhase.WAITING_FOR_ROLL, true, false, 0);
            case NONE ->
                    new TurnState(activeId, TurnPhase.WAITING_FOR_END_TURN, false, true, 0);
        };
    }

    private static String nextActivePlayerId(List<PlayerSnapshot> players, List<SeatState> seats,
                                              String currentPlayerId) {
        java.util.Map<String, Integer> seatIndex = seats.stream()
                .collect(java.util.stream.Collectors.toMap(SeatState::playerId, SeatState::seatIndex));
        List<PlayerSnapshot> active = players.stream()
                .filter(p -> !p.eliminated() && !p.bankrupt())
                .sorted(java.util.Comparator.comparingInt(p -> seatIndex.getOrDefault(p.playerId(), Integer.MAX_VALUE)))
                .toList();
        if (active.isEmpty()) return null;
        int idx = -1;
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).playerId().equals(currentPlayerId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return active.get(0).playerId();
        return active.get((idx + 1) % active.size()).playerId();
    }

    // -------------------------------------------------------------------------
    // Private: domain object helpers
    // -------------------------------------------------------------------------

    private static int buildingLevel(PropertyStateSnapshot prop) {
        return prop.hotelCount() > 0 ? 5 : prop.houseCount();
    }

    private static PropertyStateSnapshot withBuildingLevel(PropertyStateSnapshot prop, int newLevel) {
        if (newLevel >= 5) {
            return new PropertyStateSnapshot(prop.propertyId(), prop.ownerPlayerId(), prop.mortgaged(), 0, 1);
        }
        return new PropertyStateSnapshot(prop.propertyId(), prop.ownerPlayerId(), prop.mortgaged(), newLevel, 0);
    }

    private static List<PropertyStateSnapshot> replaceProperty(List<PropertyStateSnapshot> properties,
                                                                 String propertyId,
                                                                 PropertyStateSnapshot replacement) {
        return properties.stream()
                .map(p -> p.propertyId().equals(propertyId) ? replacement : p)
                .toList();
    }

    private static List<PlayerSnapshot> addCash(List<PlayerSnapshot> players, String playerId, int amount) {
        return players.stream()
                .map(p -> playerId.equals(p.playerId()) ? withCashDelta(p, amount) : p)
                .toList();
    }

    private static PlayerSnapshot withCashDelta(PlayerSnapshot p, int delta) {
        return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(), p.cash() + delta,
                p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
    }

    private static String activeDebtorId(SessionState state) {
        return state.activeDebt() != null ? state.activeDebt().debtorPlayerId() : null;
    }

    private static PropertyStateSnapshot findProperty(SessionState state, String propertyId) {
        return state.properties().stream()
                .filter(p -> propertyId.equals(p.propertyId()))
                .findFirst().orElse(null);
    }

    private static SpotType spotTypeOf(String propertyId) {
        try {
            return SpotType.valueOf(propertyId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<SpotType> spotsOfType(StreetType streetType) {
        return SpotType.SPOT_TYPES.stream()
                .filter(s -> s.streetType == streetType && s.isProperty)
                .toList();
    }
}
