package com.axes.pmweather_aeronautics;

import net.minecraft.world.phys.Vec3;

/** Scheduling and direction rules for the prescribed development atmosphere. */
final class WeatherFieldMath {
    private WeatherFieldMath() {}

    static double phaseStrength(double seconds, double phaseSeconds, double fadeSeconds) {
        if (!(fadeSeconds > 0.0)) return 0.0;
        double fraction = Math.max(0.0, Math.min(1.0,
            Math.min(seconds, phaseSeconds - seconds) / fadeSeconds));
        return fraction * fraction * (3.0 - 2.0 * fraction);
    }

    /** Capture one normalized horizontal heading for a phase; it is held until the next phase. */
    static Vec3 phaseHeading(Vec3 nose) {
        if (nose == null) return new Vec3(0, 0, 1);
        double horizontalLength = Math.hypot(nose.x, nose.z);
        if (!Double.isFinite(horizontalLength) || horizontalLength < 0.25) {
            return new Vec3(0, 0, 1);
        }
        return new Vec3(nose.x / horizontalLength, 0, nose.z / horizontalLength);
    }
}
