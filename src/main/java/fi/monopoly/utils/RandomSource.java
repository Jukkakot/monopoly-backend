package fi.monopoly.utils;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Determinism seam for all randomness used during a game session: dice, card shuffles,
 * bot bid jitter, and (later) personality sampling and softmax selection.
 *
 * <p>One instance is created per game in the evaluation harness, seeded from a fixed value
 * so that the same seed produces a byte-identical play-through. In production the
 * {@link #threadLocal()} factory delegates to {@link ThreadLocalRandom} and is stateless.</p>
 *
 * <p>Per-subsystem streams are obtained via {@link #derive(String)} so that dice RNG and bot
 * noise RNG are independent (one advancing does not advance the other) while still being
 * fully determined by the game seed.</p>
 */
public interface RandomSource {

    /** Returns a uniform int in [0, bound). */
    int nextInt(int bound);

    /** Returns a uniform double in [0.0, 1.0). */
    double nextDouble();

    /** Returns a uniform long in [origin, bound). */
    long nextLong(long origin, long bound);

    /**
     * Returns a child {@code RandomSource} whose sequence is fully determined by this source
     * and {@code salt}, but is independent from this source's ongoing sequence.
     * Useful for isolating dice from bot-noise from shuffle streams under one game seed.
     */
    RandomSource derive(String salt);

    /** Returns a {@code java.util.Random} backed by this source (for APIs that require {@code Random}). */
    default Random toJavaRandom() {
        RandomSource self = this;
        return new Random() {
            @Override public int nextInt(int bound) { return self.nextInt(bound); }
            @Override public double nextDouble()     { return self.nextDouble(); }
            @Override protected int next(int bits) {
                // Fallback for methods not overridden above: use nextInt with 2^bits bound.
                // Only triggered by shuffle / Collections internals that call next() directly.
                return self.nextInt(1 << Math.min(bits, 30));
            }
        };
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /** Creates a fully deterministic source seeded with {@code seed}. */
    static RandomSource seeded(long seed) {
        return new SeededRandomSource(new Random(seed));
    }

    /**
     * Returns a non-deterministic source backed by {@link ThreadLocalRandom}.
     * Used in production where reproducibility is not required.
     */
    static RandomSource threadLocal() {
        return ThreadLocalRandomSource.INSTANCE;
    }

    /**
     * Returns a source that cycles through fixed {@code values} for {@link #nextInt}.
     * Useful in tests to inject specific dice values without touching production code.
     */
    static RandomSource fixedSequence(int... values) {
        int[] idx = {0};
        return new RandomSource() {
            @Override public int nextInt(int bound) { return values[idx[0]++ % values.length]; }
            @Override public double nextDouble()    { return ThreadLocalRandom.current().nextDouble(); }
            @Override public long nextLong(long o, long b) { return ThreadLocalRandom.current().nextLong(o, b); }
            @Override public RandomSource derive(String salt) { return this; }
        };
    }
}
