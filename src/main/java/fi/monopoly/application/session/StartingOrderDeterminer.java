package fi.monopoly.application.session;

import java.util.*;

/**
 * Determines player starting order by simulated two-dice roll.
 *
 * <p>Highest total goes first. Ties are resolved by recursive re-roll among only the tied
 * group, matching standard Finnish Monopoly rules.</p>
 */
public final class StartingOrderDeterminer {
    private StartingOrderDeterminer() {}

    /**
     * Returns the candidates list sorted so the highest roller is first.
     *
     * @param candidates arbitrary integer tokens (e.g. seat indices, player indices)
     * @param rng        random source used for dice rolls
     * @return new list in descending roll order; ties are recursively resolved
     */
    public static List<Integer> determineStartOrder(List<Integer> candidates, Random rng) {
        if (candidates.size() <= 1) {
            return List.copyOf(candidates);
        }
        Map<Integer, Integer> rolls = new LinkedHashMap<>();
        for (int c : candidates) {
            rolls.put(c, (1 + rng.nextInt(6)) + (1 + rng.nextInt(6)));
        }
        TreeMap<Integer, List<Integer>> byRoll = new TreeMap<>(Comparator.reverseOrder());
        for (int c : candidates) {
            byRoll.computeIfAbsent(rolls.get(c), k -> new ArrayList<>()).add(c);
        }
        List<Integer> ordered = new ArrayList<>();
        for (List<Integer> group : byRoll.values()) {
            ordered.addAll(group.size() == 1 ? group : determineStartOrder(group, rng));
        }
        return ordered;
    }
}
