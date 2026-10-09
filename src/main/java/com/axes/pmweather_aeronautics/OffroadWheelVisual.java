package com.axes.pmweather_aeronautics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Keeps an unpowered wheel's visual spin tied to native longitudinal rolling motion. */
public final class OffroadWheelVisual {
    private static final ClassValue<MethodHandle> SPEED = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                return MethodHandles.publicLookup().unreflect(type.getMethod("getSpeed"))
                        .asType(MethodType.methodType(float.class, Object.class));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Offroad wheel speed API changed", error);
            }
        }
    };
    private OffroadWheelVisual() {}

    public static boolean unpowered(Object wheel) {
        if (!(wheel instanceof BlockEntity block) || block.getLevel() == null) return false;
        if (block.getLevel().getSignal(block.getBlockPos().above(), Direction.UP) >= 15) return true;
        try {
            float speed = (float) SPEED.get(wheel.getClass()).invokeExact(wheel);
            return Float.isFinite(speed) && Math.abs(speed) < 1.0e-4F;
        } catch (Throwable error) {
            if (error instanceof Error fatal) throw fatal;
            return false;
        }
    }
}
