package com.axes.pmweather_aeronautics.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleWindAccessor {
    @Accessor("level") ClientLevel pmweatherAeronautics$getLevel();
    @Accessor("x") double pmweatherAeronautics$getX();
    @Accessor("y") double pmweatherAeronautics$getY();
    @Accessor("z") double pmweatherAeronautics$getZ();
    @Accessor("xd") double pmweatherAeronautics$getXd();
    @Accessor("yd") double pmweatherAeronautics$getYd();
    @Accessor("zd") double pmweatherAeronautics$getZd();
    @Accessor("xd") void pmweatherAeronautics$setXd(double value);
    @Accessor("yd") void pmweatherAeronautics$setYd(double value);
    @Accessor("zd") void pmweatherAeronautics$setZd(double value);
    @Accessor("onGround") boolean pmweatherAeronautics$isOnGround();
}
