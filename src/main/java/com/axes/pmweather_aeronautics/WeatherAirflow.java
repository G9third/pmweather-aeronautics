package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class WeatherAirflow {
    private static final Vector3d WORLD_SAMPLE = new Vector3d();
    private static final Vector3d LOCAL_WIND = new Vector3d();

    private WeatherAirflow() {
    }

    /**
     * Called from the BlockSubLevelLiftProvider mixin immediately after Sable converts the local block
     * velocity into sub-level-local space. Sable's LIFT_VELO is mutable shared scratch state, so this
     * method must stay tiny and allocation-light.
     */
    public static void applyWindToCurrentLiftVelocity(final BlockSubLevelLiftProvider.LiftProviderContext ctx,
                                                       final ServerSubLevel subLevel) {
        if (!Config.enableAirflowLift()) {
            return;
        }

        final Pose3d pose = subLevel.logicalPose();
        WORLD_SAMPLE.set(ctx.pos().getX() + 0.5D, ctx.pos().getY() + 0.5D, ctx.pos().getZ() + 0.5D);
        pose.transformPosition(WORLD_SAMPLE);

        final Vec3 samplePos = new Vec3(WORLD_SAMPLE.x, WORLD_SAMPLE.y, WORLD_SAMPLE.z);
        final Vec3 sampledWind = WeatherWindSampler.sampleLocalAirflowWindCached(subLevel, samplePos);

        if (sampledWind.length() <= Config.windThreshold()) {
            return;
        }

        LOCAL_WIND.set(sampledWind.x, sampledWind.y, sampledWind.z).mul(Config.airflowInfluence());
        pose.transformNormalInverse(LOCAL_WIND);

        // Sable's lift math uses local air-relative velocity. Subtracting wind makes lift providers
        // behave as if the surrounding air is moving with PMWeather.
        BlockSubLevelLiftProvider.LIFT_VELO.sub(LOCAL_WIND);
    }
}
