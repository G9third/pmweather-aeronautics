package com.axes.pmweather_aeronautics;
import dev.protomanly.pmweather.event.GameBusEvents;
import dev.protomanly.pmweather.weather.Storm;
import dev.protomanly.pmweather.weather.WeatherHandler;
import dev.protomanly.pmweather.weather.WindEngine;
import dev.protomanly.pmweather.weather.storms.StormTypes;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * Samples raw PMWeather wind at exposed points around a Sable sub-level.
 *
 * 0.3.3 sampling notes:
 * - Cached wind samples are interpolated over the configured sample interval. This keeps tornado
 *   direction changes smooth without increasing PMWeather query cost.
 *
 * 0.3.2 sampling notes:
 * - Body push uses exposed exterior samples, so sealed builds do not require an interior or
 *   center-of-mass wind probe.
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
    private static final int ROLE_CENTER = 0;
    private static final int ROLE_ROOF = 1;
    private static final int ROLE_WEST = 2;
    private static final int ROLE_EAST = 3;
    private static final int ROLE_NORTH = 4;
    private static final int ROLE_SOUTH = 5;
    private static final int ROLE_BOTTOM = 6;
    private static final int ROLE_PROFILE_BASE = 1000;
    private static final Map<WindCacheKey, CachedWind> WIND_CACHE = new HashMap<>();
    private static final Map<ServerLevel, CachedRawWindBatchContext> RAW_BATCH_CONTEXT_CACHE = new HashMap<>();
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
    private static SampleStats lastSampleStats = SampleStats.empty(512);
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
    /**
     * Collects this sub-level's body and airflow requests without querying PMWeather yet. Multiple
     * prepared frames can then be resolved together so all active Sable objects in the level share
     * one exact-coordinate deduplication pass and one per-tick PMWeather storm snapshot.
     */
    static PreparedWindFrame prepareBatchedWindFrame(final ServerSubLevel subLevel,
                                                      final boolean includeBody) {
        return prepareBatchedWindFrame(subLevel, includeBody, Config.bodyWindSampleIntervalTicks());
    }

    private static PreparedWindFrame prepareBatchedWindFrame(final ServerSubLevel subLevel,
                                                              final boolean includeBody,
                                                              final int intervalTicks) {
        if (!includeBody) {
            final PhysicsTickWindBatch.Plan plan = PhysicsTickWindBatch.planProviderOnly(subLevel);
            final List<PhysicsTickWindBatch.PendingRequest> providerRequests = plan.providerRequests();
            final List<BatchWindRequest> requests = new ArrayList<>(providerRequests.size());
            for (final PhysicsTickWindBatch.PendingRequest providerRequest : providerRequests) {
                requests.add(new BatchWindRequest(
                        providerRequest.samplePosition(),
                        WindUse.AIRFLOW,
                        providerRequest.cacheRole(),
                        Config.airflowWindSampleIntervalTicks()
                ));
            }
            return new PreparedWindFrame(subLevel, false, List.of(), providerRequests, requests);
        }

        final AeroSurfaceCache.AerodynamicProfile profile = AeroSurfaceCache.get(subLevel);
        final List<AeroSurfaceCache.ProfileFace> desiredFaces;
        final int minimumFaces;
        if (profile.samples().isEmpty()) {
            desiredFaces = List.of();
            minimumFaces = 0;
        } else {
            desiredFaces = profile.selectedSamples(Math.max(0, Config.maxAeroPatchSamplesPerObject()));
            minimumFaces = profile.selectedSamples(0).size();
        }

        final PhysicsTickWindBatch.Plan plan = PhysicsTickWindBatch.plan(
                subLevel,
                desiredFaces.size(),
                minimumFaces
        );
        final List<AeroSurfaceCache.ProfileFace> selectedFaces = profile.samples().isEmpty()
                ? List.of()
                : profile.selectedSamples(plan.bodyExteriorSamples());
        recordSurfaceSampleTarget(selectedFaces.size());

        final List<PreparedSurfaceSample> prepared = new ArrayList<>(selectedFaces.size());
        if (!selectedFaces.isEmpty()) {
            final Pose3d pose = subLevel.logicalPose();
            final double margin = Config.edgeWindSampleMargin();
            int profileIndex = 0;
            for (final AeroSurfaceCache.ProfileFace face : selectedFaces) {
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
                prepared.add(new PreparedSurfaceSample(
                        samplePosition,
                        applicationPosition,
                        outwardNormal,
                        cacheRoleForFace(face, profileIndex++, profile.cacheSalt()),
                        face.weight(),
                        surfaceRole,
                        pressureCenterPosition
                ));
            }
        }

        final List<PhysicsTickWindBatch.PendingRequest> providerRequests = plan.providerRequests();
        final List<BatchWindRequest> requests = new ArrayList<>(prepared.size() + providerRequests.size());
        for (final PreparedSurfaceSample surface : prepared) {
            requests.add(new BatchWindRequest(surface.samplePosition(), WindUse.BODY, surface.cacheRole(), Math.max(1, intervalTicks)));
        }
        for (final PhysicsTickWindBatch.PendingRequest providerRequest : providerRequests) {
            requests.add(new BatchWindRequest(
                    providerRequest.samplePosition(),
                    WindUse.AIRFLOW,
                    providerRequest.cacheRole(),
                    Config.airflowWindSampleIntervalTicks()
            ));
        }
        return new PreparedWindFrame(subLevel, true, prepared, providerRequests, requests);
    }

    /** Resolve all prepared sub-levels in one same-tick global PMWeather request pass. */
    static void resolvePreparedWindFrames(final List<PreparedWindFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        final List<ScopedBatchWindRequest> scoped = new ArrayList<>();
        final List<Integer> starts = new ArrayList<>(frames.size());
        for (final PreparedWindFrame frame : frames) {
            starts.add(scoped.size());
            for (final BatchWindRequest request : frame.requests()) {
                scoped.add(new ScopedBatchWindRequest(frame.subLevel(), request));
            }
        }
        final List<Vec3> resolved = sampleWindBatchCachedScoped(scoped);
        for (int i = 0; i < frames.size(); i++) {
            final PreparedWindFrame frame = frames.get(i);
            final int start = starts.get(i);
            final int end = start + frame.requests().size();
            frame.acceptResolved(resolved.subList(start, end));
        }
    }

    private static List<Vec3> sampleWindBatchCached(final ServerSubLevel subLevel,
                                                     final List<BatchWindRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        final List<ScopedBatchWindRequest> scoped = new ArrayList<>(requests.size());
        for (final BatchWindRequest request : requests) {
            scoped.add(new ScopedBatchWindRequest(subLevel, request));
        }
        return sampleWindBatchCachedScoped(scoped);
    }

    /**
     * Resolves requests from every active sub-level in one pass. Exact world coordinates are
     * deduplicated even when they came from different Sable objects, while each object's cache key
     * remains independent so interpolation and refresh intervals keep their old semantics.
     */
    private static List<Vec3> sampleWindBatchCachedScoped(final List<ScopedBatchWindRequest> scopedRequests) {
        if (scopedRequests.isEmpty()) {
            return List.of();
        }
        final long currentTick = scopedRequests.get(0).subLevel().getLevel().getGameTime();
        resetBudgetIfNeeded(currentTick);
        pruneCacheIfNeeded(currentTick);

        final List<Vec3> result = new ArrayList<>(scopedRequests.size());
        for (int i = 0; i < scopedRequests.size(); i++) {
            result.add(Vec3.ZERO);
        }

        final Map<RawWindPositionKey, PendingBatchGroup> groups = new LinkedHashMap<>();
        for (int index = 0; index < scopedRequests.size(); index++) {
            final ScopedBatchWindRequest scoped = scopedRequests.get(index);
            final ServerSubLevel subLevel = scoped.subLevel();
            final BatchWindRequest request = scoped.request();
            final long requestTick = subLevel.getLevel().getGameTime();
            if (requestTick != currentTick) {
                // This path should only contain one same-tick physics frame. Treat a mismatched
                // request independently rather than allowing stale cache timing to leak across ticks.
                final List<Vec3> single = sampleWindBatchCached(subLevel, List.of(request));
                result.set(index, single.isEmpty() ? Vec3.ZERO : single.get(0));
                continue;
            }

            sampleRequestsThisTick++;
            final WindCacheKey cacheKey = new WindCacheKey(
                    String.valueOf(subLevel.getUniqueId()),
                    request.use(),
                    request.cacheRole()
            );
            final CachedWind existing = WIND_CACHE.get(cacheKey);
            if (existing != null && currentTick - existing.tick() < Math.max(1, request.intervalTicks())) {
                cacheHitsThisTick++;
                result.set(index, existing.wind(currentTick));
                continue;
            }
            final ServerLevel level = subLevel.getLevel();
            final RawWindPositionKey positionKey = RawWindPositionKey.of(level, request.samplePosition());
            groups.computeIfAbsent(
                    positionKey,
                    ignored -> new PendingBatchGroup(level, request.samplePosition(), new ArrayList<>())
            ).entries().add(new PendingBatchEntry(index, request, cacheKey, existing));
        }

        final Map<ServerLevel, RawWindBatchContext> rawContexts = new HashMap<>();
        final int hardBudget = Math.max(1, Config.maxWindSamplesPerTick());
        for (final PendingBatchGroup group : groups.values()) {
            if (windQueriesThisTick >= hardBudget) {
                for (final PendingBatchEntry entry : group.entries()) {
                    budgetFallbacksThisTick++;
                    if (entry.previous() == null) {
                        zeroBudgetFallbacksThisTick++;
                        result.set(entry.resultIndex(), Vec3.ZERO);
                    } else {
                        result.set(entry.resultIndex(), entry.previous().wind(currentTick));
                    }
                }
                continue;
            }

            final RawWindBatchContext rawContext = rawContexts.computeIfAbsent(
                    group.level(),
                    RawWindBatchContext::capture
            );
            final Vec3 sampled = sampleRawWindAt(group.level(), group.samplePosition(), rawContext);
            windQueriesThisTick++;
            for (final PendingBatchEntry entry : group.entries()) {
                final Vec3 previousWind = entry.previous() == null ? sampled : entry.previous().wind(currentTick);
                final CachedWind next = new CachedWind(
                        previousWind,
                        sampled,
                        currentTick,
                        Math.max(1, entry.request().intervalTicks())
                );
                WIND_CACHE.put(entry.cacheKey(), next);
                result.set(entry.resultIndex(), next.wind(currentTick));
            }
        }
        return result;
    }

    static List<Vec3> samplePendingProviderWindBatch(final ServerSubLevel subLevel,
                                                      final List<PhysicsTickWindBatch.PendingRequest> providerRequests) {
        if (providerRequests == null || providerRequests.isEmpty()) {
            return List.of();
        }
        final List<BatchWindRequest> requests = new ArrayList<>(providerRequests.size());
        for (final PhysicsTickWindBatch.PendingRequest providerRequest : providerRequests) {
            requests.add(new BatchWindRequest(
                    providerRequest.samplePosition(),
                    WindUse.AIRFLOW,
                    providerRequest.cacheRole(),
                    Config.airflowWindSampleIntervalTicks()
            ));
        }
        return sampleWindBatchCached(subLevel, requests);
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
     *
     * PMWeather's WindEngine currently reduces a normal supercell's native 3D tornado vector to
     * a scalar speed and then rebuilds the tornado direction in the horizontal X/Z plane. Sample
     * the non-tornadic WindEngine field first, then recompose tornado influence from each Storm's
     * native getTornadicWindVector(...) so PMWeather's real vertical component reaches Sable.
     */
    static Vec3 sampleRawWindAt(final ServerLevel level, final Vec3 samplePosition) {
        return sampleRawWindAt(level, samplePosition, RawWindBatchContext.capture(level));
    }

    private static Vec3 sampleRawWindAt(final ServerLevel level,
                                        final Vec3 samplePosition,
                                        final RawWindBatchContext context) {
            final Vec3 baseWind = sampleBaseWindWithoutTornado(level, samplePosition, context);
            if (!Config.enableTornadoSuction() || context.storms().isEmpty()) {
                return baseWind;
            }

            Vec3 windWithDirectTornadoEffects = baseWind;
            Vec3 nativeTornadoSum = Vec3.ZERO;
            double maxTornadoSpeed = 0.0D;
            double maxTornadoInfluence = 0.0D;

            for (final Storm storm : context.storms()) {
                if (storm == null || storm.visualOnly || !storm.isTornadic() || storm.position == null) {
                    continue;
                }
                final Vec3 relative = samplePosition.subtract(storm.position);
                final double horizontalDistance = Math.sqrt(relative.x * relative.x + relative.z * relative.z);
                final int minimumRadius = storm.is(StormTypes.FIRE_WHIRL) ? 20 : 40;
                final double maxDistance = Math.max((int) storm.width, minimumRadius) * 2.0D;
                if (!Double.isFinite(horizontalDistance) || horizontalDistance > maxDistance) {
                    continue;
                }

                if (storm.is(StormTypes.FIRE_WHIRL)) {
                    final double tornadoSpeed = storm.getTornadicWind(samplePosition, false);
                    if (!Double.isFinite(tornadoSpeed) || tornadoSpeed <= 1.0e-12D) {
                        continue;
                    }
                    final Vec3 inward = horizontalUnit(-relative.x, -relative.z);
                    final Vec3 rotational = horizontalUnit(relative.z, -relative.x);
                    final Vec3 direction = inward.scale(0.35D).add(rotational.scale(0.65D)).normalize();
                    windWithDirectTornadoEffects = windWithDirectTornadoEffects
                            .add(direction.scale(tornadoSpeed))
                            .add(0.0D, tornadoSpeed / 7.0D, 0.0D);
                    continue;
                }

                final Vec3 nativeTornado = storm.getTornadicWindVector(samplePosition, false);
                if (!isFiniteWind(nativeTornado) || nativeTornado.lengthSqr() <= 1.0e-12D) {
                    continue;
                }
                final double tornadoSpeed = nativeTornado.length();
                nativeTornadoSum = nativeTornadoSum.add(nativeTornado);
                maxTornadoSpeed = Math.max(maxTornadoSpeed, tornadoSpeed);
                if (storm.is(StormTypes.SUPERCELL)) {
                    final double influenceDenominator = clamp(
                            storm.windspeed,
                            60.0D,
                            Math.max(storm.windspeed / 1.5D, 60.0D)
                    );
                    final double influence = influenceDenominator <= 1.0e-12D
                            ? 0.0D
                            : clamp(tornadoSpeed / influenceDenominator, 0.0D, 1.0D);
                    maxTornadoInfluence = Math.max(maxTornadoInfluence, influence);
                }
            }

            if (maxTornadoInfluence <= 0.0D || nativeTornadoSum.lengthSqr() <= 1.0e-12D) {
                return windWithDirectTornadoEffects;
            }
            final Vec3 tornadoDirection = nativeTornadoSum.normalize();
            final Vec3 baseDirection = windWithDirectTornadoEffects.lengthSqr() > 1.0e-12D
                    ? windWithDirectTornadoEffects.normalize()
                    : Vec3.ZERO;
            Vec3 mixedDirection = baseDirection.lerp(tornadoDirection, maxTornadoInfluence);
            mixedDirection = mixedDirection.lengthSqr() <= 1.0e-12D ? tornadoDirection : mixedDirection.normalize();
            final double finalSpeed = Math.max(windWithDirectTornadoEffects.length(), maxTornadoSpeed);
        return mixedDirection.scale(finalSpeed);
    }

    private static Vec3 sampleBaseWindWithoutTornado(final ServerLevel level,
                                                     final Vec3 samplePosition,
                                                     final RawWindBatchContext context) {
        // PMWeather's 5-argument overload computes MOTION_BLOCKING terrain height before entering
        // this public terrain-height overload. Do that exact lookup once per X/Z column in the
        // same-tick batch instead. Unlike the old experimental reflective call, this is a direct
        // compile-time invocation of PMWeather's public API and preserves the same flags/math.
        final int terrainY = context.terrainHeight(level, samplePosition);
        return WindEngine.getWind(
                samplePosition,
                level,
                false,
                true,
                true,
                false,
                false,
                terrainY
        );
    }

    private static Vec3 horizontalUnit(final double x, final double z) {
        final Vec3 vector = new Vec3(x, 0.0D, z);
        return vector.lengthSqr() <= 1.0e-12D ? Vec3.ZERO : vector.normalize();
    }

    private static boolean isFiniteWind(final Vec3 wind) {
        return wind != null
                && Double.isFinite(wind.x)
                && Double.isFinite(wind.y)
                && Double.isFinite(wind.z);
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
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
        final int activeObjects = PhysicsTickWindBatch.activeSubLevelCount();
        final int minTarget = minSurfaceSampleTargetThisTick == Integer.MAX_VALUE ? 0 : minSurfaceSampleTargetThisTick;
        final double seconds = Math.max(1.0D / 20.0D, rateWindowTicks / 20.0D);
        if (rateWindowTicks >= 20 || completedTick - rateWindowStartTick >= 20L) {
            lastSampleStats = new SampleStats(
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
            lastSampleStats = new SampleStats(
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
    static SampleStats sampleStatsSnapshot() {
        return new SampleStats(
                budgetTick,
                Math.max(1, Config.maxWindSamplesPerTick()),
                windQueriesThisTick,
                sampleRequestsThisTick,
                cacheHitsThisTick,
                budgetFallbacksThisTick,
                zeroBudgetFallbacksThisTick,
                PhysicsTickWindBatch.activeSubLevelCount(),
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

    record SampleStats(long tick,
                       int hardBudget,
                       int currentFreshQueries,
                       int currentRequestedSamples,
                       int currentCacheHits,
                       int currentBudgetFallbacks,
                       int currentZeroFallbacks,
                       int activeSubLevelsThisTick,
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
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    1.0D,
                    0, 0, 0, 0, 0
            );
        }
    }

    record WindSample(Vec3 samplePosition,
                      Vec3 applicationPosition,
                      Vec3 outwardNormal,
                      Vec3 wind,
                      double areaWeight,
                      int surfaceRole,
                      Vec3 pressureCenterPosition) {
    }

    static final class PreparedWindFrame {
        private final ServerSubLevel subLevel;
        private final boolean bodyEnabled;
        private final List<PreparedSurfaceSample> preparedSurfaces;
        private final List<PhysicsTickWindBatch.PendingRequest> providerRequests;
        private final List<BatchWindRequest> requests;
        private List<WindSample> bodySamples = List.of();

        PreparedWindFrame(final ServerSubLevel subLevel,
                          final boolean bodyEnabled,
                          final List<PreparedSurfaceSample> preparedSurfaces,
                          final List<PhysicsTickWindBatch.PendingRequest> providerRequests,
                          final List<BatchWindRequest> requests) {
            this.subLevel = subLevel;
            this.bodyEnabled = bodyEnabled;
            this.preparedSurfaces = List.copyOf(preparedSurfaces);
            this.providerRequests = List.copyOf(providerRequests);
            this.requests = List.copyOf(requests);
        }

        ServerSubLevel subLevel() {
            return this.subLevel;
        }

        List<BatchWindRequest> requests() {
            return this.requests;
        }

        List<WindSample> bodySamples() {
            return this.bodySamples;
        }

        void acceptResolved(final List<Vec3> resolved) {
            final int expected = this.requests.size();
            if (resolved.size() != expected) {
                throw new IllegalArgumentException("Resolved wind count " + resolved.size() + " did not match prepared request count " + expected);
            }

            final int bodyOffset;
            if (!this.bodyEnabled) {
                this.bodySamples = List.of();
                bodyOffset = 0;
            } else {
                final List<WindSample> samples = new ArrayList<>(this.preparedSurfaces.size());
                for (int i = 0; i < this.preparedSurfaces.size(); i++) {
                    final PreparedSurfaceSample surface = this.preparedSurfaces.get(i);
                    final Vec3 wind = resolved.get(i);
                    samples.add(new WindSample(
                            surface.samplePosition(),
                            surface.applicationPosition(),
                            surface.outwardNormal(),
                            wind,
                            surface.areaWeight(),
                            surface.surfaceRole(),
                            surface.pressureCenterPosition()
                    ));
                }
                this.bodySamples = List.copyOf(samples);
                bodyOffset = this.preparedSurfaces.size();
            }

            final List<Vec3> providerWinds = this.providerRequests.isEmpty()
                    ? List.of()
                    : new ArrayList<>(resolved.subList(bodyOffset, bodyOffset + this.providerRequests.size()));
            PhysicsTickWindBatch.publishProviderWinds(this.subLevel, this.providerRequests, providerWinds);
        }
    }

    private record PreparedSurfaceSample(Vec3 samplePosition,
                                                 Vec3 applicationPosition,
                                                 Vec3 outwardNormal,
                                                 int cacheRole,
                                                 double areaWeight,
                                                 int surfaceRole,
                                                 Vec3 pressureCenterPosition) {
    }

    private record BatchWindRequest(Vec3 samplePosition, WindUse use, int cacheRole, int intervalTicks) {
    }

    private record ScopedBatchWindRequest(ServerSubLevel subLevel, BatchWindRequest request) {
    }

    private record RawWindPositionKey(ServerLevel level, long x, long y, long z) {
        static RawWindPositionKey of(final ServerLevel level, final Vec3 position) {
            return new RawWindPositionKey(
                    level,
                    Math.round(position.x * 1_000_000.0D),
                    Math.round(position.y * 1_000_000.0D),
                    Math.round(position.z * 1_000_000.0D)
            );
        }
    }

    private record PendingBatchEntry(int resultIndex,
                                     BatchWindRequest request,
                                     WindCacheKey cacheKey,
                                     CachedWind previous) {
    }

    private record PendingBatchGroup(ServerLevel level, Vec3 samplePosition, List<PendingBatchEntry> entries) {
    }


    private record RawWindBatchContext(List<Storm> storms, Map<Long, Integer> terrainHeights) {
        int terrainHeight(final ServerLevel level, final Vec3 samplePosition) {
            final BlockPos blockPos = BlockPos.containing(samplePosition);
            final long columnKey = ((long) blockPos.getX() << 32) ^ (blockPos.getZ() & 0xffffffffL);
            final Integer cached = terrainHeights.get(columnKey);
            if (cached != null) {
                return cached;
            }
            final int terrainY = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING,
                    blockPos
            ).getY();
            terrainHeights.put(columnKey, terrainY);
            return terrainY;
        }

        static RawWindBatchContext capture(final ServerLevel level) {
            final long tick = level.getGameTime();
            final CachedRawWindBatchContext cached = RAW_BATCH_CONTEXT_CACHE.get(level);
            if (cached != null && cached.tick() == tick) {
                return cached.context();
            }
            final List<Storm> storms;
            if (!Config.enableTornadoSuction()) {
                storms = List.of();
            } else {
                final WeatherHandler handler = GameBusEvents.MANAGERS.get(level.dimension());
                storms = handler == null || handler.getStorms() == null || handler.getStorms().isEmpty()
                        ? List.of()
                        : new ArrayList<>(handler.getStorms());
            }
            final RawWindBatchContext context = new RawWindBatchContext(storms, new HashMap<>());
            RAW_BATCH_CONTEXT_CACHE.put(level, new CachedRawWindBatchContext(tick, context));
            if (tick % 200L == 0L) {
                RAW_BATCH_CONTEXT_CACHE.entrySet().removeIf(entry -> tick - entry.getValue().tick() > 20L);
            }
            return context;
        }
    }

    private record CachedRawWindBatchContext(long tick, RawWindBatchContext context) {
    }

    private enum WindUse {
        BODY,
        AIRFLOW
    }
    private record WindCacheKey(String subLevelId, WindUse use, int role) {
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
