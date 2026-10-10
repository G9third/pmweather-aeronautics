package com.axes.pmweather_aeronautics;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Client-only, bounded local-air drift bridge for PMWeather-IV custom particles. */
public final class ParticleWindClient {
    private static final int CELL_BLOCKS = 16;
    private static final int CACHE_SIZE = 512;
    private static final int CACHE_MASK = CACHE_SIZE - 1;
    private static final int CANDIDATE_CAPACITY = 4096;
    private static final int CANDIDATE_MASK = CANDIDATE_CAPACITY - 1;
    private static final int CANDIDATE_SET_SIZE = 8192;
    private static final int CANDIDATE_SET_MASK = CANDIDATE_SET_SIZE - 1;
    private static final int CANDIDATE_REBUILD_THRESHOLD = CANDIDATE_SET_SIZE / 4;
    private static final long SAMPLE_TTL_TICKS = 40L;
    private static final long REFRESH_INTERVAL_TICKS = 4L;
    private static final double CAMERA_RADIUS_SQUARED = 96.0D * 96.0D;
    private static final double MPH_TO_BLOCKS_PER_TICK = 0.44704D / 20.0D;
    private static final int[] NEIGHBOR_X = {1, -1, 0, 0, 0, 0};
    private static final int[] NEIGHBOR_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] NEIGHBOR_Z = {0, 0, 0, 0, 1, -1};
    private static final ParticleWindBudget BUDGET = new ParticleWindBudget();
    private static final long[] CELL_KEYS = new long[CACHE_SIZE];
    private static final long[] SAMPLE_TICKS = new long[CACHE_SIZE];
    private static final double[] WIND_X = new double[CACHE_SIZE];
    private static final double[] WIND_Y = new double[CACHE_SIZE];
    private static final double[] WIND_Z = new double[CACHE_SIZE];
    private static final boolean[] OCCUPIED = new boolean[CACHE_SIZE];
    private static final boolean[] TEST_FIELD_SAMPLE = new boolean[CACHE_SIZE];
    private static final double[] WIND_SCRATCH = new double[3];
    private static final long[] CANDIDATE_QUEUE = new long[CANDIDATE_CAPACITY];
    private static final long[] CANDIDATE_SET_KEYS = new long[CANDIDATE_SET_SIZE];
    private static final byte[] CANDIDATE_SET_STATES = new byte[CANDIDATE_SET_SIZE];
    private static final long[] CANDIDATE_REBUILD_KEYS = new long[CANDIDATE_SET_SIZE];
    private static final byte[] CANDIDATE_REBUILD_STATES = new byte[CANDIDATE_SET_SIZE];
    private static final long[] IN_FLIGHT_KEYS = new long[ParticleWindBudget.MAX_REFRESH_QUERIES];
    private static final double[] IN_FLIGHT_COORDINATES = new double[ParticleWindBudget.MAX_REFRESH_QUERIES * 3];

    private static ClientLevel activeLevel;
    private static long activeTick = Long.MIN_VALUE;
    private static long lastFlushTick = Long.MIN_VALUE;
    private static long nextRequestId = 1L;
    private static long inFlightRequestId;
    private static long inFlightSentTick = Long.MIN_VALUE;
    private static int candidateHead;
    private static int candidateCount;
    private static int candidateTombstones;
    private static int inFlightCount;
    private static int occupiedCells;

    private ParticleWindClient() {
    }

    /** Called by the ParticleEngine mixin when Minecraft changes or unloads a client world. */
    public static void onParticleEngineLevelChanged(ClientLevel level) {
        if (activeLevel != level) {
            clearCache();
            activeLevel = level;
            activeTick = Long.MIN_VALUE;
        }
    }

    /**
     * Reusable client bridge for PMWeather-IV's native EntityParticle motion. The caller owns
     * and reuses {@code velocity}; its coordinates and values are in blocks and blocks/tick.
     */
    public static boolean applyParticleWind(Level level, Object particleIdentity,
            double x, double y, double z, Vector3d velocity, double response) {
        if (!ParticleWindConfig.ENABLED.get() || !(level instanceof ClientLevel clientLevel)
                || velocity == null || particleIdentity == null || !finite(x, y, z)
                || !finite(velocity.x, velocity.y, velocity.z) || !Double.isFinite(response)
                || response <= 0.0D || response > 1.0D) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != clientLevel || !withinCameraRadius(minecraft, x, y, z)) return false;
        ensureClientTick(clientLevel);
        if (!windAt(clientLevel, x, y, z, activeTick, WIND_SCRATCH)) return false;
        if (!BUDGET.reserveApplication(particleIdentity)) return false;

        velocity.set(
            velocity.x + (WIND_SCRATCH[0] - velocity.x) * response,
            velocity.y + (WIND_SCRATCH[1] - velocity.y) * response,
            velocity.z + (WIND_SCRATCH[2] - velocity.z) * response
        );
        return finite(velocity.x, velocity.y, velocity.z);
    }

    /** Clears the bounded cache after disconnect or dimension replacement. */
    public static void clearSession() {
        clearCache();
        activeLevel = null;
        activeTick = Long.MIN_VALUE;
    }

    /** Called by the client tick event to send one bounded native-wind batch every four ticks. */
    public static void flushPendingSamples() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            clearSession();
            return;
        }
        ensureClientTick(level);
        if (activeTick % REFRESH_INTERVAL_TICKS != 0L || lastFlushTick == activeTick) return;
        if (inFlightRequestId != 0L) {
            if (activeTick - inFlightSentTick < 20L) return;
            int expiredCount = inFlightCount;
            inFlightRequestId = 0L;
            inFlightCount = 0;
            requeueInFlight(expiredCount);
        }
        if (candidateCount == 0) return;
        int sampledCandidates = 0;
        int nativeCount = 0;
        while (candidateCount > 0 && sampledCandidates < ParticleWindBudget.MAX_REFRESH_QUERIES) {
            if (!BUDGET.reserveRefreshQuery()) break;
            long key = dequeueCandidate();
            int cellX = unpackCellX(key);
            int cellY = unpackCellY(key);
            int cellZ = unpackCellZ(key);
            double sampleX = (double) cellX * CELL_BLOCKS + CELL_BLOCKS * 0.5D;
            double sampleY = (double) cellY * CELL_BLOCKS + CELL_BLOCKS * 0.5D;
            double sampleZ = (double) cellZ * CELL_BLOCKS + CELL_BLOCKS * 0.5D;
            Vec3 analyticWind;
            try {
                analyticWind = AerowindTest.sampleClient(level, new Vec3(sampleX, sampleY, sampleZ));
            } catch (RuntimeException ignored) {
                enqueueCandidate(key);
                sampledCandidates++;
                continue;
            }
            if (analyticWind != null) {
                if (finite(analyticWind.x, analyticWind.y, analyticWind.z)) {
                    storeWind(key, activeTick, analyticWind, true);
                }
            } else {
                IN_FLIGHT_KEYS[nativeCount] = key;
                IN_FLIGHT_COORDINATES[nativeCount * 3] = sampleX;
                IN_FLIGHT_COORDINATES[nativeCount * 3 + 1] = sampleY;
                IN_FLIGHT_COORDINATES[nativeCount * 3 + 2] = sampleZ;
                nativeCount++;
            }
            sampledCandidates++;
        }
        if (candidateTombstones > CANDIDATE_REBUILD_THRESHOLD) rebuildCandidateSet();
        lastFlushTick = activeTick;
        if (nativeCount == 0) return;
        long requestId = nextRequestId++;
        if (nextRequestId <= 0L) nextRequestId = 1L;
        if (ParticleWindNetwork.sendClientRequest(requestId, nativeCount, IN_FLIGHT_COORDINATES)) {
            inFlightRequestId = requestId;
            inFlightSentTick = activeTick;
            inFlightCount = nativeCount;
        } else {
            requeueInFlight(nativeCount);
            inFlightCount = 0;
        }
    }

    /** Called on the client main thread after a server batch response arrives. */
    public static void acceptServerSamples(long requestId, int count, double[] winds, boolean[] available) {
        if (requestId != inFlightRequestId || count != inFlightCount || activeLevel == null
                || winds == null || available == null || winds.length < count * 3 || available.length < count) return;
        long tick = activeLevel.getGameTime();
        long sentTick = inFlightSentTick;
        inFlightRequestId = 0L;
        inFlightCount = 0;
        inFlightSentTick = Long.MIN_VALUE;
        for (int index = 0; index < count; index++) {
            long key = IN_FLIGHT_KEYS[index];
            if (!available[index]) {
                enqueueCandidate(key);
                continue;
            }
            int slot = cacheSlot(key);
            if (OCCUPIED[slot] && CELL_KEYS[slot] == key && TEST_FIELD_SAMPLE[slot]
                    && SAMPLE_TICKS[slot] >= sentTick) continue;
            double wx = winds[index * 3] * MPH_TO_BLOCKS_PER_TICK;
            double wy = winds[index * 3 + 1] * MPH_TO_BLOCKS_PER_TICK;
            double wz = winds[index * 3 + 2] * MPH_TO_BLOCKS_PER_TICK;
            if (!finite(wx, wy, wz)) continue;
            storeWind(key, tick, new Vec3(winds[index * 3], winds[index * 3 + 1], winds[index * 3 + 2]), false);
        }
    }

    /** Snapshot is allocated only when explicitly requested for diagnostics. */
    public static Metrics metrics() {
        return new Metrics(activeTick, BUDGET.refreshQueries(), BUDGET.applications(), occupiedCells,
            candidateCount, inFlightCount);
    }

    private static boolean windAt(ClientLevel level, double x, double y, double z, long tick, double[] result) {
        int cellX = floorCell(x);
        int cellY = floorCell(y);
        int cellZ = floorCell(z);
        long key = packCell(cellX, cellY, cellZ);
        if (key == Long.MIN_VALUE) return false;
        int slot = cacheSlot(key);

        if (OCCUPIED[slot] && CELL_KEYS[slot] == key) {
            long age = tick - SAMPLE_TICKS[slot];
            if (age >= 0L && age < REFRESH_INTERVAL_TICKS) {
                copyWind(slot, result);
                return true;
            }
            if (age >= 0L && age <= SAMPLE_TTL_TICKS) {
                enqueueCandidate(key);
                copyWind(slot, result);
                return true;
            }
        }
        enqueueCandidate(key);
        return nearbyCachedWind(cellX, cellY, cellZ, tick, result);
    }

    private static void storeWind(long key, long tick, Vec3 wind, boolean testField) {
        double windX = wind.x * MPH_TO_BLOCKS_PER_TICK;
        double windY = wind.y * MPH_TO_BLOCKS_PER_TICK;
        double windZ = wind.z * MPH_TO_BLOCKS_PER_TICK;
        if (!finite(windX, windY, windZ)) return;
        int slot = cacheSlot(key);
        if (!OCCUPIED[slot]) occupiedCells++;
        OCCUPIED[slot] = true;
        CELL_KEYS[slot] = key;
        SAMPLE_TICKS[slot] = tick;
        WIND_X[slot] = windX;
        WIND_Y[slot] = windY;
        WIND_Z[slot] = windZ;
        TEST_FIELD_SAMPLE[slot] = testField;
    }

    private static void requeueInFlight(int count) {
        for (int i = 0; i < count; i++) enqueueCandidate(IN_FLIGHT_KEYS[i]);
    }

    private static boolean enqueueCandidate(long key) {
        if (isInFlight(key) || candidateCount >= CANDIDATE_CAPACITY) return false;
        int slot = candidateSetInsertSlot(key);
        // A full/clustered 64-slot probe window defers this key until a later particle tick.
        if (slot < 0) return false;
        if (CANDIDATE_SET_STATES[slot] == 2) candidateTombstones--;
        CANDIDATE_SET_KEYS[slot] = key;
        CANDIDATE_SET_STATES[slot] = 1;
        CANDIDATE_QUEUE[(candidateHead + candidateCount) & CANDIDATE_MASK] = key;
        candidateCount++;
        return true;
    }

    private static long dequeueCandidate() {
        long key = CANDIDATE_QUEUE[candidateHead];
        candidateHead = (candidateHead + 1) & CANDIDATE_MASK;
        candidateCount--;
        int slot = candidateSetFindSlot(key);
        if (slot >= 0) {
            CANDIDATE_SET_STATES[slot] = 2;
            candidateTombstones++;
        }
        if (candidateCount == 0) {
            candidateHead = 0;
            Arrays.fill(CANDIDATE_SET_STATES, (byte) 0);
            candidateTombstones = 0;
        }
        return key;
    }

    private static int candidateSetFindSlot(long key) {
        return ParticleCandidateSet.findSlot(key, CANDIDATE_SET_KEYS, CANDIDATE_SET_STATES,
            CANDIDATE_SET_MASK);
    }

    private static int candidateSetInsertSlot(long key) {
        return candidateSetInsertSlot(key, CANDIDATE_SET_KEYS, CANDIDATE_SET_STATES);
    }

    private static boolean isInFlight(long key) {
        for (int i = 0; i < inFlightCount; i++) if (IN_FLIGHT_KEYS[i] == key) return true;
        return false;
    }

    private static int candidateSetInsertSlot(long key, long[] keys, byte[] states) {
        return ParticleCandidateSet.insertSlot(key, keys, states, CANDIDATE_SET_MASK);
    }

    private static void rebuildCandidateSet() {
        Arrays.fill(CANDIDATE_REBUILD_STATES, (byte) 0);
        for (int index = 0; index < candidateCount; index++) {
            long key = CANDIDATE_QUEUE[(candidateHead + index) & CANDIDATE_MASK];
            int slot = candidateSetInsertSlot(key, CANDIDATE_REBUILD_KEYS, CANDIDATE_REBUILD_STATES);
            // Keep the live set intact if the clustered queue cannot be rebuilt within 64 probes.
            if (slot < 0) return;
            CANDIDATE_REBUILD_KEYS[slot] = key;
            CANDIDATE_REBUILD_STATES[slot] = 1;
        }
        System.arraycopy(CANDIDATE_REBUILD_KEYS, 0, CANDIDATE_SET_KEYS, 0, CANDIDATE_SET_SIZE);
        System.arraycopy(CANDIDATE_REBUILD_STATES, 0, CANDIDATE_SET_STATES, 0, CANDIDATE_SET_SIZE);
        candidateTombstones = 0;
    }

    private static boolean nearbyCachedWind(int cellX, int cellY, int cellZ, long tick, double[] result) {
        for (int index = 0; index < NEIGHBOR_X.length; index++) {
            long key = packCell(cellX + NEIGHBOR_X[index], cellY + NEIGHBOR_Y[index], cellZ + NEIGHBOR_Z[index]);
            if (key == Long.MIN_VALUE) continue;
            int slot = cacheSlot(key);
            if (OCCUPIED[slot] && CELL_KEYS[slot] == key) {
                long age = tick - SAMPLE_TICKS[slot];
                if (age >= 0L && age <= SAMPLE_TTL_TICKS) {
                    copyWind(slot, result);
                    return true;
                }
            }
        }
        return false;
    }

    private static void copyWind(int slot, double[] result) {
        result[0] = WIND_X[slot];
        result[1] = WIND_Y[slot];
        result[2] = WIND_Z[slot];
    }

    private static void ensureClientTick(ClientLevel level) {
        long tick = level.getGameTime();
        if (activeLevel != level) {
            clearCache();
            activeLevel = level;
            activeTick = Long.MIN_VALUE;
        } else if (activeTick != Long.MIN_VALUE && tick < activeTick) {
            clearCache();
            activeTick = Long.MIN_VALUE;
        }
        if (activeTick != tick) {
            activeTick = tick;
            BUDGET.beginClientTick();
        }
    }

    private static void clearCache() {
        Arrays.fill(OCCUPIED, false);
        Arrays.fill(TEST_FIELD_SAMPLE, false);
        occupiedCells = 0;
        resetRequests();
    }

    private static void resetRequests() {
        candidateHead = 0;
        candidateCount = 0;
        candidateTombstones = 0;
        Arrays.fill(CANDIDATE_SET_STATES, (byte) 0);
        inFlightCount = 0;
        inFlightRequestId = 0L;
        inFlightSentTick = Long.MIN_VALUE;
        lastFlushTick = Long.MIN_VALUE;
    }

    private static boolean withinCameraRadius(Minecraft minecraft, double x, double y, double z) {
        if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) return false;
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double dx = x - camera.x;
        double dy = y - camera.y;
        double dz = z - camera.z;
        return dx * dx + dy * dy + dz * dz <= CAMERA_RADIUS_SQUARED;
    }

    private static int floorCell(double position) {
        return (int) Math.floor(position / CELL_BLOCKS);
    }

    private static int unpackCellX(long key) {
        int value = (int) (key >>> 33 & 0x1FFFFFL);
        return (value & 0x100000) == 0 ? value : value - 0x200000;
    }

    private static int unpackCellY(long key) {
        int value = (int) (key >>> 21 & 0xFFFL);
        return (value & 0x800) == 0 ? value : value - 0x1000;
    }

    private static int unpackCellZ(long key) {
        int value = (int) (key & 0x1FFFFFL);
        return (value & 0x100000) == 0 ? value : value - 0x200000;
    }

    private static long packCell(int x, int y, int z) {
        // 21 bits each for horizontal cell coordinates and 12 bits vertically cover the
        // supported Minecraft world bounds while leaving the sentinel outside the range.
        if (x < -1_048_576 || x > 1_048_575 || z < -1_048_576 || z > 1_048_575
                || y < -2048 || y > 2047) return Long.MIN_VALUE;
        return ((long) x & 0x1FFFFFL) << 33
                | ((long) y & 0xFFFL) << 21
                | ((long) z & 0x1FFFFFL);
    }

    private static int cacheSlot(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return (int) key & CACHE_MASK;
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public record Metrics(long clientTick, int refreshedCells, int appliedParticles, int cachedCells,
                          int queuedCells, int inFlightCells) {
        public int refreshBudget() { return ParticleWindBudget.MAX_REFRESH_QUERIES; }
        public int applicationBudget() { return ParticleWindBudget.MAX_APPLICATIONS; }
    }
}
