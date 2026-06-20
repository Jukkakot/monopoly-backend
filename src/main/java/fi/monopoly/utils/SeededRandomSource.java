package fi.monopoly.utils;

import java.util.Random;

/** Deterministic {@link RandomSource} backed by a {@link java.util.Random} with a fixed seed. */
final class SeededRandomSource implements RandomSource {

    private final Random rng;

    SeededRandomSource(Random rng) {
        this.rng = rng;
    }

    @Override
    public int nextInt(int bound) {
        return rng.nextInt(bound);
    }

    @Override
    public double nextDouble() {
        return rng.nextDouble();
    }

    @Override
    public long nextLong(long origin, long bound) {
        if (origin >= bound) throw new IllegalArgumentException("origin must be < bound");
        long range = bound - origin;
        if (range <= 0) {
            // overflow: fallback to bit-masking loop
            long bits;
            long val;
            do {
                bits = (rng.nextLong() >>> 1);
                val = bits % range;
            } while (bits - val + (range - 1) < 0);
            return origin + val;
        }
        return origin + (Math.abs(rng.nextLong()) % range);
    }

    @Override
    public RandomSource derive(String salt) {
        // Derive a child seed deterministically from the current state and salt.
        // XOR with the salt's hash so different salts give independent child streams.
        long childSeed = rng.nextLong() ^ (long) salt.hashCode() * 0x9e3779b97f4a7c15L;
        return new SeededRandomSource(new Random(childSeed));
    }

    @Override
    public Random toJavaRandom() {
        return rng;
    }
}
