package com.axes.pmweather_aeronautics;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Analytic test atmospheres in raw PMWeather MPH, not force multipliers. */
record WindScenario(String name, String description, double fromDegrees,
        double mph, double verticalMph, String pattern) {
    public static final List<WindScenario> FLIGHT = List.of(
        new WindScenario("calm", "0 mph reference; take off and establish flying airspeed", 0, 0, 0, "steady"),
        new WindScenario("headwind-30", "30 mph from phase-start nose", 0, 30, 0, "steady"),
        new WindScenario("headwind-50", "50 mph from phase-start nose", 0, 50, 0, "steady"),
        new WindScenario("headwind-80", "80 mph from phase-start nose", 0, 80, 0, "steady"),
        new WindScenario("tailwind-25", "25 mph from phase-start tail", 180, 25, 0, "steady"),
        new WindScenario("tailwind-45", "45 mph from phase-start tail", 180, 45, 0, "steady"),
        new WindScenario("left-crosswind-40", "40 mph from phase-start left", 270, 40, 0, "steady"),
        new WindScenario("right-crosswind-70", "70 mph from phase-start right", 90, 70, 0, "steady"),
        new WindScenario("quartering-80", "80 mph from phase-start front-right", 45, 80, 0, "steady"),
        new WindScenario("left-gusts", "30-80 mph from phase-start left; 6-second gust cycle", 270, 55, 0, "gust"),
        new WindScenario("right-gusts", "30-80 mph from phase-start right; 6-second gust cycle", 90, 55, 0, "gust"),
        new WindScenario("downdraft", "50 mph headwind + 15 mph downward", 0, 50, -15, "steady"),
        new WindScenario("updraft", "50 mph headwind + 15 mph upward", 0, 50, 15, "steady"),
        new WindScenario("microburst", "25-75 mph headwind + 6-24 mph down; spatial outflow", 0, 50, -15, "microburst"),
        new WindScenario("shear", "32 mph headwind; crosswind shear +/-40 mph over +/-20 vertical blocks", 0, 32, 0, "shear"),
        new WindScenario("vortex-pass", "moving vortex: tangential peak 90 mph + up to 20 mph up; 15 mph background", 0, 15, 20, "vortex")
    );

    public static final List<WindScenario> STRESS = List.of(
        new WindScenario("calm", "0 mph reference", 0, 0, 0, "steady"),
        new WindScenario("headwind-80", "80 mph from nose", 0, 80, 0, "steady"),
        new WindScenario("headwind-120", "120 mph from nose", 0, 120, 0, "steady"),
        new WindScenario("tailwind-100", "100 mph from tail", 180, 100, 0, "steady"),
        new WindScenario("left-crosswind-80", "80 mph from pilot's left", 270, 80, 0, "steady"),
        new WindScenario("right-crosswind-120", "120 mph from pilot's right", 90, 120, 0, "steady"),
        new WindScenario("quartering-140", "140 mph from front-right at 45 degrees", 45, 140, 0, "steady"),
        new WindScenario("left-gusts", "60-160 mph from left; 6-second gust cycle", 270, 110, 0, "gust"),
        new WindScenario("right-gusts", "60-160 mph from right; 6-second gust cycle", 90, 110, 0, "gust"),
        new WindScenario("downdraft", "100 mph from nose + 60 mph downward", 0, 100, -60, "steady"),
        new WindScenario("updraft", "80 mph from nose + 60 mph upward", 0, 80, 60, "steady"),
        new WindScenario("microburst", "40-120 mph from nose + 20-80 mph down; spatial outflow", 0, 80, -50, "microburst"),
        new WindScenario("shear", "80 mph from nose; crosswind shear +/-100 mph over +/-20 vertical blocks", 0, 80, 0, "shear"),
        new WindScenario("vortex-pass", "moving vortex: tangential peak 180 mph + up to 80 mph up; 30 mph background", 0, 30, 80, "vortex")
    );

    public Vec3 sample(Vec3 offset, Vec3 forward, double seconds, double phaseSeconds) {
        // Forward is the horizontal nose direction. Pilot's right is forward cross world-up.
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        double speed = mph;
        double up = verticalMph;
        if (pattern.equals("gust")) speed += mph * (50.0 / 110.0) * Math.sin(seconds * Math.PI / 3);
        double radians = Math.toRadians(fromDegrees);
        Vec3 wind = forward.scale(-speed * Math.cos(radians))
            .add(right.scale(-speed * Math.sin(radians)));
        if (pattern.equals("microburst")) {
            double wave = Math.sin(seconds * Math.PI / 5);
            double gradient = mph * (3.0 / 80.0);
            double limit = mph * (70.0 / 80.0);
            wind = forward.scale(-mph * (1 + 0.5 * wave))
                .add(right.scale(clamp(offset.dot(right) * gradient, -limit, limit)))
                .add(forward.scale(clamp(offset.dot(forward) * gradient, -limit, limit)));
            up = verticalMph * (1 + 0.6 * wave);
        } else if (pattern.equals("shear")) {
            wind = wind.add(right.scale(clamp(offset.y * mph / 16.0, -mph * 1.25, mph * 1.25)));
        } else if (pattern.equals("vortex")) {
            double centerRight = 70 - 140 * clamp(seconds / phaseSeconds, 0, 1);
            Vec3 fromCenter = offset.subtract(right.scale(centerRight)).subtract(forward.scale(25));
            double radius = Math.hypot(fromCenter.x, fromCenter.z);
            double peak = mph * 6;
            double swirl = radius < 30 ? peak * radius / 30 : peak * 30 / radius;
            if (radius > 1.0e-9) wind = wind.add(new Vec3(-fromCenter.z, 0, fromCenter.x).scale(swirl / radius));
            up = verticalMph * Math.exp(-radius * radius / (2 * 45 * 45));
        }
        return wind.add(0, up, 0);
    }

    public String label() {
        return switch (name) {
            case "calm" -> "Calm";
            case "headwind-30" -> "Head 30";
            case "headwind-50" -> "Head 50";
            case "headwind-80" -> "Head 80";
            case "headwind-120" -> "Head 120";
            case "tailwind-25" -> "Tail 25";
            case "tailwind-45" -> "Tail 45";
            case "tailwind-100" -> "Tail 100";
            case "left-crosswind-40" -> "Left 40";
            case "left-crosswind-80" -> "Left 80";
            case "right-crosswind-70" -> "Right 70";
            case "right-crosswind-120" -> "Right 120";
            case "quartering-80" -> "Quarter 80";
            case "quartering-140" -> "Quarter 140";
            case "left-gusts" -> "Left gusts";
            case "right-gusts" -> "Right gusts";
            case "vortex-pass" -> "Vortex";
            case "microburst" -> "Microburst";
            case "shear" -> "Shear";
            case "updraft" -> "Updraft";
            case "downdraft" -> "Downdraft";
            default -> "Custom";
        };
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
