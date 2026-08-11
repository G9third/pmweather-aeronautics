package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.WeatherForceApplier;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable's public pre-physics event is emitted after ServerSubLevel.prePhysicsTick, but lift
 * providers execute inside ServerSubLevel.prePhysicsTick. Resolve the shared wind frame just
 * before that loop begins so every provider can consume its allocated regional wind without
 * issuing a compatibility query.
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemWindBatchMixin {
    @Inject(
            method = "tickPipelinePhysics",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;prePhysicsTick(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
                    shift = At.Shift.BEFORE
            ),
            require = 0
    )
    private void pmweather_aeronautics$prepareWindBeforeLiftProviders(
            final ServerSubLevelContainer container,
            final CallbackInfo ci
    ) {
        WeatherForceApplier.prepareWindBeforeLiftProviders((SubLevelPhysicsSystem) (Object) this);
    }
}
