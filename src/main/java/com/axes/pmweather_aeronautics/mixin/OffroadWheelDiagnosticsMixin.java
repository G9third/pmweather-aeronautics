package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.ForceDiagnostics;
import com.axes.pmweather_aeronautics.OffroadWheelContactAdapter;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Offroad compile/runtime dependency; absent targets are skipped by @Pseudo. */
@Pseudo
@Mixin(targets = {
        "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity",
        "dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity"
}, remap = false)
public abstract class OffroadWheelDiagnosticsMixin {
    @Shadow @Final private Vector3d queuedForcePos;
    @Shadow @Final private Vector3d queuedForce;
    @Shadow private double touchingFriction;

    @Unique private boolean pmaero$submitted;
    @Unique private OffroadWheelContactAdapter.Snapshot pmaero$contactSnapshot;

    @Inject(method = "sable$physicsTick", at = @At("HEAD"), require = 0)
    private void pmaero$begin(ServerSubLevel body, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        pmaero$submitted = false;
        pmaero$contactSnapshot = null;
        OffroadWheelContactAdapter.begin();
    }

    @Inject(method = "sable$physicsTick", at = @At(value = "INVOKE",
            target = "Lorg/joml/Vector3d;fma(DLorg/joml/Vector3dc;)Lorg/joml/Vector3d;", ordinal = 0), require = 0)
    private void pmaero$snapshotContact(ServerSubLevel body, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        pmaero$contactSnapshot = OffroadWheelContactAdapter.snapshot(
                body,
                queuedForcePos,
                queuedForce,
                touchingFriction
        );
    }

    @Inject(method = "sable$physicsTick", at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/physics/force/ForceTotal;applyImpulseAtPoint(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V"), require = 0)
    private void pmaero$force(ServerSubLevel body, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        pmaero$submitted = true;
        OffroadWheelContactAdapter.submitted(this, body, pmaero$contactSnapshot, queuedForce);
        if (ForceDiagnostics.enabled()) ForceDiagnostics.wheel(this, body, dt, true);
    }

    @Inject(method = "sable$physicsTick", at = @At("RETURN"), require = 0)
    private void pmaero$end(ServerSubLevel body, RigidBodyHandle handle, double dt, CallbackInfo ci) {
        if (ForceDiagnostics.enabled() && !pmaero$submitted) ForceDiagnostics.wheel(this, body, dt, false);
        OffroadWheelContactAdapter.end();
    }

    @Inject(method = "computeMaxExtensionToTerrain", at = @At("RETURN"), require = 0)
    private void pmaero$terrain(Vector3dc direction, Pose3dc pose, CallbackInfoReturnable<?> ci) {
        if (ForceDiagnostics.enabled()) ForceDiagnostics.wheelTerrain(this, ci.getReturnValue());
    }
}
