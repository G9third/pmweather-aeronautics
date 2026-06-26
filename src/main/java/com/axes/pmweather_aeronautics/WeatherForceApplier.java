package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class WeatherForceApplier {
    private static final Vector3d WORLD_CENTER = new Vector3d();
    private static final Vector3d WORLD_APPLICATION_POINT = new Vector3d();
    private static final Vector3d LOCAL_APPLICATION_POINT = new Vector3d();
    private static final Vector3d LOCAL_WIND_IMPULSE = new Vector3d();
    private static final Vector3d CENTER_RELATIVE_WIND = new Vector3d();
    private static final Vector3d SURFACE_WIND = new Vector3d();
    private static final Vector3d PROFILE_SURFACE_WIND = new Vector3d();
    private static final Vector3d NORMAL = new Vector3d();
    private static final Vector3d NORMAL_COMPONENT = new Vector3d();
    private static final Vector3d LINEAR_VELOCITY = new Vector3d();
    private static final Vector3d ANGULAR_VELOCITY = new Vector3d();
    private static final Vector3d LAST_NET_AERO_FORCE = new Vector3d();
    private static final Vector3d LAST_NET_AERO_TORQUE = new Vector3d();
    private static int lastWindwardSamples;
    private static int lastPressureGroups;
    private static final double BODY_PUSH_NORMALIZATION = 100.0D;
    private static final ForceGroup WEATHER_FORCE_GROUP = new ForceGroup(
            Component.literal("PMWeather Wind"),
            Component.literal("Raw wind sampled from ProtoManly's Weather"),
            0x5fa8ff,
            true
    );

    private static final Map<String, TornadoLiftState> TORNADO_LIFT_STATES = new HashMap<>();
    private static long lastLiftStatePruneTick = Long.MIN_VALUE;

    private WeatherForceApplier() {
    }

    public static void onSablePrePhysicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        final ServerLevel level = physicsSystem.getLevel();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
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

    private static void applyWindToSubLevel(final SubLevelPhysicsSystem physicsSystem, final ServerSubLevel subLevel,
                                            final double timeStep) {
        if (!Config.enableBodyPush()) {
            return;
        }

        final MassData massData = subLevel.getMassTracker();
        if (massData == null || massData.isInvalid() || massData.getCenterOfMass() == null) {
            return;
        }

        final Pose3d pose = subLevel.logicalPose();
        final Vector3dc centerOfMassLocal = massData.getCenterOfMass();
        pose.transformPosition(centerOfMassLocal, WORLD_CENTER);

        final Vec3 centerPosition = new Vec3(WORLD_CENTER.x, WORLD_CENTER.y, WORLD_CENTER.z);
        final List<WeatherWindSampler.WindSample> samples = WeatherWindSampler.sampleWindPointsCached(subLevel, centerPosition);
        if (samples.isEmpty()) {
            return;
        }
        pruneTornadoLiftStates(subLevel.getLevel().getGameTime());

        final RigidBodyHandle handle = physicsSystem.getPhysicsHandle(subLevel);
        handle.getLinearVelocity(LINEAR_VELOCITY);
        handle.getAngularVelocity(ANGULAR_VELOCITY);
        LAST_NET_AERO_FORCE.zero();
        LAST_NET_AERO_TORQUE.zero();
        lastWindwardSamples = 0;
        lastPressureGroups = 0;

        final double threshold = Config.windThreshold();
        final double massDamping = Math.max(1.0D, Math.pow(
                Math.max(1.0D, massData.getMass()),
                Config.massScaling() * 0.25D
        ));

        final QueuedForceGroup windGroup = subLevel.getOrCreateQueuedForceGroup(WEATHER_FORCE_GROUP);

        // 0.6.0: aerodynamic profile pressure is the main wind force source.
        // Multiple exterior profile samples are converted through Sable ForceTotal; the center/COM sample is only fallback.
        final int appliedProfileSamples = applyAerodynamicProfilePressure(
                subLevel, pose, windGroup, samples, threshold, timeStep, massDamping, massData, centerOfMassLocal
        );

        int appliedCenter = 0;
        double centerSpeed = strongestSampleSpeed(subLevel, samples);
        if (appliedProfileSamples <= 0) {
            final Vec3 centerWind = applyRealisticTornadoWind(subLevel, centerPosition, samples.get(0).wind());
            setBodyPressureWind(centerWind, CENTER_RELATIVE_WIND);
            centerSpeed = CENTER_RELATIVE_WIND.length();
            if (centerSpeed > threshold) {
                final double magnitude = Math.max(0.0D, centerSpeed - threshold)
                        * Config.windInfluence()
                        * BODY_PUSH_NORMALIZATION
                        * timeStep
                        / massDamping;

                LOCAL_WIND_IMPULSE.set(CENTER_RELATIVE_WIND).normalize().mul(magnitude);
                capLength(LOCAL_WIND_IMPULSE, Config.maxImpulsePerSubstep());
                capImpulseByAirRelativeVelocity(LOCAL_WIND_IMPULSE, centerSpeed, massData.getMass());
                pose.transformNormalInverse(LOCAL_WIND_IMPULSE);
                pose.transformPositionInverse(WORLD_CENTER, LOCAL_APPLICATION_POINT);
                windGroup.applyAndRecordPointForce(new Vector3d(LOCAL_APPLICATION_POINT), new Vector3d(LOCAL_WIND_IMPULSE));
                appliedCenter = 1;
            }
        }

        if (WindDebugFile.isEnabled()) {
            WindDebugFile.recordObject(
                    subLevel.getLevel().getGameTime(),
                    String.valueOf(subLevel.getUniqueId()),
                    timeStep,
                    massData.getMass(),
                    centerOfMassLocal,
                    WORLD_CENTER,
                    LINEAR_VELOCITY,
                    ANGULAR_VELOCITY,
                    samples.size(),
                    appliedProfileSamples,
                    appliedCenter,
                    centerSpeed,
                    LAST_NET_AERO_FORCE,
                    LAST_NET_AERO_TORQUE,
                    lastWindwardSamples,
                    lastPressureGroups,
                    WeatherWindSampler.sampleStatsSnapshot()
            );
        }

        if (Config.debugLogging() && subLevel.getLevel().getGameTime() % 100L == 0L) {
            PMWeatherAeronautics.LOGGER.info(
                    "PMWeather aerodynamic wind for Sable sub-level {}: centerApplied={}, profileApplied={}, strongestProfileSpeed={}, mass={}, damping={}, profileStrength={}, patchPerObjectMax={}, centerOfPressureSideOnly=true, relativeBodyDrag={}",
                    subLevel.getUniqueId(), appliedCenter, appliedProfileSamples,
                    centerSpeed, massData.getMass(), massDamping,
                    Config.aeroPatchPressureStrength(), Config.maxAeroPatchSamplesPerObject(), Config.enableBodyRelativeWindDrag()
            );
        }
    }


    private static double strongestSampleSpeed(final ServerSubLevel subLevel,
                                               final List<WeatherWindSampler.WindSample> samples) {
        double strongest = 0.0D;
        for (final WeatherWindSampler.WindSample sample : samples) {
            final Vec3 wind = applyRealisticTornadoWind(subLevel, sample.samplePosition(), sample.wind());
            strongest = Math.max(strongest, wind.length());
        }
        return strongest;
    }

    private static Vec3 computeSampleFinalWind(final ServerSubLevel subLevel,
                                               final WeatherWindSampler.WindSample sample) {
        return applyRealisticTornadoWind(subLevel, sample.samplePosition(), sample.wind());
    }

    private static void computeSampleRelativeWind(final ServerSubLevel subLevel,
                                                  final WeatherWindSampler.WindSample sample,
                                                  final Vector3d result) {
        final Vec3 wind = computeSampleFinalWind(subLevel, sample);
        setBodyPressureWind(wind, result);
    }

    private static void setBodyPressureWind(final Vec3 wind, final Vector3d result) {
        result.set(wind.x, wind.y, wind.z);
        if (Config.enableBodyRelativeWindDrag()) {
            result.sub(LINEAR_VELOCITY);
        }
    }


    /**
     * Applies multi-point windward pressure from the cached exterior aerodynamic profile.
     *
     * 0.6.0 fix: avoid artificial Dzhanibekov-style spin from sparse sample point torque and uniform-pressure center bias.
     * Each major exterior side is reduced to one center-of-pressure impulse. Uniform wind on a
     * side therefore pushes through that side's pressure center instead of creating a rotating
     * couple from arbitrary selected sample locations.
     *
     * This is not small-object dampening: it does not scale torque down by object size or add
     * drag. It removes the artificial residual point-torque path that could inject angular
     * momentum into symmetric/small objects. Real torque can still come from different forces on
     * different side centers or from off-center pressure on irregular shapes.
     */
    private static int applyAerodynamicProfilePressure(final ServerSubLevel subLevel,
                                                       final Pose3d pose,
                                                       final QueuedForceGroup windGroup,
                                                       final List<WeatherWindSampler.WindSample> samples,
                                                       final double threshold,
                                                       final double timeStep, final double massDamping,
                                                       final MassData massData,
                                                       final Vector3dc centerOfMassLocal) {
        final double profileStrength = Config.aeroPatchPressureStrength();
        if (profileStrength <= 0.0D || samples.size() <= 1) {
            return 0;
        }

        int windwardSamples = 0;
        final double profileThreshold = Math.max(0.0D, threshold * 0.20D);
        for (int i = 1; i < samples.size(); i++) {
            final WeatherWindSampler.WindSample sample = samples.get(i);
            if (sample.areaWeight() <= 0.0D) {
                continue;
            }
            computeSampleRelativeWind(subLevel, sample, SURFACE_WIND);
            computeWindwardSurfacePressure(sample, SURFACE_WIND, PROFILE_SURFACE_WIND);
            if (PROFILE_SURFACE_WIND.length() > profileThreshold) {
                windwardSamples++;
            }
        }
        lastWindwardSamples = windwardSamples;
        if (windwardSamples <= 0) {
            return 0;
        }

        final ProfilePressureGroup[] groups = new ProfilePressureGroup[8];
        final double perProfileCap = Config.maxImpulsePerSubstep() * profileStrength / windwardSamples;
        double maxAppliedAirRelativeSpeed = 0.0D;
        for (int i = 1; i < samples.size(); i++) {
            final WeatherWindSampler.WindSample sample = samples.get(i);
            if (sample.areaWeight() <= 0.0D) {
                continue;
            }
            final Vec3 finalWind = computeSampleFinalWind(subLevel, sample);
            setBodyPressureWind(finalWind, SURFACE_WIND);
            computeWindwardSurfacePressure(sample, SURFACE_WIND, PROFILE_SURFACE_WIND);
            final double surfaceSpeed = PROFILE_SURFACE_WIND.length();
            if (surfaceSpeed <= profileThreshold) {
                continue;
            }
            maxAppliedAirRelativeSpeed = Math.max(maxAppliedAirRelativeSpeed, surfaceSpeed);

            final double shareWeight = Math.max(0.05D, sample.areaWeight());
            final double magnitude = Math.max(0.0D, surfaceSpeed - profileThreshold)
                    * Config.windInfluence()
                    * profileStrength
                    * shareWeight
                    * BODY_PUSH_NORMALIZATION
                    * timeStep
                    / massDamping
                    / windwardSamples;

            LOCAL_WIND_IMPULSE.set(PROFILE_SURFACE_WIND).normalize().mul(magnitude);
            capLength(LOCAL_WIND_IMPULSE, perProfileCap);
            if (LOCAL_WIND_IMPULSE.lengthSquared() <= 1.0e-10D) {
                continue;
            }

            WORLD_APPLICATION_POINT.set(
                    sample.applicationPosition().x,
                    sample.applicationPosition().y,
                    sample.applicationPosition().z
            );
            pose.transformPositionInverse(WORLD_APPLICATION_POINT, LOCAL_APPLICATION_POINT);
            final Vector3d localApplicationPoint = new Vector3d(LOCAL_APPLICATION_POINT);

            final Vec3 pressureCenter = sample.pressureCenterPosition() == null
                    ? sample.applicationPosition()
                    : sample.pressureCenterPosition();
            WORLD_APPLICATION_POINT.set(pressureCenter.x, pressureCenter.y, pressureCenter.z);
            pose.transformPositionInverse(WORLD_APPLICATION_POINT, LOCAL_APPLICATION_POINT);
            final Vector3d localPressureCenter = new Vector3d(LOCAL_APPLICATION_POINT);

            pose.transformNormalInverse(LOCAL_WIND_IMPULSE);
            final Vector3d localImpulse = new Vector3d(LOCAL_WIND_IMPULSE);

            if (WindDebugFile.isEnabled()) {
                WindDebugFile.recordSample(
                        subLevel.getLevel().getGameTime(),
                        String.valueOf(subLevel.getUniqueId()),
                        i,
                        sample,
                        finalWind,
                        SURFACE_WIND,
                        PROFILE_SURFACE_WIND,
                        profileThreshold,
                        surfaceSpeed,
                        shareWeight,
                        magnitude,
                        localApplicationPoint,
                        localPressureCenter,
                        localImpulse
                );
            }

            final int groupIndex = pressureGroupIndex(sample.surfaceRole());
            ProfilePressureGroup group = groups[groupIndex];
            if (group == null) {
                group = new ProfilePressureGroup(sample.surfaceRole());
                groups[groupIndex] = group;
            }
            group.add(localApplicationPoint, localPressureCenter, localImpulse, shareWeight);
        }

        int applied = 0;
        final ForceTotal netAeroForce = new ForceTotal();
        final long currentTick = subLevel.getLevel().getGameTime();
        final String subLevelId = String.valueOf(subLevel.getUniqueId());
        for (final ProfilePressureGroup group : groups) {
            if (group != null) {
                applied += group.applyTo(subLevelId, currentTick, massData, netAeroForce);
            }
        }
        final ForceTotal cappedAeroForce = capForceTotalByAirRelativeVelocity(netAeroForce, maxAppliedAirRelativeSpeed, massData.getMass());
        LAST_NET_AERO_FORCE.set(cappedAeroForce.getLocalForce());
        LAST_NET_AERO_TORQUE.set(cappedAeroForce.getLocalTorque());

        if (applied > 0) {
            // Submit one clean net external force/torque to Sable's ForceTotal.
            // PMWeather Aeronautics calculates wind pressure; Sable/Rapier handles translation,
            // rotation, mass, inertia, collisions, and integration.
            windGroup.getForceTotal().applyForceTotal(cappedAeroForce);
            if (subLevel.isTrackingIndividualQueuedForces() && cappedAeroForce.getLocalForce().lengthSquared() > 1.0e-6D) {
                windGroup.recordPointForce(new Vector3d(centerOfMassLocal), new Vector3d(cappedAeroForce.getLocalForce()));
            }
        }

        return applied;
    }

    private static int pressureGroupIndex(final int role) {
        return Math.max(0, Math.min(7, role));
    }

    private static final class ProfilePressureEntry implements SableAeroSolver.PressureEntry {
        final Vector3d applicationPoint;
        final Vector3d impulse;
        final double shareWeight;

        ProfilePressureEntry(final Vector3d applicationPoint, final Vector3d impulse, final double shareWeight) {
            this.applicationPoint = applicationPoint;
            this.impulse = impulse;
            this.shareWeight = shareWeight;
        }

        @Override
        public Vector3dc applicationPoint() {
            return this.applicationPoint;
        }

        @Override
        public Vector3dc impulse() {
            return this.impulse;
        }
    }

    private static final class ProfilePressureGroup {
        private final int role;
        private final java.util.ArrayList<ProfilePressureEntry> entries = new java.util.ArrayList<>();
        private final Vector3d weightedPressureCenter = new Vector3d();
        private final Vector3d totalImpulse = new Vector3d();
        private double totalShareWeight;

        ProfilePressureGroup(final int role) {
            this.role = role;
        }

        void add(final Vector3d applicationPoint, final Vector3d pressureCenter,
                 final Vector3d impulse, final double shareWeight) {
            final double safeWeight = Math.max(0.0D, shareWeight);
            this.entries.add(new ProfilePressureEntry(applicationPoint, impulse, safeWeight));
            this.weightedPressureCenter.fma(safeWeight, pressureCenter);
            this.totalImpulse.add(impulse);
            this.totalShareWeight += safeWeight;
        }

        int applyTo(final String subLevelId, final long tick, final MassData massData, final ForceTotal forceTotal) {
            if (this.entries.isEmpty() || this.totalImpulse.lengthSquared() <= 1.0e-12D) {
                return 0;
            }

            final Vector3d center = new Vector3d(this.weightedPressureCenter);
            if (this.totalShareWeight > 1.0e-12D) {
                center.div(this.totalShareWeight);
            } else {
                center.set(this.entries.get(0).applicationPoint);
            }

            final Vector3d pressureLineCenter = SableAeroSolver.pressureLineCenter(this.role, center, massData.getCenterOfMass());
            final Vector3d localTorque = new Vector3d(pressureLineCenter).sub(massData.getCenterOfMass()).cross(this.totalImpulse, new Vector3d());
            final Vector3d differentialTorque = SableAeroSolver.computeDifferentialPressureTorque(
                    massData,
                    this.entries,
                    pressureLineCenter,
                    this.totalImpulse
            );
            final Vector3d totalLocalTorque = new Vector3d(localTorque).add(differentialTorque);

            if (WindDebugFile.isEnabled()) {
                WindDebugFile.recordGroup(
                        tick,
                        subLevelId,
                        this.role,
                        this.entries.size(),
                        massData.getCenterOfMass(),
                        pressureLineCenter,
                        this.totalImpulse,
                        totalLocalTorque
                );
            }

            // Apply this side as ONE uniform-pressure line of action. The sampled side pressure
            // chooses the face-normal component and magnitude, but the uniform component is aligned
            // through Sable's actual center of mass on the two tangential axes. This is not damping:
            // it prevents a single cube or compact symmetric body from receiving a constant fake
            // rotational couple just because the selected aero patch centers are slightly offset from
            // Sable's MassData center of mass. 0.7 then adds a capped differential-pressure residual
            // from actual uneven patch pressure so airborne structures can tumble/yaw naturally.
            forceTotal.applyImpulseAtPoint(massData, pressureLineCenter, this.totalImpulse);
            if (differentialTorque.lengthSquared() > 1.0e-12D) {
                forceTotal.applyLinearAndAngularImpulse(new Vector3d(), differentialTorque);
            }
            lastPressureGroups++;

            return this.entries.size();
        }
    }

    private static Vector3d pressureLineCenter(final int role, final Vector3dc profileCenter, final Vector3dc centerOfMass) {
        return SableAeroSolver.pressureLineCenter(role, profileCenter, centerOfMass);
    }



    /**
     * PMWeather 0.16's normal supercell/tornado WindEngine vector is mostly horizontal.
     * This optional layer keeps PMWeather as the source for tornado direction/strength, then
     * adds a bounded Sable-specific updraft and smooth coherent gust field.
     *
     * The updraft is intentionally not infinite: when a structure first enters tornado-strength
     * wind, it gets a stable per-object lift ceiling. Updraft fades out near that ceiling so
     * structures can be lifted near the bottom of the tornado without climbing forever. The
     * ceiling has deterministic per-object variation, so multiple objects do not orbit at the
     * exact same altitude.
     */
    private static Vec3 applyRealisticTornadoWind(final ServerSubLevel subLevel, final Vec3 position, final Vec3 rawWind) {
        if (!Config.enableTornadoUpdraftModel()) {
            return rawWind;
        }

        final double horizontalSpeed = Math.sqrt(rawWind.x * rawWind.x + rawWind.z * rawWind.z);
        final double threshold = Config.tornadoUpdraftThreshold();
        if (horizontalSpeed <= threshold) {
            expireLiftStateIfOld(subLevel);
            return rawWind;
        }

        final double excess = horizontalSpeed - threshold;
        final double activation = Math.max(0.0D, Math.min(1.0D, excess / Math.max(1.0D, threshold)));
        final TornadoLiftState state = getOrCreateLiftState(subLevel, position.y, activation);
        state.lastActiveTick = subLevel.getLevel().getGameTime();

        final double altitude = Math.max(0.0D, position.y - state.baseY);
        final double heightFade = updraftHeightFade(altitude, state.liftHeight);
        final double rawUpdraft = Math.min(Config.maxTornadoUpdraft(), excess * Config.tornadoUpdraftStrength());
        final double updraft = rawUpdraft * heightFade;

        final double timeScale = Math.max(1.0D, Config.tornadoGustScaleTicks());
        final double spatialScale = Math.max(1.0D, Config.tornadoGustSpatialScale());
        final double t = subLevel.getLevel().getGameTime() / timeScale;
        final double x = position.x / spatialScale;
        final double y = position.y / spatialScale;
        final double z = position.z / spatialScale;

        final double horizontalGust = horizontalSpeed * Config.tornadoGustStrength() * activation;
        final double gustX = coherentNoise(x, y, z, t, 11) * horizontalGust;
        final double gustZ = coherentNoise(x, y, z, t, 29) * horizontalGust;
        final double gustY = coherentNoise(x, y, z, t, 47) * rawUpdraft * Config.tornadoVerticalGustStrength() * activation * Math.max(0.25D, heightFade);
        final double verticalWind = Math.max(0.0D, updraft + gustY);

        return new Vec3(rawWind.x + gustX, rawWind.y + verticalWind, rawWind.z + gustZ);
    }

    private static TornadoLiftState getOrCreateLiftState(final ServerSubLevel subLevel, final double currentY,
                                                        final double activation) {
        final String key = String.valueOf(subLevel.getUniqueId());
        final long currentTick = subLevel.getLevel().getGameTime();
        final TornadoLiftState existing = TORNADO_LIFT_STATES.get(key);
        if (existing != null && currentTick - existing.lastActiveTick <= 100L) {
            // If the structure falls well below its old capture height, allow it to be lifted again
            // from the new lower position instead of keeping an obsolete ceiling forever.
            if (currentY >= existing.baseY - 8.0D) {
                return existing;
            }
        }

        final double baseLiftHeight = Config.tornadoUpdraftLiftHeight() * (0.75D + 0.5D * activation);
        final double noise = stableUnitNoise(key.hashCode()) * Config.tornadoUpdraftHeightNoise();
        final double liftHeight = Math.max(4.0D, baseLiftHeight + noise);
        final TornadoLiftState created = new TornadoLiftState(currentY, liftHeight, currentTick);
        TORNADO_LIFT_STATES.put(key, created);
        return created;
    }

    private static void expireLiftStateIfOld(final ServerSubLevel subLevel) {
        final String key = String.valueOf(subLevel.getUniqueId());
        final TornadoLiftState existing = TORNADO_LIFT_STATES.get(key);
        if (existing != null && subLevel.getLevel().getGameTime() - existing.lastActiveTick > 100L) {
            TORNADO_LIFT_STATES.remove(key);
        }
    }

    private static double updraftHeightFade(final double altitude, final double liftHeight) {
        if (liftHeight <= 0.0D) {
            return 0.0D;
        }

        final double fadeStart = liftHeight * Math.max(0.0D, Math.min(1.0D, Config.tornadoUpdraftFadeStartRatio()));
        if (altitude <= fadeStart) {
            return 1.0D;
        }
        if (altitude >= liftHeight) {
            return 0.0D;
        }

        final double alpha = (altitude - fadeStart) / Math.max(1.0e-6D, liftHeight - fadeStart);
        final double smooth = alpha * alpha * (3.0D - 2.0D * alpha);
        return 1.0D - smooth;
    }

    private static double stableUnitNoise(final int seed) {
        int x = seed;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return ((x & 0xfffffff) / (double) 0xfffffff) * 2.0D - 1.0D;
    }

    private static void pruneTornadoLiftStates(final long currentTick) {
        if (lastLiftStatePruneTick == currentTick || currentTick % 200L != 0L) {
            return;
        }

        lastLiftStatePruneTick = currentTick;
        final Iterator<Map.Entry<String, TornadoLiftState>> iterator = TORNADO_LIFT_STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, TornadoLiftState> entry = iterator.next();
            if (currentTick - entry.getValue().lastActiveTick > 400L) {
                iterator.remove();
            }
        }
    }

    private static final class TornadoLiftState {
        final double baseY;
        final double liftHeight;
        long lastActiveTick;

        TornadoLiftState(final double baseY, final double liftHeight, final long lastActiveTick) {
            this.baseY = baseY;
            this.liftHeight = liftHeight;
            this.lastActiveTick = lastActiveTick;
        }
    }

    private static double coherentNoise(final double x, final double y, final double z, final double t, final int seed) {
        final double seedOffset = seed * 0.6180339887498948D;
        final double a = Math.sin(x * 1.31D + y * 0.73D + z * 1.91D + t * 2.03D + seedOffset);
        final double b = Math.sin(x * 2.17D - y * 1.11D + z * 0.67D + t * 1.37D + seedOffset * 2.0D);
        final double c = Math.sin(-x * 0.83D + y * 1.79D + z * 2.41D + t * 0.79D + seedOffset * 3.0D);
        return (a + b * 0.5D + c * 0.25D) / 1.75D;
    }

    /**
     * Converts each exterior PMWeather wind sample into windward surface pressure.
     *
     * 0.6.0 note: after the torque fixes, body pressure uses air-relative PMWeather wind
     * by default. The 0.5.6 CSV showed zero direct wind torque, but compact objects were
     * still accelerating far past the actual wind speed; collisions then produced spin/glitching.
     * Relative wind prevents one-way endless acceleration without any small-object damping.
     *
     * 0.6.0 intentionally uses only the normal pressure component for body push. Tangential
     * shear at sparse exterior sample points acts like an artificial off-axis torque source and
     * was the main path that made small symmetric bodies rapidly flip. Removing that shear is not
     * damping; it prevents the bridge from inventing angular momentum that Sable never asked for.
     */
    private static void computeWindwardSurfacePressure(final WeatherWindSampler.WindSample sample,
                                                       final Vector3d differentialWind,
                                                       final Vector3d result) {
        final Vec3 outwardNormal = sample.outwardNormal();
        if (outwardNormal == Vec3.ZERO || outwardNormal.lengthSqr() <= 1.0e-12D) {
            result.set(differentialWind);
            return;
        }

        NORMAL.set(outwardNormal.x, outwardNormal.y, outwardNormal.z).normalize();
        final double dot = differentialWind.dot(NORMAL);
        final double incoming = -dot;
        if (incoming <= 0.0D) {
            result.zero();
            return;
        }

        // Wind force on a face should be normal pressure. Tangential components from updraft/gusts
        // are ignored for body torque because this sparse profile is not a viscous shear solver.
        NORMAL_COMPONENT.set(NORMAL).mul(dot);
        result.set(NORMAL_COMPONENT);
    }


    private static ForceTotal capForceTotalByAirRelativeVelocity(final ForceTotal forceTotal,
                                                                 final double airRelativeSpeed,
                                                                 final double mass) {
        if (forceTotal == null) {
            return new ForceTotal();
        }

        final double maxImpulse = maxAirRelativeImpulse(airRelativeSpeed, mass);
        final double forceLength = forceTotal.getLocalForce().length();
        if (!Double.isFinite(maxImpulse) || maxImpulse <= 0.0D || forceLength <= maxImpulse || forceLength <= 1.0e-12D) {
            return forceTotal;
        }

        final double scale = maxImpulse / forceLength;
        final ForceTotal capped = new ForceTotal();
        capped.applyLinearAndAngularImpulse(
                new Vector3d(forceTotal.getLocalForce()).mul(scale),
                new Vector3d(forceTotal.getLocalTorque()).mul(scale)
        );
        return capped;
    }

    private static void capImpulseByAirRelativeVelocity(final Vector3d impulse,
                                                        final double airRelativeSpeed,
                                                        final double mass) {
        final double maxImpulse = maxAirRelativeImpulse(airRelativeSpeed, mass);
        if (maxImpulse > 0.0D && Double.isFinite(maxImpulse)) {
            capLength(impulse, maxImpulse);
        }
    }

    private static double maxAirRelativeImpulse(final double airRelativeSpeed, final double mass) {
        final double correctionFraction = Config.maxAirRelativeVelocityCorrectionPerSubstep();
        if (correctionFraction <= 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0D, mass) * Math.max(0.0D, airRelativeSpeed) * correctionFraction;
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
