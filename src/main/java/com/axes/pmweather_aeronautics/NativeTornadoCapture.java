package com.axes.pmweather_aeronautics;

import dev.protomanly.pmweather.weather.Storm;
import dev.protomanly.pmweather.weather.storms.StormTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Observes vectors evaluated by PMWeather during our own query. No tornado formulas or
 * horizontal combination rules are implemented here. Unrelated PMWeather callers are unchanged.
 */
public final class NativeTornadoCapture {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private NativeTornadoCapture() {}

    static Scope begin() {
        final Scope scope = new Scope(ACTIVE.get());
        ACTIVE.set(scope);
        return scope;
    }

    public static void observe(Storm storm, Vec3 vector) {
        final Scope scope = ACTIVE.get();
        if (scope == null || storm == null || vector == null || !storm.is(StormTypes.SUPERCELL)) return;
        if (Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z)) {
            scope.verticalMph += vector.y;
            scope.vectorCount++;
        }
    }

    static final class Scope implements AutoCloseable {
        private final Scope previous;
        private double verticalMph;
        private int vectorCount;
        private Scope(Scope previous) { this.previous = previous; }
        double verticalMph() { return verticalMph; }
        int vectorCount() { return vectorCount; }
        @Override public void close() {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }
}
