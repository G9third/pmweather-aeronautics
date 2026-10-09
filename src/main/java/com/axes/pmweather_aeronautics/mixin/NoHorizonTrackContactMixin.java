package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.NoHorizonTrackContactAdapter;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Optional exact-callsite adapter for the virtual-track path in NoHorizon 1.0.3. */
@Pseudo
@Mixin(targets = "com.github.aero_no_horizon.trackwork.content.track.SableTrackPhysicsHelper", remap = false)
public abstract class NoHorizonTrackContactMixin {
    @Inject(method = "accumulateTrackForces", at = @At("HEAD"), require = 0)
    private static void pmaero$begin(Level level,
                                     ServerSubLevel body,
                                     RigidBodyHandle handle,
                                     ForceTotal forceTotal,
                                     BlockPos position,
                                     Direction.Axis driveAxis,
                                     double horizontalOffset,
                                     double axialOffset,
                                     double rideHeightOffset,
                                     double wheelRadius,
                                     double suspensionTravel,
                                     double suspensionStiffness,
                                     double tractionScale,
                                     double drivenRpm,
                                     boolean freeSpin,
                                     double steeringValue,
                                     @Coerce Object controllerType,
                                     int networkSize,
                                     double allocatedSprungMass,
                                     double timeStep,
                                     CallbackInfo ci) {
        NoHorizonTrackContactAdapter.begin(body, position, tractionScale);
    }

    @ModifyArgs(method = "accumulateTrackForces", at = @At(value = "INVOKE",
            target = "Lcom/github/aero_no_horizon/trackwork/content/track/SableTrackPhysicsHelper;queuePointImpulse(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/force/ForceGroup;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V",
            ordinal = 0), require = 0)
    private static void pmaero$captureSupport(Args args) {
        NoHorizonTrackContactAdapter.supportQueued(
                args.get(0),
                args.get(2),
                args.get(3)
        );
    }

    @ModifyArgs(method = "accumulateTrackForces", at = @At(value = "INVOKE",
            target = "Lcom/github/aero_no_horizon/trackwork/content/track/SableTrackPhysicsHelper;queuePointImpulse(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/force/ForceGroup;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V",
            ordinal = 1), require = 0)
    private static void pmaero$captureTraction(Args args) {
        NoHorizonTrackContactAdapter.tractionQueued(
                args.get(0),
                args.get(2),
                args.get(3)
        );
    }

    @Inject(method = "accumulateTrackForces", at = @At("RETURN"), require = 0)
    private static void pmaero$end(Level level,
                                   ServerSubLevel body,
                                   RigidBodyHandle handle,
                                   ForceTotal forceTotal,
                                   BlockPos position,
                                   Direction.Axis driveAxis,
                                   double horizontalOffset,
                                   double axialOffset,
                                   double rideHeightOffset,
                                   double wheelRadius,
                                   double suspensionTravel,
                                   double suspensionStiffness,
                                   double tractionScale,
                                   double drivenRpm,
                                   boolean freeSpin,
                                   double steeringValue,
                                   @Coerce Object controllerType,
                                   int networkSize,
                                   double allocatedSprungMass,
                                   double timeStep,
                                   CallbackInfo ci) {
        NoHorizonTrackContactAdapter.end();
    }
}
