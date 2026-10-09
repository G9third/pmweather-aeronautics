package com.axes.pmweather_aeronautics;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import java.util.List;
import java.util.ServiceLoader;
/** Optional observation callbacks. The public artifact has no recorder or provider. */
public final class AeroObserver {
    private static final Observer NOOP = new Observer() {};
    private static volatile Observer observer = NOOP;
    private AeroObserver() {}

    static void install(net.neoforged.fml.ModContainer container) {
        try {
            Provider provider = ServiceLoader.load(Provider.class, AeroObserver.class.getClassLoader())
                    .findFirst().orElse(null);
            if (provider != null) {
                Observer loaded = provider.create();
                if (loaded == null) throw new IllegalStateException("Null PMAero observer");
                observer = loaded;
                provider.initialize(container);
            }
        } catch (RuntimeException | java.util.ServiceConfigurationError | LinkageError failure) {
            disable(failure);
        }
    }
    private static void disable(Throwable failure) {
        observer = NOOP;
        PMWeatherAeronautics.LOGGER.warn("Optional PMAero development observer disabled", failure);
    }
    public interface Provider {
        Observer create();
        void initialize(net.neoforged.fml.ModContainer container);
    }
    public interface Observer {
        default void clearSession() {}
        default boolean summaryLoggingEnabled() { return false; }
        default boolean hasPatchViewers() { return false; }
        default boolean enabled() { return false; }
        default void event(ServerSubLevel body, String type, Object... fields) {  }
        default void profile(ServerSubLevel body, AeroSurfaceCache.AerodynamicProfile profile, java.util.List<AeroSurfaceCache.ProfileFace> selected) {  }
        default void face(ServerSubLevel body, String decision, AeroSurfaceCache.ProfileFace face, Vec3 point, Vec3 normal, double margin) {  }
        default void pressure(ServerSubLevel body, WeatherWindField.WindSample sample, String decision, Vector3dc actualRelative, double normalSpeed, double threshold, double area, double rawMagnitude, Vector3dc patchImpulseWorld, double dt, double massDamping) {  }
        default void begin(SubLevelPhysicsSystem system) {  }
        default void snapshot(ServerSubLevel body, String phase, double dt) {  }
        default void beforeSolver(SubLevelPhysicsSystem system) {  }
        default void submitted(PhysicsPipelineBody target, String kind, Vector3dc linear, Vector3dc angular, Vector3dc point, boolean wake) {  }
        default void wheel(Object target, ServerSubLevel body, double dt, boolean applied) {  }
        default void wheelTerrain(Object target, Object result) {  }
        default boolean traceEnabled() { return false; }
        default void recordObject(long tick, String subLevelId, double timeStep, double mass, Vector3dc localCenterOfMass, Vector3dc worldCenterOfMass, Vector3dc linearVelocity, Vector3dc angularVelocity, int totalSamples, int appliedProfileSamples, int appliedCenter, double strongestSpeed, Vector3dc netLocalForce, Vector3dc netLocalTorque, int windwardSamples, int pressureGroups, WeatherWindField.SampleStats stats) {  }
        default void recordSample(long tick, String subLevelId, int sampleIndex, WeatherWindField.WindSample sample, Vec3 finalWind, Vector3dc relativeWind, Vector3dc pressureVectorWorld, double profileThreshold, double surfaceSpeed, double shareWeight, double magnitude, Vector3dc localApplicationPoint, Vector3dc localPressureCenter, Vector3dc localImpulse) {  }
        default void recordGroup(long tick, String subLevelId, int role, int entryCount, Vector3dc localCenterOfMass, Vector3dc localPressureCenter, Vector3dc totalLocalImpulse, Vector3dc localTorque) {  }
        default void observeSelectedPatches(ServerSubLevel subLevel, List<AeroSurfaceCache.ProfileFace> selectedFaces) {  }
    }
    public static boolean summaryLoggingEnabled() {
        try { return observer.summaryLoggingEnabled(); }
        catch (RuntimeException | LinkageError failure) { disable(failure); return false; }
    }
    public static boolean hasPatchViewers() {
        try { return observer.hasPatchViewers(); }
        catch (RuntimeException | LinkageError failure) { disable(failure); return false; }
    }
    public static void clearSession() {
        try { observer.clearSession(); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static boolean enabled() {
        try { return observer.enabled(); }
        catch (RuntimeException | LinkageError failure) { disable(failure); return false; }
    }
    public static void event(ServerSubLevel body, String type, Object... fields) {
        try { observer.event(body, type, fields); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void profile(ServerSubLevel body, AeroSurfaceCache.AerodynamicProfile profile, java.util.List<AeroSurfaceCache.ProfileFace> selected) {
        try { observer.profile(body, profile, selected); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void face(ServerSubLevel body, String decision, AeroSurfaceCache.ProfileFace face, Vec3 point, Vec3 normal, double margin) {
        try { observer.face(body, decision, face, point, normal, margin); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void pressure(ServerSubLevel body, WeatherWindField.WindSample sample, String decision, Vector3dc actualRelative, double normalSpeed, double threshold, double area, double rawMagnitude, Vector3dc patchImpulseWorld, double dt, double massDamping) {
        try { observer.pressure(body, sample, decision, actualRelative, normalSpeed, threshold, area, rawMagnitude, patchImpulseWorld, dt, massDamping); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void begin(SubLevelPhysicsSystem system) {
        try { observer.begin(system); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void snapshot(ServerSubLevel body, String phase, double dt) {
        try { observer.snapshot(body, phase, dt); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void beforeSolver(SubLevelPhysicsSystem system) {
        try { observer.beforeSolver(system); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void submitted(PhysicsPipelineBody target, String kind, Vector3dc linear, Vector3dc angular, Vector3dc point, boolean wake) {
        try { observer.submitted(target, kind, linear, angular, point, wake); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void wheel(Object target, ServerSubLevel body, double dt, boolean applied) {
        try { observer.wheel(target, body, dt, applied); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void wheelTerrain(Object target, Object result) {
        try { observer.wheelTerrain(target, result); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static boolean traceEnabled() {
        try { return observer.traceEnabled(); }
        catch (RuntimeException | LinkageError failure) { disable(failure); return false; }
    }
    public static void recordObject(long tick, String subLevelId, double timeStep, double mass, Vector3dc localCenterOfMass, Vector3dc worldCenterOfMass, Vector3dc linearVelocity, Vector3dc angularVelocity, int totalSamples, int appliedProfileSamples, int appliedCenter, double strongestSpeed, Vector3dc netLocalForce, Vector3dc netLocalTorque, int windwardSamples, int pressureGroups, WeatherWindField.SampleStats stats) {
        try { observer.recordObject(tick, subLevelId, timeStep, mass, localCenterOfMass, worldCenterOfMass, linearVelocity, angularVelocity, totalSamples, appliedProfileSamples, appliedCenter, strongestSpeed, netLocalForce, netLocalTorque, windwardSamples, pressureGroups, stats); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void recordSample(long tick, String subLevelId, int sampleIndex, WeatherWindField.WindSample sample, Vec3 finalWind, Vector3dc relativeWind, Vector3dc pressureVectorWorld, double profileThreshold, double surfaceSpeed, double shareWeight, double magnitude, Vector3dc localApplicationPoint, Vector3dc localPressureCenter, Vector3dc localImpulse) {
        try { observer.recordSample(tick, subLevelId, sampleIndex, sample, finalWind, relativeWind, pressureVectorWorld, profileThreshold, surfaceSpeed, shareWeight, magnitude, localApplicationPoint, localPressureCenter, localImpulse); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void recordGroup(long tick, String subLevelId, int role, int entryCount, Vector3dc localCenterOfMass, Vector3dc localPressureCenter, Vector3dc totalLocalImpulse, Vector3dc localTorque) {
        try { observer.recordGroup(tick, subLevelId, role, entryCount, localCenterOfMass, localPressureCenter, totalLocalImpulse, localTorque); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
    public static void observeSelectedPatches(ServerSubLevel subLevel, List<AeroSurfaceCache.ProfileFace> selectedFaces) {
        try { observer.observeSelectedPatches(subLevel, selectedFaces); }
        catch (RuntimeException | LinkageError failure) { disable(failure); }
    }
}
