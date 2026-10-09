package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.ForceDiagnostics;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RigidBodyHandle.class, remap = false)
public abstract class RigidBodyDiagnosticsMixin {
    @Shadow @Final private PhysicsPipelineBody body;

    @Inject(method = "applyLinearAndAngularImpulse(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Z)V", at = @At("HEAD"), require = 0)
    private void pmaero$impulse(Vector3dc linear, Vector3dc angular, boolean wake, CallbackInfo ci) {
        if (ForceDiagnostics.enabled()) ForceDiagnostics.submitted(body, "local_impulse", linear, angular, null, wake);
    }

    @Inject(method = "addLinearAndAngularVelocity", at = @At("HEAD"), require = 0)
    private void pmaero$velocity(Vector3dc linear, Vector3dc angular, CallbackInfo ci) {
        if (ForceDiagnostics.enabled()) ForceDiagnostics.submitted(body, "world_velocity_add", linear, angular, null, false);
    }

    @Inject(method = "applyImpulseAtPoint(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)V", at = @At("HEAD"), require = 0)
    private void pmaero$point(Vector3dc point, Vector3dc impulse, CallbackInfo ci) {
        if (ForceDiagnostics.enabled()) ForceDiagnostics.submitted(body, "local_point_impulse", impulse, null, point, true);
    }

    @Inject(method = "applyImpulseAtPoint(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), require = 0)
    private void pmaero$pointMojang(Vec3 point, Vec3 impulse, CallbackInfo ci) {
        if (ForceDiagnostics.enabled()) ForceDiagnostics.submitted(body, "local_point_impulse", new Vector3d(impulse.x, impulse.y, impulse.z), null, new Vector3d(point.x, point.y, point.z), true);
    }
}
