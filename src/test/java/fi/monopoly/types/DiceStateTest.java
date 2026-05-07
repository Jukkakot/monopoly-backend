package fi.monopoly.types;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiceStateTest {

    @Test
    void valueOfMapsPairCountsToExpectedStates() {
        assertEquals(DiceState.NOREROLL, DiceState.valueOf(0));
        assertEquals(DiceState.DOUBLES, DiceState.valueOf(1));
        assertEquals(DiceState.DOUBLES, DiceState.valueOf(2));
        assertEquals(DiceState.JAIL, DiceState.valueOf(3));
    }
}
