package com.axes.pmweather_aeronautics;

import com.google.gson.Gson;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Observer only. JSONL complements the legacy CSV; none of these values feed physics. */
public final class ForceDiagnostics {
    private static final Gson JSON = new Gson();
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_NANOS = 300_000_000_000L;
    private static BufferedWriter writer;
    private static CompressedDebugOutput output;
    private static long bytes, started, sequence, nextStep, records;
    private static final Map<String, String> lastSteps = new HashMap<>();
    private static final Map<String, Long> steps = new HashMap<>();
    private static final Map<String, String> lastConfigs = new HashMap<>();
    private static final Map<String, Vector3d> solverVelocity = new HashMap<>();
    private static final Map<String, Vector3d> solverAngular = new HashMap<>();
    private static final Map<String, Double> solverMass = new HashMap<>();
    private static final Map<String, Long> hookCounts = new HashMap<>();
    private static final Map<String, String> profiles = new HashMap<>();

    private ForceDiagnostics() {}
    public static boolean enabled() { return writer != null; }

    static void start(Path path) throws IOException {
        close();
        output = new CompressedDebugOutput(path);
        writer = output.writer;
        started = System.nanoTime(); bytes = 0; sequence = 0; nextStep = 0; records = 0;
        event(null, "session", "schema", "pmaero-force-audit-2", "version", "1.0",
                "units", "wind=mph; velocity=blocks/s; angular=rad/s; mass=Sable kpg; impulse=kpg*blocks/s; local torque impulse=kpg*blocks^2/s",
                "limits", "300 wall seconds or approximately 64 MiB compressed per output; partial final substep possible",
                "coverage", "Selected and unselected profile faces; actual sampler decisions; submitted Java impulses, not native contact impulses. Optional Offroad hooks report only when hit.");
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object list = modList.getMethod("get").invoke(null);
            Object mods = modList.getMethod("getMods").invoke(list);
            if (mods instanceof Iterable<?> iterable) for (Object mod : iterable) {
                event(null, "mod", "id", call(mod, "getModId"), "version", String.valueOf(call(mod, "getVersion")));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            event(null, "unavailable", "feature", "mod_versions", "error", e.toString());
        }
    }

    static void close() {
        if (writer != null) {
            try {
                writer.write(JSON.toJson(Map.of("type", "trace_end", "records", records, "hookCounts", hookCounts)));
                writer.newLine();
            } catch (IOException ignored) { }
            finally { try { writer.close(); } catch (IOException ignored) { } }
            writer = null;
            output = null;
        }
        lastSteps.clear(); steps.clear(); lastConfigs.clear(); solverVelocity.clear();
        solverAngular.clear(); solverMass.clear(); hookCounts.clear(); profiles.clear();
    }

    static void flush() throws IOException { if (writer != null) writer.flush(); }

    public static void serverTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (enabled() && System.nanoTime() - started >= MAX_NANOS) {
            PMWeatherAeronautics.LOGGER.info("PMAero winddebug stopped at five minutes.");
            WindDebugFile.clearSession();
        }
    }

    /** All arguments are scalars, vectors converted below, arrays, or strings: never serialize game objects. */
    public static void event(ServerSubLevel body, String type, Object... fields) {
        if (!enabled()) return;
        if (body != null && !WindDebugFile.accepts(String.valueOf(body.getUniqueId()))) return;
        try {
            if (System.nanoTime() - started >= MAX_NANOS || output.bytes() >= MAX_BYTES) {
                PMWeatherAeronautics.LOGGER.info("PMAero winddebug automatically stopped at the time/size limit.");
                WindDebugFile.clearSession(); return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", ++sequence); row.put("type", type);
            if (body != null) {
                row.put("body", String.valueOf(body.getUniqueId()));
                row.put("dimension", body.getLevel().dimension().location().toString());
                row.put("tick", body.getLevel().getGameTime()); row.put("step", steps.get(key(body)));
            }
            for (int i = 0; i + 1 < fields.length; i += 2) row.put(String.valueOf(fields[i]), safe(fields[i + 1]));
            String line = JSON.toJson(row);
            writer.write(line); writer.newLine();
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1; records++;
        } catch (IOException | RuntimeException e) {
            PMWeatherAeronautics.LOGGER.warn("PMAero diagnostic recording stopped after observer failure", e);
            WindDebugFile.clearSession();
        }
    }

    private static Object safe(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) return Double.isFinite(n.doubleValue()) ? n : n.toString();
        if (value instanceof Vector3dc v) return safe(new double[]{v.x(), v.y(), v.z()});
        if (value instanceof Vec3 v) return safe(new double[]{v.x, v.y, v.z});
        if (value.getClass().isArray()) {
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) result.add(safe(Array.get(value, i)));
            return result;
        }
        return value.toString();
    }

    private static String key(ServerSubLevel b) { return b.getLevel().dimension().location() + "/" + b.getUniqueId(); }

    static void profile(ServerSubLevel body, AeroSurfaceCache.AerodynamicProfile profile,
                        java.util.List<AeroSurfaceCache.ProfileFace> selected) {
        if (!enabled() || !WindDebugFile.accepts(String.valueOf(body.getUniqueId()))) return;
        event(body, "profile_selection", "cachedPatchCount", profile.samples().size(), "selectedCount", selected.size(), "cacheSalt", profile.cacheSalt());
        String signature = profile.cacheSalt() + ":" + profile.samples().hashCode();
        if (signature.equals(profiles.put(key(body), signature))) return;
        for (var face : profile.samples()) event(body, "profile_inventory", "localPoint", face.point(), "localSampleAnchor", face.sampleAnchor(), "anchorSource", face.anchorSource(), "localNormal", face.normal(), "area", face.weight(), "sourceFootprints", face.sourceFootprints().toString());
        inventory(body);
    }

    private static void inventory(ServerSubLevel body) {
        if (!enabled()) return;
        int count = 0;
        try {
            for (var holder : body.getPlot().getLoadedChunks()) {
                var chunk = holder.getChunk();
                if (chunk == null || chunk.isEmpty()) continue;
                var sections = chunk.getSections();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    var section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) continue;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        if (!enabled()) return;
                        var state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        if (++count > 4096) { event(body, "inventory_truncated", "limit", 4096); return; }
                        var pos = new net.minecraft.core.BlockPos(chunk.getPos().getMinBlockX() + x, (chunk.getSectionYFromSectionIndex(sectionIndex) << 4) + y, chunk.getPos().getMinBlockZ() + z);
                        event(body, "block_inventory", "plotBlockPos", pos.toShortString(), "state", state.toString(),
                                "blockMass", dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper.getMass(body.getLevel(), pos, state),
                                "collisionBoxesBlockLocal", state.getCollisionShape(body.getLevel(), pos).toAabbs().toString());
                    }
                }
            }
            event(body, "inventory_complete", "blocks", count);
        } catch (RuntimeException e) { event(body, "observer_error", "phase", "inventory", "error", e.toString()); }
    }

    static void face(ServerSubLevel body, String decision, AeroSurfaceCache.ProfileFace face, Vec3 point, Vec3 normal, double margin) {
        if (!enabled()) return;
        try {
            Vec3 probe = point.add(normal.scale(margin));
            var blockPos = net.minecraft.core.BlockPos.containing(probe.x, probe.y, probe.z);
            boolean loaded = body.getLevel().isLoaded(blockPos);
            event(body, "face_geometry", "decision", decision, "localPoint", face.point(), "localNormal", face.normal(), "rawArea", face.weight(),
                    "worldSurfaceAnchor", point, "localSampleAnchor", face.sampleAnchor(), "anchorSource", face.anchorSource(),
                    "worldNormal", normal, "samplePosition", probe, "terrainProbe", probe,
                    "probeLoaded", loaded, "probeBlock", loaded ? body.getLevel().getBlockState(blockPos).toString() : "unloaded");
        } catch (RuntimeException e) { event(body, "observer_error", "phase", "face_geometry", "error", e.toString()); }
    }

    static void pressure(ServerSubLevel body, WeatherWindField.WindSample sample, String decision,
                         Vector3dc actualRelative, double normalSpeed, double threshold, double area,
                         double rawMagnitude, Vector3dc patchImpulseWorld, double dt, double massDamping) {
        if (!enabled()) return;
        try {
            var handle = RigidBodyHandle.of(body);
            if (handle == null || body.getMassTracker().isInvalid()) return;
            Vector3d center = body.logicalPose().transformPosition(new Vector3d(body.getMassTracker().getCenterOfMass()));
            Vec3 app = sample.applicationPosition();
            Vector3d arm = new Vector3d(app.x, app.y, app.z).sub(center);
            Vector3d rotational = handle.getAngularVelocity(new Vector3d()).cross(arm);
            Vector3d pointRelative = new Vector3d(sample.wind().x, sample.wind().y, sample.wind().z).mul(0.44704)
                    .sub(handle.getLinearVelocity(new Vector3d())).sub(rotational);
            event(body, "pressure_decision", "decision", decision, "role", sample.surfaceRole(), "samplePosition", sample.samplePosition(),
                    "applicationPosition", app, "normalWorld", sample.outwardNormal(), "rawWindMph", sample.wind(),
                    "actualRelativeWind", actualRelative, "rotationalPointVelocity", rotational, "diagnosticPointRelativeWind", pointRelative,
                    "normalSpeed", normalSpeed, "thresholdPhysics", threshold, "rawArea", sample.areaWeight(), "effectiveArea", area,
                    "massDamping", massDamping, "dt", dt, "rawImpulseMagnitude", rawMagnitude, "afterPatchCapWorldImpulse", patchImpulseWorld,
                    "note", "Point-relative diagnostic is NOT used by solver. Patch impulse is BEFORE group and net caps.");
        } catch (RuntimeException e) { event(body, "observer_error", "phase", "pressure", "error", e.toString()); }
    }

    /** Called before the first actor/provider in a substep; repeated calls in that substep are no-ops. */
    public static void begin(SubLevelPhysicsSystem system) {
        if (!enabled()) return;
        try {
            var container = SubLevelContainer.getContainer(system.getLevel());
            if (container == null) return;
            String stamp = system.getLevel().getGameTime() + ":" + system.getPartialPhysicsTick();
            for (ServerSubLevel body : container.getAllSubLevels()) {
                if (body.isRemoved() || !WindDebugFile.accepts(String.valueOf(body.getUniqueId()))) continue;
                String key = key(body);
                if (stamp.equals(lastSteps.get(key))) continue;
                lastSteps.put(key, stamp); steps.put(key, ++nextStep);
                snapshot(body, "before_actors", 0);
                Map<String, Object> config = new java.util.TreeMap<>();
                for (Method method : Config.class.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                            && method.getReturnType() != void.class
                            && (method.getReturnType().isPrimitive() || method.getReturnType() == String.class)) {
                        method.setAccessible(true); config.put(method.getName(), method.invoke(null));
                    }
                }
                for (String name : new String[]{"sableWindMult", "sableInertiaMod"}) {
                    config.put("pmweather." + name, dev.protomanly.pmweather.config.ServerConfig.class.getField(name).get(null));
                }
                String encoded = JSON.toJson(config);
                if (!encoded.equals(lastConfigs.put(key, encoded))) event(body, "config", "valuesJson", encoded);
            }
        } catch (ReflectiveOperationException | RuntimeException e) { event(null, "observer_error", "phase", "begin", "error", e.toString()); }
    }

    public static void snapshot(ServerSubLevel body, String phase, double dt) {
        if (!enabled() || body.isRemoved() || !WindDebugFile.accepts(String.valueOf(body.getUniqueId()))) return;
        try {
            var mass = body.getMassTracker();
            var handle = RigidBodyHandle.of(body);
            if (handle == null || mass == null || mass.isInvalid()) { event(body, "snapshot_unavailable", "phase", phase); return; }
            Vector3d velocity = handle.getLinearVelocity(new Vector3d());
            Vector3d angular = handle.getAngularVelocity(new Vector3d());
            Vector3d center = body.logicalPose().transformPosition(new Vector3d(mass.getCenterOfMass()));
            var rotation = body.logicalPose().orientation();
            var inertia = mass.getInertiaTensor();
            Vector3d gravity = DimensionPhysicsData.getGravity(body.getLevel(), center, new Vector3d());
            event(body, "motion", "phase", phase, "dt", dt, "mass", mass.getMass(),
                    "worldCOM", center, "localCOM", mass.getCenterOfMass(), "worldVelocity", velocity, "worldAngularVelocity", angular,
                    "quaternionXYZW", new double[]{rotation.x(), rotation.y(), rotation.z(), rotation.w()},
                    "localInertiaColumnMajor", new double[]{inertia.m00(),inertia.m01(),inertia.m02(),inertia.m10(),inertia.m11(),inertia.m12(),inertia.m20(),inertia.m21(),inertia.m22()},
                    "gravity", gravity, "universalDrag", DimensionPhysicsData.getUniversalDrag(body.getLevel()),
                    "relativeAirPressure", DimensionPhysicsData.getAirPressure(body.getLevel(), center),
                    "liftProviders", body.getPlot().getLiftProviders().size());
            String key = key(body);
            if (phase.equals("before_solver")) {
                solverVelocity.put(key, velocity); solverAngular.put(key, angular); solverMass.put(key, mass.getMass());
            } else if (phase.equals("after_solver")) {
                Vector3d before = solverVelocity.remove(key), beforeAngular = solverAngular.remove(key);
                Double beforeMass = solverMass.remove(key);
                if (before != null && beforeAngular != null && dt > 0 && beforeMass != null && beforeMass == mass.getMass()) {
                    event(body, "solver_delta", "dt", dt,
                            "worldDeltaVelocity", new Vector3d(velocity).sub(before),
                            "worldDeltaAngularVelocity", new Vector3d(angular).sub(beforeAngular),
                            "worldAccelerationMinusGravity", new Vector3d(velocity).sub(before).div(dt).sub(gravity),
                            "interpretation", "Solver interval only: contacts, constraints, damping and any pipeline forces; NOT a measured tire/contact force.");
                }
            }
        } catch (RuntimeException e) { event(body, "observer_error", "phase", phase, "error", e.toString()); }
    }

    public static void afterPhysics(SubLevelPhysicsSystem system, double dt) {
        if (!enabled()) return;
        try {
            var container = SubLevelContainer.getContainer(system.getLevel());
            if (container != null) for (ServerSubLevel body : container.getAllSubLevels()) snapshot(body, "after_solver", dt);
        } catch (RuntimeException e) { event(null, "observer_error", "phase", "after_solver", "error", e.toString()); }
    }

    public static void beforeSolver(SubLevelPhysicsSystem system) {
        if (!enabled()) return;
        try {
            var container = SubLevelContainer.getContainer(system.getLevel());
            if (container != null) for (ServerSubLevel body : container.getAllSubLevels()) snapshot(body, "before_solver", 0);
        } catch (RuntimeException e) { event(null, "observer_error", "phase", "before_solver", "error", e.toString()); }
    }

    public static void submitted(PhysicsPipelineBody target, String kind, Vector3dc linear, Vector3dc angular,
                                 Vector3dc point, boolean wake) {
        if (!enabled() || !(target instanceof ServerSubLevel body)) return;
        try {
            hookCounts.merge(kind, 1L, Long::sum);
            String caller = StackWalker.getInstance().walk(stream -> stream
                    .filter(f -> !f.getClassName().contains("ForceDiagnostics") && !f.getMethodName().contains("pmaero"))
                    .limit(9).map(f -> f.getClassName() + "." + f.getMethodName()).collect(java.util.stream.Collectors.joining(" <- ")));
            event(body, "submitted", "kind", kind, "linear", linear, "angular", angular, "localPoint", point, "wakeRequested", wake, "caller", caller);
        } catch (RuntimeException e) { event(body, "observer_error", "phase", "submitted", "error", e.toString()); }
    }

    /** Optional Offroad hook: captures actual queued values, without recomputing or applying forces. */
    public static void wheel(Object target, ServerSubLevel body, double dt, boolean applied) {
        if (!enabled()) return;
        try {
            if (!(target instanceof BlockEntity block)) return;
            hookCounts.merge("offroad_wheel", 1L, Long::sum);
            Object strength = field(target, "strength");
            Object localPoint = field(target, "queuedForcePos");
            event(body, "wheel", "blockPos", block.getBlockPos().toShortString(), "blockState", block.getBlockState().toString(),
                    "dt", dt, "reachedImpulseSubmission", applied,
                    "queuedLocalImpulse", applied ? field(target, "queuedForce") : null,
                    "localApplicationPoint", applied ? localPoint : null,
                    "suspensionStrength", call(strength, "getValue"), "touchingFriction", applied ? field(target, "touchingFriction") : null,
                    "extension", field(target, "extension"), "wheelYawRadians", field(target, "chasingYaw"),
                    "kineticRPM", call(target, "getSpeed"), "tire", String.valueOf(call(target, "getHeldItem")),
                    "brakeSignal", block.getLevel() == null ? null : block.getLevel().getSignal(block.getBlockPos().above(), Direction.UP),
                    "note", "No-submission rows may be tire absent or no contact; correlate wheel_terrain. Fields unavailable on a different Offroad version are explicit.");
            if (applied && localPoint instanceof Vector3dc point && call(strength, "getValue") instanceof Number strengthValue
                    && field(target, "touchingFriction") instanceof Number frictionValue && field(target, "chasingYaw") instanceof Number yawValue) {
                var facing = block.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
                Vector3d side = facing.getAxis() == Direction.Axis.X ? new Vector3d(1,0,0) : new Vector3d(0,0,1);
                Vector3d rolling = facing.getAxis() == Direction.Axis.X ? new Vector3d(0,0,1) : new Vector3d(1,0,0);
                side.rotateY(yawValue.doubleValue()); rolling.rotateY(yawValue.doubleValue());
                Vector3d localVelocity = body.logicalPose().transformNormalInverse(Sable.HELPER.getVelocity(block.getLevel(), point, new Vector3d()));
                double effectiveMass = 1.0 / body.getMassTracker().getInverseNormalMass(point, new Vector3d(0,1,0));
                double strengthMul = 20 * Math.min(effectiveMass, strengthValue.doubleValue());
                double friction = frictionValue.doubleValue();
                double brake = block.getLevel().getSignal(block.getBlockPos().above(), Direction.UP) / 15.0;
                event(body, "wheel_components", "blockPos", block.getBlockPos().toShortString(), "localPointVelocity", localVelocity,
                        "effectiveNormalMass", effectiveMass, "strengthMultiplier", strengthMul, "sideAxisLocal", side, "rollingAxisLocal", rolling,
                        "predictedSideResistanceImpulse", -localVelocity.dot(side) * 0.6 * friction * strengthMul * dt,
                        "predictedRollingResistanceImpulse", -localVelocity.dot(rolling) * (0.075 + brake * 0.3) * Math.min(friction,1) * strengthMul * dt,
                        "note", "Observer reconstruction from inspected Offroad formula, NOT additional applied impulses. Actual combined impulse is in wheel and submitted rows.");
            }
        } catch (RuntimeException e) { event(body, "observer_error", "phase", "wheel", "error", e.toString()); }
    }

    public static void wheelTerrain(Object target, Object result) {
        if (!enabled() || !(target instanceof BlockEntity block) || block.getLevel() == null || block.getLevel().isClientSide) return;
        try {
            var containing = Sable.HELPER.getContaining(block);
            if (!(containing instanceof ServerSubLevel body)) return;
            Object pos = call(result, "minInteractingBlock");
            event(body, "wheel_terrain", "blockPos", block.getBlockPos().toShortString(), "maxExtension", call(result, "maxExtension"),
                    "hitNormal", String.valueOf(call(result, "normal")), "hitBlockPos", String.valueOf(pos),
                    "hitBlockState", pos instanceof net.minecraft.core.BlockPos p ? block.getLevel().getBlockState(p).toString() : null,
                    "hitSublevel", call(result, "subLevel") instanceof ServerSubLevel hit ? String.valueOf(hit.getUniqueId()) : null);
        } catch (RuntimeException e) { event(null, "observer_error", "phase", "wheel_terrain", "error", e.toString()); }
    }

    private static Object field(Object object, String name) {
        if (object == null) return "unavailable";
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException e) { /* try superclass */ }
            catch (ReflectiveOperationException | RuntimeException e) { return "unavailable:" + e.getClass().getSimpleName(); }
        }
        return "unavailable:" + name;
    }

    private static Object call(Object object, String name) {
        if (object == null) return "unavailable";
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Method m = type.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(object); }
            catch (NoSuchMethodException e) { /* try superclass */ }
            catch (ReflectiveOperationException | RuntimeException e) { return "unavailable:" + e.getClass().getSimpleName(); }
        }
        return "unavailable:" + name;
    }
}
