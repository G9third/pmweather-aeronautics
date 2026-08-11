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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.ArrayList;
import java.util.List;
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
    private static final double BODY_DYNAMIC_PRESSURE_NORMALIZATION = 0.8D;
    private static final double CENTER_FALLBACK_AREA = 1.0D;
    /**
     * Use PMWeather Aeronautics' registered Sable force group instead of an ad-hoc ForceGroup.
     *
     * Create Aeronautics / Sable diagram data serializes queued force groups by registry id.
     * A ForceGroup constructed directly in this class has no registry id and can crash
     * simulated:diagram_data encoding. Do not use ForceGroups.DRAG.get() here either: that
     * accessor exposes Veil's RegistryObject type, which is not on this project's compile classpath.
     */
    private WeatherForceApplier() {
    }
    private static final java.util.IdentityHashMap<SubLevelPhysicsSystem, PreparedPhysicsStep> PREPARED_STEPS = new java.util.IdentityHashMap<>();

    /**
     * Collects and resolves the complete BODY + AIRFLOW wind frame before Sable enters
     * ServerSubLevel.prePhysicsTick's lift-provider loop.
     *
     * Sable publishes its public prePhysicsTick event only after the individual sub-level
     * prePhysicsTick methods have already run. Airflow providers therefore cannot wait for that
     * event: by then their lift/drag callbacks have already happened. A small Sable mixin calls
     * this method immediately before the first ServerSubLevel.prePhysicsTick invocation of each
     * physics substep. Calls before later sub-levels in the same substep are cheap no-ops.
     */
    public static void prepareWindBeforeLiftProviders(final SubLevelPhysicsSystem physicsSystem) {
        prepareWindFrame(physicsSystem);
    }

    public static void onSablePrePhysicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        // Compatibility fallback if a future Sable build changes the injected call site. Normally
        // the frame was already prepared before any lift provider ran.
        final PreparedPhysicsStep prepared = prepareWindFrame(physicsSystem);
        if (prepared == null) {
            return;
        }
        for (int i = 0; i < prepared.activeSubLevels().size(); i++) {
            applyWindToSubLevel(
                    physicsSystem,
                    prepared.activeSubLevels().get(i),
                    timeStep,
                    prepared.windFrames().get(i).bodySamples()
            );
        }
    }

    private static PreparedPhysicsStep prepareWindFrame(final SubLevelPhysicsSystem physicsSystem) {
        if (physicsSystem == null) {
            return null;
        }
        final ServerLevel level = physicsSystem.getLevel();
        final long tick = level.getGameTime();
        final long partialBits = Double.doubleToLongBits(physicsSystem.getPartialPhysicsTick());
        final PreparedPhysicsStep existing = PREPARED_STEPS.get(physicsSystem);
        if (existing != null && existing.tick() == tick && existing.partialPhysicsTickBits() == partialBits) {
            return existing;
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            PREPARED_STEPS.remove(physicsSystem);
            return null;
        }

        final List<ServerSubLevel> activeSubLevels = new ArrayList<>();
        // Collect every active sub-level first. This makes the global maxWindSamplesPerTick split
        // aware of the complete same-substep object count before the first object is allowed to
        // query PMWeather, so an early object cannot consume the whole budget.
        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            PhysicsTickWindBatch.begin(physicsSystem, subLevel);
            activeSubLevels.add(subLevel);
        }

        // Prepare BODY and AIRFLOW requests for every active object before making any PMWeather
        // query. Exact coordinates can be deduplicated across different Sable objects and all
        // requests share the same PMWeather storm snapshot.
        final List<WeatherWindField.PreparedWindFrame> windFrames = new ArrayList<>(activeSubLevels.size());
        for (final ServerSubLevel subLevel : activeSubLevels) {
            Vec3 bodyCenter = null;
            if (Config.enableBodyPush()) {
                final MassData massData = subLevel.getMassTracker();
                if (massData != null && !massData.isInvalid() && massData.getCenterOfMass() != null) {
                    final Vector3d center = new Vector3d();
                    subLevel.logicalPose().transformPosition(massData.getCenterOfMass(), center);
                    bodyCenter = new Vec3(center.x, center.y, center.z);
                }
            }
            windFrames.add(WeatherWindField.prepareBatchedWindFrame(subLevel, bodyCenter));
        }
        WeatherWindField.resolvePreparedWindFrames(windFrames);

        final PreparedPhysicsStep prepared = new PreparedPhysicsStep(
                tick,
                partialBits,
                List.copyOf(activeSubLevels),
                List.copyOf(windFrames)
        );
        PREPARED_STEPS.put(physicsSystem, prepared);
        return prepared;
    }

    private record PreparedPhysicsStep(
            long tick,
            long partialPhysicsTickBits,
            List<ServerSubLevel> activeSubLevels,
            List<WeatherWindField.PreparedWindFrame> windFrames
    ) {
    }
    private static void applyWindToSubLevel(final SubLevelPhysicsSystem physicsSystem, final ServerSubLevel subLevel,
                                            final double timeStep,
                                            final List<WeatherWindSampler.WindSample> samples) {
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
        if (samples == null || samples.isEmpty()) {
            return;
        }
        final RigidBodyHandle handle = physicsSystem.getPhysicsHandle(subLevel);
        handle.getLinearVelocity(LINEAR_VELOCITY);
        handle.getAngularVelocity(ANGULAR_VELOCITY);
        LAST_NET_AERO_FORCE.zero();
        LAST_NET_AERO_TORQUE.zero();
        lastWindwardSamples = 0;
        lastPressureGroups = 0;
        // Config windThreshold remains in PMWeather/mph-style units. Body pressure below uses
        // converted block/second physics wind, so compare against the converted threshold here.
        final double threshold = WeatherWindField.pmweatherSpeedToBlocksPerSecond(Config.windThreshold());
        final double massDamping = Math.max(1.0D, Math.pow(
                Math.max(1.0D, massData.getMass()),
                Config.massScaling() * 0.25D
        ));
        final QueuedForceGroup windGroup = subLevel.getOrCreateQueuedForceGroup(PMWeatherForceGroups.weatherWind());
        // 0.6.0: aerodynamic profile pressure is the main wind force source.
        // Multiple exterior profile samples are converted through Sable ForceTotal; the center/COM sample is only fallback.
        final int appliedProfileSamples = applyAerodynamicProfilePressure(
                subLevel, pose, windGroup, samples, threshold, timeStep, massDamping, massData, centerOfMassLocal
        );
        int appliedCenter = 0;
        double centerSpeed = strongestSampleSpeed(subLevel, samples);
        if (appliedProfileSamples <= 0) {
            final Vec3 centerWind = samples.get(0).wind();
            setBodyPressureWind(centerWind, CENTER_RELATIVE_WIND);
            centerSpeed = CENTER_RELATIVE_WIND.length();
            if (centerSpeed > threshold) {
                final double magnitude = aerodynamicPressureMagnitude(centerSpeed, threshold)
                        * Config.windInfluence()
                        * Config.aeroPatchPressureStrength()
                        * effectivePatchArea(CENTER_FALLBACK_AREA)
                        * BODY_DYNAMIC_PRESSURE_NORMALIZATION
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
                    "PMWeather aerodynamic wind for Sable sub-level {}: centerApplied={}, profileApplied={}, strongestProfileSpeed={}, mass={}, damping={}, profileStrength={}, areaWeightStrength={}, patchPerObjectMax={}, quadraticPressure=true, relativeBodyDrag={}",
                    subLevel.getUniqueId(), appliedCenter, appliedProfileSamples,
                    centerSpeed, massData.getMass(), massDamping,
                    Config.aeroPatchPressureStrength(), Config.aeroPatchAreaWeightStrength(), Config.maxAeroPatchSamplesPerObject(), Config.enableBodyRelativeWindDrag()
            );
        }
    }
    private static double strongestSampleSpeed(final ServerSubLevel subLevel,
                                               final List<WeatherWindSampler.WindSample> samples) {
        double strongest = 0.0D;
        for (final WeatherWindSampler.WindSample sample : samples) {
            final Vec3 wind = sample.wind();
            strongest = Math.max(strongest, WeatherWindField.pmweatherWindToPhysicsWind(wind).length());
        }
        return strongest;
    }
    private static Vec3 computeSampleFinalWind(final ServerSubLevel subLevel,
                                               final WeatherWindSampler.WindSample sample) {
        return sample.wind();
    }
    private static void computeSampleRelativeWind(final ServerSubLevel subLevel,
                                                  final WeatherWindSampler.WindSample sample,
                                                  final Vector3d result) {
        final Vec3 wind = computeSampleFinalWind(subLevel, sample);
        setBodyPressureWind(wind, result);
    }
    private static void setBodyPressureWind(final Vec3 wind, final Vector3d result) {
        final Vec3 physicsWind = WeatherWindField.pmweatherWindToPhysicsWind(wind);
        result.set(physicsWind.x, physicsWind.y, physicsWind.z);
        if (Config.enableBodyRelativeWindDrag()) {
            result.sub(LINEAR_VELOCITY);
        }
    }
    /**
     * Applies multi-point quadratic windward pressure from the cached exterior aerodynamic profile.
     *
     * 0.6.0 fix: avoid artificial Dzhanibekov-style spin from sparse sample point torque and uniform-pressure center bias.
     * Each major exterior side is reduced to one center-of-pressure impulse after summing area-weighted patch pressure. Uniform wind on a
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
        final double profileThreshold = Math.max(0.0D, threshold);
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
            final double shareWeight = effectivePatchArea(sample.areaWeight());
            final int groupIndex = pressureGroupIndex(sample.surfaceRole());
            final double magnitude = aerodynamicPressureMagnitude(surfaceSpeed, profileThreshold)
                    * Config.windInfluence()
                    * profileStrength
                    * shareWeight
                    * BODY_DYNAMIC_PRESSURE_NORMALIZATION
                    * timeStep
                    / massDamping;
            final double perProfileCap = Config.maxImpulsePerSubstep() * profileStrength;
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
    private static double aerodynamicPressureMagnitude(final double normalSpeed, final double threshold) {
        if (!Double.isFinite(normalSpeed) || normalSpeed <= threshold) {
            return 0.0D;
        }
        final double speed = Math.max(0.0D, normalSpeed);
        final double deadZone = Math.max(0.0D, threshold);
        return Math.max(0.0D, speed * speed - deadZone * deadZone);
    }
    private static double effectivePatchArea(final double rawArea) {
        final double area = Double.isFinite(rawArea) ? Math.max(0.0D, rawArea) : 0.0D;
        final double strength = Math.max(0.0D, Math.min(1.0D, Config.aeroPatchAreaWeightStrength()));
        return Math.max(0.05D, 1.0D + (area - 1.0D) * strength);
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
