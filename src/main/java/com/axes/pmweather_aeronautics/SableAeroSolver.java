package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.physics.force.ForceTotal;
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
        final Vector3d center = new Vector3d(profileCenter);
        switch (role) {
            case 2, 3 -> {
                center.y = centerOfMass.y();
                center.z = centerOfMass.z();
            }
            case 4, 5 -> {
                center.x = centerOfMass.x();
                center.y = centerOfMass.y();
            }
            case 1, 6 -> {
                center.x = centerOfMass.x();
                center.z = centerOfMass.z();
            }
            default -> center.set(centerOfMass);
        }
        return center;
    }

    static Vector3d computeDifferentialPressureTorque(final MassData massData,
                                                      final List<? extends PressureEntry> entries,
                                                      final Vector3dc pressureLineCenter,
                                                      final Vector3dc totalImpulse) {
        if (!Config.enableDifferentialPressureTorque()
                || massData == null
                || entries == null
                || entries.isEmpty()
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

    static Vector3d applyDifferentialPressureTorque(final ForceTotal forceTotal,
                                                    final MassData massData,
                                                    final List<? extends PressureEntry> entries,
                                                    final Vector3dc pressureLineCenter,
                                                    final Vector3dc totalImpulse) {
        final Vector3d differentialTorque = computeDifferentialPressureTorque(
                massData, entries, pressureLineCenter, totalImpulse
        );
        if (differentialTorque.lengthSquared() > 1.0e-12D) {
            forceTotal.applyLinearAndAngularImpulse(new Vector3d(), differentialTorque);
        }
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
}
