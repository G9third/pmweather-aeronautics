package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side debug visualization of PMAero body-pressure patch membership.
 *
 * Each selected smart-LOD patch receives one stable color. Every exposed source block face that
 * contributes to that selected patch gets a dust marker in the same color. A larger marker is
 * drawn at the patch's actual force/sample application center. This is observer-only: it never
 * changes patch selection, PMWeather sampling, force calculation, or Sable state.
 */
final class AeroPatchVisualizer {
    private static final int REFRESH_TICKS = 5;
    private static final int MAX_FACE_PARTICLES_PER_PLAYER = 4096;
    private static final int MAX_PATCH_CENTER_PARTICLES_PER_PLAYER = 128;
    private static final long ACTUAL_SELECTION_MAX_AGE_TICKS = 20L;
    private static final double FACE_NORMAL_OFFSET = 0.035D;

    private static final Vector3f[] COLORS = new Vector3f[] {
            new Vector3f(1.00F, 0.25F, 0.25F),
            new Vector3f(0.25F, 0.65F, 1.00F),
            new Vector3f(0.30F, 1.00F, 0.40F),
            new Vector3f(1.00F, 0.80F, 0.20F),
            new Vector3f(0.85F, 0.35F, 1.00F),
            new Vector3f(0.20F, 1.00F, 0.95F),
            new Vector3f(1.00F, 0.45F, 0.75F),
            new Vector3f(0.65F, 1.00F, 0.20F),
            new Vector3f(1.00F, 0.55F, 0.20F),
            new Vector3f(0.50F, 0.45F, 1.00F),
            new Vector3f(0.20F, 0.90F, 0.65F),
            new Vector3f(1.00F, 0.95F, 0.55F)
    };

    private static final Set<UUID> VIEWERS = new HashSet<>();
    private static final Map<UUID, SelectedPatchSnapshot> LATEST_SELECTED = new HashMap<>();

    private AeroPatchVisualizer() {
    }

    static boolean hasViewers() {
        return !VIEWERS.isEmpty();
    }

    static boolean isEnabled(final UUID playerId) {
        return playerId != null && VIEWERS.contains(playerId);
    }

    static boolean toggle(final ServerPlayer player) {
        final boolean enabled = !VIEWERS.contains(player.getUUID());
        setEnabled(player, enabled);
        return enabled;
    }

    static void setEnabled(final ServerPlayer player, final boolean enabled) {
        final boolean hadViewers = !VIEWERS.isEmpty();
        if (enabled) {
            VIEWERS.add(player.getUUID());
        } else {
            VIEWERS.remove(player.getUUID());
        }
        final boolean hasViewers = !VIEWERS.isEmpty();
        if (hadViewers != hasViewers) {
            // Exact source membership is retained only while somebody is viewing it. Rebuild the
            // cached profiles on the first enable, and drop that debug-only metadata after the
            // last viewer disables it. Physics geometry itself is unchanged.
            LATEST_SELECTED.clear();
            AeroSurfaceCache.invalidateProfilesForDebugMembershipToggle();
        }
    }

    /** Called from the real body-wind preparation path after the actual per-object budget is known. */
    static void observeSelectedPatches(final ServerSubLevel subLevel,
                                       final List<AeroSurfaceCache.ProfileFace> selectedFaces) {
        if (!hasViewers() || subLevel == null) {
            return;
        }
        LATEST_SELECTED.put(
                subLevel.getUniqueId(),
                new SelectedPatchSnapshot(
                        subLevel.getLevel().getGameTime(),
                        selectedFaces == null ? List.of() : List.copyOf(selectedFaces)
                )
        );
    }

    static void onServerTick(final MinecraftServer server) {
        if (VIEWERS.isEmpty() || server.getTickCount() % REFRESH_TICKS != 0) {
            return;
        }
        final Iterator<UUID> iterator = VIEWERS.iterator();
        while (iterator.hasNext()) {
            final UUID playerId = iterator.next();
            final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                iterator.remove();
                continue;
            }
            renderNearest(player);
        }
    }

    static String status(final ServerPlayer player) {
        final ServerSubLevel nearest = nearestSubLevel(player);
        if (nearest == null) {
            return "PMAero patch visualization " + (isEnabled(player.getUUID()) ? "enabled" : "disabled")
                    + "; no Sable/Aeronautics structure found in this level.";
        }
        final DisplaySelection selection = displaySelection(nearest);
        return String.format(
                Locale.ROOT,
                "PMAero patch visualization %s; nearest=%s patches=%d representedFaces=%d source=%s",
                isEnabled(player.getUUID()) ? "enabled" : "disabled",
                nearest.getUniqueId(),
                selection.faces().size(),
                representedFaces(selection.faces()),
                selection.actualSelection() ? "actual current body-wind selection" : "patch-layout preview"
        );
    }

    private static void renderNearest(final ServerPlayer player) {
        final ServerSubLevel subLevel = nearestSubLevel(player);
        if (subLevel == null) {
            return;
        }
        final DisplaySelection selection = displaySelection(subLevel);
        final List<AeroSurfaceCache.ProfileFace> faces = selection.faces();
        if (faces.isEmpty()) {
            return;
        }

        final int totalFaces = representedFaces(faces);
        final int stride = Math.max(1, (int) Math.ceil(totalFaces / (double) MAX_FACE_PARTICLES_PER_PLAYER));
        final Pose3d pose = subLevel.logicalPose();
        int globalFaceIndex = 0;
        int patchIndex = 0;
        int centerParticles = 0;

        for (final AeroSurfaceCache.ProfileFace face : faces) {
            final Vector3f color = COLORS[Math.floorMod(patchIndex, COLORS.length)];
            final DustParticleOptions faceParticle = new DustParticleOptions(color, 0.48F);
            final DustParticleOptions centerParticle = new DustParticleOptions(color, 1.05F);

            final Vector3d worldNormal = new Vector3d(face.normal().x, face.normal().y, face.normal().z);
            pose.transformNormal(worldNormal);
            if (worldNormal.lengthSquared() > 1.0e-12D) {
                worldNormal.normalize();
            } else {
                worldNormal.zero();
            }

            for (final AeroSurfaceCache.PatchFootprint footprint : face.sourceFootprints()) {
                for (int da = 0; da < footprint.height(); da++) {
                    for (int db = 0; db < footprint.width(); db++) {
                        if (globalFaceIndex++ % stride != 0) {
                            continue;
                        }
                        final Vec3 localCenter = footprint.localFaceCenter(da, db);
                        if (localCenter == Vec3.ZERO) {
                            continue;
                        }
                        final Vector3d world = new Vector3d(localCenter.x, localCenter.y, localCenter.z);
                        pose.transformPosition(world);
                        player.serverLevel().sendParticles(
                                player,
                                faceParticle,
                                true,
                                world.x + worldNormal.x * FACE_NORMAL_OFFSET,
                                world.y + worldNormal.y * FACE_NORMAL_OFFSET,
                                world.z + worldNormal.z * FACE_NORMAL_OFFSET,
                                1,
                                0.0D,
                                0.0D,
                                0.0D,
                                0.0D
                        );
                    }
                }
            }

            if (centerParticles < MAX_PATCH_CENTER_PARTICLES_PER_PLAYER
                    && face.point() != Vec3.ZERO
                    && face.point().lengthSqr() > 1.0e-12D) {
                final Vector3d center = new Vector3d(face.point().x, face.point().y, face.point().z);
                pose.transformPosition(center);
                player.serverLevel().sendParticles(
                        player,
                        centerParticle,
                        true,
                        center.x + worldNormal.x * 0.10D,
                        center.y + worldNormal.y * 0.10D,
                        center.z + worldNormal.z * 0.10D,
                        1,
                        0.0D,
                        0.0D,
                        0.0D,
                        0.0D
                );
                centerParticles++;
            }
            patchIndex++;
        }
    }

    private static DisplaySelection displaySelection(final ServerSubLevel subLevel) {
        final SelectedPatchSnapshot actual = LATEST_SELECTED.get(subLevel.getUniqueId());
        final long now = subLevel.getLevel().getGameTime();
        if (actual != null && now - actual.tick() <= ACTUAL_SELECTION_MAX_AGE_TICKS) {
            return new DisplaySelection(actual.faces(), true);
        }
        final AeroSurfaceCache.AerodynamicProfile profile = AeroSurfaceCache.get(subLevel);
        if (profile.samples().isEmpty()) {
            return new DisplaySelection(List.of(), false);
        }
        return new DisplaySelection(
                profile.selectedSamples(Math.max(0, Config.maxAeroPatchSamplesPerObject())),
                false
        );
    }

    private static ServerSubLevel nearestSubLevel(final ServerPlayer player) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(player.serverLevel());
        if (container == null) {
            return null;
        }
        ServerSubLevel nearest = null;
        double nearestDistanceSqr = Double.POSITIVE_INFINITY;
        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            final Vec3 center = worldCenter(subLevel);
            final double distanceSqr = player.position().distanceToSqr(center);
            if (distanceSqr < nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearest = subLevel;
            }
        }
        return nearest;
    }

    private static Vec3 worldCenter(final ServerSubLevel subLevel) {
        final MassData massData = subLevel.getMassTracker();
        final Pose3d pose = subLevel.logicalPose();
        if (massData != null && !massData.isInvalid()) {
            final Vector3dc centerOfMass = massData.getCenterOfMass();
            if (centerOfMass != null) {
                final Vector3d world = new Vector3d();
                pose.transformPosition(centerOfMass, world);
                return new Vec3(world.x, world.y, world.z);
            }
        }
        final Vector3d origin = new Vector3d();
        pose.transformPosition(origin);
        return new Vec3(origin.x, origin.y, origin.z);
    }

    private static int representedFaces(final List<AeroSurfaceCache.ProfileFace> faces) {
        int total = 0;
        for (final AeroSurfaceCache.ProfileFace face : faces) {
            total += face.representedFaceCount();
        }
        return total;
    }

    static void clearSession() {
        VIEWERS.clear();
        LATEST_SELECTED.clear();
    }

    private record SelectedPatchSnapshot(long tick, List<AeroSurfaceCache.ProfileFace> faces) {
    }

    private record DisplaySelection(List<AeroSurfaceCache.ProfileFace> faces, boolean actualSelection) {
    }
}
