package fi.monopoly.utils;

import java.util.concurrent.ThreadLocalRandom;

/** Non-deterministic {@link RandomSource} backed by {@link ThreadLocalRandom}. Used in production. */
final class ThreadLocalRandomSource implements RandomSource {

    static final ThreadLocalRandomSource INSTANCE = new ThreadLocalRandomSource();

    private ThreadLocalRandomSource() {}

    @Override
    public int nextInt(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    @Override
    public double nextDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }

    @Override
    public long nextLong(long origin, long bound) {
        return ThreadLocalRandom.current().nextLong(origin, bound);
    }

    @Override
    public RandomSource derive(String salt) {
        return this; // thread-local is already per-thread; no isolation needed
    }
}
