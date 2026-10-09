package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Bridges optional Sable wheel contact calculations into the current wind physics step. */
public final class OffroadWheelContactAdapter {
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();

    private OffroadWheelContactAdapter() {
    }

    public static void begin() {
        ACTIVE.set(new Capture());
    }

    public static void terrainNormal(final Direction normal) {
        final Capture capture = ACTIVE.get();
        if (capture != null && normal != null) {
            capture.terrainNormal = normal;
        }
    }

    public static void terrainSubLevel(final SubLevel terrainSubLevel) {
        final Capture capture = ACTIVE.get();
        if (capture != null) {
            capture.terrainSubLevel = terrainSubLevel;
        }
    }

    public static Snapshot snapshot(final ServerSubLevel body,
                             final Vector3dc point,
                             final Vector3dc springImpulse,
                             final double friction) {
        if (body == null || point == null || springImpulse == null
                || !isFinite(point) || !isFinite(springImpulse) || !Double.isFinite(friction)) {
            return null;
        }

        final Capture capture = ACTIVE.get();
        if (capture == null || capture.terrainNormal == null) {
            return null;
        }
        final Direction direction = capture.terrainNormal;
        final Vector3d normal = new Vector3d(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        final SubLevel terrainSubLevel = capture.terrainSubLevel;
        if (terrainSubLevel != null) {
            terrainSubLevel.logicalPose().transformNormal(normal);
        }
        body.logicalPose().transformNormalInverse(normal);
        if (!isFinite(normal) || normal.lengthSquared() <= 1.0e-12D) {
            return null;
        }
        normal.normalize();

        return new Snapshot(
                new Vector3d(point),
                normal,
                springImpulse.dot(normal),
                Math.max(0.0D, friction)
        );
    }

    public static void submitted(final Object wheel,
                          final ServerSubLevel body,
                          final Snapshot snapshot,
                          final Vector3dc originalCombinedImpulse) {
        if (body == null) return;
        if (snapshot != null && originalCombinedImpulse != null && isFinite(originalCombinedImpulse)) {
            WheelContactWindApi.recordContactImpulse(
                    body,
                    wheel,
                    snapshot.position(),
                    snapshot.normal(),
                    snapshot.normalImpulse(),
                    snapshot.friction(),
                    originalCombinedImpulse
            );
        } else {
            IntegrationHealth.contact(body.getLevel().getServer().getTickCount(), false);
        }
    }

    public static void end() {
        ACTIVE.remove();
    }

    private static boolean isFinite(final Vector3dc vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    public record Snapshot(Vector3d position, Vector3d normal, double normalImpulse, double friction) {
    }

    private static final class Capture {
        private Direction terrainNormal;
        private SubLevel terrainSubLevel;

        private Capture() {
        }
    }
}
