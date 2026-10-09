package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.OffroadWheelVisual;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reuses Offroad's per-wheel longitudinal rolling angle. Does not touch wheel forces,
 * friction, throttle, radius, steering or the powered-wheel visual calculation.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
public abstract class OffroadWheelVisualMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;lerp(DDD)D", ordinal = 4), require = 0)
    private double pmaero$coastingWheelAngle(double friction, double drivenAngle, double rollingAngle) {
        return OffroadWheelVisual.unpowered(this) ? rollingAngle : Mth.lerp(friction, drivenAngle, rollingAngle);
    }
}
