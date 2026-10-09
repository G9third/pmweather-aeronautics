package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.Config;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optionally disables PMWeather's duplicate Sable-only wind/inertia force callback. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.compat.sable.SableHandler", remap = false)
public abstract class SableBuiltinWindMixin {
    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void pmaero$suppressBuiltinSableWind(final ServerLevel level, final CallbackInfo ci) {
        if (Config.suppressBuiltinSableWind()) {
            ci.cancel();
        }
    }
}
