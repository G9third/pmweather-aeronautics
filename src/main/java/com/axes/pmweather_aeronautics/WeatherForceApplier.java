package com.axes.pmweather_aeronautics;
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
    private static final Vector3d SURFACE_WIND = new Vector3d();
    private static final Vector3d PROFILE_SURFACE_WIND = new Vector3d();
    private static final Vector3d NORMAL = new Vector3d();
    private static final Vector3d NORMAL_COMPONENT = new Vector3d();
    private static final Vector3d LINEAR_VELOCITY = new Vector3d();
    private static final Vector3d ANGULAR_VELOCITY = new Vector3d();
    private static final Vector3d APPLICATION_OFFSET = new Vector3d();
    private static final Vector3d ANGULAR_POINT_VELOCITY = new Vector3d();
    private static final Vector3d BODY_POINT_VELOCITY = new Vector3d();
    private static final Vector3d BODY_WIND_IMPULSE = new Vector3d();
    private static final Vector3d BODY_WIND_BASELINE_IMPULSE = new Vector3d();
    private static final ThreadLocal<PreparedPhysicsStep> ACTIVE_WHEEL_STEP = new ThreadLocal<>();
    private static final Vector3d LAST_NET_AERO_FORCE = new Vector3d();
    private static final Vector3d LAST_NET_AERO_TORQUE = new Vector3d();
    private static int lastWindwardSamples;
    private static int lastPressureGroups;
    private static final double BODY_DYNAMIC_PRESSURE_NORMALIZATION = 0.8D;
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
        ForceDiagnostics.begin(physicsSystem);
        final PreparedPhysicsStep prepared = prepareWindFrame(physicsSystem);
        if (prepared == null) {
            ACTIVE_WHEEL_STEP.remove();
        } else {
            ACTIVE_WHEEL_STEP.set(prepared);
        }
    }

    public static void onSablePrePhysicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        // Compatibility fallback if a future Sable build changes the injected call site. Normally
        // the frame was already prepared before any lift provider ran.
        final PreparedPhysicsStep prepared = prepareWindFrame(physicsSystem);
        if (prepared == null) {
            ACTIVE_WHEEL_STEP.remove();
            return;
        }
        for (int i = 0; i < prepared.activeSubLevels().size(); i++) {
            applyWindToSubLevel(
                    physicsSystem,
                    prepared.activeSubLevels().get(i),
                    timeStep,
                    prepared.windFrames().get(i).bodySamples(),
                    prepared
            );
        }
        prepared.wheelContacts().clear();
        ACTIVE_WHEEL_STEP.remove();
    }

    private static PreparedPhysicsStep prepareWindFrame(final SubLevelPhysicsSystem physicsSystem) {
        if (physicsSystem == null) {
            return null;
        }
        final ServerLevel level = physicsSystem.getLevel();
        if (level == null) {
            PREPARED_STEPS.remove(physicsSystem);
            return null;
        }
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
            final MassData massData = subLevel.getMassTracker();
            final Vector3dc centerOfMass = massData == null ? null : massData.getCenterOfMass();
            final boolean includeBody = Config.enableBodyPush()
                    && massData != null
                    && !massData.isInvalid()
                    && isFinite(centerOfMass);
            windFrames.add(WeatherWindField.prepareBatchedWindFrame(subLevel, includeBody));
        }
        WeatherWindField.resolvePreparedWindFrames(windFrames);

        final PreparedPhysicsStep prepared = new PreparedPhysicsStep(
                tick,
                partialBits,
                List.copyOf(activeSubLevels),
                List.copyOf(windFrames),
                new java.util.IdentityHashMap<>()
        );
        PREPARED_STEPS.put(physicsSystem, prepared);
        return prepared;
    }

    private record PreparedPhysicsStep(
            long tick,
            long partialPhysicsTickBits,
            List<ServerSubLevel> activeSubLevels,
            List<WeatherWindField.PreparedWindFrame> windFrames,
            java.util.IdentityHashMap<ServerSubLevel, java.util.Map<Object, WheelContactSnapshot>> wheelContacts
    ) {
    }

    /** Optional Sable wheel adapters record native contact impulses before wind is resolved. */
    static boolean recordWheelContactImpulse(final ServerSubLevel subLevel,
                                             final Object contact,
                                             final Vector3dc position,
                                             final Vector3dc normal,
                                             final double normalImpulse,
                                             final double friction,
                                             final Vector3dc originalCombinedImpulse) {
        if (subLevel == null || contact == null || position == null || normal == null || originalCombinedImpulse == null
                || !isFinite(position) || !isFinite(normal) || !isFinite(originalCombinedImpulse)
                || normal.lengthSquared() <= 1.0e-12D
                || !Double.isFinite(normalImpulse) || !Double.isFinite(friction)) {
            return false;
        }
        final PreparedPhysicsStep prepared = ACTIVE_WHEEL_STEP.get();
        final SubLevelPhysicsSystem steppingSystem = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        if (prepared == null
                || steppingSystem == null
                || PREPARED_STEPS.get(steppingSystem) != prepared
                || prepared.partialPhysicsTickBits() != Double.doubleToLongBits(steppingSystem.getPartialPhysicsTick())
                || steppingSystem.getLevel() != subLevel.getLevel()
                || prepared.tick() != subLevel.getLevel().getGameTime()
                || !prepared.activeSubLevels().contains(subLevel)) {
            return false;
        }
        final java.util.Map<Object, WheelContactSnapshot> contacts = prepared.wheelContacts()
                .computeIfAbsent(subLevel, ignored -> new java.util.HashMap<>());
        contacts.put(contact, new WheelContactSnapshot(
                new Vector3d(position),
                new Vector3d(normal).normalize(),
                normalImpulse,
                Math.max(0.0D, friction),
                new Vector3d(originalCombinedImpulse)
        ));
        return true;
    }

    private record WheelContactSnapshot(Vector3d position,
                                        Vector3d normal,
                                        double normalImpulse,
                                        double friction,
                                        Vector3d originalCombinedImpulse) {
    }
    private static void applyWindToSubLevel(final SubLevelPhysicsSystem physicsSystem, final ServerSubLevel subLevel,
                                            final double timeStep,
                                            final List<WeatherWindField.WindSample> samples,
                                            final PreparedPhysicsStep prepared) {
        if (!Config.enableBodyPush()) {
            if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_skipped", "reason", "body_push_disabled");
            return;
        }
        if (!Double.isFinite(timeStep) || timeStep <= 0.0D) {
            if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_skipped", "reason", "invalid_timestep", "dt", timeStep);
            return;
        }
        final MassData massData = subLevel.getMassTracker();
        if (massData == null || massData.isInvalid() || !isFinite(massData.getCenterOfMass())) {
            if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_skipped", "reason", "invalid_mass");
            return;
        }
        final Pose3d pose = subLevel.logicalPose();
        final Vector3dc centerOfMassLocal = massData.getCenterOfMass();
        pose.transformPosition(centerOfMassLocal, WORLD_CENTER);
        if (samples == null || samples.isEmpty()) {
            if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_skipped", "reason", "no_prepared_samples");
            return;
        }
        final RigidBodyHandle handle = physicsSystem.getPhysicsHandle(subLevel);
        if (handle == null || !handle.isValid()) {
            if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_skipped", "reason", "invalid_handle");
            return;
        }
        handle.getLinearVelocity(LINEAR_VELOCITY);
        handle.getAngularVelocity(ANGULAR_VELOCITY);
        if (!isFinite(LINEAR_VELOCITY) || !isFinite(ANGULAR_VELOCITY)) {
            if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_skipped", "reason", "invalid_velocity");
            return;
        }
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
        // Exterior aerodynamic profile pressure is the only whole-body wind-force source.
        // There is deliberately no synthetic center/COM fallback query or force.
        final int appliedProfileSamples = applyAerodynamicProfilePressure(
                subLevel, pose, windGroup, samples, threshold, timeStep, massDamping, massData, centerOfMassLocal
        );
        applyWheelBreakawayCorrections(
                subLevel, prepared, samples, massData, timeStep, massDamping, threshold, windGroup
        );
        final int appliedCenter = 0; // Kept in the debug CSV schema for backward compatibility.
        final double strongestProfileSpeed = strongestSampleSpeed(samples);
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
                    strongestProfileSpeed,
                    LAST_NET_AERO_FORCE,
                    LAST_NET_AERO_TORQUE,
                    lastWindwardSamples,
                    lastPressureGroups,
                    WeatherWindField.sampleStatsSnapshot()
            );
        }
        if (Config.debugLogging() && subLevel.getLevel().getGameTime() % 100L == 0L) {
            PMWeatherAeronautics.LOGGER.info(
                    "PMWeather aerodynamic wind for Sable sub-level {}: profileApplied={}, strongestProfileSpeed={}, mass={}, damping={}, profileStrength={}, areaWeightStrength={}, patchPerObjectMax={}, quadraticPressure=true, relativeBodyDrag={}",
                    subLevel.getUniqueId(), appliedProfileSamples,
                    strongestProfileSpeed, massData.getMass(), massDamping,
                    Config.aeroPatchPressureStrength(), Config.aeroPatchAreaWeightStrength(), Config.maxAeroPatchSamplesPerObject(), Config.enableBodyRelativeWindDrag()
            );
        }
    }
    private static double strongestSampleSpeed(final List<WeatherWindField.WindSample> samples) {
        double strongest = 0.0D;
        for (final WeatherWindField.WindSample sample : samples) {
            final Vec3 wind = sample.wind();
            strongest = Math.max(strongest, WeatherWindField.pmweatherWindToPhysicsWind(wind).length());
        }
        return strongest;
    }
    private static void setBodyPressureWind(final WeatherWindField.WindSample sample, final Vector3d result) {
        final Vec3 physicsWind = WeatherWindField.pmweatherWindToPhysicsWind(sample.wind());
        result.set(physicsWind.x, physicsWind.y, physicsWind.z);
        if (Config.enableBodyRelativeWindDrag()) {
            APPLICATION_OFFSET.set(
                    sample.applicationPosition().x - WORLD_CENTER.x,
                    sample.applicationPosition().y - WORLD_CENTER.y,
                    sample.applicationPosition().z - WORLD_CENTER.z
            );
            ANGULAR_VELOCITY.cross(APPLICATION_OFFSET, ANGULAR_POINT_VELOCITY);
            BODY_POINT_VELOCITY.set(LINEAR_VELOCITY).add(ANGULAR_POINT_VELOCITY);
            result.sub(BODY_POINT_VELOCITY);
        }
    }

    private static void applyWheelBreakawayCorrections(final ServerSubLevel subLevel,
                                                       final PreparedPhysicsStep prepared,
                                                       final List<WeatherWindField.WindSample> samples,
                                                       final MassData massData,
                                                       final double timeStep,
                                                       final double massDamping,
                                                       final double threshold,
                                                       final QueuedForceGroup windGroup) {
        final java.util.Map<Object, WheelContactSnapshot> contacts = prepared.wheelContacts().get(subLevel);
        if (contacts == null || contacts.isEmpty() || samples == null || samples.isEmpty()) {
            return;
        }

        final double pressureStrength = Config.aeroPatchPressureStrength();
        final double windInfluence = Config.windInfluence();
        if (pressureStrength <= 0.0D || windInfluence <= 0.0D) {
            return;
        }

        final Vector3d actualBodyImpulse = new Vector3d();
        final Vector3d zeroWeatherBodyImpulse = new Vector3d();
        double maxActualNormalSpeed = 0.0D;
        double maxZeroWeatherNormalSpeed = 0.0D;
        for (final WeatherWindField.WindSample sample : samples) {
            if (sample == null || sample.wind() == null || !isFinite(sample.wind())
                    || sample.applicationPosition() == null || !isFinite(sample.applicationPosition())
                    || sample.areaWeight() <= 0.0D) {
                continue;
            }
            maxActualNormalSpeed = Math.max(maxActualNormalSpeed, computePressureImpulse(
                    sample, sample.wind(), threshold, timeStep, massDamping, BODY_WIND_IMPULSE
            ));
            maxZeroWeatherNormalSpeed = Math.max(maxZeroWeatherNormalSpeed, computePressureImpulse(
                    sample, Vec3.ZERO, threshold, timeStep, massDamping, BODY_WIND_BASELINE_IMPULSE
            ));
            actualBodyImpulse.add(BODY_WIND_IMPULSE);
            zeroWeatherBodyImpulse.add(BODY_WIND_BASELINE_IMPULSE);
        }
        capLinearImpulse(actualBodyImpulse, maxActualNormalSpeed, massData.getMass());
        capLinearImpulse(zeroWeatherBodyImpulse, maxZeroWeatherNormalSpeed, massData.getMass());
        final Vector3d weatherImpulseDelta = actualBodyImpulse.sub(zeroWeatherBodyImpulse);
        if (!isFinite(weatherImpulseDelta) || weatherImpulseDelta.lengthSquared() <= 1.0e-12D) {
            return;
        }

        double totalPositiveNormalImpulse = 0.0D;
        double totalGrip = 0.0D;
        for (final WheelContactSnapshot contact : contacts.values()) {
            final double compression = Math.max(0.0D, contact.normalImpulse());
            totalPositiveNormalImpulse += compression;
            totalGrip += compression * contact.friction();
        }
        if (!Double.isFinite(totalPositiveNormalImpulse) || totalPositiveNormalImpulse <= 1.0e-12D
                || !Double.isFinite(totalGrip)) {
            return;
        }

        double projectedWindDemand = 0.0D;
        for (final WheelContactSnapshot contact : contacts.values()) {
            final double normalLoad = Math.max(0.0D, contact.normalImpulse());
            if (normalLoad <= 1.0e-12D) {
                continue;
            }
            final double share = normalLoad / totalPositiveNormalImpulse;
            final Vector3d tangentWind = new Vector3d(weatherImpulseDelta)
                    .fma(-weatherImpulseDelta.dot(contact.normal()), contact.normal());
            projectedWindDemand += tangentWind.length() * share;
        }

        if (!Double.isFinite(projectedWindDemand) || projectedWindDemand <= totalGrip + 1.0e-9D) {
            return;
        }

        final ForceTotal correctionTotal = windGroup.getForceTotal();
        for (final WheelContactSnapshot contact : contacts.values()) {
            final Vector3d normal = contact.normal();
            final Vector3d original = contact.originalCombinedImpulse();
            final double originalNormal = original.dot(normal);
            final Vector3d originalTangent = new Vector3d(original).fma(-originalNormal, normal);
            final double tangentLength = originalTangent.length();
            final double normalLoad = Math.max(0.0D, contact.normalImpulse());
            if (normalLoad <= 1.0e-12D || !isFinite(normal)) {
                continue;
            }
            final double capacity = normalLoad * contact.friction();
            if (!Double.isFinite(tangentLength) || tangentLength <= capacity + 1.0e-9D) {
                continue;
            }

            final Vector3d limitedTangent = tangentLength <= 1.0e-12D
                    ? new Vector3d()
                    : new Vector3d(originalTangent).mul(capacity / tangentLength);
            final Vector3d correction = limitedTangent.sub(originalTangent);
            if (correction.lengthSquared() <= 1.0e-12D) {
                continue;
            }
            correctionTotal.applyImpulseAtPoint(massData, contact.position(), correction);
            windGroup.recordPointForce(contact.position(), correction);
            if (ForceDiagnostics.enabled()) {
                ForceDiagnostics.event(subLevel, "wheel_wind_breakaway", "windDemand", projectedWindDemand,
                        "aggregateGrip", totalGrip, "normalImpulse", contact.normalImpulse(),
                        "friction", contact.friction(), "originalTangent", originalTangent,
                        "limitedTangent", limitedTangent, "correction", correction);
            }
        }
    }

    private static double computePressureImpulse(final WeatherWindField.WindSample sample,
                                                 final Vec3 wind,
                                                 final double threshold,
                                                 final double timeStep,
                                                 final double massDamping,
                                                 final Vector3d result) {
        final Vec3 physicsWind = WeatherWindField.pmweatherWindToPhysicsWind(wind);
        result.set(physicsWind.x, physicsWind.y, physicsWind.z);
        if (Config.enableBodyRelativeWindDrag()) {
            APPLICATION_OFFSET.set(
                    sample.applicationPosition().x - WORLD_CENTER.x,
                    sample.applicationPosition().y - WORLD_CENTER.y,
                    sample.applicationPosition().z - WORLD_CENTER.z
            );
            ANGULAR_VELOCITY.cross(APPLICATION_OFFSET, ANGULAR_POINT_VELOCITY);
            BODY_POINT_VELOCITY.set(LINEAR_VELOCITY).add(ANGULAR_POINT_VELOCITY);
            result.sub(BODY_POINT_VELOCITY);
        }

        computeWindwardSurfacePressure(sample, result, PROFILE_SURFACE_WIND);
        final double normalSpeed = PROFILE_SURFACE_WIND.length();
        final double safeThreshold = Math.max(0.0D, threshold);
        if (normalSpeed <= safeThreshold || !Double.isFinite(normalSpeed)) {
            result.zero();
            return 0.0D;
        }
        final double magnitude = aerodynamicPressureMagnitude(normalSpeed, safeThreshold)
                * Config.windInfluence()
                * Config.aeroPatchPressureStrength()
                * effectivePatchArea(sample.areaWeight())
                * BODY_DYNAMIC_PRESSURE_NORMALIZATION
                * timeStep
                / massDamping;
        result.set(PROFILE_SURFACE_WIND).normalize().mul(magnitude);
        capLength(result, Config.maxImpulsePerSubstep() * Config.aeroPatchPressureStrength());
        return normalSpeed;
    }

    private static void capLinearImpulse(final Vector3d impulse, final double maxNormalSpeed, final double mass) {
        final double maxImpulse = maxAirRelativeImpulse(maxNormalSpeed, mass);
        final double length = impulse.length();
        if (Double.isFinite(maxImpulse) && maxImpulse > 0.0D && Double.isFinite(length) && length > maxImpulse) {
            impulse.mul(maxImpulse / length);
        }
    }
    /**
     * Applies multi-point quadratic windward pressure from the cached exterior aerodynamic profile.
     *
     * Uses each exposed face's measured centroid as the uniform-pressure line of action, then
     * keeps only a bounded residual for real pressure differences across that face.
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
                                                       final List<WeatherWindField.WindSample> samples,
                                                       final double threshold,
                                                       final double timeStep, final double massDamping,
                                                       final MassData massData,
                                                       final Vector3dc centerOfMassLocal) {
        final double profileStrength = Config.aeroPatchPressureStrength();
        if (profileStrength <= 0.0D || samples.isEmpty()) {
            if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_skipped", "reason", "profile_strength_or_samples_zero");
            return 0;
        }
        int windwardSamples = 0;
        final double profileThreshold = Math.max(0.0D, threshold);
        final ProfilePressureGroup[] groups = new ProfilePressureGroup[8];
        double maxAppliedAirRelativeSpeed = 0.0D;
        for (int i = 0; i < samples.size(); i++) {
            final WeatherWindField.WindSample sample = samples.get(i);
            if (sample.areaWeight() <= 0.0D) {
                if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "pressure_rejected", "reason", "zero_area", "samplePosition", sample.samplePosition());
                continue;
            }
            setBodyPressureWind(sample, SURFACE_WIND);
            computeWindwardSurfacePressure(sample, SURFACE_WIND, PROFILE_SURFACE_WIND);
            final double surfaceSpeed = PROFILE_SURFACE_WIND.length();
            if (surfaceSpeed <= profileThreshold) {
                if (ForceDiagnostics.enabled()) ForceDiagnostics.pressure(subLevel, sample,
                        sample.wind().lengthSqr() == 0 ? "zero_weather_wind_or_body_motion_only" : surfaceSpeed == 0 ? "leeward_or_tangent" : "below_threshold",
                        SURFACE_WIND, surfaceSpeed, profileThreshold, effectivePatchArea(sample.areaWeight()), 0, new Vector3d(), timeStep, massDamping);
                continue;
            }
            windwardSamples++;
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
            if (ForceDiagnostics.enabled()) ForceDiagnostics.pressure(subLevel, sample,
                    LOCAL_WIND_IMPULSE.lengthSquared() <= 1.0e-10D ? "negligible_impulse" : "patch_impulse",
                    SURFACE_WIND, surfaceSpeed, profileThreshold, shareWeight, magnitude, LOCAL_WIND_IMPULSE, timeStep, massDamping);
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
                        sample.wind(),
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
        lastWindwardSamples = windwardSamples;
        if (windwardSamples <= 0) {
            return 0;
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
        if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_before_net_cap", "dt", timeStep, "localImpulse", netAeroForce.getLocalForce(), "localTorqueImpulse", netAeroForce.getLocalTorque(), "maxNormalSpeed", maxAppliedAirRelativeSpeed);
        final ForceTotal cappedAeroForce = capForceTotalByAirRelativeVelocity(netAeroForce, maxAppliedAirRelativeSpeed, massData.getMass());
        if (ForceDiagnostics.enabled()) ForceDiagnostics.event(subLevel, "body_after_net_cap", "dt", timeStep, "localImpulse", cappedAeroForce.getLocalForce(), "localTorqueImpulse", cappedAeroForce.getLocalTorque(),
                "worldImpulse", pose.transformNormal(new Vector3d(cappedAeroForce.getLocalForce())), "mass", massData.getMass());
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
            // Use the actual full-face profile centroid for uniform pressure. The capped residual
            // adds only the measured patch-to-patch pressure difference around that same line.
            forceTotal.applyImpulseAtPoint(massData, pressureLineCenter, this.totalImpulse);
            if (differentialTorque.lengthSquared() > 1.0e-12D) {
                forceTotal.applyLinearAndAngularImpulse(new Vector3d(), differentialTorque);
            }
            lastPressureGroups++;
            return this.entries.size();
        }
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
    private static void computeWindwardSurfacePressure(final WeatherWindField.WindSample sample,
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

    private static boolean isFinite(final Vector3dc vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    private static boolean isFinite(final Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    static void clearSession() {
        PREPARED_STEPS.clear();
        ACTIVE_WHEEL_STEP.remove();
    }

}
