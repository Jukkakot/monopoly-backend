package fi.monopoly.server.session;

import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-function strategy helpers shared by {@link PureDomainBotDriver} and the bot tournament runner.
 * All methods are stateless and take only {@link SessionState} + {@link StrongBotConfig} inputs.
 *
 * Package-private: not part of the public server API.
 */
final class StrongBotStrategy {

    private StrongBotStrategy() {}

    // -------------------------------------------------------------------------
    // Buy decision
    // -------------------------------------------------------------------------

    static double buyScore(SessionState state, String botId, String propId, StrongBotConfig cfg) {
        SpotType st = spotType(propId);
        StreetType group = st.streetType;
        double score = 0;

        score += cfg.completionWeight() * (wouldCompleteSet(state, botId, propId) ? 1.0 : 0.0);
        int ownedInSet = ownedInSet(state, botId, group);
        int setSize = setSize(group);
        if (setSize > 1) score += cfg.progressWeight() * (ownedInSet + 1.0) / setSize;

        if (cfg.buyToBlockOpponent()) {
            int bestOpponent = state.players().stream()
                    .filter(p -> !p.playerId().equals(botId) && !p.eliminated() && !p.bankrupt())
                    .mapToInt(p -> ownedInSet(state, p.playerId(), group))
                    .max().orElse(0);
            if (bestOpponent == setSize - 1) score += cfg.opponentBlockWeight() * cfg.opponentLeaderPressure();
        }

        score += switch (group.placeType) {
            case RAILROAD -> cfg.railroadWeight() + ownedInSet * cfg.railroadCompletionWeight() / 10.0;
            case UTILITY  -> cfg.utilityWeight()  + ownedInSet * cfg.utilityCompletionWeight()  / 10.0;
            case STREET   -> {
                int price = st.getIntegerProperty("price");
                yield price >= 200 ? 1.5 : 0.5;
            }
            default -> 0;
        };

        if (wouldCompleteSet(state, botId, propId)) score += cfg.developmentBias();
        else if (ownedInSet > 0) score += cfg.developmentBias() * 0.5;

        score *= cfg.colorGroupWeight(group);

        int reserve = dynamicReserve(state, botId, cfg);
        PlayerSnapshot player = findPlayer(state, botId);
        int postCash = (player != null ? player.cash() : 0) - st.getIntegerProperty("price");
        double liquidityRisk = Math.max(0, reserve - postCash) / 100.0;
        score -= cfg.liquidityPenaltyWeight() * liquidityRisk;

        return score;
    }

    static double buyThreshold(SessionState state, String propId, StrongBotConfig cfg) {
        int unowned = unownedCount(state);
        SpotType st = spotType(propId);
        // Early game (>20 unowned): buy almost everything affordable
        if (unowned > 20) return switch (st.streetType.placeType) {
            case STREET   -> 1.5;
            case RAILROAD -> 2.5;
            case UTILITY  -> 2.5;
            default -> cfg.buyThreshold();
        };
        // Mid game (10-20 unowned): still fairly aggressive — same STREET threshold
        // (first property in a group scores ~2.0, would be blocked at 2.5)
        if (unowned > 10) return switch (st.streetType.placeType) {
            case STREET   -> 1.5;
            case RAILROAD -> 3.0;
            case UTILITY  -> 5.0;
            default -> cfg.buyThreshold();
        };
        // Late game: use config threshold (default 5.5)
        return cfg.buyThreshold();
    }

    static boolean wouldCompleteSet(SessionState state, String botId, String propId) {
        StreetType group = spotType(propId).streetType;
        return ownedInSet(state, botId, group) + 1 >= setSize(group);
    }

    // -------------------------------------------------------------------------
    // Build decision
    // -------------------------------------------------------------------------

    static double buildGroupScore(SessionState state, String playerId, StreetType group, StrongBotConfig cfg) {
        double score = streetStrengthScore(group) * 3.0;
        int roundCost = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && spotType(p.propertyId()).streetType == group)
                .mapToInt(p -> spotType(p.propertyId()).getIntegerProperty("housePrice"))
                .sum();
        if (roundCost > 0) {
            int rentGrowth = state.properties().stream()
                    .filter(p -> playerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                            && spotType(p.propertyId()).streetType == group)
                    .mapToInt(p -> {
                        String rentsStr = spotType(p.propertyId()).getStringProperty("rents");
                        if (rentsStr == null) return 0;
                        String[] rents = rentsStr.split(",");
                        int level = buildingLevel(p);
                        int next = Math.min(5, level + 1);
                        try {
                            int cur = level < rents.length ? Integer.parseInt(rents[level].trim()) : 0;
                            int nxt = next < rents.length ? Integer.parseInt(rents[next].trim()) : 0;
                            return nxt - cur;
                        } catch (Exception e) { return 0; }
                    })
                    .sum();
            score += rentGrowth / (double) roundCost * 100.0;
            score += 100.0 / roundCost;
        }
        score += cfg.developmentBias();
        double avgLevel = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .mapToInt(StrongBotStrategy::buildingLevel)
                .average().orElse(0);
        if (cfg.prioritizeThreeHouses() && avgLevel < 3.0) score += 4.0;
        boolean wouldPushToHotel = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .anyMatch(p -> buildingLevel(p) >= 4);
        if (wouldPushToHotel) score -= cfg.hotelAversion();
        if (unownedCount(state) > 8) score -= 2.0;
        if (boardDangerScore(state, playerId) >= cfg.dangerCashReserve()) score -= 6.0;
        score *= cfg.houseBuildAggression();
        score *= cfg.colorGroupWeight(group);
        return score;
    }

    static boolean canAffordBuildRound(SessionState state, PlayerSnapshot player, StreetType group, int reserve) {
        int roundCost = state.properties().stream()
                .filter(p -> player.playerId().equals(p.ownerPlayerId())
                        && !p.mortgaged()
                        && spotType(p.propertyId()).streetType == group)
                .mapToInt(p -> spotType(p.propertyId()).getIntegerProperty("housePrice"))
                .sum();
        return roundCost > 0 && player.cash() - roundCost >= reserve;
    }

    static PropertyStateSnapshot findBuildTarget(SessionState state, String playerId, StreetType group) {
        List<PropertyStateSnapshot> groupProps = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && !p.mortgaged()
                        && spotType(p.propertyId()).streetType == group
                        && p.hotelCount() == 0)
                .toList();
        int minLevel = groupProps.stream().mapToInt(PropertyStateSnapshot::houseCount).min().orElse(0);
        return groupProps.stream().filter(p -> p.houseCount() == minLevel).findFirst().orElse(null);
    }

    static int maxLevelInGroup(SessionState state, String playerId, StreetType group) {
        return state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId()) && spotType(p.propertyId()).streetType == group)
                .mapToInt(StrongBotStrategy::buildingLevel)
                .max().orElse(0);
    }

    // -------------------------------------------------------------------------
    // Unmortgage decision
    // -------------------------------------------------------------------------

    static double unmortgageScore(PropertyStateSnapshot prop, SessionState state, StrongBotConfig cfg) {
        StreetType group = spotType(prop.propertyId()).streetType;
        double score = streetStrengthScore(group) * 2.5;
        String rentsStr = spotType(prop.propertyId()).getStringProperty("rents");
        if (rentsStr != null && !rentsStr.isBlank()) {
            try {
                int baseRent = Integer.parseInt(rentsStr.split(",")[0].trim());
                score += baseRent / 20.0;
            } catch (Exception ignored) {}
        }
        if (botOwnsFullGroup(state, prop.ownerPlayerId(), group)) score += 12.0 + cfg.developmentBias();
        if (group.placeType == PlaceType.RAILROAD) score += 2.0;
        if (group.placeType == PlaceType.UTILITY)  score -= 1.0;
        if (unownedCount(state) > 10) score -= 2.0;
        // Unmortgaging when an opponent is approaching pays off sooner — prioritise it
        score += opponentLandingDanger(state, prop.ownerPlayerId(), prop) * 3.0;
        score *= cfg.unmortgageAggression();
        score *= cfg.mortgageRecoveryPriority();
        score *= cfg.colorGroupWeight(group);
        return score;
    }

    static int unmortgageCost(String propertyId) {
        int mortgageValue = SpotType.valueOf(propertyId).getIntegerProperty("price") / 2;
        return mortgageValue + (int) (mortgageValue * 0.1);
    }

    // -------------------------------------------------------------------------
    // Reserve calculation
    // -------------------------------------------------------------------------

    /** Dynamic cash reserve for a STRONG bot — scales with board danger and opponent monopolies. */
    static int dynamicReserve(SessionState state, String playerId, StrongBotConfig cfg) {
        int dangerScore  = boardDangerScore(state, playerId);
        int unowned      = unownedCount(state);

        int baseReserve = (dangerScore >= cfg.dangerCashReserve() || unowned <= 10)
                ? cfg.dangerCashReserve() : cfg.minCashReserve();

        int dynamic = cfg.minCashReserve();
        if (dangerScore > cfg.minCashReserve()) {
            dynamic += (int) Math.round((dangerScore - cfg.minCashReserve()) * 0.6);
        }
        if (unowned <= 10) dynamic += 100;
        if (unowned <= 5)  dynamic += 100;
        dynamic += opponentMonopolyCount(state, playerId) * cfg.buildReservePerOpponentMonopoly();
        if (!completedColorGroups(state, playerId).isEmpty()) dynamic += cfg.postMonopolyCashBuffer();

        int raw      = Math.max(baseReserve, dynamic);
        int discount = (int) Math.round(Math.max(0, raw - cfg.minCashReserve()) * cfg.mortgageTolerance());
        return Math.max(cfg.minCashReserve(), raw - discount);
    }

    // -------------------------------------------------------------------------
    // Board analysis helpers
    // -------------------------------------------------------------------------

    static int boardDangerScore(SessionState state, String playerId) {
        return state.properties().stream()
                .filter(p -> p.ownerPlayerId() != null && !p.ownerPlayerId().equals(playerId))
                .filter(p -> p.houseCount() > 0 || p.hotelCount() > 0)
                .mapToInt(p -> {
                    String rentsStr = SpotType.valueOf(p.propertyId()).getStringProperty("rents");
                    if (rentsStr == null || rentsStr.isBlank()) return 0;
                    String[] rents = rentsStr.split(",");
                    int level = buildingLevel(p);
                    if (level >= rents.length) return 0;
                    try { return Integer.parseInt(rents[level].trim()); } catch (Exception e) { return 0; }
                })
                .sum();
    }

    static int unownedCount(SessionState state) {
        return (int) state.properties().stream().filter(p -> p.ownerPlayerId() == null).count();
    }

    static int opponentMonopolyCount(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> !p.playerId().equals(playerId) && !p.eliminated() && !p.bankrupt())
                .mapToInt(p -> completedColorGroupsCount(state, p.playerId()))
                .sum();
    }

    static int completedColorGroupsCount(SessionState state, String playerId) {
        int count = 0;
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer gs = SpotType.getNumberOfSpots(group);
            if (gs == null || gs == 0) continue;
            long owned = state.properties().stream()
                    .filter(p -> playerId.equals(p.ownerPlayerId())
                            && spotType(p.propertyId()).streetType == group)
                    .count();
            if (owned == gs) count++;
        }
        return count;
    }

    static Set<StreetType> completedColorGroups(SessionState state, String playerId) {
        Set<StreetType> result = new HashSet<>();
        for (StreetType group : StreetType.values()) {
            if (group.placeType != PlaceType.STREET) continue;
            Integer groupSize = SpotType.getNumberOfSpots(group);
            if (groupSize == null || groupSize == 0) continue;
            long owned = state.properties().stream()
                    .filter(p -> playerId.equals(p.ownerPlayerId())
                            && !p.mortgaged()
                            && spotType(p.propertyId()).streetType == group)
                    .count();
            if (owned == groupSize) result.add(group);
        }
        return result;
    }

    static boolean botOwnsFullGroup(SessionState state, String playerId, StreetType group) {
        Integer groupSize = SpotType.getNumberOfSpots(group);
        if (groupSize == null || groupSize == 0) return false;
        long owned = state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId())
                        && spotType(p.propertyId()).streetType == group)
                .count();
        return owned == groupSize;
    }

    static int ownedInSet(SessionState state, String playerId, StreetType group) {
        return (int) state.properties().stream()
                .filter(p -> playerId.equals(p.ownerPlayerId())
                        && spotType(p.propertyId()).streetType == group)
                .count();
    }

    static int setSize(StreetType group) {
        return switch (group.placeType) {
            case STREET   -> (group == StreetType.BROWN || group == StreetType.DARK_BLUE) ? 2 : 3;
            case RAILROAD -> 4;
            case UTILITY  -> 2;
            default -> 0;
        };
    }

    static int buildingLevel(PropertyStateSnapshot p) {
        return p.hotelCount() > 0 ? 5 : p.houseCount();
    }

    static double streetStrengthScore(StreetType group) {
        return switch (group) {
            case ORANGE, RED                -> 5;
            case YELLOW, LIGHT_BLUE         -> 4;
            case GREEN, PURPLE              -> 3;
            case DARK_BLUE, BROWN           -> 2;
            default                         -> 1;
        };
    }

    static SpotType spotType(String propertyId) {
        return SpotType.valueOf(propertyId);
    }

    static PlayerSnapshot findPlayer(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> playerId.equals(p.playerId()))
                .findFirst().orElse(null);
    }

    // -------------------------------------------------------------------------
    // Debt resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Priority for mortgaging a property during debt resolution.
     * Lower = sacrifice first (least strategically valuable).
     *
     * <ol>
     *   <li>1 — Utility (worst investment, no group synergy)</li>
     *   <li>2 — Single railroad owned (no synergy yet)</li>
     *   <li>3 — Isolated street (only 1 in the group owned)</li>
     *   <li>4 — Partial railroad set (2+ owned, some synergy)</li>
     *   <li>5 — Partial street group (not near completion)</li>
     *   <li>6 — Near-monopoly street (bot owns n-1 of group — very costly to lose)</li>
     *   <li>9 — Complete monopoly (absolute last resort)</li>
     * </ol>
     */
    static int debtMortgagePriority(SessionState state, String playerId, PropertyStateSnapshot prop) {
        SpotType st = spotType(prop.propertyId());
        StreetType group = st.streetType;

        int base;
        if (group.placeType == PlaceType.UTILITY) {
            base = 1;
        } else if (group.placeType == PlaceType.RAILROAD) {
            base = ownedInSet(state, playerId, group) <= 1 ? 2 : 4;
        } else {
            int owned = ownedInSet(state, playerId, group);
            int gs    = setSize(group);
            if (owned == gs)         base = 9; // full monopoly — last resort
            else if (owned == gs - 1) base = 6; // near-monopoly — keep if at all possible
            else if (owned == 1)      base = 3; // isolated — OK to sacrifice
            else                      base = 5; // partial group
        }

        // Opponent likely to land here soon — protect it from liquidation
        return Math.min(9, base + opponentLandingDanger(state, playerId, prop));
    }

    /**
     * Score for which building to sell first during debt resolution.
     * Lower = sell first (least valuable building to keep).
     *
     * Computed as rent-loss-per-sell-value — the lower this ratio, the less
     * it hurts to sell that building compared to the cash it frees up.
     * A bonus is added when an opponent is likely to land on the property soon.
     */
    static double debtBuildingSellScore(SessionState state, String playerId, PropertyStateSnapshot prop) {
        String rentsStr = spotType(prop.propertyId()).getStringProperty("rents");
        if (rentsStr == null || rentsStr.isBlank()) return 0;
        String[] rents = rentsStr.split(",");
        int level = buildingLevel(prop);
        if (level < 1 || level >= rents.length) return 0;
        try {
            int rentCur  = Integer.parseInt(rents[level].trim());
            int rentPrev = level > 0 ? Integer.parseInt(rents[level - 1].trim()) : 0;
            int rentLoss = rentCur - rentPrev;
            int sellValue = spotType(prop.propertyId()).getIntegerProperty("housePrice") / 2;
            double baseScore = sellValue > 0 ? (double) rentLoss / sellValue : 0;
            // Protect buildings on properties opponents are likely to land on
            return baseScore + opponentLandingDanger(state, playerId, prop) * 0.5;
        } catch (Exception e) { return 0; }
    }

    /**
     * Returns 1 if any active opponent is within 7 board steps of this property
     * (i.e. could land on it on their next average dice roll), 0 otherwise.
     */
    static int opponentLandingDanger(SessionState state, String playerId, PropertyStateSnapshot prop) {
        int propBoardIndex = SpotType.SPOT_TYPES.indexOf(SpotType.valueOf(prop.propertyId()));
        if (propBoardIndex < 0) return 0;
        boolean danger = state.players().stream()
                .filter(p -> !p.playerId().equals(playerId) && !p.bankrupt() && !p.eliminated())
                .anyMatch(p -> {
                    int dist = (propBoardIndex - p.boardIndex() + 40) % 40;
                    return dist >= 1 && dist <= 7;
                });
        return danger ? 1 : 0;
    }

    /**
     * Estimates a player's net worth: cash + sum of owned property face prices.
     * Does not include building value (conservative estimate — buildings are illiquid).
     */
    static int estimateNetWorth(SessionState state, String playerId) {
        PlayerSnapshot p = findPlayer(state, playerId);
        int cash = p != null ? p.cash() : 0;
        return cash + state.properties().stream()
                .filter(prop -> playerId.equals(prop.ownerPlayerId()))
                .mapToInt(prop -> SpotType.valueOf(prop.propertyId()).getIntegerProperty("price"))
                .sum();
    }

    /**
     * Returns true if the player owns n−1 of any color group that has 3+ properties.
     * Two-property groups (Brown, Dark Blue) are excluded because their monopoly threat
     * is low enough that blocking trades is too restrictive.
     */
    static boolean isNearMonopoly(SessionState state, String playerId) {
        for (StreetType g : StreetType.values()) {
            if (g.placeType != PlaceType.STREET) continue;
            Integer gs = SpotType.getNumberOfSpots(g);
            if (gs == null || gs < 3) continue;
            if (ownedInSet(state, playerId, g) == gs - 1) return true;
        }
        return false;
    }

    /**
     * Returns true if this player is a "leading threat" — they are one property away
     * from a strong monopoly (3-property group), or they already have the highest
     * net worth among all active players.  Used to gate trade decisions so the bot
     * does not inadvertently hand the leader a decisive advantage.
     */
    static boolean isLeadingThreat(SessionState state, String playerId) {
        if (isNearMonopoly(state, playerId)) return true;
        int playerWorth = estimateNetWorth(state, playerId);
        return state.players().stream()
                .filter(p -> !p.playerId().equals(playerId) && !p.bankrupt() && !p.eliminated())
                .allMatch(p -> estimateNetWorth(state, p.playerId()) < playerWorth);
    }
}
