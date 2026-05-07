package fi.monopoly.application.session.turn;

import fi.monopoly.types.CardType;

import java.util.*;

/**
 * Builds shuffled card deck sequences for use in pure domain sessions.
 *
 * <p>Reads the same properties files as {@link fi.monopoly.components.cards.Cards} but
 * returns a simple {@code List<String>} in {@code "CARDTYPE:entryIndex"} format. This
 * representation is stored in {@link fi.monopoly.domain.session.SessionState} so that
 * deck state is session-local (not a shared global singleton) and is serializable.</p>
 *
 * <p>Format: {@code "MONEY:0"}, {@code "MOVE:2"}, {@code "GO_JAIL:0"}, etc.
 * The first element of the list is the next card to draw.</p>
 */
public final class CardDeckLoader {

    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    private static final String ENTRY_DELIMITER = "#";
    private static final String VALUE_DELIMITER = ";";

    private CardDeckLoader() {}

    /**
     * Builds a shuffled deck for the given bundle name ({@code "chance"} or {@code "community"}).
     *
     * @param bundleName lower-case resource bundle base name
     * @param random     random source for shuffling (pass a seeded random for reproducible tests)
     * @return mutable list of card identifiers, shuffled
     */
    public static List<String> buildDeck(String bundleName, Random random) {
        ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle(bundleName, DEFAULT_LOCALE);
        } catch (MissingResourceException e) {
            return new ArrayList<>();
        }

        List<String> cards = new ArrayList<>();
        for (CardType ct : CardType.values()) {
            String raw = getBundleValue(bundle, ct.name());
            if (raw == null || raw.isBlank()) continue;
            List<String> entries = splitEntries(raw);
            for (int i = 0; i < entries.size(); i++) {
                cards.add(ct.name() + ":" + i);
            }
        }
        Collections.shuffle(cards, random);
        return cards;
    }

    /**
     * Parses the raw values list for a given card entry in a deck.
     *
     * <p>Given a card key {@code "MONEY:0"} and bundle name, returns the list of
     * semicolon-separated value tokens after the text part. For example, {@code "MONEY:0"}
     * in the Chance deck returns {@code ["150"]} (the amount).</p>
     */
    public static List<String> cardValues(String bundleName, String cardKey) {
        String[] parts = cardKey.split(":", 2);
        if (parts.length != 2) return List.of();
        CardType ct;
        try {
            ct = CardType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        int idx;
        try {
            idx = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return List.of();
        }
        ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle(bundleName, DEFAULT_LOCALE);
        } catch (MissingResourceException e) {
            return List.of();
        }
        String raw = getBundleValue(bundle, ct.name());
        if (raw == null || raw.isBlank()) return List.of();
        List<String> entries = splitEntries(raw);
        if (idx >= entries.size()) return List.of();
        String entry = entries.get(idx);
        List<String> valueParts = new ArrayList<>(Arrays.asList(entry.split(VALUE_DELIMITER)));
        if (!valueParts.isEmpty()) valueParts.remove(0); // remove text part
        return valueParts;
    }

    private static String getBundleValue(ResourceBundle bundle, String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return null;
        }
    }

    private static List<String> splitEntries(String raw) {
        return Arrays.stream(raw.split(ENTRY_DELIMITER))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
