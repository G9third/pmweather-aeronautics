package com.axes.pmweather_aeronautics;

import dev.protomanly.pmweather.weather.WindEngine;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Samples raw PMWeather wind at exposed points around a Sable sub-level.
 *
 * 0.3.3 sampling notes:
 * - Cached wind samples are interpolated over the configured sample interval. This keeps tornado
 *   direction changes smooth without increasing PMWeather query cost.
 *
 * 0.3.2 sampling notes:
 * - Center wind is sampled for diagnostics/fallback only; body push should use exterior samples
 *   so closed builds do not require holes at the center of mass to catch wind.
 * - 0.5.3 uses a cached exterior aerodynamic profile as the main body-force source.
 *   PMWeather wind is sampled at multiple exposed profile points and queued through Sable ForceTotal instead of reducing a
 *   whole creation to center wind or one averaged exterior point.
 * - Interior sealed rooms are ignored by an exterior-air flood fill, so closed houses catch wind
 *   from outside walls and no longer need roof holes above the center of mass.
 * - 0.5.3 keeps the fair-share surface-sample target. When many Sable objects are active, each
 *   object reduces its even exterior sample count toward a configurable minimum before the old
 *   hard global PMWeather-query budget falls back to cached/stale wind.
 *
 * 0.3.0 optimization notes:
 * - PMWeather wind queries are cached per sub-level, role, and use-case.
 * - Sable can run multiple physics substeps inside one Minecraft tick, and lift providers can call this
 *   for many blocks. Querying PMWeather every one of those calls is the main TPS killer on large builds.
 * - Cached values are still raw PMWeather wind vectors; this does not invent wind, lift, or tumble.
 */
final class WeatherWindField {
    /**
     * PMWeather wind magnitudes are exposed in mph-style weather units. Sable/Rapier velocity is
     * block/second style, and one Minecraft block is treated as roughly one meter. Keep this
     * internal so user config can stay readable: thresholds remain PMWeather/mph values while
     * windInfluence=1.0 means realistic converted physics speed.
     */
    static final double PMWEATHER_MPH_TO_BLOCKS_PER_SECOND = 0.44704D;

    private static final Vec3 CENTER_NORMAL = Vec3.ZERO;
    private static final Vec3 ROOF_NORMAL = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 BOTTOM_NORMAL = new Vec3(0.0D, -1.0D, 0.0D);
    private static final Vec3 WEST_NORMAL = new Vec3(-1.0D, 0.0D, 0.0D);
    private static final Vec3 EAST_NORMAL = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 NORTH_NORMAL = new Vec3(0.0D, 0.0D, -1.0D);
    private static final Vec3 SOUTH_NORMAL = new Vec3(0.0D, 0.0D, 1.0D);

    private static final int ROLE_CENTER = 0;
    private static final int ROLE_ROOF = 1;
    private static final int ROLE_WEST = 2;
    private static final int ROLE_EAST = 3;
    private static final int ROLE_NORTH = 4;
    private static final int ROLE_SOUTH = 5;
    private static final int ROLE_BOTTOM = 6;
    private static final int ROLE_PROFILE_BASE = 1000;

    private static final Map<WindCacheKey, CachedWind> WIND_CACHE = new HashMap<>();
    private static final Map<String, CachedAerodynamicProfile> PROFILE_CACHE = new HashMap<>();
    private static final Vector3d PROFILE_WORLD_POINT = new Vector3d();
    private static final Vector3d PROFILE_WORLD_NORMAL = new Vector3d();
    private static long budgetTick = Long.MIN_VALUE;
    private static int windQueriesThisTick;
    private static int sampleRequestsThisTick;
    private static int cacheHitsThisTick;
    private static int budgetFallbacksThisTick;
    private static int zeroBudgetFallbacksThisTick;
    private static int minSurfaceSampleTargetThisTick = Integer.MAX_VALUE;
    private static int maxSurfaceSampleTargetThisTick;
    private static int lastSurfaceSampleTargetThisTick;
    private static long lastPruneTick = Long.MIN_VALUE;
    private static long rateWindowStartTick = Long.MIN_VALUE;
    private static int rateWindowTicks;
    private static int rateWindowFreshQueries;
    private static int rateWindowRequestedSamples;
    private static int rateWindowCacheHits;
    private static int rateWindowBudgetFallbacks;
    private static int rateWindowZeroFallbacks;
    private static WeatherWindSampler.SampleStats lastSampleStats = WeatherWindSampler.SampleStats.empty(512);
    private static final Set<String> activeBodySampleSubLevelsThisTick = new HashSet<>();
    private static int previousTickBodySampleSubLevelCount = 1;

    private static Method boundingBoxMethod;
    private static boolean searchedBoundingBoxMethod;

    private static Method minXMethod;
    private static Method minYMethod;
    private static Method minZMethod;
    private static Method maxXMethod;
    private static Method maxYMethod;
    private static Method maxZMethod;
    private static Class<?> cachedBoundsClass;

    private WeatherWindField() {
    }


    static double pmweatherSpeedToBlocksPerSecond(final double speed) {
        if (!Double.isFinite(speed)) {
            return 0.0D;
        }
        return speed * PMWEATHER_MPH_TO_BLOCKS_PER_SECOND;
    }

    static Vec3 pmweatherWindToPhysicsWind(final Vec3 wind) {
        if (wind == null || wind == Vec3.ZERO || wind.lengthSqr() <= 1.0e-12D) {
            return Vec3.ZERO;
        }
        return wind.scale(PMWEATHER_MPH_TO_BLOCKS_PER_SECOND);
    }

    static Vec3 sampleBestWind(final ServerSubLevel subLevel, final Vec3 preferredPosition) {
        Vec3 bestWind = sampleWindUncached(subLevel, preferredPosition);
        double bestSpeed = bestWind.length();

        for (final WeatherWindSampler.WindSample sample : sampleWindPointsUncached(subLevel, preferredPosition)) {
            final Vec3 candidate = sample.wind();
            final double candidateSpeed = candidate.length();
            if (candidateSpeed > bestSpeed) {
                bestWind = candidate;
                bestSpeed = candidateSpeed;
            }
        }

        return bestWind;
    }

    static Vec3 sampleBestAirflowWindCached(final ServerSubLevel subLevel, final Vec3 preferredPosition) {
        Vec3 bestWind = Vec3.ZERO;
        double bestSpeed = 0.0D;

        for (final WeatherWindSampler.WindSample sample : sampleWindPointsCached(
                subLevel,
                preferredPosition,
                WindUse.AIRFLOW,
                Config.airflowWindSampleIntervalTicks()
        )) {
            final Vec3 candidate = sample.wind();
            final double candidateSpeed = candidate.length();
            if (candidateSpeed > bestSpeed) {
                bestWind = candidate;
                bestSpeed = candidateSpeed;
            }
        }

        return bestWind;
    }

    static Vec3 sampleLocalAirflowWindCached(final ServerSubLevel subLevel, final Vec3 samplePosition) {
        if (subLevel == null || samplePosition == null) {
            return Vec3.ZERO;
        }
        return sampleWindCached(
                subLevel,
                samplePosition,
                WindUse.AIRFLOW,
                airflowRoleForPosition(samplePosition),
                Config.airflowWindSampleIntervalTicks()
        );
    }

    static List<WeatherWindSampler.WindSample> sampleWindPointsCached(final ServerSubLevel subLevel, final Vec3 centerPosition) {
        return sampleWindPointsCached(
                subLevel,
                centerPosition,
                WindUse.BODY,
                Config.bodyWindSampleIntervalTicks()
        );
    }

    private static List<WeatherWindSampler.WindSample> sampleWindPointsCached(final ServerSubLevel subLevel, final Vec3 centerPosition,
                                                          final WindUse use, final int intervalTicks) {
        return sampleWindPoints(subLevel, centerPosition, use, Math.max(1, intervalTicks), true);
    }

    private static List<WeatherWindSampler.WindSample> sampleWindPointsUncached(final ServerSubLevel subLevel, final Vec3 centerPosition) {
        return sampleWindPoints(subLevel, centerPosition, WindUse.BODY, 1, false);
    }

    private static List<WeatherWindSampler.WindSample> sampleWindPoints(final ServerSubLevel subLevel, final Vec3 centerPosition,
                                                    final WindUse use, final int intervalTicks,
                                                    final boolean cached) {
        final int maxSurfaceSamples = effectiveSurfaceSampleLimit(subLevel, use);
        final List<WeatherWindSampler.WindSample> samples = new ArrayList<>(1 + maxSurfaceSamples);

        // Center is kept as fallback/debug only. The normal wind force should come from exterior
        // aero-profile samples so closed houses catch wind on outside walls instead of needing a
        // hole above the center of mass.
        addSample(subLevel, samples, centerPosition, centerPosition, CENTER_NORMAL, use, ROLE_CENTER, intervalTicks, cached);

        if (!Config.enableEdgeWindSampling() || maxSurfaceSamples <= 0) {
            return samples;
        }

        final AeroSurfaceCache.AerodynamicProfile profile = cached
                ? AeroSurfaceCache.get(subLevel)
                : AeroSurfaceCache.build(subLevel);
        if (profile.samples().isEmpty()) {
            return samples;
        }

        final Pose3d pose = subLevel.logicalPose();
        final double margin = Config.edgeWindSampleMargin();
        final List<AeroSurfaceCache.ProfileFace> selectedProfileFaces = profile.selectedSamples(maxSurfaceSamples);
        if (use == WindUse.BODY) {
            recordSurfaceSampleTarget(selectedProfileFaces.size());
        }
        int profileIndex = 0;
        for (final AeroSurfaceCache.ProfileFace face : selectedProfileFaces) {
            if (samples.size() >= 1 + selectedProfileFaces.size()) {
                break;
            }
            if (face.weight() <= 0.0D || face.point().lengthSqr() <= 1.0e-12D || face.normal().lengthSqr() <= 1.0e-12D) {
                continue;
            }

            PROFILE_WORLD_POINT.set(face.point().x, face.point().y, face.point().z);
            pose.transformPosition(PROFILE_WORLD_POINT, PROFILE_WORLD_POINT);

            PROFILE_WORLD_NORMAL.set(face.normal().x, face.normal().y, face.normal().z);
            pose.transformNormal(PROFILE_WORLD_NORMAL);
            if (PROFILE_WORLD_NORMAL.lengthSquared() <= 1.0e-12D) {
                continue;
            }
            PROFILE_WORLD_NORMAL.normalize();

            final Vec3 applicationPosition = new Vec3(PROFILE_WORLD_POINT.x, PROFILE_WORLD_POINT.y, PROFILE_WORLD_POINT.z);
            final Vec3 outwardNormal = new Vec3(PROFILE_WORLD_NORMAL.x, PROFILE_WORLD_NORMAL.y, PROFILE_WORLD_NORMAL.z);
            if (isWorldBlockedNearExteriorFace(subLevel, applicationPosition, outwardNormal)) {
                continue;
            }
            final int surfaceRole = roleForNormal(face.normal());
            final Vec3 pressureCenterPosition = profile.worldPoint(surfaceRole, applicationPosition, pose);
            final Vec3 samplePosition = new Vec3(
                    applicationPosition.x + outwardNormal.x * margin,
                    applicationPosition.y + outwardNormal.y * margin,
                    applicationPosition.z + outwardNormal.z * margin
            );

            addSample(subLevel, samples, samplePosition, applicationPosition, outwardNormal, use,
                    cacheRoleForFace(face, profileIndex, profile.cacheSalt()), intervalTicks, cached, face.weight(), surfaceRole, pressureCenterPosition);
            profileIndex++;
        }

        return samples;
    }

    private static void addSampleIfRoom(final ServerSubLevel subLevel, final List<WeatherWindSampler.WindSample> samples,
                                        final int maxSamples, final Vec3 samplePosition,
                                        final Vec3 applicationPosition, final Vec3 outwardNormal,
                                        final WindUse use, final int role, final int intervalTicks,
                                        final boolean cached, final double areaWeight) {
        if (samples.size() < maxSamples) {
            addSample(subLevel, samples, samplePosition, applicationPosition, outwardNormal, use, role, intervalTicks, cached, areaWeight);
        }
    }

    private static void addSample(final ServerSubLevel subLevel, final List<WeatherWindSampler.WindSample> samples,
                                  final Vec3 samplePosition, final Vec3 applicationPosition,
                                  final Vec3 outwardNormal, final WindUse use, final int role,
                                  final int intervalTicks, final boolean cached) {
        addSample(subLevel, samples, samplePosition, applicationPosition, outwardNormal, use, role, intervalTicks, cached, 1.0D);
    }

    private static void addSample(final ServerSubLevel subLevel, final List<WeatherWindSampler.WindSample> samples,
                                  final Vec3 samplePosition, final Vec3 applicationPosition,
                                  final Vec3 outwardNormal, final WindUse use, final int role,
                                  final int intervalTicks, final boolean cached, final double areaWeight) {
        addSample(subLevel, samples, samplePosition, applicationPosition, outwardNormal, use, role, intervalTicks, cached,
                areaWeight, roleForNormal(outwardNormal), applicationPosition);
    }

    private static void addSample(final ServerSubLevel subLevel, final List<WeatherWindSampler.WindSample> samples,
                                  final Vec3 samplePosition, final Vec3 applicationPosition,
                                  final Vec3 outwardNormal, final WindUse use, final int role,
                                  final int intervalTicks, final boolean cached, final double areaWeight,
                                  final int surfaceRole, final Vec3 pressureCenterPosition) {
        final Vec3 wind = cached
                ? sampleWindCached(subLevel, samplePosition, use, role, intervalTicks)
                : sampleWindUncached(subLevel, samplePosition);
        samples.add(new WeatherWindSampler.WindSample(samplePosition, applicationPosition, outwardNormal, wind, areaWeight, surfaceRole, pressureCenterPosition));
    }

    private static int cacheRoleForFace(final AeroSurfaceCache.ProfileFace face, final int profileIndex, final int profileCacheSalt) {
        final int role = roleForNormal(face.normal());
        final int safeIndex = Math.max(0, Math.min(8191, profileIndex));
        final int salt = Math.max(0, Math.min(1023, profileCacheSalt));
        return ROLE_PROFILE_BASE + salt * 65536 + role * 8192 + safeIndex;
    }

    private static int airflowRoleForPosition(final Vec3 position) {
        final int x = (int) Math.floor(position.x);
        final int y = (int) Math.floor(position.y);
        final int z = (int) Math.floor(position.z);
        int hash = 0x51ed270b;
        hash = 31 * hash + x;
        hash = 31 * hash + y;
        hash = 31 * hash + z;
        return 0x40000000 | (hash & 0x0fffffff);
    }

    private static boolean isWorldBlockedNearExteriorFace(final ServerSubLevel subLevel,
                                                         final Vec3 applicationPosition,
                                                         final Vec3 outwardNormal) {
        if (subLevel == null || applicationPosition == null || outwardNormal == null || outwardNormal.lengthSqr() <= 1.0e-12D) {
            return false;
        }

        try {
            final Vec3 normal = outwardNormal.normalize();
            final Vec3 probe = new Vec3(
                    applicationPosition.x + normal.x * 0.45D,
                    applicationPosition.y + normal.y * 0.45D,
                    applicationPosition.z + normal.z * 0.45D
            );
            final ServerLevel level = subLevel.getLevel();
            final BlockPos pos = BlockPos.containing(probe.x, probe.y, probe.z);
            if (!level.isLoaded(pos)) {
                return false;
            }
            final BlockState state = level.getBlockState(pos);
            return state != null && !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private static double faceAreaWeight(final double faceArea, final double maxFaceArea) {
        if (!Double.isFinite(faceArea) || !Double.isFinite(maxFaceArea) || maxFaceArea <= 0.0D) {
            return 1.0D;
        }

        final double ratio = Math.max(0.0D, Math.min(1.0D, faceArea / maxFaceArea));
        final double strength = Config.aeroPatchAreaWeightStrength();
        return Math.max(0.15D, 1.0D + (ratio - 1.0D) * strength);
    }

    private static Vec3 sampleWindCached(final ServerSubLevel subLevel, final Vec3 samplePosition,
                                         final WindUse use, final int role, final int intervalTicks) {
        final long currentTick = subLevel.getLevel().getGameTime();
        resetBudgetIfNeeded(currentTick);
        pruneCacheIfNeeded(currentTick);
        sampleRequestsThisTick++;

        final WindCacheKey key = new WindCacheKey(String.valueOf(subLevel.getUniqueId()), use, role);
        final CachedWind cached = WIND_CACHE.get(key);
        if (cached != null && currentTick - cached.tick() < intervalTicks) {
            cacheHitsThisTick++;
            return cached.wind(currentTick);
        }

        if (windQueriesThisTick >= Math.max(1, Config.maxWindSamplesPerTick())) {
            budgetFallbacksThisTick++;
            if (cached == null) {
                zeroBudgetFallbacksThisTick++;
                return Vec3.ZERO;
            }
            return cached.wind(currentTick);
        }

        final Vec3 sampled = sampleWindUncached(subLevel, samplePosition);
        windQueriesThisTick++;

        // Do not snap directly to a new tornado direction. Use the current interpolated wind as the
        // start of the next segment and blend to the newly sampled raw PMWeather wind over the
        // next cache interval. This preserves raw PMWeather wind targets while removing visible
        // square/stepped movement from cached direction updates.
        final Vec3 previous = cached == null ? sampled : cached.wind(currentTick);
        final CachedWind next = new CachedWind(previous, sampled, currentTick, Math.max(1, intervalTicks));
        WIND_CACHE.put(key, next);
        return next.wind(currentTick);
    }

    /**
     * Returns PMWeather's raw wind vector in PMWeather/mph-style units. Do not use this directly
     * as a Sable physics velocity. Convert with pmweatherWindToPhysicsWind(...) at the point where
     * the value becomes body wind, lift-provider airflow, or Sable force.
     */
    static Vec3 sampleRawWindAt(final ServerLevel level, final Vec3 samplePosition) {
        return WindEngine.getWind(
                samplePosition,
                level,
                false,
                !Config.enableTornadoSuction(),
                true
        );
    }

    private static Vec3 sampleWindUncached(final ServerSubLevel subLevel, final Vec3 samplePosition) {
        return sampleRawWindAt(subLevel.getLevel(), samplePosition);
    }

    private static void resetBudgetIfNeeded(final long currentTick) {
        if (budgetTick != currentTick) {
            if (budgetTick != Long.MIN_VALUE) {
                completeSampleStatsWindow(budgetTick);
            }

            budgetTick = currentTick;
            windQueriesThisTick = 0;
            sampleRequestsThisTick = 0;
            cacheHitsThisTick = 0;
            budgetFallbacksThisTick = 0;
            zeroBudgetFallbacksThisTick = 0;
            minSurfaceSampleTargetThisTick = Integer.MAX_VALUE;
            maxSurfaceSampleTargetThisTick = 0;
            lastSurfaceSampleTargetThisTick = 0;
            previousTickBodySampleSubLevelCount = Math.max(1, activeBodySampleSubLevelsThisTick.size());
            activeBodySampleSubLevelsThisTick.clear();
        }
    }

    private static void completeSampleStatsWindow(final long completedTick) {
        if (rateWindowStartTick == Long.MIN_VALUE) {
            rateWindowStartTick = completedTick;
        }

        rateWindowTicks++;
        rateWindowFreshQueries += windQueriesThisTick;
        rateWindowRequestedSamples += sampleRequestsThisTick;
        rateWindowCacheHits += cacheHitsThisTick;
        rateWindowBudgetFallbacks += budgetFallbacksThisTick;
        rateWindowZeroFallbacks += zeroBudgetFallbacksThisTick;

        final int activeObjects = activeBodySampleSubLevelsThisTick.size();
        final int minTarget = minSurfaceSampleTargetThisTick == Integer.MAX_VALUE ? 0 : minSurfaceSampleTargetThisTick;
        final double seconds = Math.max(1.0D / 20.0D, rateWindowTicks / 20.0D);
        if (rateWindowTicks >= 20 || completedTick - rateWindowStartTick >= 20L) {
            lastSampleStats = new WeatherWindSampler.SampleStats(
                    completedTick,
                    Math.max(1, Config.maxWindSamplesPerTick()),
                    windQueriesThisTick,
                    sampleRequestsThisTick,
                    cacheHitsThisTick,
                    budgetFallbacksThisTick,
                    zeroBudgetFallbacksThisTick,
                    activeObjects,
                    lastSurfaceSampleTargetThisTick,
                    minTarget,
                    maxSurfaceSampleTargetThisTick,
                    seconds,
                    Math.round(rateWindowFreshQueries / seconds),
                    Math.round(rateWindowRequestedSamples / seconds),
                    Math.round(rateWindowCacheHits / seconds),
                    Math.round(rateWindowBudgetFallbacks / seconds),
                    Math.round(rateWindowZeroFallbacks / seconds)
            );
            rateWindowStartTick = completedTick;
            rateWindowTicks = 0;
            rateWindowFreshQueries = 0;
            rateWindowRequestedSamples = 0;
            rateWindowCacheHits = 0;
            rateWindowBudgetFallbacks = 0;
            rateWindowZeroFallbacks = 0;
        } else {
            lastSampleStats = new WeatherWindSampler.SampleStats(
                    completedTick,
                    Math.max(1, Config.maxWindSamplesPerTick()),
                    windQueriesThisTick,
                    sampleRequestsThisTick,
                    cacheHitsThisTick,
                    budgetFallbacksThisTick,
                    zeroBudgetFallbacksThisTick,
                    activeObjects,
                    lastSurfaceSampleTargetThisTick,
                    minTarget,
                    maxSurfaceSampleTargetThisTick,
                    seconds,
                    Math.round(rateWindowFreshQueries / seconds),
                    Math.round(rateWindowRequestedSamples / seconds),
                    Math.round(rateWindowCacheHits / seconds),
                    Math.round(rateWindowBudgetFallbacks / seconds),
                    Math.round(rateWindowZeroFallbacks / seconds)
            );
        }
    }

    static WeatherWindSampler.SampleStats sampleStatsSnapshot() {
        return new WeatherWindSampler.SampleStats(
                budgetTick,
                Math.max(1, Config.maxWindSamplesPerTick()),
                windQueriesThisTick,
                sampleRequestsThisTick,
                cacheHitsThisTick,
                budgetFallbacksThisTick,
                zeroBudgetFallbacksThisTick,
                activeBodySampleSubLevelsThisTick.size(),
                lastSurfaceSampleTargetThisTick != 0 ? lastSurfaceSampleTargetThisTick : lastSampleStats.lastSurfaceSampleTarget(),
                minSurfaceSampleTargetThisTick == Integer.MAX_VALUE ? lastSampleStats.minSurfaceSampleTarget() : minSurfaceSampleTargetThisTick,
                maxSurfaceSampleTargetThisTick != 0 ? maxSurfaceSampleTargetThisTick : lastSampleStats.maxSurfaceSampleTarget(),
                lastSampleStats.rateSeconds(),
                lastSampleStats.rateFreshQueries(),
                lastSampleStats.rateRequestedSamples(),
                lastSampleStats.rateCacheHits(),
                lastSampleStats.rateBudgetFallbacks(),
                lastSampleStats.rateZeroFallbacks()
        );
    }

    private static int effectiveSurfaceSampleLimit(final ServerSubLevel subLevel, final WindUse use) {
        final int configuredMax = evenFloor(Math.max(0, Math.min(8192,
                use == WindUse.BODY ? Config.maxAeroPatchSamplesPerObject() : Config.maxFallbackSurfaceWindSamples())));
        if (configuredMax <= 0 || use != WindUse.BODY) {
            recordSurfaceSampleTarget(configuredMax);
            return configuredMax;
        }

        final long currentTick = subLevel.getLevel().getGameTime();
        resetBudgetIfNeeded(currentTick);

        final String subLevelId = String.valueOf(subLevel.getUniqueId());
        activeBodySampleSubLevelsThisTick.add(subLevelId);

        final int activeEstimate = Math.max(previousTickBodySampleSubLevelCount, activeBodySampleSubLevelsThisTick.size());
        final int hardBudget = Math.max(1, Config.maxWindSamplesPerTick());
        final int perObjectTotalBudget = Math.max(1, hardBudget / Math.max(1, activeEstimate));

        // The center sample is still requested for fallback/debug and counts against the hard query
        // budget, so reserve one slot before assigning exterior aero-profile surface samples.
        final int fairSurfaceBudget = evenFloor(Math.max(0, perObjectTotalBudget - 1));

        // Reduce evenly down to this minimum before allowing the older hard budget/cache fallback to
        // decide which requests get fresh PMWeather wind.
        final int configuredMin = Math.min(configuredMax, evenFloor(Math.max(0, Math.min(8192, Config.minAeroPatchCount()))));
        final int target = fairSurfaceBudget >= configuredMax ? configuredMax : Math.max(configuredMin, fairSurfaceBudget);
        recordSurfaceSampleTarget(target);
        return target;
    }

    private static void recordSurfaceSampleTarget(final int target) {
        final int evenTarget = evenFloor(target);
        lastSurfaceSampleTargetThisTick = evenTarget;
        minSurfaceSampleTargetThisTick = Math.min(minSurfaceSampleTargetThisTick, evenTarget);
        maxSurfaceSampleTargetThisTick = Math.max(maxSurfaceSampleTargetThisTick, evenTarget);
    }

    private static int evenFloor(final int value) {
        final int clamped = Math.max(0, value);
        return clamped - (clamped & 1);
    }

    private static void pruneCacheIfNeeded(final long currentTick) {
        if (lastPruneTick == currentTick || currentTick % 200L != 0L) {
            return;
        }

        lastPruneTick = currentTick;
        final Iterator<Map.Entry<WindCacheKey, CachedWind>> iterator = WIND_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<WindCacheKey, CachedWind> entry = iterator.next();
            if (currentTick - entry.getValue().tick() > 400L) {
                iterator.remove();
            }
        }

        final Iterator<Map.Entry<String, CachedAerodynamicProfile>> profileIterator = PROFILE_CACHE.entrySet().iterator();
        while (profileIterator.hasNext()) {
            final Map.Entry<String, CachedAerodynamicProfile> entry = profileIterator.next();
            if (currentTick - entry.getValue().tick() > 1200L) {
                profileIterator.remove();
            }
        }
    }

    private static AerodynamicProfile getAerodynamicProfileCached(final ServerSubLevel subLevel) {
        final String key = String.valueOf(subLevel.getUniqueId());
        final ProfileBounds bounds = getProfileBounds(subLevel);
        if (bounds == null) {
            return AerodynamicProfile.EMPTY;
        }

        final long currentTick = subLevel.getLevel().getGameTime();
        final CachedAerodynamicProfile cached = PROFILE_CACHE.get(key);
        if (cached != null && cached.bounds().equals(bounds) && currentTick - cached.tick() < 1200L) {
            return cached.profile();
        }

        final AerodynamicProfile profile = buildAerodynamicProfile(subLevel, bounds);
        PROFILE_CACHE.put(key, new CachedAerodynamicProfile(bounds, profile, currentTick));
        return profile;
    }

    private static AerodynamicProfile buildAerodynamicProfile(final ServerSubLevel subLevel) {
        final ProfileBounds bounds = getProfileBounds(subLevel);
        return bounds == null ? AerodynamicProfile.EMPTY : buildAerodynamicProfile(subLevel, bounds);
    }

    private static AerodynamicProfile buildAerodynamicProfile(final ServerSubLevel subLevel, final ProfileBounds bounds) {
        // 0.6.0 full-surface patch pipeline:
        // Use the actual exposed block faces from the Sable plot, greedily merge connected coplanar
        // faces into rectangular patches, and cache that real exterior surface. Runtime LOD then
        // merges this full surface only when the PMWeather wind-query budget is tight.
        final List<ProfileFace> rawSurfacePatches = buildFullResolutionSurfacePatches(subLevel, bounds);
        if (rawSurfacePatches.isEmpty()) {
            return AerodynamicProfile.EMPTY;
        }

        final int maxCachedPatches = Math.max(64, Math.min(32768, Config.maxCachedAeroPatches()));
        final List<ProfileFace> cachedPatches = rawSurfacePatches.size() > maxCachedPatches
                ? selectProfileSamples(rawSurfacePatches, maxCachedPatches)
                : normalizeProfileWeights(rawSurfacePatches);

        final ProfileAccumulator west = new ProfileAccumulator();
        final ProfileAccumulator east = new ProfileAccumulator();
        final ProfileAccumulator north = new ProfileAccumulator();
        final ProfileAccumulator south = new ProfileAccumulator();
        final ProfileAccumulator roof = new ProfileAccumulator();
        final ProfileAccumulator bottom = new ProfileAccumulator();
        for (final ProfileFace face : cachedPatches) {
            accumulatorForRole(roleForNormal(face.normal()), west, east, north, south, roof, bottom)
                    .add(face.point().x, face.point().y, face.point().z, Math.max(0.0D, face.weight()));
        }

        final double maxArea = Math.max(1.0D, Math.max(Math.max(west.weight, east.weight),
                Math.max(Math.max(north.weight, south.weight), Math.max(roof.weight, bottom.weight))));

        return new AerodynamicProfile(
                profileFace(west, maxArea, ROLE_WEST, new Vec3(bounds.minX(), midpoint(bounds.minY(), bounds.maxY() + 1.0D), midpoint(bounds.minZ(), bounds.maxZ() + 1.0D))),
                profileFace(east, maxArea, ROLE_EAST, new Vec3(bounds.maxX() + 1.0D, midpoint(bounds.minY(), bounds.maxY() + 1.0D), midpoint(bounds.minZ(), bounds.maxZ() + 1.0D))),
                profileFace(north, maxArea, ROLE_NORTH, new Vec3(midpoint(bounds.minX(), bounds.maxX() + 1.0D), midpoint(bounds.minY(), bounds.maxY() + 1.0D), bounds.minZ())),
                profileFace(south, maxArea, ROLE_SOUTH, new Vec3(midpoint(bounds.minX(), bounds.maxX() + 1.0D), midpoint(bounds.minY(), bounds.maxY() + 1.0D), bounds.maxZ() + 1.0D)),
                profileFace(roof, maxArea, ROLE_ROOF, new Vec3(midpoint(bounds.minX(), bounds.maxX() + 1.0D), bounds.maxY() + 1.0D, midpoint(bounds.minZ(), bounds.maxZ() + 1.0D))),
                profileFace(bottom, maxArea, ROLE_BOTTOM, new Vec3(midpoint(bounds.minX(), bounds.maxX() + 1.0D), bounds.minY(), midpoint(bounds.minZ(), bounds.maxZ() + 1.0D))),
                cachedPatches
        );
    }

    private static ProfileAccumulator accumulatorForRole(final int role,
                                                         final ProfileAccumulator west,
                                                         final ProfileAccumulator east,
                                                         final ProfileAccumulator north,
                                                         final ProfileAccumulator south,
                                                         final ProfileAccumulator roof,
                                                         final ProfileAccumulator bottom) {
        return switch (role) {
            case ROLE_EAST -> east;
            case ROLE_NORTH -> north;
            case ROLE_SOUTH -> south;
            case ROLE_ROOF -> roof;
            case ROLE_BOTTOM -> bottom;
            default -> west;
        };
    }

    private static List<ProfileFace> buildFullResolutionSurfacePatches(final ServerSubLevel subLevel,
                                                                       final ProfileBounds bounds) {
        final Map<SurfacePlaneKey, Set<Long>> planes = new HashMap<>();
        final Set<Long> outsideAir = computeFullResolutionOutsideAir(subLevel, bounds);
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    if (!isSolidAt(subLevel, x, y, z)) {
                        continue;
                    }
                    if (isFullResolutionOutsideAir(outsideAir, bounds, x - 1, y, z)) {
                        addSurfaceCell(planes, ROLE_WEST, x, y, z, bounds.minY(), bounds.minZ());
                    }
                    if (isFullResolutionOutsideAir(outsideAir, bounds, x + 1, y, z)) {
                        addSurfaceCell(planes, ROLE_EAST, x + 1, y, z, bounds.minY(), bounds.minZ());
                    }
                    if (isFullResolutionOutsideAir(outsideAir, bounds, x, y, z - 1)) {
                        addSurfaceCell(planes, ROLE_NORTH, z, x, y, bounds.minX(), bounds.minY());
                    }
                    if (isFullResolutionOutsideAir(outsideAir, bounds, x, y, z + 1)) {
                        addSurfaceCell(planes, ROLE_SOUTH, z + 1, x, y, bounds.minX(), bounds.minY());
                    }
                    if (isFullResolutionOutsideAir(outsideAir, bounds, x, y + 1, z)) {
                        addSurfaceCell(planes, ROLE_ROOF, y + 1, x, z, bounds.minX(), bounds.minZ());
                    }
                    if (isFullResolutionOutsideAir(outsideAir, bounds, x, y - 1, z)) {
                        addSurfaceCell(planes, ROLE_BOTTOM, y, x, z, bounds.minX(), bounds.minZ());
                    }
                }
            }
        }

        final List<ProfileFace> patches = new ArrayList<>();
        for (final Map.Entry<SurfacePlaneKey, Set<Long>> entry : planes.entrySet()) {
            mergePlaneCells(entry.getKey(), entry.getValue(), patches);
        }
        patches.sort(Comparator
                .comparingInt((ProfileFace face) -> roleForNormal(face.normal()))
                .thenComparingDouble(face -> face.point().x)
                .thenComparingDouble(face -> face.point().y)
                .thenComparingDouble(face -> face.point().z));
        return patches;
    }


    private static Set<Long> computeFullResolutionOutsideAir(final ServerSubLevel subLevel,
                                                             final ProfileBounds bounds) {
        final int minX = bounds.minX() - 1;
        final int minY = bounds.minY() - 1;
        final int minZ = bounds.minZ() - 1;
        final int maxX = bounds.maxX() + 1;
        final int maxY = bounds.maxY() + 1;
        final int maxZ = bounds.maxZ() + 1;
        final Set<Long> outside = new HashSet<>();
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {minX, minY, minZ});
        outside.add(packAirCell(minX - minX, minY - minY, minZ - minZ));

        while (!queue.isEmpty()) {
            final int[] current = queue.removeFirst();
            fullResolutionFloodNeighbor(subLevel, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0] + 1, current[1], current[2]);
            fullResolutionFloodNeighbor(subLevel, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0] - 1, current[1], current[2]);
            fullResolutionFloodNeighbor(subLevel, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1] + 1, current[2]);
            fullResolutionFloodNeighbor(subLevel, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1] - 1, current[2]);
            fullResolutionFloodNeighbor(subLevel, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1], current[2] + 1);
            fullResolutionFloodNeighbor(subLevel, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1], current[2] - 1);
        }
        return outside;
    }

    private static void fullResolutionFloodNeighbor(final ServerSubLevel subLevel,
                                                    final Set<Long> outside,
                                                    final ArrayDeque<int[]> queue,
                                                    final int minX, final int minY, final int minZ,
                                                    final int maxX, final int maxY, final int maxZ,
                                                    final int x, final int y, final int z) {
        if (x < minX || y < minY || z < minZ || x > maxX || y > maxY || z > maxZ) {
            return;
        }
        final long packed = packAirCell(x - minX, y - minY, z - minZ);
        if (outside.contains(packed) || isSolidAt(subLevel, x, y, z)) {
            return;
        }
        outside.add(packed);
        queue.add(new int[] {x, y, z});
    }

    private static boolean isFullResolutionOutsideAir(final Set<Long> outside,
                                                      final ProfileBounds bounds,
                                                      final int x, final int y, final int z) {
        final int minX = bounds.minX() - 1;
        final int minY = bounds.minY() - 1;
        final int minZ = bounds.minZ() - 1;
        final int maxX = bounds.maxX() + 1;
        final int maxY = bounds.maxY() + 1;
        final int maxZ = bounds.maxZ() + 1;
        if (x < minX || y < minY || z < minZ || x > maxX || y > maxY || z > maxZ) {
            return true;
        }
        return outside.contains(packAirCell(x - minX, y - minY, z - minZ));
    }

    private static long packAirCell(final int x, final int y, final int z) {
        return ((long) (x & 0x1fffff) << 42)
                | ((long) (y & 0x1fffff) << 21)
                | (z & 0x1fffffL);
    }

    private static void addSurfaceCell(final Map<SurfacePlaneKey, Set<Long>> planes,
                                       final int role, final int plane,
                                       final int a, final int b,
                                       final int minA, final int minB) {
        final SurfacePlaneKey key = new SurfacePlaneKey(role, plane, minA, minB);
        planes.computeIfAbsent(key, ignored -> new HashSet<>()).add(packCell(a - minA, b - minB));
    }

    private static long packCell(final int a, final int b) {
        return (((long) a) << 32) ^ (b & 0xffffffffL);
    }

    private static int unpackA(final long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackB(final long packed) {
        return (int) packed;
    }

    private static void mergePlaneCells(final SurfacePlaneKey key, final Set<Long> cells,
                                        final List<ProfileFace> patches) {
        if (cells.isEmpty()) {
            return;
        }
        final Set<Long> remaining = new HashSet<>(cells);
        final List<Long> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator
                .comparingInt((Long packed) -> unpackA(packed))
                .thenComparingInt((Long packed) -> unpackB(packed)));

        for (final long start : sorted) {
            if (!remaining.contains(start)) {
                continue;
            }
            final int a0 = unpackA(start);
            final int b0 = unpackB(start);
            int width = 1;
            while (remaining.contains(packCell(a0, b0 + width))) {
                width++;
            }
            int height = 1;
            outer:
            while (true) {
                for (int db = 0; db < width; db++) {
                    if (!remaining.contains(packCell(a0 + height, b0 + db))) {
                        break outer;
                    }
                }
                height++;
            }
            for (int da = 0; da < height; da++) {
                for (int db = 0; db < width; db++) {
                    remaining.remove(packCell(a0 + da, b0 + db));
                }
            }
            patches.add(mergedPatch(key, a0, b0, height, width));
        }
    }

    private static ProfileFace mergedPatch(final SurfacePlaneKey key, final int a0, final int b0,
                                           final int height, final int width) {
        final double area = Math.max(1.0D, height * (double) width);
        final double aCenter = key.minA() + a0 + height * 0.5D;
        final double bCenter = key.minB() + b0 + width * 0.5D;
        final Vec3 normal = localNormalForRole(key.role());
        final Vec3 point = switch (key.role()) {
            case ROLE_WEST, ROLE_EAST -> new Vec3(key.plane(), aCenter, bCenter);
            case ROLE_NORTH, ROLE_SOUTH -> new Vec3(aCenter, bCenter, key.plane());
            case ROLE_ROOF, ROLE_BOTTOM -> new Vec3(aCenter, key.plane(), bCenter);
            default -> Vec3.ZERO;
        };
        return new ProfileFace(point, normal, area);
    }

    private static ProfileFace profileFace(final ProfileAccumulator accumulator,
                                           final double maxArea, final int role, final Vec3 fallbackLocalPoint) {
        // Store LOCAL profile data in the cache. World-space pressure points/normals must be computed
        // from the current pose every tick; caching world values causes violent self-spin after a body rotates.
        final Vec3 localPoint = accumulator.weight > 0.0D ? accumulator.average() : fallbackLocalPoint;
        final Vec3 localNormal = localNormalForRole(role);
        final double weight = accumulator.weight <= 0.0D ? 0.0D : Math.max(0.08D, Math.min(1.0D, accumulator.weight / maxArea));
        return new ProfileFace(localPoint, localNormal, weight);
    }


    private static List<ProfileFace> selectProfileSamples(final List<ProfileFace> rawSamples,
                                                          final int requestedSamples) {
        if (rawSamples.isEmpty() || requestedSamples <= 0) {
            return List.of();
        }

        final int percentFloor = (int) Math.ceil(rawSamples.size() * Math.max(0.0D, Math.min(1.0D, Config.minAeroPatchDetailPercent())));
        final int absoluteFloor = Math.max(1, Math.min(256, Config.minAeroPatchCount()));
        final int minimum = Math.min(rawSamples.size(), Math.max(percentFloor, absoluteFloor));
        final int selectedCount = Math.min(rawSamples.size(), Math.max(minimum, requestedSamples));
        if (selectedCount >= rawSamples.size()) {
            return normalizeProfileWeights(rawSamples);
        }

        final Map<Integer, List<ProfileFace>> byRole = new HashMap<>();
        final Map<Integer, Double> roleAreas = new HashMap<>();
        double totalArea = 0.0D;
        for (final ProfileFace face : rawSamples) {
            final int role = roleForNormal(face.normal());
            final double area = Math.max(0.0D, face.weight());
            byRole.computeIfAbsent(role, ignored -> new ArrayList<>()).add(face);
            roleAreas.put(role, roleAreas.getOrDefault(role, 0.0D) + area);
            totalArea += area;
        }
        if (byRole.isEmpty() || totalArea <= 0.0D) {
            return List.of();
        }

        final Map<Integer, Integer> allocation = new HashMap<>();
        final List<Integer> roles = new ArrayList<>(byRole.keySet());
        roles.sort(Comparator.comparingDouble((Integer role) -> -roleAreas.getOrDefault(role, 0.0D)));

        int remaining = selectedCount;
        for (final int role : roles) {
            if (remaining <= 0) {
                break;
            }
            allocation.put(role, 1);
            remaining--;
        }
        while (remaining > 0) {
            int bestRole = roles.get(0);
            double bestDeficit = Double.NEGATIVE_INFINITY;
            for (final int role : roles) {
                final int current = allocation.getOrDefault(role, 0);
                final int maxForRole = byRole.get(role).size();
                if (current >= maxForRole) {
                    continue;
                }
                final double ideal = selectedCount * roleAreas.getOrDefault(role, 0.0D) / totalArea;
                final double deficit = ideal - current;
                if (deficit > bestDeficit) {
                    bestDeficit = deficit;
                    bestRole = role;
                }
            }
            allocation.put(bestRole, allocation.getOrDefault(bestRole, 0) + 1);
            remaining--;
        }

        final List<ProfileFace> selected = new ArrayList<>(selectedCount);
        for (final int role : roles) {
            final List<ProfileFace> roleFaces = byRole.get(role);
            final int count = Math.max(0, Math.min(roleFaces.size(), allocation.getOrDefault(role, 0)));
            if (count <= 0) {
                continue;
            }
            selected.addAll(aggregateRolePatches(roleFaces, count));
        }
        return normalizeProfileWeights(selected);
    }

    private static List<ProfileFace> normalizeProfileWeights(final List<ProfileFace> samples) {
        if (samples.isEmpty()) {
            return List.of();
        }
        double totalArea = 0.0D;
        for (final ProfileFace sample : samples) {
            totalArea += Math.max(0.0D, sample.weight());
        }
        final double averageArea = totalArea <= 1.0e-12D ? 1.0D : totalArea / Math.max(1, samples.size());
        final List<ProfileFace> normalized = new ArrayList<>(samples.size());
        for (final ProfileFace sample : samples) {
            final double weight = Math.max(0.05D, Math.min(24.0D, Math.max(0.0D, sample.weight()) / averageArea));
            normalized.add(new ProfileFace(sample.point(), sample.normal(), weight));
        }
        return List.copyOf(normalized);
    }

    private static List<ProfileFace> aggregateRolePatches(final List<ProfileFace> roleFaces, final int targetCount) {
        if (roleFaces.isEmpty() || targetCount <= 0) {
            return List.of();
        }
        if (targetCount >= roleFaces.size()) {
            return roleFaces;
        }

        final List<ProfileFace> sorted = new ArrayList<>(roleFaces);
        sorted.sort(Comparator
                .comparingDouble((ProfileFace face) -> primarySort(face, roleForNormal(face.normal())))
                .thenComparingDouble(face -> secondarySort(face, roleForNormal(face.normal())))
                .thenComparingDouble(face -> tertiarySort(face, roleForNormal(face.normal()))));

        final List<ProfileFace> aggregated = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) {
            final int start = (int) Math.floor(i * sorted.size() / (double) targetCount);
            final int end = (int) Math.floor((i + 1) * sorted.size() / (double) targetCount);
            aggregated.add(aggregatePatchGroup(sorted.subList(start, Math.max(start + 1, end))));
        }
        return aggregated;
    }

    private static ProfileFace aggregatePatchGroup(final List<ProfileFace> group) {
        double totalArea = 0.0D;
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        Vec3 normal = group.get(0).normal();
        for (final ProfileFace face : group) {
            final double area = Math.max(0.0D, face.weight());
            x += face.point().x * area;
            y += face.point().y * area;
            z += face.point().z * area;
            totalArea += area;
        }
        if (totalArea <= 1.0e-12D) {
            return group.get(0);
        }
        return new ProfileFace(new Vec3(x / totalArea, y / totalArea, z / totalArea), normal, totalArea);
    }

    private static double primarySort(final ProfileFace face, final int role) {
        return switch (role) {
            case ROLE_WEST, ROLE_EAST -> face.point().z;
            case ROLE_NORTH, ROLE_SOUTH -> face.point().x;
            case ROLE_ROOF, ROLE_BOTTOM -> face.point().x;
            default -> face.point().x;
        };
    }

    private static double secondarySort(final ProfileFace face, final int role) {
        return switch (role) {
            case ROLE_WEST, ROLE_EAST -> face.point().y;
            case ROLE_NORTH, ROLE_SOUTH -> face.point().y;
            case ROLE_ROOF, ROLE_BOTTOM -> face.point().z;
            default -> face.point().y;
        };
    }

    private static double tertiarySort(final ProfileFace face, final int role) {
        return switch (role) {
            case ROLE_WEST, ROLE_EAST -> face.point().x;
            case ROLE_NORTH, ROLE_SOUTH -> face.point().z;
            case ROLE_ROOF, ROLE_BOTTOM -> face.point().y;
            default -> face.point().z;
        };
    }

    private static boolean[][][] computeOutsideAir(final boolean[][][] solid, final int rx, final int ry, final int rz) {
        final boolean[][][] outside = new boolean[rx + 2][ry + 2][rz + 2];
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        outside[0][0][0] = true;
        queue.add(new int[] {0, 0, 0});

        while (!queue.isEmpty()) {
            final int[] current = queue.removeFirst();
            floodNeighbor(solid, outside, queue, rx, ry, rz, current[0] + 1, current[1], current[2]);
            floodNeighbor(solid, outside, queue, rx, ry, rz, current[0] - 1, current[1], current[2]);
            floodNeighbor(solid, outside, queue, rx, ry, rz, current[0], current[1] + 1, current[2]);
            floodNeighbor(solid, outside, queue, rx, ry, rz, current[0], current[1] - 1, current[2]);
            floodNeighbor(solid, outside, queue, rx, ry, rz, current[0], current[1], current[2] + 1);
            floodNeighbor(solid, outside, queue, rx, ry, rz, current[0], current[1], current[2] - 1);
        }

        return outside;
    }

    private static void floodNeighbor(final boolean[][][] solid, final boolean[][][] outside,
                                      final ArrayDeque<int[]> queue,
                                      final int rx, final int ry, final int rz,
                                      final int x, final int y, final int z) {
        if (x < 0 || y < 0 || z < 0 || x > rx + 1 || y > ry + 1 || z > rz + 1 || outside[x][y][z]) {
            return;
        }
        if (x >= 1 && x <= rx && y >= 1 && y <= ry && z >= 1 && z <= rz && solid[x - 1][y - 1][z - 1]) {
            return;
        }
        outside[x][y][z] = true;
        queue.add(new int[] {x, y, z});
    }

    private static boolean isOutsideAir(final boolean[][][] outside, final int x, final int y, final int z) {
        return x >= 0 && y >= 0 && z >= 0
                && x < outside.length
                && y < outside[x].length
                && z < outside[x][y].length
                && outside[x][y][z];
    }

    private static Vec3 localNormalForRole(final int role) {
        return switch (role) {
            case ROLE_ROOF -> ROOF_NORMAL;
            case ROLE_BOTTOM -> BOTTOM_NORMAL;
            case ROLE_WEST -> WEST_NORMAL;
            case ROLE_EAST -> EAST_NORMAL;
            case ROLE_NORTH -> NORTH_NORMAL;
            case ROLE_SOUTH -> SOUTH_NORMAL;
            default -> CENTER_NORMAL;
        };
    }

    private static int roleForNormal(final Vec3 normal) {
        if (normal == null || normal.lengthSqr() <= 1.0e-12D) {
            return ROLE_CENTER;
        }
        final double ax = Math.abs(normal.x);
        final double ay = Math.abs(normal.y);
        final double az = Math.abs(normal.z);
        if (ay >= ax && ay >= az) {
            return normal.y >= 0.0D ? ROLE_ROOF : ROLE_BOTTOM;
        }
        if (ax >= az) {
            return normal.x < 0.0D ? ROLE_WEST : ROLE_EAST;
        }
        return normal.z < 0.0D ? ROLE_NORTH : ROLE_SOUTH;
    }

    private static double midpoint(final double min, final double max) {
        return (min + max) * 0.5D;
    }

    private static int axisResolution(final int size, final int maxSize, final int maxResolution) {
        final double ratio = size / (double) Math.max(1, maxSize);
        return Math.max(2, Math.min(maxResolution, (int) Math.ceil(maxResolution * ratio)));
    }

    private static int cellBlock(final int min, final int size, final int resolution, final int cell, final int max) {
        final double center = min + (cell + 0.5D) * size / (double) resolution;
        return Math.max(min, Math.min(max, (int) Math.floor(center)));
    }

    private static boolean isSolidAt(final ServerSubLevel subLevel, final int x, final int y, final int z) {
        try {
            final LevelPlot plot = subLevel.getPlot();
            final BlockPos pos = new BlockPos(x, y, z);
            final LevelChunk chunk = plot.getChunk(plot.toLocal(new ChunkPos(pos)));
            if (chunk == null) {
                return false;
            }

            final BlockState state = chunk.getBlockState(pos);
            return state != null && !state.isAir();
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private static ProfileBounds getProfileBounds(final ServerSubLevel subLevel) {
        try {
            final Object bounds = subLevel.getPlot().getBoundingBox();
            final Class<?> boundsClass = bounds.getClass();
            final double rawMinX = invokeDouble(boundsClass.getMethod("minX"), bounds);
            final double rawMinY = invokeDouble(boundsClass.getMethod("minY"), bounds);
            final double rawMinZ = invokeDouble(boundsClass.getMethod("minZ"), bounds);
            final double rawMaxX = invokeDouble(boundsClass.getMethod("maxX"), bounds);
            final double rawMaxY = invokeDouble(boundsClass.getMethod("maxY"), bounds);
            final double rawMaxZ = invokeDouble(boundsClass.getMethod("maxZ"), bounds);
            if (!isUsableBounds(rawMinX, rawMinY, rawMinZ, rawMaxX, rawMaxY, rawMaxZ)) {
                return null;
            }
            final int minX = (int) Math.floor(rawMinX);
            final int minY = (int) Math.floor(rawMinY);
            final int minZ = (int) Math.floor(rawMinZ);
            final int maxX = (int) Math.floor(rawMaxX);
            final int maxY = (int) Math.floor(rawMaxY);
            final int maxZ = (int) Math.floor(rawMaxZ);
            if (maxX < minX || maxY < minY || maxZ < minZ) {
                return null;
            }
            return new ProfileBounds(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object getBoundingBox(final ServerSubLevel subLevel) {
        try {
            final Method method = getBoundingBoxMethod(subLevel.getClass());
            return method == null ? null : method.invoke(subLevel);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method getBoundingBoxMethod(final Class<?> subLevelClass) {
        if (searchedBoundingBoxMethod) {
            return boundingBoxMethod;
        }

        searchedBoundingBoxMethod = true;
        try {
            boundingBoxMethod = subLevelClass.getMethod("boundingBox");
        } catch (final NoSuchMethodException ignored) {
            boundingBoxMethod = null;
        }
        return boundingBoxMethod;
    }

    private static boolean cacheBoundsMethods(final Class<?> boundsClass) {
        if (boundsClass == cachedBoundsClass) {
            return minXMethod != null;
        }

        try {
            minXMethod = boundsClass.getMethod("minX");
            minYMethod = boundsClass.getMethod("minY");
            minZMethod = boundsClass.getMethod("minZ");
            maxXMethod = boundsClass.getMethod("maxX");
            maxYMethod = boundsClass.getMethod("maxY");
            maxZMethod = boundsClass.getMethod("maxZ");
            cachedBoundsClass = boundsClass;
            return true;
        } catch (final NoSuchMethodException ignored) {
            cachedBoundsClass = boundsClass;
            minXMethod = null;
            minYMethod = null;
            minZMethod = null;
            maxXMethod = null;
            maxYMethod = null;
            maxZMethod = null;
            return false;
        }
    }

    private static double invokeDouble(final Method method, final Object target) {
        try {
            final Object raw = method.invoke(target);
            return raw instanceof Number number ? number.doubleValue() : Double.NaN;
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static boolean isUsableBounds(final double minX, final double minY, final double minZ,
                                          final double maxX, final double maxY, final double maxZ) {
        return Double.isFinite(minX)
                && Double.isFinite(minY)
                && Double.isFinite(minZ)
                && Double.isFinite(maxX)
                && Double.isFinite(maxY)
                && Double.isFinite(maxZ)
                && maxX >= minX
                && maxY >= minY
                && maxZ >= minZ;
    }

    private static final class ProfileAccumulator {
        double x;
        double y;
        double z;
        double weight;

        void add(final double x, final double y, final double z, final double weight) {
            if (!Double.isFinite(weight) || weight <= 0.0D) {
                return;
            }
            this.x += x * weight;
            this.y += y * weight;
            this.z += z * weight;
            this.weight += weight;
        }

        Vec3 average() {
            if (this.weight <= 0.0D) {
                return Vec3.ZERO;
            }
            return new Vec3(this.x / this.weight, this.y / this.weight, this.z / this.weight);
        }
    }

    private record ProfileBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    private record ProfileFace(Vec3 point, Vec3 normal, double weight) {
    }

    private record SurfacePlaneKey(int role, int plane, int minA, int minB) {
    }

    private record AerodynamicProfile(ProfileFace west, ProfileFace east, ProfileFace north, ProfileFace south,
                                      ProfileFace roof, ProfileFace bottom, List<ProfileFace> samples) {
        static final AerodynamicProfile EMPTY = new AerodynamicProfile(
                new ProfileFace(Vec3.ZERO, WEST_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, EAST_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, NORTH_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, SOUTH_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, ROOF_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, BOTTOM_NORMAL, 1.0D),
                List.of()
        );

        List<ProfileFace> selectedSamples(final int requestedSamples) {
            return selectProfileSamples(this.samples, requestedSamples);
        }

        Vec3 worldPoint(final int role, final Vec3 fallback, final Pose3d pose) {
            final ProfileFace face = face(role);
            if (face.point() == Vec3.ZERO || face.point().lengthSqr() <= 1.0e-12D) {
                return fallback;
            }
            PROFILE_WORLD_POINT.set(face.point().x, face.point().y, face.point().z);
            pose.transformPosition(PROFILE_WORLD_POINT, PROFILE_WORLD_POINT);
            return new Vec3(PROFILE_WORLD_POINT.x, PROFILE_WORLD_POINT.y, PROFILE_WORLD_POINT.z);
        }

        Vec3 worldNormal(final int role, final Vec3 fallback, final Pose3d pose) {
            final ProfileFace face = face(role);
            if (face.normal() == Vec3.ZERO || face.normal().lengthSqr() <= 1.0e-12D) {
                return fallback;
            }
            PROFILE_WORLD_NORMAL.set(face.normal().x, face.normal().y, face.normal().z);
            pose.transformNormal(PROFILE_WORLD_NORMAL);
            if (PROFILE_WORLD_NORMAL.lengthSquared() <= 1.0e-12D) {
                return fallback;
            }
            PROFILE_WORLD_NORMAL.normalize();
            return new Vec3(PROFILE_WORLD_NORMAL.x, PROFILE_WORLD_NORMAL.y, PROFILE_WORLD_NORMAL.z);
        }

        double weight(final int role) {
            return Math.max(0.0D, Math.min(1.0D, face(role).weight()));
        }

        private ProfileFace face(final int role) {
            return switch (role) {
                case ROLE_WEST -> this.west;
                case ROLE_EAST -> this.east;
                case ROLE_NORTH -> this.north;
                case ROLE_SOUTH -> this.south;
                case ROLE_ROOF -> this.roof;
                case ROLE_BOTTOM -> this.bottom;
                default -> this.west;
            };
        }
    }

    private enum WindUse {
        BODY,
        AIRFLOW
    }

    private record WindCacheKey(String subLevelId, WindUse use, int role) {
    }

    private record CachedAerodynamicProfile(ProfileBounds bounds, AerodynamicProfile profile, long tick) {
    }

    private record CachedWind(Vec3 previousWind, Vec3 targetWind, long tick, int intervalTicks) {
        Vec3 wind(final long currentTick) {
            if (intervalTicks <= 1) {
                return targetWind;
            }
            if (currentTick <= tick) {
                return previousWind;
            }

            final double rawAlpha = (currentTick - tick) / (double) intervalTicks;
            final double alpha = Math.max(0.0D, Math.min(1.0D, rawAlpha));
            final double smoothAlpha = alpha * alpha * (3.0D - 2.0D * alpha);
            return new Vec3(
                    previousWind.x + (targetWind.x - previousWind.x) * smoothAlpha,
                    previousWind.y + (targetWind.y - previousWind.y) * smoothAlpha,
                    previousWind.z + (targetWind.z - previousWind.z) * smoothAlpha
            );
        }
    }

}
