package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.NativeTornadoCapture;
import dev.protomanly.pmweather.weather.Storm;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Observes the existing vector evaluation without changing PMWeather's return value. */
@Mixin(value = Storm.class, remap = false)
public abstract class NativeStormVectorMixin {
    @Redirect(method = "getTornadicWind(Lnet/minecraft/world/phys/Vec3;Z)F",
            at = @At(value = "INVOKE",
                    target = "Ldev/protomanly/pmweather/weather/Storm;getTornadicWindVector(Lnet/minecraft/world/phys/Vec3;Z)Lnet/minecraft/world/phys/Vec3;"),
            require = 1)
    private Vec3 pmaero$observeNativeVector(Storm storm, Vec3 point, boolean flag) {
        final Vec3 vector = storm.getTornadicWindVector(point, flag);
        NativeTornadoCapture.observe(storm, vector);
        return vector;
    }
}
