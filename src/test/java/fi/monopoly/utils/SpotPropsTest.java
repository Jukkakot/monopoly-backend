package fi.monopoly.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpotPropsTest {

    @Test
    void constructorsPopulateExpectedFields() {
        SpotProps plain = new SpotProps(10, 20, 30, 40);
        SpotProps fromCoordinates = new SpotProps(new Coordinates(11, 22, 33), 44, 55);

        assertEquals(10, plain.x());
        assertEquals(20, plain.y());
        assertEquals(30.0f, plain.w());
        assertEquals(40.0f, plain.h());
        assertEquals(0.0f, plain.r());

        assertEquals(11, fromCoordinates.x());
        assertEquals(22, fromCoordinates.y());
        assertEquals(44.0f, fromCoordinates.w());
        assertEquals(55.0f, fromCoordinates.h());
        assertEquals(33.0f, fromCoordinates.r());
    }
}
