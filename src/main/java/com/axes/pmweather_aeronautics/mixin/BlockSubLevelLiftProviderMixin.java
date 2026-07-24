package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.WeatherAirflow;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Adds PMWeather airflow at Sable's concrete lift-provider call site rather than injecting into
 * BlockSubLevelLiftProvider's default interface method.
 *
 * The Create Aeronautics Lift Patch replaces that interface default method with its own mixin.
 * Injecting into the replaced interface method causes Mixin's InvalidInterfaceMixinException.
 * Redirecting the ServerSubLevel invocation remains compatible with either Sable's original
 * implementation or the lift patch's replacement implementation.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class BlockSubLevelLiftProviderMixin {
    @Redirect(
            method = "prePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider;sable$contributeLiftAndDrag(Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider$LiftProviderContext;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/companion/math/Pose3d;DLorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;Lorg/joml/Vector3d;Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider$LiftProviderGroup;)V"
            ),
            require = 0
    )
    private void pmweather_aeronautics$applyWindBeforeLiftAndDrag(
            final BlockSubLevelLiftProvider provider,
            final BlockSubLevelLiftProvider.LiftProviderContext ctx,
            final ServerSubLevel subLevel,
            @Nullable final Pose3d localPose,
            final double timeStep,
            final Vector3dc linearVelocity,
            final Vector3dc angularVelocity,
            final Vector3d linearImpulse,
            final Vector3d angularImpulse,
            @Nullable final BlockSubLevelLiftProvider.LiftProviderGroup group
    ) {
        final Vector3dc airRelativeLinearVelocity = WeatherAirflow.airRelativeLinearVelocity(
                ctx,
                subLevel,
                localPose,
                linearVelocity
        );

        provider.sable$contributeLiftAndDrag(
                ctx,
                subLevel,
                localPose,
                timeStep,
                airRelativeLinearVelocity,
                angularVelocity,
                linearImpulse,
                angularImpulse,
                group
        );
    }
}
