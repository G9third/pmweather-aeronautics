package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.ParticleWindClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineWindMixin {
    @Inject(method = "tickParticle", at = @At("HEAD"))
    private void pmweatherAeronautics$applyParticleWind(Particle particle, CallbackInfo callback) {
        ParticleWindClient.applyToMinecraftParticle(particle);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void pmweatherAeronautics$clearParticleWindCache(ClientLevel level, CallbackInfo callback) {
        ParticleWindClient.onParticleEngineLevelChanged(level);
    }
}
