package com.axes.pmweather_aeronautics;

/** Allocation-free per-client-tick limits shared by vanilla and IV particles. */
final class ParticleWindBudget {
    static final int MAX_REFRESH_QUERIES = 8;
    static final int MAX_APPLICATIONS = 4096;

    private final long[] applicationBucketEpoch = new long[MAX_APPLICATIONS];
    private long epoch;
    private int refreshQueries;
    private int applications;

    void beginClientTick() {
        if (epoch == Long.MAX_VALUE) {
            java.util.Arrays.fill(applicationBucketEpoch, 0L);
            epoch = 1L;
        } else {
            epoch++;
        }
        refreshQueries = 0;
        applications = 0;
    }

    boolean reserveRefreshQuery() {
        if (refreshQueries >= MAX_REFRESH_QUERIES) return false;
        refreshQueries++;
        return true;
    }

    boolean reserveApplication(Object particle) {
        if (particle == null) return false;
        int stableHash = System.identityHashCode(particle);
        int tickHash = (int) (epoch * 0x9E3779B97F4A7C15L);
        int bucket = mix(stableHash ^ tickHash) & (MAX_APPLICATIONS - 1);
        if (applicationBucketEpoch[bucket] == epoch) return false;
        applicationBucketEpoch[bucket] = epoch;
        applications++;
        return true;
    }

    int refreshQueries() { return refreshQueries; }
    int applications() { return applications; }
    long epoch() { return epoch; }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        return value ^ (value >>> 16);
    }
}
