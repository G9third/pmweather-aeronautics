package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Resolves the rider's world point and samples nearby solid boundaries when useful. */
public final class WindSamplePosition {
    private static final double MAX_EXTERIOR_SEARCH_METERS = 6.0;
    private static final double STEP_METERS = 0.5;
    private static final double CLEARANCE_METERS = 1.5;
    private static final int HEADING_BLOCK_RADIUS = 1;
    private static final long HEADING_CACHE_TICKS = 10L;
    private static final Vec3[] DIRECTIONS = buildDirections();
    private static final Map<Player, StructureHeading> STRUCTURE_HEADINGS = new WeakHashMap<>();
    private static volatile Method pmivForward;
    private static volatile boolean pmivForwardResolved;

    private record StructureHeading(Entity vehicle, UUID subLevelId, BlockPos storageSeatBlock,
                                    long refreshedAt, Vec3 localForward) {}

    private WindSamplePosition() {}

    /** World position and fixed horizontal nose direction for the current rider/vehicle. */
    public record RiderSample(Vec3 worldPoint, Vec3 forward) {}

    public static RiderSample resolve(ServerPlayer player) {
        Vec3 forward = invokeForward(player);
        if (forward == null) forward = structureForward(player);
        return new RiderSample(worldPoint(player), forward != null ? forward : forwardFromYaw(player.getYRot()));
    }

    public static Vec3 worldPoint(ServerPlayer player) {
        return worldPoint((Player) player);
    }

    public static Vec3 worldPoint(Player player) {
        return worldPosition(player, player.getEyePosition());
    }

    private static Vec3 worldPosition(Player player, Vec3 position) {
        try {
            // Tracked riders use world coordinates; storage-plot entities still need projection.
            Vec3 projected = Sable.HELPER.projectOutOfSubLevel(player.level(), position);
            return finite(projected) ? projected : position;
        } catch (RuntimeException | LinkageError ignored) {
            return position;
        }
    }

    private static Vec3 forwardFromYaw(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    /** Resolve a structure-authored local heading when PMIV is absent or no IV seat is used. */
    public static Vec3 structureForward(Player player) {
        if (!player.isPassenger()) return null;
        SubLevel subLevel = relatedSubLevel(player, player.level());
        if (subLevel == null) {
            synchronized (STRUCTURE_HEADINGS) { STRUCTURE_HEADINGS.remove(player); }
            return null;
        }
        Vec3 world = worldPosition(player, player.position());
        Vec3 storage = toSubLevelLocal(subLevel.logicalPose(), world);
        if (!finite(storage)) return null;

        BlockPos seatPos = storageBlockPos(subLevel.logicalPose(), world);
        long now = player.level().getGameTime();
        Entity vehicle = player.getVehicle();
        UUID subLevelId = subLevel.getUniqueId();
        StructureHeading cached;
        synchronized (STRUCTURE_HEADINGS) {
            cached = STRUCTURE_HEADINGS.get(player);
        }
        if (cached == null || cached.vehicle() != vehicle || !Objects.equals(cached.subLevelId(), subLevelId)
            || !cached.storageSeatBlock().equals(seatPos) || now < cached.refreshedAt()
            || now - cached.refreshedAt() >= HEADING_CACHE_TICKS) {
            BlockPos authored = nearestHorizontalFacing(player.level(), subLevel, seatPos);
            Vec3 localForward = authored == null
                ? new Vec3(0.0, 0.0, 1.0)
                : directionVector(player.level().getBlockState(authored)
                    .getValue(BlockStateProperties.HORIZONTAL_FACING));
            cached = new StructureHeading(vehicle, subLevelId, seatPos, now, localForward);
            synchronized (STRUCTURE_HEADINGS) { STRUCTURE_HEADINGS.put(player, cached); }
        }
        // Keep the authored heading local in the cache and apply the current pose
        // once per HUD render, so moving structures rotate with the reference.
        return horizontalUnit(subLevel.logicalPose().transformNormal(cached.localForward()));
    }

    private static BlockPos nearestHorizontalFacing(Level level, SubLevel subLevel, BlockPos center) {
        BlockPos nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -HEADING_BLOCK_RADIUS; dy <= HEADING_BLOCK_RADIUS; dy++) {
            for (int dx = -HEADING_BLOCK_RADIUS; dx <= HEADING_BLOCK_RADIUS; dx++) {
                for (int dz = -HEADING_BLOCK_RADIUS; dz <= HEADING_BLOCK_RADIUS; dz++) {
                    int distance = dx * dx + dy * dy + dz * dz;
                    if (distance >= bestDistance) continue;
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    Vec3 centerPoint = new Vec3(cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5);
                    if (!subLevel.getPlot().contains(centerPoint) || !level.hasChunkAt(cursor)) continue;
                    if (!level.getBlockState(cursor).hasProperty(BlockStateProperties.HORIZONTAL_FACING)) continue;
                    nearest = cursor.immutable();
                    bestDistance = distance;
                }
            }
        }
        return nearest;
    }

    private static Vec3 directionVector(Direction direction) {
        Vec3i normal = direction.getNormal();
        return new Vec3(normal.getX(), normal.getY(), normal.getZ());
    }

    private static Vec3 horizontalUnit(Vec3 direction) {
        if (!finite(direction)) return null;
        double length = Math.hypot(direction.x, direction.z);
        return length > 1.0e-6 ? new Vec3(direction.x / length, 0.0, direction.z / length) : null;
    }

    private static SubLevel relatedSubLevel(Player player, Level level) {
        SubLevel subLevel;
        try {
            // Prefer the geometric structure tracking or carrying the rider.
            subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
            if (subLevel == null) {
                // This lookup is only the storage/local-plot fallback.
                subLevel = Sable.HELPER.getContaining(player);
            }
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        return subLevel == null || subLevel.isRemoved() || subLevel.getLevel() != level ? null : subLevel;
    }

    public static Vec3 exposedWorldPoint(ServerLevel level, ServerPlayer player) {
        Vec3 origin = worldPoint(player);
        if (!finite(origin)) return Vec3.ZERO;
        SubLevel containing = relatedSubLevel(player, level);
        Vec3 nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Vec3 direction : DIRECTIONS) {
            boolean crossedSolid = false;
            double firstOpenDistance = Double.NaN;
            for (double distance = STEP_METERS; distance <= MAX_EXTERIOR_SEARCH_METERS; distance += STEP_METERS) {
                Vec3 point = origin.add(direction.scale(distance));
                Boolean blocked = blockedAt(level, containing, point);
                if (blocked == null) break;
                if (blocked) {
                    crossedSolid = true;
                    firstOpenDistance = Double.NaN;
                    continue;
                }
                if (!crossedSolid) continue;
                if (Double.isNaN(firstOpenDistance)) firstOpenDistance = distance;
                if (distance - firstOpenDistance < CLEARANCE_METERS) continue;
                if (firstOpenDistance < nearestDistance) {
                    nearest = origin.add(direction.scale(firstOpenDistance));
                    nearestDistance = firstOpenDistance;
                }
                break;
            }
        }
        return nearest == null ? origin : nearest;
    }

    private static Vec3 invokeForward(ServerPlayer player) {
        Method method = pmivForwardMethod();
        if (method == null) return null;
        try {
            Object value = method.invoke(null, player);
            if (!(value instanceof Vec3 direction) || !finite(direction)) return null;
            double length = Math.hypot(direction.x, direction.z);
            if (length < 1.0e-6) return null;
            return new Vec3(direction.x / length, 0.0, direction.z / length);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Method pmivForwardMethod() {
        if (pmivForwardResolved) return pmivForward;
        synchronized (WindSamplePosition.class) {
            if (pmivForwardResolved) return pmivForward;
            pmivForwardResolved = true;
            try {
                if (!net.neoforged.fml.ModList.get().isLoaded("pmweather_iv")) return null;
                Class<?> helper = Class.forName("com.g9third.pmweatheriv.network.WindSamplePosition", false,
                    WindSamplePosition.class.getClassLoader());
                pmivForward = helper.getMethod("vehicleForward", ServerPlayer.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                pmivForward = null;
            }
            return pmivForward;
        }
    }

    private static Boolean blockedAt(ServerLevel level, SubLevel subLevel, Vec3 worldPoint) {
        Vec3 point = worldPoint;
        if (subLevel != null) {
            point = toSubLevelLocal(subLevel.logicalPose(), worldPoint);
            if (!finite(point)) return null;
            if (!subLevel.getPlot().contains(point)) return false;
        }
        BlockPos pos = subLevel == null
            ? BlockPos.containing(point)
            : storageBlockPos(subLevel.logicalPose(), worldPoint);
        if (!level.hasChunkAt(pos)) return null;
        try {
            // Inverse pose coordinates are absolute plot-storage coordinates. The
            // embedded accessor adds getCenterBlock() internally, so adding it here
            // would query a second, unrelated location.
            VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) return false;
            double x = point.x - pos.getX();
            double y = point.y - pos.getY();
            double z = point.z - pos.getZ();
            for (AABB box : shape.toAabbs()) {
                if (x >= box.minX && x < box.maxX && y >= box.minY && y < box.maxY
                    && z >= box.minZ && z < box.maxZ) return true;
            }
            return false;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Vec3[] buildDirections() {
        return new Vec3[] {
            new Vec3(1.0, 0.15, 0.0).normalize(), new Vec3(-1.0, 0.15, 0.0).normalize(),
            new Vec3(0.0, 0.15, 1.0).normalize(), new Vec3(0.0, 0.15, -1.0).normalize(),
            new Vec3(1.0, 1.0, 1.0).normalize(), new Vec3(-1.0, 1.0, 1.0).normalize(),
            new Vec3(1.0, 1.0, -1.0).normalize(), new Vec3(-1.0, 1.0, -1.0).normalize()
        };
    }

    static Vec3 toSubLevelLocal(dev.ryanhcode.sable.companion.math.Pose3dc pose, Vec3 worldPoint) {
        return pose.transformPositionInverse(worldPoint);
    }

    static Vec3 toWorldFromSubLevel(dev.ryanhcode.sable.companion.math.Pose3dc pose, Vec3 localPoint) {
        return pose.transformPosition(localPoint);
    }

    static BlockPos storageBlockPos(dev.ryanhcode.sable.companion.math.Pose3dc pose, Vec3 worldPoint) {
        return BlockPos.containing(toSubLevelLocal(pose, worldPoint));
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
