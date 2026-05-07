package fi.monopoly.types;

import fi.monopoly.text.UiTexts;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@ToString(onlyExplicitlyIncluded = true)
@Slf4j
public enum SpotType {
    B1(StreetType.BROWN, 1), B2(StreetType.BROWN, 2),
    LB1(StreetType.LIGHT_BLUE, 1), LB2(StreetType.LIGHT_BLUE, 2), LB3(StreetType.LIGHT_BLUE, 3),
    P1(StreetType.PURPLE, 1), P2(StreetType.PURPLE, 2), P3(StreetType.PURPLE, 3),
    O1(StreetType.ORANGE, 1), O2(StreetType.ORANGE, 2), O3(StreetType.ORANGE, 3),
    R1(StreetType.RED, 1), R2(StreetType.RED, 2), R3(StreetType.RED, 3),
    Y1(StreetType.YELLOW, 1), Y2(StreetType.YELLOW, 2), Y3(StreetType.YELLOW, 3),
    G1(StreetType.GREEN, 1), G2(StreetType.GREEN, 2), G3(StreetType.GREEN, 3),
    DB1(StreetType.DARK_BLUE, 1), DB2(StreetType.DARK_BLUE, 2),
    RR1(StreetType.RAILROAD, 1), RR2(StreetType.RAILROAD, 2), RR3(StreetType.RAILROAD, 3), RR4(StreetType.RAILROAD, 4),
    U1(StreetType.UTILITY, 1), U2(StreetType.UTILITY, 2),
    TAX1(StreetType.TAX, 1, false), TAX2(StreetType.TAX, 2, false),
    COMMUNITY1(StreetType.COMMUNITY, 1, false), COMMUNITY2(StreetType.COMMUNITY, 2, false), COMMUNITY3(StreetType.COMMUNITY, 3, false),
    CHANCE1(StreetType.CHANCE, 1, false), CHANCE2(StreetType.CHANCE, 2, false), CHANCE3(StreetType.CHANCE, 3, false),
    GO_SPOT(StreetType.CORNER, 1, false), JAIL(StreetType.CORNER, 2, false), FREE_PARKING(StreetType.CORNER, 3, false), GO_TO_JAIL(StreetType.CORNER, 4, false);

    public static final List<SpotType> SPOT_TYPES = Arrays.asList(SpotType.GO_SPOT, SpotType.B1, SpotType.COMMUNITY1, SpotType.B2,
            SpotType.TAX1, SpotType.RR1, SpotType.LB1, SpotType.CHANCE1, SpotType.LB2, SpotType.LB3, SpotType.JAIL,
            SpotType.P1, SpotType.U1, SpotType.P2, SpotType.P3, SpotType.RR2, SpotType.O1, SpotType.COMMUNITY2, SpotType.O2, SpotType.O3, SpotType.FREE_PARKING,
            SpotType.R1, SpotType.CHANCE2, SpotType.R2, SpotType.R3, SpotType.RR3, SpotType.Y1, SpotType.Y2, SpotType.U2, SpotType.Y3, SpotType.GO_TO_JAIL,
            SpotType.G1, SpotType.G2, SpotType.COMMUNITY3, SpotType.G3, SpotType.RR4, SpotType.CHANCE3, SpotType.DB1, SpotType.TAX2, SpotType.DB2);
    private static final String BUNDLE_NAME = SpotType.class.getSimpleName();
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    private static Locale loadedLocale = null;
    private static ResourceBundle bundle;
    private final static ResourceBundle defaultBundle = ResourceBundle.getBundle(BUNDLE_NAME, DEFAULT_LOCALE);
    private static final Map<StreetType, Integer> SPOT_COUNT_BY_STREET = createSpotCountByStreet();

    public final StreetType streetType;
    public final int id;
    public final boolean isProperty;

    SpotType(StreetType sType, int id) {
        this.streetType = sType;
        this.id = id;
        this.isProperty = true;
    }

    SpotType(StreetType sType, int id, boolean isProperty) {
        this.streetType = sType;
        this.id = id;
        this.isProperty = isProperty;
    }

    public static SpotType randomType() {
        int randomIndex = (int) (Math.random() * SpotType.values().length);
        return SpotType.values()[randomIndex];
    }

    public static Integer getNumberOfSpots(StreetType streetType) {
        return SPOT_COUNT_BY_STREET.getOrDefault(streetType, 0);
    }

    public boolean hasProperty(String propName) {
        return getProperty(propName) != null;
    }

    public String getStringProperty(String propName) {
        String result = getProperty(propName);
        return result != null ? result : "";
    }

    private static ResourceBundle getBundle() {
        Locale locale = UiTexts.getLocale();
        if (bundle == null || !locale.equals(loadedLocale)) {
            bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale);
            loadedLocale = locale;
        }
        return bundle;
    }

    public int getIntegerProperty(String propName) {
        int result = 0;
        try {
            result = Integer.parseInt(getProperty(propName));
        } catch (NumberFormatException e) {
            log.error("Error getting integer property {} for property: {}", propName, name());
        }
        return result;
    }

    private static String getBundleValue(ResourceBundle bundle, String key) {
        if (!bundle.containsKey(key)) {
            return null;
        }
        return bundle.getString(key);
    }

    private static Map<StreetType, Integer> createSpotCountByStreet() {
        Map<StreetType, Integer> counts = new EnumMap<>(StreetType.class);
        for (SpotType spotType : SPOT_TYPES) {
            counts.merge(spotType.streetType, 1, Integer::sum);
        }
        return counts;
    }

    private String getProperty(String propName) {
        ResourceBundle properties = getBundle();
        String result = getBundleValue(properties, name() + "." + propName);
        if (result == null || result.isBlank()) {
            result = getBundleValue(properties, name().substring(0, name().length() - 1) + "." + propName);
        }
        if (result == null || result.isBlank()) {
            result = getBundleValue(defaultBundle, name() + "." + propName);
        }
        if (result == null || result.isBlank()) {
            result = getBundleValue(defaultBundle, name().substring(0, name().length() - 1) + "." + propName);
        }
        return result;
    }
}
