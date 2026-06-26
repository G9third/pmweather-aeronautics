package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 0.7 aero-surface system.
 *
 * This class owns Sable shape inspection, exposed exterior patch generation, dirty revisions,
 * and patch LOD. WeatherWindField only asks this cache for local exterior patches to sample.
 */
public final class AeroSurfaceCache {
    static final Vec3 CENTER_NORMAL = Vec3.ZERO;
    static final Vec3 ROOF_NORMAL = new Vec3(0.0D, 1.0D, 0.0D);
    static final Vec3 BOTTOM_NORMAL = new Vec3(0.0D, -1.0D, 0.0D);
    static final Vec3 WEST_NORMAL = new Vec3(-1.0D, 0.0D, 0.0D);
    static final Vec3 EAST_NORMAL = new Vec3(1.0D, 0.0D, 0.0D);
    static final Vec3 NORTH_NORMAL = new Vec3(0.0D, 0.0D, -1.0D);
    static final Vec3 SOUTH_NORMAL = new Vec3(0.0D, 0.0D, 1.0D);

    static final int ROLE_CENTER = 0;
    static final int ROLE_ROOF = 1;
    static final int ROLE_WEST = 2;
    static final int ROLE_EAST = 3;
    static final int ROLE_NORTH = 4;
    static final int ROLE_SOUTH = 5;
    static final int ROLE_BOTTOM = 6;

    private static final Map<String, CachedProfile> CACHE = new HashMap<>();
    private static final Map<String, Long> DIRTY_REVISIONS = new HashMap<>();
    private static final Vector3d PROFILE_WORLD_POINT = new Vector3d();
    private static final Vector3d PROFILE_WORLD_NORMAL = new Vector3d();
    private static long lastPruneTick = Long.MIN_VALUE;

    private AeroSurfaceCache() {
    }

    public static void markDirty(final UUID subLevelId) {
        if (subLevelId == null) {
            return;
        }
        final String key = subLevelId.toString();
        DIRTY_REVISIONS.put(key, DIRTY_REVISIONS.getOrDefault(key, 0L) + 1L);
        CACHE.remove(key);
    }

    public static void markDirty(final ServerSubLevel subLevel) {
        if (subLevel != null) {
            markDirty(subLevel.getUniqueId());
        }
    }

    static AerodynamicProfile get(final ServerSubLevel subLevel) {
        final ProfileBounds bounds = getProfileBounds(subLevel);
        if (bounds == null) {
            return AerodynamicProfile.EMPTY;
        }

        final String key = String.valueOf(subLevel.getUniqueId());
        final long currentTick = subLevel.getLevel().getGameTime();
        pruneIfNeeded(currentTick);
        final long revision = DIRTY_REVISIONS.getOrDefault(key, 0L);
        final CachedProfile cached = CACHE.get(key);
        if (cached != null && cached.bounds().equals(bounds) && cached.revision() == revision && currentTick - cached.tick() < 1200L) {
            return cached.profile();
        }

        final AerodynamicProfile profile = build(subLevel, bounds, revision);
        CACHE.put(key, new CachedProfile(bounds, revision, profile, currentTick));
        return profile;
    }

    static AerodynamicProfile build(final ServerSubLevel subLevel) {
        final ProfileBounds bounds = getProfileBounds(subLevel);
        return bounds == null ? AerodynamicProfile.EMPTY : build(subLevel, bounds, DIRTY_REVISIONS.getOrDefault(String.valueOf(subLevel.getUniqueId()), 0L));
    }

    private static AerodynamicProfile build(final ServerSubLevel subLevel, final ProfileBounds bounds, final long revision) {
        final SolidCellSet solidCells = collectSolidCellsSparse(subLevel, bounds);
        if (solidCells.cells().isEmpty()) {
            return AerodynamicProfile.EMPTY.withRevision(revision, solidCells.fingerprint());
        }

        final List<ProfileFace> rawSurfacePatches = buildFullResolutionSurfacePatches(bounds, solidCells);
        if (rawSurfacePatches.isEmpty()) {
            return AerodynamicProfile.EMPTY.withRevision(revision, solidCells.fingerprint());
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
                cachedPatches,
                revision,
                solidCells.fingerprint(),
                currentCacheSalt(revision, solidCells.fingerprint())
        );
    }

    private static SolidCellSet collectSolidCellsSparse(final ServerSubLevel subLevel, final ProfileBounds bounds) {
        final Set<Long> solid = new HashSet<>();
        long fingerprint = 0xcbf29ce484222325L;
        int count = 0;
        try {
            final LevelPlot plot = subLevel.getPlot();
            final Collection<PlotChunkHolder> holders = plot.getLoadedChunks();
            for (final PlotChunkHolder holder : holders) {
                final LevelChunk chunk = holder.getChunk();
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                final ChunkPos chunkPos = chunk.getPos();
                final int baseX = chunkPos.getMinBlockX();
                final int baseZ = chunkPos.getMinBlockZ();
                final LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    final LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    final int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                    for (int x = 0; x < 16; x++) {
                        final int blockX = baseX + x;
                        if (blockX < bounds.minX() || blockX > bounds.maxX()) {
                            continue;
                        }
                        for (int z = 0; z < 16; z++) {
                            final int blockZ = baseZ + z;
                            if (blockZ < bounds.minZ() || blockZ > bounds.maxZ()) {
                                continue;
                            }
                            for (int y = 0; y < 16; y++) {
                                final int blockY = baseY + y;
                                if (blockY < bounds.minY() || blockY > bounds.maxY()) {
                                    continue;
                                }
                                final BlockState state = section.getBlockState(x, y, z);
                                if (state == null || state.isAir()) {
                                    continue;
                                }
                                final long packed = BlockPos.asLong(blockX, blockY, blockZ);
                                solid.add(packed);
                                fingerprint = mixFingerprint(fingerprint, packed ^ state.hashCode());
                                count++;
                            }
                        }
                    }
                }
            }
        } catch (final RuntimeException ignored) {
            // Fall through to the slower, older bounds scan if sparse iteration failed for this Sable build.
            solid.clear();
            count = 0;
            fingerprint = 0xcbf29ce484222325L;
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        if (isSolidAt(subLevel, x, y, z)) {
                            final long packed = BlockPos.asLong(x, y, z);
                            solid.add(packed);
                            fingerprint = mixFingerprint(fingerprint, packed);
                            count++;
                        }
                    }
                }
            }
        }
        fingerprint = mixFingerprint(fingerprint, count);
        fingerprint = mixFingerprint(fingerprint, bounds.hashCode());
        return new SolidCellSet(solid, fingerprint);
    }

    private static long mixFingerprint(final long current, final long value) {
        long mixed = current ^ value;
        mixed *= 0x100000001b3L;
        mixed ^= mixed >>> 32;
        return mixed;
    }

    private static List<ProfileFace> buildFullResolutionSurfacePatches(final ProfileBounds bounds, final SolidCellSet solidCells) {
        final Map<SurfacePlaneKey, Set<Long>> planes = new HashMap<>();
        final Set<Long> outsideAir = computeFullResolutionOutsideAir(bounds, solidCells.cells());
        for (final long packed : solidCells.cells()) {
            final int x = BlockPos.getX(packed);
            final int y = BlockPos.getY(packed);
            final int z = BlockPos.getZ(packed);
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

    private static Set<Long> computeFullResolutionOutsideAir(final ProfileBounds bounds, final Set<Long> solid) {
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
            fullResolutionFloodNeighbor(solid, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0] + 1, current[1], current[2]);
            fullResolutionFloodNeighbor(solid, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0] - 1, current[1], current[2]);
            fullResolutionFloodNeighbor(solid, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1] + 1, current[2]);
            fullResolutionFloodNeighbor(solid, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1] - 1, current[2]);
            fullResolutionFloodNeighbor(solid, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1], current[2] + 1);
            fullResolutionFloodNeighbor(solid, outside, queue, minX, minY, minZ, maxX, maxY, maxZ,
                    current[0], current[1], current[2] - 1);
        }
        return outside;
    }

    private static void fullResolutionFloodNeighbor(final Set<Long> solid,
                                                    final Set<Long> outside,
                                                    final ArrayDeque<int[]> queue,
                                                    final int minX, final int minY, final int minZ,
                                                    final int maxX, final int maxY, final int maxZ,
                                                    final int x, final int y, final int z) {
        if (x < minX || y < minY || z < minZ || x > maxX || y > maxY || z > maxZ) {
            return;
        }
        final long packedAir = packAirCell(x - minX, y - minY, z - minZ);
        if (outside.contains(packedAir) || solid.contains(BlockPos.asLong(x, y, z))) {
            return;
        }
        outside.add(packedAir);
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

    private static ProfileFace profileFace(final ProfileAccumulator accumulator,
                                           final double maxArea, final int role, final Vec3 fallbackLocalPoint) {
        final Vec3 localPoint = accumulator.weight > 0.0D ? accumulator.average() : fallbackLocalPoint;
        final Vec3 localNormal = localNormalForRole(role);
        final double weight = accumulator.weight <= 0.0D ? 0.0D : Math.max(0.08D, Math.min(1.0D, accumulator.weight / maxArea));
        return new ProfileFace(localPoint, localNormal, weight);
    }

    static List<ProfileFace> selectProfileSamples(final List<ProfileFace> rawSamples, final int requestedSamples) {
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
        final Vec3 normal = group.get(0).normal();
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

    static Vec3 localNormalForRole(final int role) {
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

    static int roleForNormal(final Vec3 normal) {
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

    private static double midpoint(final double min, final double max) {
        return (min + max) * 0.5D;
    }

    private static int currentCacheSalt(final long revision, final long fingerprint) {
        return (int) ((revision * 31L + fingerprint) & 0x3ffL);
    }

    private static void pruneIfNeeded(final long currentTick) {
        if (lastPruneTick == currentTick || currentTick % 200L != 0L) {
            return;
        }
        lastPruneTick = currentTick;
        final Iterator<Map.Entry<String, CachedProfile>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, CachedProfile> entry = iterator.next();
            if (currentTick - entry.getValue().tick() > 1200L) {
                iterator.remove();
            }
        }
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

    record AerodynamicProfile(ProfileFace west, ProfileFace east, ProfileFace north, ProfileFace south,
                              ProfileFace roof, ProfileFace bottom, List<ProfileFace> samples,
                              long revision, long fingerprint, int cacheSalt) {
        static final AerodynamicProfile EMPTY = new AerodynamicProfile(
                new ProfileFace(Vec3.ZERO, WEST_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, EAST_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, NORTH_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, SOUTH_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, ROOF_NORMAL, 1.0D),
                new ProfileFace(Vec3.ZERO, BOTTOM_NORMAL, 1.0D),
                List.of(),
                0L,
                0L,
                0
        );

        AerodynamicProfile withRevision(final long revision, final long fingerprint) {
            return new AerodynamicProfile(this.west, this.east, this.north, this.south, this.roof, this.bottom,
                    this.samples, revision, fingerprint, currentCacheSalt(revision, fingerprint));
        }

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

    record ProfileFace(Vec3 point, Vec3 normal, double weight) {
    }

    private record SolidCellSet(Set<Long> cells, long fingerprint) {
    }

    private record ProfileBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    private record SurfacePlaneKey(int role, int plane, int minA, int minB) {
    }

    private record CachedProfile(ProfileBounds bounds, long revision, AerodynamicProfile profile, long tick) {
    }
}
