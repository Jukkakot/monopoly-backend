package fi.monopoly.utils;

public record SpotProps(int x, int y, float w, float h, float r) {
    public SpotProps(int x, int y, float w, float h) {
        this(x, y, w, h, 0);
    }

    public SpotProps(Coordinates c, float w, float h) {
        this((int) c.x(), (int) c.y(), w, h, c.r());
    }
}
