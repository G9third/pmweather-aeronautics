package com.axes.pmweather_aeronautics;

import dev.protomanly.pmweather.weather.WindEngine;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Utilities used by the Sable lift-provider mixin.
 *
 * Sable computes wing/lift-provider forces from local velocity through air. In stock Sable that
 * is just the sub-level body's local velocity. This helper subtracts PMWeather's local wind from
 * that velocity so Sable's existing lift and drag math sees headwind/tailwind/crosswind naturally.
 */
public final class WeatherAirflow {
    private static final Vector3d WORLD_SAMPLE_POS = new Vector3d();
    private static final Vector3d LOCAL_WIND = new Vector3d();

    private WeatherAirflow() {
    }

    public static void applyWindToCurrentLiftVelocity(final BlockSubLevelLiftProvider.LiftProviderContext ctx,
                                                      final ServerSubLevel subLevel) {
        if (!Config.ENABLE_AIRFLOW_LIFT.get()) {
            return;
        }

        final Pose3d pose = subLevel.logicalPose();

        // At the mixin injection point Sable's static LIFT_POS already contains the lift provider's
        // sub-level-local position, including local contraption pose if this provider is on a
        // kinematic contraption nested inside the sub-level.
        pose.transformPosition(BlockSubLevelLiftProvider.LIFT_POS, WORLD_SAMPLE_POS);
        final Vec3 samplePos = new Vec3(WORLD_SAMPLE_POS.x, WORLD_SAMPLE_POS.y, WORLD_SAMPLE_POS.z);

        final Vec3 sampledWind = WindEngine.getWind(
                samplePos,
                subLevel.getLevel(),
                false,
                !Config.ENABLE_TORNADO_SUCTION.get(),
                true
        );

        final double rawWindSpeed = sampledWind.length();
        final double threshold = Config.WIND_THRESHOLD.get();
        if (rawWindSpeed <= threshold) {
            return;
        }

        final double influence = Config.AIRFLOW_INFLUENCE.get();
        if (influence <= 0.0D) {
            return;
        }

        LOCAL_WIND.set(sampledWind.x, sampledWind.y, sampledWind.z);

        // PMWeather returns world wind. Sable's LIFT_VELO has just been transformed into sub-level
        // local coordinates, so transform the wind the same way before subtracting it.
        pose.transformNormalInverse(LOCAL_WIND);

        // Treat wind below the threshold as calm, then scale the remaining wind smoothly.
        LOCAL_WIND.mul(((rawWindSpeed - threshold) / rawWindSpeed) * influence);

        // Existing Sable semantics: positive LIFT_VELO means the wing is moving through still air.
        // Relative airflow is body velocity minus wind velocity: tailwind reduces airflow, headwind
        // increases it, crosswind contributes sideways flow over rudders/stabilizers.
        BlockSubLevelLiftProvider.LIFT_VELO.sub(LOCAL_WIND);

        if (Config.DEBUG_LOGGING.get() && subLevel.getLevel().getGameTime() % 100L == 0L) {
            PMWeatherAeronautics.LOGGER.info(
                    "PMWeather airflow for lift provider at {} on sub-level {}: wind={}, localWind={}, effectiveLiftVelocity={}",
                    ctx.pos(), subLevel.getUniqueId(), sampledWind, LOCAL_WIND, BlockSubLevelLiftProvider.LIFT_VELO
            );
        }
    }
}
