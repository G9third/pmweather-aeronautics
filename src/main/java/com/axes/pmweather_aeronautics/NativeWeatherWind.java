package com.axes.pmweather_aeronautics;

import dev.protomanly.pmweather.weather.WindEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Calls the installed native engine; weather formulas remain in PMWeather. */
final class NativeWeatherWind {
    private NativeWeatherWind() {}

    record Sample(Vec3 wind, double missingVerticalMph, int capturedVectors) {}

    static Sample sample(ServerLevel level, Vec3 point,
                         PMWeatherWindApi.SampleOptions options, int terrainY) {
        return PMWeatherCompatibility.isLegacy0164()
                ? Legacy0164.sample(level, point, options)
                : Modern.sample(level, point, options, terrainY);
    }

    /** This API already returns vertical wind; no additional Y is applied. */
    private static final class Legacy0164 {
        static Sample sample(ServerLevel level, Vec3 point, PMWeatherWindApi.SampleOptions options) {
            return new Sample(WindEngine.getWind(point, level, !options.includeStorms(),
                    !options.includeTornadoes(), false, options.atmosphericField(), false), 0.0D, 0);
        }
    }

    /** Isolated so the 0.16.4 path does not resolve the newer overload. */
    private static final class Modern {
        static Sample sample(ServerLevel level, Vec3 point,
                             PMWeatherWindApi.SampleOptions options, int terrainY) {
            try (NativeTornadoCapture.Scope capture = NativeTornadoCapture.begin()) {
                final Vec3 wind = WindEngine.getWind(point, level, !options.includeStorms(),
                        !options.includeTornadoes(), false, options.atmosphericField(), false, terrainY);
                return new Sample(wind, capture.verticalMph(), capture.vectorCount());
            }
        }
    }
}
