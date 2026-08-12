package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class WeatherAirflow {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private WeatherAirflow() {
    }

    /**
     * Returns the rigid body's linear velocity relative to PMWeather airflow at this lift provider.
     *
     * This is called from Sable's concrete ServerSubLevel lift-provider invocation. Supplying
     * world-space {@code linearVelocity - windVelocity} here is equivalent to subtracting the
     * transformed wind from Sable's local LIFT_VELO scratch vector, but it avoids injecting into
     * BlockSubLevelLiftProvider's default interface method. That interface method may be replaced by
     * Create Aeronautics Lift Patch, which made the former interface injection incompatible.
     */
    public static Vector3dc airRelativeLinearVelocity(
            final BlockSubLevelLiftProvider provider,
            final BlockSubLevelLiftProvider.LiftProviderContext ctx,
            final ServerSubLevel subLevel,
            @Nullable final Pose3d localPose,
            final Vector3dc linearVelocity
    ) {
        if (!Config.enableAirflowLift()) {
            return linearVelocity;
        }

        final Scratch scratch = SCRATCH.get();
        scratch.worldSample.set(
                ctx.pos().getX() + 0.5D,
                ctx.pos().getY() + 0.5D,
                ctx.pos().getZ() + 0.5D
        );

        // Match Sable's own lift-provider position transforms. Providers inside a kinematic
        // contraption must first be transformed into sub-level space before the sub-level pose is
        // transformed into world space.
        if (localPose != null) {
            localPose.transformPosition(scratch.worldSample);
        }
        subLevel.logicalPose().transformPosition(scratch.worldSample);

        final Vec3 samplePos = new Vec3(
                scratch.worldSample.x,
                scratch.worldSample.y,
                scratch.worldSample.z
        );
        Vec3 sampledWind = PhysicsTickWindBatch.windForProvider(provider, ctx, subLevel, localPose, samplePos);
        if (sampledWind == null) {
            PhysicsTickWindBatch.ensureResolved(subLevel);
            sampledWind = PhysicsTickWindBatch.windForProvider(provider, ctx, subLevel, localPose, samplePos);
        }
        if (sampledWind == null) {
            // If this provider was part of the current frame but the hard budget could not assign its
            // component a fresh probe, do not bypass the budget with a surprise per-provider query.
            // A zero wind fallback matches the existing hard-budget behavior for uncached fresh
            // samples. Only genuinely uncollected third-party invocation paths use the compatibility
            // single-position sampler.
            sampledWind = PhysicsTickWindBatch.wasCollectedProvider(provider, ctx, subLevel, localPose, samplePos)
                    ? Vec3.ZERO
                    : WeatherWindField.sampleLocalAirflowWindCached(subLevel, samplePos);
        }

        // Cached airflow wind remains in PMWeather/mph-style units so thresholds stay readable.
        if (sampledWind.length() <= Config.windThreshold()) {
            return linearVelocity;
        }

        final Vec3 physicsWind = WeatherWindField.pmweatherWindToPhysicsWind(sampledWind);
        final double influence = Config.airflowInfluence();

        // Sable receives a world-space rigid-body velocity. It adds the provider's angular point
        // velocity and transforms the result into local space inside whichever lift implementation
        // is active (stock Sable or Create Aeronautics Lift Patch).
        return scratch.airRelativeLinearVelocity
                .set(linearVelocity)
                .sub(
                        physicsWind.x * influence,
                        physicsWind.y * influence,
                        physicsWind.z * influence
                );
    }

    private static final class Scratch {
        private final Vector3d worldSample = new Vector3d();
        private final Vector3d airRelativeLinearVelocity = new Vector3d();
    }
}
