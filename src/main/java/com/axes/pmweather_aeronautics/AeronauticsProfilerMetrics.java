package com.axes.pmweather_aeronautics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional low-overhead counters consumed by PMWeather Aeronautics Profiler 0.2.0+.
 * Counters remain inactive when the profiler mod is absent.
 */
public final class AeronauticsProfilerMetrics {
    private static final boolean PRESENT = detectProfiler();
    private static volatile boolean enabled = PRESENT;

    private static final LongAdder BODY_PATCH_REQUESTS = new LongAdder();
    private static final LongAdder BODY_PATCHES_DESIRED = new LongAdder();
    private static final LongAdder BODY_PATCHES_ALLOCATED = new LongAdder();
    private static final LongAdder BALLOON_REGION_REQUESTS = new LongAdder();
    private static final LongAdder BALLOONS_REPRESENTED = new LongAdder();
    private static final LongAdder NON_BALLOON_PROVIDER_REQUESTS = new LongAdder();
    private static final LongAdder AIRFLOW_REGION_REQUESTS = new LongAdder();
    private static final LongAdder AIRFLOW_PROVIDERS_REPRESENTED = new LongAdder();
    private static final LongAdder AIRFLOW_COMPONENTS = new LongAdder();
    private static final LongAdder AIRFLOW_PROVIDERS_DISCOVERED = new LongAdder();
    private static final LongAdder AIRFLOW_PROBES_DESIRED = new LongAdder();
    private static final LongAdder AIRFLOW_PROBES_ALLOCATED = new LongAdder();
    private static final LongAdder CENTER_QUERIES = new LongAdder();
    private static final LongAdder BATCH_CALLS = new LongAdder();
    private static final LongAdder PROVIDER_ONLY_BATCH_CALLS = new LongAdder();
    private static final LongAdder UNCACHED_BATCH_CALLS = new LongAdder();
    private static final LongAdder BATCH_REQUESTS = new LongAdder();
    private static final LongAdder UNIQUE_PENDING_POSITIONS = new LongAdder();
    private static final LongAdder DUPLICATE_POSITIONS_REMOVED = new LongAdder();
    private static final LongAdder CACHE_HITS = new LongAdder();
    private static final LongAdder RAW_QUERIES = new LongAdder();
    private static final LongAdder BUDGET_FALLBACKS = new LongAdder();
    private static final LongAdder ZERO_FALLBACKS = new LongAdder();
    private static final LongAdder TERRAIN_CACHE_HITS = new LongAdder();
    private static final LongAdder TERRAIN_CACHE_MISSES = new LongAdder();
    private static final LongAdder COLLECTION_NANOS = new LongAdder();
    private static final LongAdder PREPARATION_NANOS = new LongAdder();
    private static final LongAdder RESOLUTION_NANOS = new LongAdder();
    private static final LongAdder RAW_QUERY_NANOS = new LongAdder();

    private AeronauticsProfilerMetrics() {
    }

    private static boolean detectProfiler() {
        try {
            Class.forName(
                    "com.axes.pmweather_aeronautics_profiler.ProfilerRecorder",
                    false,
                    AeronauticsProfilerMetrics.class.getClassLoader()
            );
            return true;
        } catch (final ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean active() {
        return enabled;
    }

    public static long timerStart() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void setEnabled(final boolean value) {
        enabled = PRESENT && value;
    }

    public static void addBodyPatches(final long count) {
        add(BODY_PATCH_REQUESTS, count);
    }

    public static void addBalloonRegion(final long balloonsRepresented) {
        if (!enabled) {
            return;
        }
        BALLOON_REGION_REQUESTS.increment();
        if (balloonsRepresented > 0L) {
            BALLOONS_REPRESENTED.add(balloonsRepresented);
        }
    }

    public static void addNonBalloonProvider() {
        if (enabled) {
            NON_BALLOON_PROVIDER_REQUESTS.increment();
        }
    }

    public static void addAirflowRegion(final long providersRepresented) {
        if (!enabled) {
            return;
        }
        AIRFLOW_REGION_REQUESTS.increment();
        if (providersRepresented > 0L) {
            AIRFLOW_PROVIDERS_REPRESENTED.add(providersRepresented);
        }
    }

    public static void addAirflowComponents(final long count) {
        add(AIRFLOW_COMPONENTS, count);
    }


    public static void recordAllocation(final long bodyDesired,
                                        final long bodyAllocated,
                                        final long providersDiscovered,
                                        final long airflowDesired,
                                        final long airflowAllocated) {
        add(BODY_PATCHES_DESIRED, bodyDesired);
        add(BODY_PATCHES_ALLOCATED, bodyAllocated);
        add(AIRFLOW_PROVIDERS_DISCOVERED, providersDiscovered);
        add(AIRFLOW_PROBES_DESIRED, airflowDesired);
        add(AIRFLOW_PROBES_ALLOCATED, airflowAllocated);
    }

    public static void recordCenterQuery() {
        if (enabled) {
            CENTER_QUERIES.increment();
        }
    }

    public static void recordBatch(final long requested,
                                   final long uniquePending,
                                   final long duplicatesRemoved) {
        if (!enabled) {
            return;
        }
        BATCH_CALLS.increment();
        add(BATCH_REQUESTS, requested);
        add(UNIQUE_PENDING_POSITIONS, uniquePending);
        add(DUPLICATE_POSITIONS_REMOVED, duplicatesRemoved);
    }

    public static void recordProviderOnlyBatch() {
        if (enabled) {
            PROVIDER_ONLY_BATCH_CALLS.increment();
        }
    }

    public static void recordUncachedBatch() {
        if (enabled) {
            UNCACHED_BATCH_CALLS.increment();
        }
    }

    public static void recordCacheHit() {
        if (enabled) {
            CACHE_HITS.increment();
        }
    }

    public static void recordRawQuery(final long startedNanos) {
        if (!enabled) {
            return;
        }
        RAW_QUERIES.increment();
        if (startedNanos != 0L) {
            RAW_QUERY_NANOS.add(Math.max(0L, System.nanoTime() - startedNanos));
        }
    }

    public static void recordBudgetFallback(final boolean zero) {
        if (!enabled) {
            return;
        }
        BUDGET_FALLBACKS.increment();
        if (zero) {
            ZERO_FALLBACKS.increment();
        }
    }

    public static void recordTerrainCache(final boolean hit) {
        if (!enabled) {
            return;
        }
        (hit ? TERRAIN_CACHE_HITS : TERRAIN_CACHE_MISSES).increment();
    }

    public static void recordCollectionTime(final long startedNanos) {
        addElapsed(COLLECTION_NANOS, startedNanos);
    }

    public static void recordPreparationTime(final long startedNanos) {
        addElapsed(PREPARATION_NANOS, startedNanos);
    }

    public static void recordResolutionTime(final long startedNanos) {
        addElapsed(RESOLUTION_NANOS, startedNanos);
    }

    public static Map<String, Long> drain() {
        final Map<String, Long> result = new LinkedHashMap<>();
        result.put("body_patch_requests", BODY_PATCH_REQUESTS.sumThenReset());
        result.put("body_patches_desired", BODY_PATCHES_DESIRED.sumThenReset());
        result.put("body_patches_allocated", BODY_PATCHES_ALLOCATED.sumThenReset());
        result.put("balloon_region_requests", BALLOON_REGION_REQUESTS.sumThenReset());
        result.put("balloons_represented", BALLOONS_REPRESENTED.sumThenReset());
        result.put("non_balloon_provider_requests", NON_BALLOON_PROVIDER_REQUESTS.sumThenReset());
        result.put("airflow_region_requests", AIRFLOW_REGION_REQUESTS.sumThenReset());
        result.put("airflow_providers_represented", AIRFLOW_PROVIDERS_REPRESENTED.sumThenReset());
        result.put("airflow_components", AIRFLOW_COMPONENTS.sumThenReset());
        result.put("airflow_providers_discovered", AIRFLOW_PROVIDERS_DISCOVERED.sumThenReset());
        result.put("airflow_probes_desired", AIRFLOW_PROBES_DESIRED.sumThenReset());
        result.put("airflow_probes_allocated", AIRFLOW_PROBES_ALLOCATED.sumThenReset());
        result.put("batch_calls", BATCH_CALLS.sumThenReset());
        result.put("provider_only_batch_calls", PROVIDER_ONLY_BATCH_CALLS.sumThenReset());
        result.put("uncached_batch_calls", UNCACHED_BATCH_CALLS.sumThenReset());
        result.put("batch_requests", BATCH_REQUESTS.sumThenReset());
        result.put("unique_pending_positions", UNIQUE_PENDING_POSITIONS.sumThenReset());
        result.put("duplicate_positions_removed", DUPLICATE_POSITIONS_REMOVED.sumThenReset());
        result.put("cache_hits", CACHE_HITS.sumThenReset());
        result.put("raw_queries", RAW_QUERIES.sumThenReset());
        result.put("budget_fallbacks", BUDGET_FALLBACKS.sumThenReset());
        result.put("zero_fallbacks", ZERO_FALLBACKS.sumThenReset());
        result.put("terrain_cache_hits", TERRAIN_CACHE_HITS.sumThenReset());
        result.put("terrain_cache_misses", TERRAIN_CACHE_MISSES.sumThenReset());
        result.put("collection_nanos", COLLECTION_NANOS.sumThenReset());
        result.put("preparation_nanos", PREPARATION_NANOS.sumThenReset());
        result.put("resolution_nanos", RESOLUTION_NANOS.sumThenReset());
        result.put("raw_query_nanos", RAW_QUERY_NANOS.sumThenReset());
        result.put("center_queries", CENTER_QUERIES.sumThenReset());
        return result;
    }

    private static void add(final LongAdder counter, final long value) {
        if (enabled && value > 0L) {
            counter.add(value);
        }
    }

    private static void addElapsed(final LongAdder counter, final long startedNanos) {
        if (enabled && startedNanos != 0L) {
            counter.add(Math.max(0L, System.nanoTime() - startedNanos));
        }
    }
}
