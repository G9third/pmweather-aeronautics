package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.WeatherAirflow;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockSubLevelLiftProvider.class, remap = false)
public interface BlockSubLevelLiftProviderMixin {
    @Inject(
            method = "sable$contributeLiftAndDrag",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/companion/math/Pose3d;transformNormalInverse(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void pmweather_aeronautics$subtractWindFromLiftVelocity(
            final BlockSubLevelLiftProvider.LiftProviderContext ctx,
            final ServerSubLevel subLevel,
            @NotNull final Pose3d localPose,
            final double timeStep,
            final Vector3dc linearVelocity,
            final Vector3dc angularVelocity,
            final Vector3d linearImpulse,
            final Vector3d angularImpulse,
            @Nullable final BlockSubLevelLiftProvider.LiftProviderGroup group,
            final CallbackInfo ci
    ) {
        WeatherAirflow.applyWindToCurrentLiftVelocity(ctx, subLevel);
    }
}
