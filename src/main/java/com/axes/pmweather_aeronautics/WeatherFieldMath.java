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

    static Vec3 heading(Vec3 previous, Vec3 nose, double maximumTurnRadians) {
        double horizontalLength = Math.hypot(nose.x, nose.z);
        // Horizontal nose azimuth is undefined at vertical and reverses through it.
        // Keep the last direction in that region, then slew toward the new heading.
        if (horizontalLength < 0.25) return previous == null ? new Vec3(0, 0, 1) : previous;
        Vec3 desired = new Vec3(nose.x / horizontalLength, 0, nose.z / horizontalLength);
        if (previous == null) return desired;
        double angle = Math.atan2(previous.x * desired.z - previous.z * desired.x,
            previous.dot(desired));
        double turn = Math.max(-maximumTurnRadians, Math.min(maximumTurnRadians, angle));
        double cosine = Math.cos(turn), sine = Math.sin(turn);
        return new Vec3(previous.x * cosine - previous.z * sine, 0,
            previous.x * sine + previous.z * cosine);
    }
}
