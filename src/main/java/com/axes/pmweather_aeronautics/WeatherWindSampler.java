package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Backwards-compatible facade for the 0.7 split wind field.
 *
 * WeatherWindField owns PMWeather wind queries, fair sample budgeting, interpolation, and cache stats.
 * AeroSurfaceCache owns Sable exterior patch generation.
 * SableAeroSolver owns conversion from sampled wind pressure into Sable force/torque totals.
 */
final class WeatherWindSampler {
    private WeatherWindSampler() {
    }

    static Vec3 sampleBestWind(final ServerSubLevel subLevel, final Vec3 preferredPosition) {
        return WeatherWindField.sampleBestWind(subLevel, preferredPosition);
    }

    static Vec3 sampleBestAirflowWindCached(final ServerSubLevel subLevel, final Vec3 preferredPosition) {
        return WeatherWindField.sampleBestAirflowWindCached(subLevel, preferredPosition);
    }

    static Vec3 sampleLocalAirflowWindCached(final ServerSubLevel subLevel, final Vec3 samplePosition) {
        return WeatherWindField.sampleLocalAirflowWindCached(subLevel, samplePosition);
    }

    static List<WindSample> sampleWindPointsCached(final ServerSubLevel subLevel, final Vec3 centerPosition) {
        return WeatherWindField.sampleWindPointsCached(subLevel, centerPosition);
    }

    static Vec3 sampleRawWindAt(final ServerLevel level, final Vec3 samplePosition) {
        return WeatherWindField.sampleRawWindAt(level, samplePosition);
    }

    static SampleStats sampleStatsSnapshot() {
        return WeatherWindField.sampleStatsSnapshot();
    }

    record SampleStats(long tick,
                       int hardBudget,
                       int currentFreshQueries,
                       int currentRequestedSamples,
                       int currentCacheHits,
                       int currentBudgetFallbacks,
                       int currentZeroFallbacks,
                       int activeBodyObjectsThisTick,
                       int lastSurfaceSampleTarget,
                       int minSurfaceSampleTarget,
                       int maxSurfaceSampleTarget,
                       double rateSeconds,
                       long rateFreshQueries,
                       long rateRequestedSamples,
                       long rateCacheHits,
                       long rateBudgetFallbacks,
                       long rateZeroFallbacks) {
        static SampleStats empty(final int hardBudget) {
            return new SampleStats(
                    Long.MIN_VALUE,
                    Math.max(1, hardBudget),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    1.0D,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }
    }

    record WindSample(Vec3 samplePosition, Vec3 applicationPosition, Vec3 outwardNormal, Vec3 wind,
                      double areaWeight, int surfaceRole, Vec3 pressureCenterPosition) {
    }
}
