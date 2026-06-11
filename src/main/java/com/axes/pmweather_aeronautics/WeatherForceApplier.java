package com.axes.pmweather_aeronautics;

import dev.protomanly.pmweather.weather.WindEngine;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class WeatherForceApplier {
    private static final Vector3d WORLD_CENTER = new Vector3d();
    private static final Vector3d LOCAL_WIND_IMPULSE = new Vector3d();
    private static final Vector3d RELATIVE_WIND = new Vector3d();
    private static final Vector3d LINEAR_VELOCITY = new Vector3d();
    private static final Vector3d TORQUE = new Vector3d();

    private WeatherForceApplier() {
    }

    public static void onSablePrePhysicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        final ServerLevel level = physicsSystem.getLevel();
        final ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }

            applyWindToSubLevel(physicsSystem, subLevel, timeStep);
        }
    }

    private static void applyWindToSubLevel(final SubLevelPhysicsSystem physicsSystem, final ServerSubLevel subLevel, final double timeStep) {
        if (!Config.ENABLE_BODY_PUSH.get()) {
            return;
        }

        final MassData massData = subLevel.getMassTracker();
        if (massData == null || massData.isInvalid() || massData.getCenterOfMass() == null) {
            return;
        }

        final Pose3d pose = subLevel.logicalPose();
        final Vector3dc centerOfMassLocal = massData.getCenterOfMass();
        pose.transformPosition(centerOfMassLocal, WORLD_CENTER);

        final Vec3 samplePos = new Vec3(WORLD_CENTER.x, WORLD_CENTER.y, WORLD_CENTER.z);

        // This follows the no-GitHub Immersive Aircraft compat's PMWeather call shape:
        // getWind(position, level, false, !enableTornadoSuction, true)
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

        final RigidBodyHandle handle = physicsSystem.getPhysicsHandle(subLevel);
        handle.getLinearVelocity(LINEAR_VELOCITY);

        RELATIVE_WIND.set(sampledWind.x, sampledWind.y, sampledWind.z).sub(LINEAR_VELOCITY);
        final double relativeSpeed = RELATIVE_WIND.length();
        if (relativeSpeed <= 1.0e-6) {
            return;
        }

        final double effectiveMass = Math.max(0.1D, Math.pow(Math.max(0.1D, massData.getMass()), Config.MASS_SCALING.get()));
        final double magnitude = (rawWindSpeed - threshold)
                * Config.WIND_INFLUENCE.get()
                * timeStep
                / effectiveMass;

        LOCAL_WIND_IMPULSE.set(RELATIVE_WIND).normalize().mul(magnitude);
        capLength(LOCAL_WIND_IMPULSE, Config.MAX_IMPULSE_PER_SUBSTEP.get());

        // Sable force totals are local to the sub-level/plot. PMWeather returns a world vector.
        pose.transformNormalInverse(LOCAL_WIND_IMPULSE);

        final QueuedForceGroup windGroup = subLevel.getOrCreateQueuedForceGroup(ForceGroups.DRAG.get());
        windGroup.applyAndRecordPointForce(centerOfMassLocal, LOCAL_WIND_IMPULSE);

        applyTurbulence(subLevel, windGroup, rawWindSpeed, threshold, timeStep);

        if (Config.DEBUG_LOGGING.get() && subLevel.getLevel().getGameTime() % 100L == 0L) {
            PMWeatherAeronautics.LOGGER.info(
                    "PMWeather wind for Sable sub-level {}: raw={}, impulseLocal={}, mass={}",
                    subLevel.getUniqueId(), rawWindSpeed, LOCAL_WIND_IMPULSE, massData.getMass()
            );
        }
    }

    private static void applyTurbulence(final ServerSubLevel subLevel, final QueuedForceGroup windGroup,
                                        final double rawWindSpeed, final double threshold, final double timeStep) {
        final double multiplier = Config.TURBULENCE_MULTIPLIER.get();
        if (multiplier <= 0.0D || rawWindSpeed <= threshold + 40.0D) {
            return;
        }

        final long seed = subLevel.getUniqueId().getMostSignificantBits()
                ^ subLevel.getUniqueId().getLeastSignificantBits()
                ^ (subLevel.getLevel().getGameTime() * 31L);

        final double severity = Math.min(1.0D, (rawWindSpeed - threshold - 40.0D) / 80.0D);
        final double strength = severity * multiplier * rawWindSpeed * timeStep;

        // Small deterministic pseudo-random torque; avoids allocating Random every substep.
        TORQUE.set(
                signedNoise(seed),
                signedNoise(seed * 1664525L + 1013904223L),
                signedNoise(seed * 1103515245L + 12345L)
        ).mul(strength);

        subLevel.logicalPose().transformNormalInverse(TORQUE);
        windGroup.getForceTotal().applyAngularImpulse(TORQUE);
    }

    private static double signedNoise(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return ((x & 0xffffL) / 32767.5D) - 1.0D;
    }

    private static void capLength(final Vector3d vector, final double maxLength) {
        if (maxLength <= 0.0D) {
            vector.zero();
            return;
        }

        final double len = vector.length();
        if (len > maxLength) {
            vector.mul(maxLength / len);
        }
    }
}
