package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Captures the two finalized impulses queued by NoHorizon 1.0.3 for one virtual track contact. */
public final class NoHorizonTrackContactAdapter {
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();
    private static final double QUEUE_POINT_IMPULSE_MIN_LENGTH_SQUARED = 1.0e-8D;

    private NoHorizonTrackContactAdapter() {
    }

    public static void begin(final ServerSubLevel body, final BlockPos position, final double tractionScale) {
        if (body == null || position == null || !Double.isFinite(tractionScale)) {
            ACTIVE.remove();
            return;
        }
        ACTIVE.set(new Capture(body, position.immutable(), Math.max(0.0D, tractionScale)));
    }

    public static void supportQueued(final ServerSubLevel body,
                                     final Vector3dc applicationPoint,
                                     final Vector3dc supportImpulse) {
        final Capture capture = ACTIVE.get();
        if (capture == null || capture.body != body || !isFinite(applicationPoint) || !isFinite(supportImpulse)) {
            return;
        }
        capture.applicationPoint = new Vector3d(applicationPoint);
        capture.supportImpulse = queuedImpulse(supportImpulse);
    }

    public static void tractionQueued(final ServerSubLevel body,
                                      final Vector3dc applicationPoint,
                                      final Vector3dc tractionImpulse) {
        final Capture capture = ACTIVE.get();
        if (capture == null || capture.body != body || capture.supportImpulse == null
                || !isFinite(applicationPoint) || !isFinite(tractionImpulse)) {
            return;
        }
        final Vector3d actualTractionImpulse = queuedImpulse(tractionImpulse);
        if (!applicationPoint.equals(capture.applicationPoint)) {
            // Both native calls target the same local point. If a future binary changes that
            // invariant, skipping the generic-contact record is safer than inventing one point.
            return;
        }
        final Vector3d originalCombinedImpulse = new Vector3d(capture.supportImpulse).add(actualTractionImpulse);
        WheelContactWindApi.recordContactImpulse(
                body,
                capture.contactKey,
                capture.applicationPoint,
                LOCAL_UP,
                capture.supportImpulse.y,
                capture.friction,
                originalCombinedImpulse
        );
    }

    public static void end() {
        ACTIVE.remove();
    }

    private static Vector3d queuedImpulse(final Vector3dc impulse) {
        if (impulse.lengthSquared() < QUEUE_POINT_IMPULSE_MIN_LENGTH_SQUARED) {
            return new Vector3d();
        }
        return new Vector3d(impulse);
    }

    private static boolean isFinite(final Vector3dc vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    private static final Vector3d LOCAL_UP = new Vector3d(0.0D, 1.0D, 0.0D);

    private static final class Capture {
        private final ServerSubLevel body;
        private final BlockPos contactKey;
        private final double friction;
        private Vector3d applicationPoint;
        private Vector3d supportImpulse;

        private Capture(final ServerSubLevel body, final BlockPos contactKey, final double friction) {
            this.body = body;
            this.contactKey = contactKey;
            this.friction = friction;
        }
    }
}
