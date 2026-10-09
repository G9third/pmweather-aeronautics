package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;

/**
 * 0.7 force solver.
 *
 * WeatherWindField supplies PMWeather wind and AeroSurfaceCache supplies exterior patches.
 * This class owns the stable conversion from patch pressure into Sable local force/torque.
 */
final class SableAeroSolver {
    private static final Vector3d ZERO_TORQUE = new Vector3d();

    private SableAeroSolver() {
    }

    interface PressureEntry {
        Vector3dc applicationPoint();

        Vector3dc impulse();
    }

    static Vector3d pressureLineCenter(final int role, final Vector3dc profileCenter, final Vector3dc centerOfMass) {
        if (isFinite(profileCenter)) {
            return new Vector3d(profileCenter);
        }
        return centerOfMass == null || !isFinite(centerOfMass)
                ? new Vector3d()
                : new Vector3d(centerOfMass);
    }

    static Vector3d computeDifferentialPressureTorque(final MassData massData,
                                                      final List<? extends PressureEntry> entries,
                                                      final Vector3dc pressureLineCenter,
                                                      final Vector3dc totalImpulse) {
        if (!Config.enableDifferentialPressureTorque()
                || massData == null
                || entries == null
                || entries.isEmpty()
                || pressureLineCenter == null
                || !isFinite(pressureLineCenter)
                || !isFinite(totalImpulse)
                || totalImpulse.lengthSquared() <= 1.0e-12D) {
            return new Vector3d(ZERO_TORQUE);
        }

        final Vector3dc centerOfMass = massData.getCenterOfMass();
        final Vector3d rawPointTorque = new Vector3d();
        for (final PressureEntry entry : entries) {
            if (entry == null || entry.applicationPoint() == null || entry.impulse() == null) {
                continue;
            }
            rawPointTorque.add(new Vector3d(entry.applicationPoint()).sub(centerOfMass).cross(entry.impulse(), new Vector3d()));
        }

        final Vector3d uniformTorque = new Vector3d(pressureLineCenter).sub(centerOfMass).cross(totalImpulse, new Vector3d());
        final Vector3d differentialTorque = rawPointTorque.sub(uniformTorque);
        differentialTorque.mul(Config.differentialPressureTorqueStrength());
        capLength(differentialTorque, Config.maxDifferentialTorqueImpulse());
        return differentialTorque;
    }

    private static void capLength(final Vector3d vector, final double maxLength) {
        if (maxLength <= 0.0D) {
            vector.zero();
            return;
        }

        final double len = vector.length();
        if (len > maxLength) {
            vector.mul(maxLength / len);
        }
    }

    private static boolean isFinite(final Vector3dc vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }
}
