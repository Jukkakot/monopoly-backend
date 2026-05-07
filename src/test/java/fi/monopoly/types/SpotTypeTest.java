package fi.monopoly.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpotTypeTest {

    @Test
    void getNumberOfSpotsReturnsExpectedCounts() {
        assertEquals(2, SpotType.getNumberOfSpots(StreetType.BROWN));
        assertEquals(4, SpotType.getNumberOfSpots(StreetType.RAILROAD));
        assertEquals(2, SpotType.getNumberOfSpots(StreetType.UTILITY));
    }

    @Test
    void propertyMetadataLoadsFromPropertiesFile() {
        assertTrue(SpotType.B1.hasProperty("price"));
        assertFalse(SpotType.GO_SPOT.hasProperty("price"));
        assertFalse(SpotType.B1.getStringProperty("name").isBlank());
        assertTrue(SpotType.B1.getIntegerProperty("price") > 0);
    }
}
