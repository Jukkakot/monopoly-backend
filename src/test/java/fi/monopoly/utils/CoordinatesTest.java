package fi.monopoly.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoordinatesTest {

    @Test
    void ofFactoryMethodsCreateExpectedCoordinates() {
        Coordinates point = Coordinates.of(3.5, 7.5);
        Coordinates square = Coordinates.of(4.0);

        assertEquals(3.5f, point.x());
        assertEquals(7.5f, point.y());
        assertEquals(0.0f, point.r());

        assertEquals(4.0f, square.x());
        assertEquals(4.0f, square.y());
        assertEquals(0.0f, square.r());
    }

    @Test
    void moveReturnsNewCoordinatesWithOffsetsApplied() {
        Coordinates base = new Coordinates(10, 20, 30);
        Coordinates moved = base.move(5, -4, 10);
        Coordinates movedByCoordinates = base.move(new Coordinates(1, 2, 3));
        Coordinates movedWithoutRotation = base.move(5, -4);

        assertEquals(new Coordinates(15, 16, 40), moved);
        assertEquals(new Coordinates(11, 22, 33), movedByCoordinates);
        assertEquals(new Coordinates(15, 16, 30), movedWithoutRotation);
    }

    @Test
    void getDistanceReturnsEuclideanDistance() {
        Coordinates start = new Coordinates(0, 0);
        Coordinates end = new Coordinates(3, 4);

        assertEquals(5.0, start.getDistance(end));
    }
}
