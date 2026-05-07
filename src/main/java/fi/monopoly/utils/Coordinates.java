package fi.monopoly.utils;

public record Coordinates(float x, float y, float r) {
    public double getDistance(Coordinates c) {
        return Math.sqrt(Math.pow(x - c.x, 2) + Math.pow(y - c.y, 2));
    }

    public Coordinates(float x, float y) {
        this(x, y, 0);
    }

    public Coordinates(float w) {
        this(w, w, 0);
    }

    public Coordinates() {
        this(0, 0, 0);
    }

    public static Coordinates of(double x, double y) {
        return new Coordinates((float) x, (float) y);
    }

    public static Coordinates of(double w) {
        return new Coordinates((float) w);
    }

    public Coordinates move(float x, float y, float r) {
        return new Coordinates(this.x + x, this.y + y, this.r + r);
    }

    public Coordinates move(float x, float y) {
        return new Coordinates(this.x + x, this.y + y, this.r);
    }

    public Coordinates move(Coordinates coords) {
        return move(coords.x, coords.y, coords.r);
    }

    public static Coordinates of(SpotProps sp) {
        return new Coordinates(sp.x(), sp.y(), sp.r());
    }
}
