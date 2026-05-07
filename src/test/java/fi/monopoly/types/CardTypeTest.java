package fi.monopoly.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardTypeTest {

    @Test
    void getTypesReturnsAllEnumValues() {
        assertEquals(CardType.values().length, CardType.getTypes().size());
        assertTrue(CardType.getTypes().contains(CardType.MONEY));
        assertTrue(CardType.getTypes().contains(CardType.GO_JAIL));
    }
}
