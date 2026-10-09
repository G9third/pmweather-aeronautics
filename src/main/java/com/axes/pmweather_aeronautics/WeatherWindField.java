package com.axes.pmweather_aeronautics;
import dev.protomanly.pmweather.event.GameBusEvents;
import dev.protomanly.pmweather.weather.Storm;
import dev.protomanly.pmweather.weather.WeatherHandler;
import dev.protomanly.pmweather.weather.WindEngine;
import dev.protomanly.pmweather.weather.storms.StormTypes;
import dev.protomanly.pmweather.util.Util;
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
 * Samples native PMWeather wind at exterior body and lift-provider points.
 * Sable frames share scheduled samples; external owners reuse exact same-tick queries.
 * Native combination owns horizontal wind and fire-whirl Y. A scoped observation restores
 * supercell Y discarded by the supported native engine without duplicating its formulas.
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
    private static SampleStats lastSampleStats = SampleStats.empty(128);
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
        AeroObserver.observeSelectedPatches(subLevel, selectedFaces);
        if (AeroObserver.enabled()) AeroObserver.profile(subLevel, profile, selectedFaces);

        final List<PreparedSurfaceSample> prepared = new ArrayList<>(selectedFaces.size());
        if (!selectedFaces.isEmpty()) {
            final Pose3d pose = subLevel.logicalPose();
            final double margin = Config.bodySurfaceProbeOffset();
            int profileIndex = 0;
            for (final AeroSurfaceCache.ProfileFace face : selectedFaces) {
                if (!isFinitePosition(face.point()) || !isFinitePosition(face.sampleAnchor())
                        || !isFinitePosition(face.normal()) || !Double.isFinite(face.weight())
                        || face.weight() <= 0.0D || face.normal().lengthSqr() <= 1.0e-12D) {
                    if (AeroObserver.enabled()) AeroObserver.event(subLevel, "face_rejected", "reason", "invalid_local_face", "localPoint", face.point(), "localNormal", face.normal(), "area", face.weight());
                    continue;
                }
                PROFILE_WORLD_POINT.set(face.point().x, face.point().y, face.point().z);
                pose.transformPosition(PROFILE_WORLD_POINT, PROFILE_WORLD_POINT);
                PROFILE_WORLD_NORMAL.set(face.normal().x, face.normal().y, face.normal().z);
                pose.transformNormal(PROFILE_WORLD_NORMAL);
                if (!Double.isFinite(PROFILE_WORLD_NORMAL.x)
                        || !Double.isFinite(PROFILE_WORLD_NORMAL.y)
                        || !Double.isFinite(PROFILE_WORLD_NORMAL.z)
                        || PROFILE_WORLD_NORMAL.lengthSquared() <= 1.0e-12D) {
                    if (AeroObserver.enabled()) AeroObserver.event(subLevel, "face_rejected", "reason", "invalid_world_normal", "localPoint", face.point());
                    continue;
                }
                PROFILE_WORLD_NORMAL.normalize();
                final Vec3 applicationPosition = new Vec3(PROFILE_WORLD_POINT.x, PROFILE_WORLD_POINT.y, PROFILE_WORLD_POINT.z);
                if (!isFinitePosition(applicationPosition)) {
                    if (AeroObserver.enabled()) AeroObserver.event(subLevel, "face_rejected", "reason", "invalid_world_point", "localPoint", face.point());
                    continue;
                }
                PROFILE_WORLD_POINT.set(face.sampleAnchor().x, face.sampleAnchor().y, face.sampleAnchor().z);
                pose.transformPosition(PROFILE_WORLD_POINT, PROFILE_WORLD_POINT);
                final Vec3 surfaceAnchor = new Vec3(PROFILE_WORLD_POINT.x, PROFILE_WORLD_POINT.y, PROFILE_WORLD_POINT.z);
                if (!isFinitePosition(surfaceAnchor)) {
                    if (AeroObserver.enabled()) AeroObserver.event(subLevel, "face_rejected", "reason", "invalid_world_anchor", "localPoint", face.sampleAnchor());
                    continue;
                }
                final Vec3 outwardNormal = new Vec3(PROFILE_WORLD_NORMAL.x, PROFILE_WORLD_NORMAL.y, PROFILE_WORLD_NORMAL.z);
                if (isWorldBlockedNearExteriorFace(subLevel, surfaceAnchor, outwardNormal)) {
                    if (AeroObserver.enabled()) AeroObserver.face(subLevel, "terrain_blocked", face, surfaceAnchor, outwardNormal, margin);
                    continue;
                }
                if (AeroObserver.enabled()) AeroObserver.face(subLevel, "prepared", face, surfaceAnchor, outwardNormal, margin);
                final int surfaceRole = roleForNormal(face.normal());
                final Vec3 pressureCenterPosition = profile.worldPoint(surfaceRole, applicationPosition, pose);
                final Vec3 samplePosition = new Vec3(
                        surfaceAnchor.x + outwardNormal.x * margin,
                        surfaceAnchor.y + outwardNormal.y * margin,
                        surfaceAnchor.z + outwardNormal.z * margin
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
        final ServerLevel firstLevel = scopedRequests.get(0).subLevel().getLevel();
        final long currentTick = firstLevel.getGameTime();
        final long serverTick = firstLevel.getServer().getTickCount();
        resetBudgetIfNeeded(serverTick);
        pruneCacheIfNeeded(serverTick);

        final List<Vec3> result = new ArrayList<>(scopedRequests.size());
        for (int i = 0; i < scopedRequests.size(); i++) {
            result.add(Vec3.ZERO);
        }

        final Map<RawWindPositionKey, PendingBatchGroup> groups = new LinkedHashMap<>();
        for (int index = 0; index < scopedRequests.size(); index++) {
            final ScopedBatchWindRequest scoped = scopedRequests.get(index);
            final ServerSubLevel subLevel = scoped.subLevel();
            final BatchWindRequest request = scoped.request();
            if (subLevel == null || request == null || !isFinitePosition(request.samplePosition())) {
                result.set(index, Vec3.ZERO);
                continue;
            }
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
                if (AeroObserver.enabled()) AeroObserver.event(subLevel, "wind_lookup", "source", "interpolated_cache", "use", request.use().name(), "cacheRole", request.cacheRole(), "position", request.samplePosition(), "ageTicks", currentTick - existing.tick(), "targetMph", existing.targetWind(), "returnedMph", result.get(index));
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
                    if (AeroObserver.enabled()) AeroObserver.event(scopedRequests.get(entry.resultIndex()).subLevel(), "wind_lookup", "source", entry.previous() == null ? "budget_zero" : "budget_stale", "use", entry.request().use().name(), "cacheRole", entry.request().cacheRole(), "position", group.samplePosition(), "returnedMph", result.get(entry.resultIndex()));
                }
                continue;
            }

            final RawWindBatchContext rawContext = rawContexts.computeIfAbsent(
                    group.level(),
                    level -> RawWindBatchContext.capture(level)
            );
            final Vec3 sampled = sampleRawWindAt(group.level(), group.samplePosition(), rawContext);
            windQueriesThisTick++;
            for (final PendingBatchEntry entry : group.entries()) {
                final Vec3 previousWind = entry.previous() == null ? sampled : entry.previous().wind(currentTick);
                final CachedWind next = new CachedWind(
                        group.level(),
                        previousWind,
                        sampled,
                        currentTick,
                        Math.max(1, entry.request().intervalTicks())
                );
                WIND_CACHE.put(entry.cacheKey(), next);
                result.set(entry.resultIndex(), next.wind(currentTick));
                if (AeroObserver.enabled()) AeroObserver.event(scopedRequests.get(entry.resultIndex()).subLevel(), "wind_lookup", "source", "fresh_target_interpolated", "use", entry.request().use().name(), "cacheRole", entry.request().cacheRole(), "position", group.samplePosition(), "targetMph", sampled, "returnedMph", result.get(entry.resultIndex()));
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
        if (subLevel == null || !isFinitePosition(applicationPosition) || !isFinitePosition(outwardNormal)
                || outwardNormal.lengthSqr() <= 1.0e-12D) {
            return true;
        }
        try {
            final Vec3 normal = outwardNormal.normalize();
            final Vec3 probe = new Vec3(
                    applicationPosition.x + normal.x * Config.bodySurfaceProbeOffset(),
                    applicationPosition.y + normal.y * Config.bodySurfaceProbeOffset(),
                    applicationPosition.z + normal.z * Config.bodySurfaceProbeOffset()
            );
            final ServerLevel level = subLevel.getLevel();
            final BlockPos pos = BlockPos.containing(probe.x, probe.y, probe.z);
            if (!level.isLoaded(pos)) {
                return true;
            }
            final BlockState state = level.getBlockState(pos);
            if (state == null || state.isAir()) return false;
            final double x = probe.x - pos.getX(), y = probe.y - pos.getY(), z = probe.z - pos.getZ();
            for (var box : state.getCollisionShape(level, pos).toAabbs()) {
                if (x >= box.minX && x < box.maxX && y >= box.minY && y < box.maxY && z >= box.minZ && z < box.maxZ) return true;
            }
            return false;
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
    private static Vec3 sampleWindCached(final ServerSubLevel subLevel, final Vec3 samplePosition,
                                         final WindUse use, final int role, final int intervalTicks) {
        if (subLevel == null || !isFinitePosition(samplePosition)) {
            return Vec3.ZERO;
        }
        final ServerLevel level = subLevel.getLevel();
        final long currentTick = level.getGameTime();
        final long serverTick = level.getServer().getTickCount();
        resetBudgetIfNeeded(serverTick);
        pruneCacheIfNeeded(serverTick);
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
        final CachedWind next = new CachedWind(level, previous, sampled, currentTick, Math.max(1, intervalTicks));
        WIND_CACHE.put(key, next);
        return next.wind(currentTick);
    }
    /**
     * Returns PMWeather's raw wind vector in PMWeather/mph-style units. Do not use this directly
     * as a Sable physics velocity. Convert with pmweatherWindToPhysicsWind(...) at the point where
     * the value becomes body wind, lift-provider airflow, or Sable force.
     *
     * Native combined X/Z and Y are retained. The native supercell vectors already evaluated
     * inside the query supply only the missing vertical contribution.
     */
    static Vec3 sampleRawWindAt(final ServerLevel level, final Vec3 samplePosition) {
        // Internal PMAero semantics retain the existing config gate for tornado body/lift effects.
        final PMWeatherWindApi.SampleOptions options = new PMWeatherWindApi.SampleOptions(
                true, Config.enableTornadoSuction(), true, false
        );
        return sampleRawWindAt(level, samplePosition, options);
    }

    private static Vec3 sampleRawWindAt(final ServerLevel level,
                                        final Vec3 samplePosition,
                                        final RawWindBatchContext context) {
        final PMWeatherWindApi.SampleOptions options = new PMWeatherWindApi.SampleOptions(
                true, Config.enableTornadoSuction(), true, false);
        if (AeroObserver.enabled()) {
            final RawWindSample detail = sampleRawWindDetailedAt(level, samplePosition, context, options);
            AeroObserver.event(null, "raw_query", "dimension", level.dimension().location().toString(),
                    "tick", level.getGameTime(), "position", samplePosition, "windMph", detail.wind(),
                    "nativeVerticalCorrectionUsed", detail.nativeTornadoVectorUsed(),
                    "stormCount", detail.stormSnapshotCount(),
                    "nearestStormDistance", detail.nearestStormDistanceMeters());
            return detail.wind();
        }
        return resolveRawWind(level, samplePosition, context, options, false).wind();
    }

    static Vec3 sampleRawWindAt(final ServerLevel level, final Vec3 samplePosition,
                                final PMWeatherWindApi.SampleOptions options) {
        if (level == null || !isFinitePosition(samplePosition)) return Vec3.ZERO;
        return resolveRawWind(level, samplePosition, RawWindBatchContext.capture(level),
                options == null ? PMWeatherWindApi.SOURCE_NATIVE : options, false).wind();
    }

    /** Vector-only batches avoid nearest-storm scans and diagnostic result records. */
    static List<Vec3> sampleRawWindBatchAt(final ServerLevel level, final List<Vec3> positions,
                                           final PMWeatherWindApi.SampleOptions options) {
        if (level == null || positions == null || positions.isEmpty()) return List.of();
        final RawWindBatchContext context = RawWindBatchContext.capture(level);
        final PMWeatherWindApi.SampleOptions safe = options == null ? PMWeatherWindApi.SOURCE_NATIVE : options;
        final List<Vec3> result = new ArrayList<>(positions.size());
        for (final Vec3 point : positions) {
            result.add(isFinitePosition(point) ? resolveRawWind(level, point, context, safe, false).wind() : Vec3.ZERO);
        }
        return List.copyOf(result);
    }

    static List<Vec3> sampleRawWindBatchAt(final ServerLevel level, final List<Vec3> positions) {
        return sampleRawWindBatchAt(level, positions, PMWeatherWindApi.SOURCE_NATIVE);
    }

    static List<RawWindSample> sampleRawWindDetailedBatchAt(final ServerLevel level,
            final List<Vec3> positions, final PMWeatherWindApi.SampleOptions options) {
        if (level == null || positions == null || positions.isEmpty()) return List.of();
        final RawWindBatchContext context = RawWindBatchContext.capture(level);
        final PMWeatherWindApi.SampleOptions safe = options == null ? PMWeatherWindApi.SOURCE_NATIVE : options;
        final List<RawWindSample> result = new ArrayList<>(positions.size());
        for (final Vec3 point : positions) {
            result.add(isFinitePosition(point) ? sampleRawWindDetailedAt(level, point, context, safe) : RawWindSample.zero());
        }
        return List.copyOf(result);
    }

    private static RawWindSample sampleRawWindDetailedAt(final ServerLevel level, final Vec3 point,
            final RawWindBatchContext context, final PMWeatherWindApi.SampleOptions options) {
        final ResolvedWind sample = resolveRawWind(level, point, context, options, false);
        return RawWindSample.of(sample.wind(), sample.nativeVerticalCorrectionUsed(),
                sample.synthetic() ? StormSnapshotStats.synthetic() : stormSnapshotStats(context.storms(), point));
    }

    private static ResolvedWind resolveRawWind(final ServerLevel level, final Vec3 point,
            final RawWindBatchContext context, final PMWeatherWindApi.SampleOptions options, final boolean strict) {
        context.requests++;
        // The analytic test replaces weather at this point. Do not first compute discarded weather.
        final Vec3 synthetic = AerowindTest.sample(level, point);
        if (synthetic != null) {
            context.syntheticQueries++;
            return new ResolvedWind(synthetic, false, true);
        }
        final RawQueryKey key = new RawQueryKey(point.x, point.y, point.z, options);
        final ResolvedWind cached = context.vectors.get(key);
        if (cached != null) {
            context.cacheHits++;
            return cached;
        }
        final int terrainY = context.terrainHeight(level, point);
        if (!options.atmosphericField() && (point.y < terrainY
                || options.respectShelter() && !Util.canWindAffect(point, level))) {
            context.shelteredQueries++;
            return context.remember(key, new ResolvedWind(Vec3.ZERO, false, false));
        }
        // Shelter was checked once above. The native combination owns all horizontal blending,
        // fire-whirl Y, influence decisions and storm selection. Capture the supercell vectors
        // it already evaluates, then add only their otherwise discarded vertical component.
        final boolean timed = AeroObserver.enabled();
        final long began = timed ? System.nanoTime() : 0L;
        final Vec3 nativeWind;
        final double missingY;
        try (NativeTornadoCapture.Scope capture = NativeTornadoCapture.begin()) {
            context.nativeQueries++;
            nativeWind = WindEngine.getWind(point, level, !options.includeStorms(),
                    !options.includeTornadoes(), false, options.atmosphericField(), false, terrainY);
            missingY = capture.verticalMph();
            context.nativeVerticalVectors += capture.vectorCount();
        } finally {
            if (timed) context.nativeNanos += System.nanoTime() - began;
        }
        final Vec3 combined = nativeWind == null ? null : nativeWind.add(0.0D, missingY, 0.0D);
        if (!isFiniteWind(combined)) {
            context.invalidQueries++;
            if (strict) throw new IllegalArgumentException("Invalid native PMWeather wind");
            return new ResolvedWind(Vec3.ZERO, false, false);
        }
        return context.remember(key, new ResolvedWind(combined, missingY != 0.0D, false));
    }

    static boolean sampleAircraftInto(ServerLevel level, double[] xyz, double[] output) {
        final RawWindBatchContext context = RawWindBatchContext.capture(level);
        try {
            for (int i = 0, o = 0; i < xyz.length; i += 3, o += PMWeatherWindApi.PACKED_RESULT_STRIDE) {
                final Vec3 point = new Vec3(xyz[i], xyz[i + 1], xyz[i + 2]);
                final ResolvedWind resolved = resolveRawWind(level, point, context, PMWeatherWindApi.AIRCRAFT_ATMOSPHERE, true);
                final RawWindSample sample = RawWindSample.of(resolved.wind(), resolved.nativeVerticalCorrectionUsed(),
                        resolved.synthetic() ? StormSnapshotStats.synthetic() : stormSnapshotStats(context.storms(), point));
                PMWeatherWindApi.writePacked(sample, output, o);
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            java.util.Arrays.fill(output, 0.0D);
            return false;
        }
    }

    static boolean sampleAircraftVectorsInto(ServerLevel level, double[] xyz, double[] output) {
        final RawWindBatchContext context = RawWindBatchContext.capture(level);
        try {
            for (int i = 0; i < xyz.length; i += 3) {
                final Vec3 wind = resolveRawWind(level, new Vec3(xyz[i], xyz[i + 1], xyz[i + 2]),
                        context, PMWeatherWindApi.AIRCRAFT_ATMOSPHERE, true).wind();
                output[i] = wind.x; output[i + 1] = wind.y; output[i + 2] = wind.z;
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            java.util.Arrays.fill(output, 0.0D);
            return false;
        }
    }

    /** Counts every consumer, including PMIV; timing is enabled only during private recording. */
    static RawQueryStats rawQueryStats(ServerLevel level) {
        final RawWindBatchContext context = RawWindBatchContext.capture(level);
        return new RawQueryStats(context.requests, context.cacheHits, context.nativeQueries,
                context.syntheticQueries, context.shelteredQueries, context.nativeVerticalVectors,
                context.invalidQueries, context.nativeNanos);
    }
    record RawQueryStats(long requests, long cacheHits, long nativeQueries, long syntheticQueries,
                         long shelteredQueries, long nativeVerticalVectors, long invalidQueries, long nativeNanos) {}
    private record ResolvedWind(Vec3 wind, boolean nativeVerticalCorrectionUsed, boolean synthetic) {}
    private record RawQueryKey(double x, double y, double z, PMWeatherWindApi.SampleOptions options) {}

    private static StormSnapshotStats stormSnapshotStats(final List<Storm> storms, final Vec3 position) {
        if (storms == null || storms.isEmpty()) {
            return StormSnapshotStats.empty();
        }
        int count = 0;
        int tornadicCount = 0;
        int maximumStage = -1;
        double nearestDistance = Double.POSITIVE_INFINITY;
        int nearestStage = -1;
        boolean nearestTornadic = false;
        double nearestWidth = Double.NaN;
        double nearestInfluenceRadius = Double.NaN;
        double nearestWindspeed = Double.NaN;
        for (final Storm storm : storms) {
            if (storm == null || storm.visualOnly) {
                continue;
            }
            count++;
            final boolean tornadic = storm.isTornadic();
            if (tornadic) {
                tornadicCount++;
            }
            maximumStage = Math.max(maximumStage, storm.stage);
            if (position != null && storm.position != null) {
                final double distance = Math.hypot(position.x - storm.position.x, position.z - storm.position.z);
                if (Double.isFinite(distance) && distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestStage = storm.stage;
                    nearestTornadic = tornadic;
                    nearestWidth = storm.width;
                    // The native API does not expose an authoritative combined influence radius.
                    nearestInfluenceRadius = Double.NaN;
                    nearestWindspeed = storm.windspeed;
                }
            }
        }
        return new StormSnapshotStats(
                count, tornadicCount, maximumStage,
                Double.isFinite(nearestDistance) ? nearestDistance : Double.NaN,
                nearestStage, nearestTornadic, nearestWidth, nearestInfluenceRadius, nearestWindspeed
        );
    }

    private static boolean isFiniteWind(final Vec3 wind) {
        return wind != null
                && Double.isFinite(wind.x)
                && Double.isFinite(wind.y)
                && Double.isFinite(wind.z);
    }

    private static boolean isFinitePosition(final Vec3 position) {
        return position != null
                && Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
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

    private static void pruneCacheIfNeeded(final long serverTick) {
        if (lastPruneTick == serverTick || serverTick % 200L != 0L) {
            return;
        }
        lastPruneTick = serverTick;
        final Iterator<Map.Entry<WindCacheKey, CachedWind>> iterator = WIND_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<WindCacheKey, CachedWind> entry = iterator.next();
            if (entry.getValue().level().getGameTime() - entry.getValue().tick() > 400L) {
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


    private static final class RawWindBatchContext {
        private static final int MAX_CACHED_POINTS = 8192;
        private final ServerLevel level;
        private List<Storm> storms;
        private final Map<Long, Integer> terrainHeights = new HashMap<>();
        private final Map<RawQueryKey, ResolvedWind> vectors = new HashMap<>();
        private long requests, cacheHits, nativeQueries, syntheticQueries, shelteredQueries,
                nativeVerticalVectors, invalidQueries, nativeNanos;

        private RawWindBatchContext(ServerLevel level) { this.level = level; }

        List<Storm> storms() {
            if (storms == null) {
                final WeatherHandler handler = GameBusEvents.MANAGERS.get(level.dimension());
                storms = handler == null || handler.getStorms() == null
                        ? List.of() : new ArrayList<>(handler.getStorms());
            }
            return storms;
        }

        ResolvedWind remember(RawQueryKey key, ResolvedWind wind) {
            // Bound memory without dropping physics samples or substituting calm wind.
            if (vectors.size() >= MAX_CACHED_POINTS) vectors.clear();
            vectors.put(key, wind);
            return wind;
        }

        int terrainHeight(final ServerLevel level, final Vec3 point) {
            final BlockPos blockPos = BlockPos.containing(point);
            final long key = ((long) blockPos.getX() << 32) ^ (blockPos.getZ() & 0xffffffffL);
            final Integer cached = terrainHeights.get(key);
            if (cached != null) return cached;
            final int height = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, blockPos).getY();
            if (terrainHeights.size() >= MAX_CACHED_POINTS) terrainHeights.clear();
            terrainHeights.put(key, height);
            return height;
        }

        static RawWindBatchContext capture(final ServerLevel level) {
            final long tick = level.getGameTime();
            final CachedRawWindBatchContext cached = RAW_BATCH_CONTEXT_CACHE.get(level);
            if (cached != null && cached.tick() == tick) return cached.context();
            final RawWindBatchContext context = new RawWindBatchContext(level);
            RAW_BATCH_CONTEXT_CACHE.put(level, new CachedRawWindBatchContext(tick, context));
            if (level.getServer().getTickCount() % 200L == 0L) {
                RAW_BATCH_CONTEXT_CACHE.entrySet().removeIf(entry ->
                        entry.getKey().getGameTime() - entry.getValue().tick() > 20L);
            }
            return context;
        }
    }

    record RawWindSample(
            Vec3 wind,
            boolean nativeTornadoVectorUsed,
            int stormSnapshotCount,
            int tornadicStormSnapshotCount,
            int maximumStormStage,
            double nearestStormDistanceMeters,
            int nearestStormStage,
            boolean nearestStormTornadic,
            double nearestStormWidthMeters,
            double nearestStormTornadoInfluenceRadiusMeters,
            double nearestStormWindspeedMph
    ) {
        static RawWindSample zero() {
            return of(Vec3.ZERO, false, StormSnapshotStats.empty());
        }

        static RawWindSample of(final Vec3 wind, final boolean nativeUsed, final StormSnapshotStats stats) {
            final Vec3 safeWind = isFiniteWind(wind) ? wind : Vec3.ZERO;
            final StormSnapshotStats safeStats = stats == null ? StormSnapshotStats.empty() : stats;
            return new RawWindSample(
                    safeWind, nativeUsed,
                    safeStats.stormSnapshotCount(), safeStats.tornadicStormSnapshotCount(),
                    safeStats.maximumStormStage(), safeStats.nearestStormDistanceMeters(),
                    safeStats.nearestStormStage(), safeStats.nearestStormTornadic(),
                    safeStats.nearestStormWidthMeters(), safeStats.nearestStormTornadoInfluenceRadiusMeters(),
                    safeStats.nearestStormWindspeedMph()
            );
        }
    }

    private record StormSnapshotStats(
            int stormSnapshotCount,
            int tornadicStormSnapshotCount,
            int maximumStormStage,
            double nearestStormDistanceMeters,
            int nearestStormStage,
            boolean nearestStormTornadic,
            double nearestStormWidthMeters,
            double nearestStormTornadoInfluenceRadiusMeters,
            double nearestStormWindspeedMph
    ) {
        static StormSnapshotStats synthetic() {
            return new StormSnapshotStats(0, 0, 0, Double.POSITIVE_INFINITY, 0, false, 0, 0, 0);
        }
        static StormSnapshotStats empty() {
            return new StormSnapshotStats(0, 0, -1, Double.NaN, -1, false, Double.NaN, Double.NaN, Double.NaN);
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
    private record CachedWind(ServerLevel level, Vec3 previousWind, Vec3 targetWind, long tick, int intervalTicks) {
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
    static void clearSession() {
        WIND_CACHE.clear();
        RAW_BATCH_CONTEXT_CACHE.clear();
        budgetTick = Long.MIN_VALUE;
        windQueriesThisTick = 0;
        sampleRequestsThisTick = 0;
        cacheHitsThisTick = 0;
        budgetFallbacksThisTick = 0;
        zeroBudgetFallbacksThisTick = 0;
        minSurfaceSampleTargetThisTick = Integer.MAX_VALUE;
        maxSurfaceSampleTargetThisTick = 0;
        lastSurfaceSampleTargetThisTick = 0;
        lastPruneTick = Long.MIN_VALUE;
        rateWindowStartTick = Long.MIN_VALUE;
        rateWindowTicks = 0;
        rateWindowFreshQueries = 0;
        rateWindowRequestedSamples = 0;
        rateWindowCacheHits = 0;
        rateWindowBudgetFallbacks = 0;
        rateWindowZeroFallbacks = 0;
        lastSampleStats = SampleStats.empty(128);
    }

}
