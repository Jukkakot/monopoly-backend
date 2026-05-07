package fi.monopoly.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StreetTypeTest {

    @Test
    void streetTypesExposeExpectedPlaceTypes() {
        assertEquals(PlaceType.STREET, StreetType.BROWN.placeType);
        assertEquals(PlaceType.RAILROAD, StreetType.RAILROAD.placeType);
        assertEquals(PlaceType.UTILITY, StreetType.UTILITY.placeType);
        assertEquals(PlaceType.CORNER, StreetType.CORNER.placeType);
    }

    @Test
    void allStreetTypesHavePlaceType() {
        for (StreetType st : StreetType.values()) {
            assertNotNull(st.placeType, "placeType must not be null for " + st);
        }
    }
}
