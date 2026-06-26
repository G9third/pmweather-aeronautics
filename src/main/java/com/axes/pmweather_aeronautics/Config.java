package com.axes.pmweather_aeronautics;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("general");
    }

    public static final ModConfigSpec.DoubleValue WIND_THRESHOLD = BUILDER
            .comment("Minimum PMWeather wind-vector magnitude before effects are applied. PMWeather's own units are used.")
            .translation("pmweather_aeronautics.configuration.windThreshold")
            .defineInRange("windThreshold", 8.0D, 0.0D, 300.0D);

    public static final ModConfigSpec.BooleanValue ENABLE_TORNADO_SUCTION = BUILDER
            .comment("Allow ProtoManly's Weather tornado/suction effects in the sampled wind vector.")
            .translation("pmweather_aeronautics.configuration.enableTornadoSuction")
            .define("enableTornadoSuction", true);

    static {
        BUILDER.pop();
        BUILDER.push("airflow_lift");
    }

    public static final ModConfigSpec.BooleanValue ENABLE_AIRFLOW_LIFT = BUILDER
            .comment("Inject local PMWeather wind into Sable lift/drag calculations so each lift provider sees airflow from its own block position.")
            .translation("pmweather_aeronautics.configuration.enableAirflowLift")
            .define("enableAirflowLift", true);

    public static final ModConfigSpec.DoubleValue AIRFLOW_INFLUENCE = BUILDER
            .comment("How much local PMWeather wind is treated as aerodynamic airflow for Sable lift providers. 1.0 = full sampled wind.")
            .translation("pmweather_aeronautics.configuration.airflowInfluence")
            .defineInRange("airflowInfluence", 1.0D, 0.0D, 40.0D);

    public static final ModConfigSpec.IntValue AIRFLOW_WIND_SAMPLE_INTERVAL_TICKS = BUILDER
            .comment("How often each local lift-provider wind sample asks PMWeather for a fresh airflow value, in game ticks. Cached wind is reused between samples.")
            .translation("pmweather_aeronautics.configuration.airflowWindSampleIntervalTicks")
            .defineInRange("airflowWindSampleIntervalTicks", 10, 1, 200);

    static {
        BUILDER.pop();
        BUILDER.push("body_wind");
    }

    public static final ModConfigSpec.BooleanValue ENABLE_BODY_PUSH = BUILDER
            .comment("Apply exterior aerodynamic pressure to the whole Sable sub-level. Disable for pure lift/drag integration.")
            .translation("pmweather_aeronautics.configuration.enableBodyPush")
            .define("enableBodyPush", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BODY_RELATIVE_WIND_DRAG = BUILDER
            .comment("If true, body aero pressure uses real air-relative wind by subtracting the Sable body's linear velocity from PMWeather wind. This prevents bodies from being accelerated past the local air speed in one direction.")
            .translation("pmweather_aeronautics.configuration.enableBodyRelativeWindDrag")
            .define("enableBodyRelativeWindDrag", true);

    public static final ModConfigSpec.DoubleValue WIND_INFLUENCE = BUILDER
            .comment("Whole-body wind pressure multiplier. PMWeather 0.16 tornado wind commonly reaches roughly 30-80+ in its own vector units.")
            .translation("pmweather_aeronautics.configuration.windInfluence")
            .defineInRange("windInfluence", 2.0D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue MASS_SCALING = BUILDER
            .comment("Optional extra mass damping for body wind. 0.0 = no extra damping, 1.0 = strongest damping. Sable physics already handles real mass.")
            .translation("pmweather_aeronautics.configuration.massScaling")
            .defineInRange("massScaling", 0.0D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue AERO_PATCH_PRESSURE_STRENGTH = BUILDER
            .comment("Strength of 0.7 full exterior surface-patch pressure. This replaces the old aerodynamicProfileStrength config name.")
            .translation("pmweather_aeronautics.configuration.aeroPatchPressureStrength")
            .defineInRange("aeroPatchPressureStrength", 1.0D, 0.0D, 5.0D);

    public static final ModConfigSpec.DoubleValue AERO_PATCH_AREA_WEIGHT_STRENGTH = BUILDER
            .comment("How strongly larger exterior patches receive more wind-sample weight during patch LOD. 0.0 = equal patches, 1.0 = fully area-weighted.")
            .translation("pmweather_aeronautics.configuration.aeroPatchAreaWeightStrength")
            .defineInRange("aeroPatchAreaWeightStrength", 0.65D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue MAX_IMPULSE_PER_SUBSTEP = BUILDER
            .comment("Safety cap on the linear impulse applied to a sub-level during each Sable physics substep.")
            .translation("pmweather_aeronautics.configuration.maxImpulsePerSubstep")
            .defineInRange("maxImpulsePerSubstep", 900.0D, 0.0D, 100000.0D);

    public static final ModConfigSpec.DoubleValue MAX_AIR_RELATIVE_VELOCITY_CORRECTION_PER_SUBSTEP = BUILDER
            .comment("Maximum fraction of the current air-relative normal velocity that body wind pressure may correct in one Sable physics substep. This is a physical impulse overshoot limiter, not small-object damping.")
            .translation("pmweather_aeronautics.configuration.maxAirRelativeVelocityCorrectionPerSubstep")
            .defineInRange("maxAirRelativeVelocityCorrectionPerSubstep", 0.25D, 0.0D, 1.0D);

    static {
        BUILDER.pop();
        BUILDER.push("differential_torque");
    }

    public static final ModConfigSpec.BooleanValue ENABLE_DIFFERENTIAL_PRESSURE_TORQUE = BUILDER
            .comment("Allow real uneven-pressure aerodynamic torque from differences between exterior patch pressures. Uniform side pressure still uses a stable pressure line through the center of mass, but pressure variation across patches may add capped rotational impulse so airborne objects do not look stale.")
            .translation("pmweather_aeronautics.configuration.enableDifferentialPressureTorque")
            .define("enableDifferentialPressureTorque", true);

    public static final ModConfigSpec.DoubleValue DIFFERENTIAL_PRESSURE_TORQUE_STRENGTH = BUILDER
            .comment("Strength multiplier for uneven-pressure torque. 0.0 disables the extra torque; 1.0 uses the full measured patch-pressure residual. Defaults below 1.0 keep small objects lively without reintroducing old sparse-sample spin.")
            .translation("pmweather_aeronautics.configuration.differentialPressureTorqueStrength")
            .defineInRange("differentialPressureTorqueStrength", 0.45D, 0.0D, 2.0D);

    public static final ModConfigSpec.DoubleValue MAX_DIFFERENTIAL_TORQUE_IMPULSE = BUILDER
            .comment("Safety cap for added uneven-pressure torque impulse per Sable physics substep. This only caps the differential torque residual; normal Sable/Rapier inertia still handles final rotation.")
            .translation("pmweather_aeronautics.configuration.maxDifferentialTorqueImpulse")
            .defineInRange("maxDifferentialTorqueImpulse", 180.0D, 0.0D, 100000.0D);

    static {
        BUILDER.pop();
        BUILDER.push("aero_patch_sampling");
    }

    public static final ModConfigSpec.IntValue MAX_AERO_PATCH_SAMPLES_PER_OBJECT = BUILDER
            .comment("Maximum full-surface aero patches from one Sable object that may request fresh PMWeather wind during one body wind update when the global budget allows it.")
            .translation("pmweather_aeronautics.configuration.maxAeroPatchSamplesPerObject")
            .defineInRange("maxAeroPatchSamplesPerObject", 512, 0, 8192);

    public static final ModConfigSpec.DoubleValue MIN_AERO_PATCH_DETAIL_PERCENT = BUILDER
            .comment("Minimum percent of a full-resolution exterior patch set to preserve when smart patch LOD is forced by the wind sample budget. 0.05 means a 400-patch ship may merge down to about 20 representative patches.")
            .translation("pmweather_aeronautics.configuration.minAeroPatchDetailPercent")
            .defineInRange("minAeroPatchDetailPercent", 0.05D, 0.0D, 1.0D);

    public static final ModConfigSpec.IntValue MIN_AERO_PATCH_COUNT = BUILDER
            .comment("Absolute minimum representative patch count for smart patch LOD on compact objects. 6 keeps simple test objects represented by their main pressure directions while the percent floor protects large ships.")
            .translation("pmweather_aeronautics.configuration.minAeroPatchCount")
            .defineInRange("minAeroPatchCount", 6, 1, 256);

    public static final ModConfigSpec.IntValue MAX_CACHED_AERO_PATCHES = BUILDER
            .comment("Maximum number of full-resolution exterior patches cached per Sable object after greedy face merging. If a structure has more raw patches, tiny low-importance regions are merged more aggressively during cache build.")
            .translation("pmweather_aeronautics.configuration.maxCachedAeroPatches")
            .defineInRange("maxCachedAeroPatches", 4096, 64, 32768);

    static {
        BUILDER.pop();
        BUILDER.push("tornado_updraft");
    }

    public static final ModConfigSpec.BooleanValue ENABLE_TORNADO_UPDRAFT_MODEL = BUILDER
            .comment("Add a Sable-specific tornado updraft model on top of PMWeather's raw horizontal tornado wind. PMWeather 0.16's WindEngine usually returns little/no Y wind for normal supercell tornadoes, so enable this for realistic debris lift.")
            .translation("pmweather_aeronautics.configuration.enableTornadoUpdraftModel")
            .define("enableTornadoUpdraftModel", true);

    public static final ModConfigSpec.DoubleValue TORNADO_UPDRAFT_THRESHOLD = BUILDER
            .comment("Horizontal PMWeather wind speed where the Sable-specific tornado updraft starts. Set above normal storm winds so regular weather remains mostly horizontal.")
            .translation("pmweather_aeronautics.configuration.tornadoUpdraftThreshold")
            .defineInRange("tornadoUpdraftThreshold", 35.0D, 0.0D, 300.0D);

    public static final ModConfigSpec.DoubleValue TORNADO_UPDRAFT_STRENGTH = BUILDER
            .comment("How much upward wind is added from tornado-strength horizontal PMWeather wind. Updraft = (horizontalSpeed - threshold) * this value, capped by maxTornadoUpdraft.")
            .translation("pmweather_aeronautics.configuration.tornadoUpdraftStrength")
            .defineInRange("tornadoUpdraftStrength", 0.35D, 0.0D, 5.0D);

    public static final ModConfigSpec.DoubleValue MAX_TORNADO_UPDRAFT = BUILDER
            .comment("Maximum added upward wind from the Sable-specific tornado updraft model, in PMWeather wind-vector units.")
            .translation("pmweather_aeronautics.configuration.maxTornadoUpdraft")
            .defineInRange("maxTornadoUpdraft", 30.0D, 0.0D, 300.0D);

    public static final ModConfigSpec.DoubleValue TORNADO_UPDRAFT_LIFT_HEIGHT = BUILDER
            .comment("Approximate vertical distance in blocks over which tornado updraft remains strong after an object first enters tornado-strength wind. Above this per-object lift zone, updraft fades out instead of carrying objects upward forever.")
            .translation("pmweather_aeronautics.configuration.tornadoUpdraftLiftHeight")
            .defineInRange("tornadoUpdraftLiftHeight", 28.0D, 0.0D, 256.0D);

    public static final ModConfigSpec.DoubleValue TORNADO_UPDRAFT_HEIGHT_NOISE = BUILDER
            .comment("Per-object variation in tornado lift height, in blocks. This keeps different structures from orbiting at the exact same altitude while remaining smooth/stable instead of random jitter.")
            .translation("pmweather_aeronautics.configuration.tornadoUpdraftHeightNoise")
            .defineInRange("tornadoUpdraftHeightNoise", 16.0D, 0.0D, 256.0D);

    public static final ModConfigSpec.DoubleValue TORNADO_UPDRAFT_FADE_START_RATIO = BUILDER
            .comment("Fraction of the per-object lift height where updraft starts fading out. 0.65 means lift is mostly full for the lower 65%, then smoothly drops to zero near the object's lift ceiling.")
            .translation("pmweather_aeronautics.configuration.tornadoUpdraftFadeStartRatio")
            .defineInRange("tornadoUpdraftFadeStartRatio", 0.65D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue TORNADO_GUST_STRENGTH = BUILDER
            .comment("Low-frequency coherent gust strength added during tornado-strength wind, as a fraction of horizontal PMWeather wind speed. This is smooth noise, not per-tick random jitter.")
            .translation("pmweather_aeronautics.configuration.tornadoGustStrength")
            .defineInRange("tornadoGustStrength", 0.15D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue TORNADO_VERTICAL_GUST_STRENGTH = BUILDER
            .comment("Vertical part of the coherent tornado gust noise, as a fraction of the computed updraft. Lower values avoid bouncy motion.")
            .translation("pmweather_aeronautics.configuration.tornadoVerticalGustStrength")
            .defineInRange("tornadoVerticalGustStrength", 0.14D, 0.0D, 1.0D);

    public static final ModConfigSpec.IntValue TORNADO_GUST_SCALE_TICKS = BUILDER
            .comment("How slowly tornado gust noise changes over time. Higher = smoother/slower gusts. This does not increase PMWeather query cost.")
            .translation("pmweather_aeronautics.configuration.tornadoGustScaleTicks")
            .defineInRange("tornadoGustScaleTicks", 55, 1, 1200);

    public static final ModConfigSpec.DoubleValue TORNADO_GUST_SPATIAL_SCALE = BUILDER
            .comment("Spatial scale for coherent tornado gust noise in blocks. Higher = nearby sample points get more similar gusts, reducing jitter on small structures.")
            .translation("pmweather_aeronautics.configuration.tornadoGustSpatialScale")
            .defineInRange("tornadoGustSpatialScale", 40.0D, 1.0D, 512.0D);

    static {
        BUILDER.pop();
        BUILDER.push("performance");
    }

    public static final ModConfigSpec.IntValue BODY_WIND_SAMPLE_INTERVAL_TICKS = BUILDER
            .comment("How often each Sable sub-level asks PMWeather for body-push wind samples, in game ticks. Cached raw wind is reused between samples and Sable physics substeps. 1 = every tick, 5 = four times per second.")
            .translation("pmweather_aeronautics.configuration.bodyWindSampleIntervalTicks")
            .defineInRange("bodyWindSampleIntervalTicks", 5, 1, 200);

    public static final ModConfigSpec.IntValue MAX_WIND_SAMPLES_PER_TICK = BUILDER
            .comment("Global safety budget for PMWeather wind queries per server tick. The patch sampler divides work fairly between active Sable objects, uses smart patch LOD, then falls back to cached wind if the hard budget is still reached.")
            .translation("pmweather_aeronautics.configuration.maxWindSamplesPerTick")
            .defineInRange("maxWindSamplesPerTick", 512, 16, 100000);

    public static final ModConfigSpec.IntValue MAX_FALLBACK_SURFACE_WIND_SAMPLES = BUILDER
            .comment("Maximum exterior fallback wind samples used by legacy compatibility sampling paths. Main 0.7 body aerodynamics use maxAeroPatchSamplesPerObject instead.")
            .translation("pmweather_aeronautics.configuration.maxFallbackSurfaceWindSamples")
            .defineInRange("maxFallbackSurfaceWindSamples", 12, 0, 64);

    public static final ModConfigSpec.BooleanValue ENABLE_EDGE_WIND_SAMPLING = BUILDER
            .comment("Enable extra fallback PMWeather samples around the sub-level roof and edges. The main 0.7 path still uses exterior aero patches.")
            .translation("pmweather_aeronautics.configuration.enableEdgeWindSampling")
            .define("enableEdgeWindSampling", true);

    public static final ModConfigSpec.DoubleValue EDGE_WIND_SAMPLE_MARGIN = BUILDER
            .comment("Distance outside the sub-level bounding box used for fallback roof and edge wind samples.")
            .translation("pmweather_aeronautics.configuration.edgeWindSampleMargin")
            .defineInRange("edgeWindSampleMargin", 2.0D, 0.0D, 64.0D);

    static {
        BUILDER.pop();
        BUILDER.push("debug");
    }

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Log sampled wind and impulses occasionally. Very noisy when enabled.")
            .translation("pmweather_aeronautics.configuration.debugLogging")
            .define("debugLogging", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static double windThreshold() {
        return doubleValue(WIND_THRESHOLD, 8.0D);
    }

    public static boolean enableTornadoSuction() {
        return booleanValue(ENABLE_TORNADO_SUCTION, true);
    }

    public static boolean enableAirflowLift() {
        return booleanValue(ENABLE_AIRFLOW_LIFT, true);
    }

    public static double airflowInfluence() {
        return doubleValue(AIRFLOW_INFLUENCE, 1.0D);
    }

    public static int airflowWindSampleIntervalTicks() {
        return intValue(AIRFLOW_WIND_SAMPLE_INTERVAL_TICKS, 10);
    }

    public static boolean enableBodyPush() {
        return booleanValue(ENABLE_BODY_PUSH, true);
    }

    public static boolean enableBodyRelativeWindDrag() {
        return booleanValue(ENABLE_BODY_RELATIVE_WIND_DRAG, true);
    }

    public static double windInfluence() {
        return doubleValue(WIND_INFLUENCE, 2.0D);
    }

    public static double massScaling() {
        return doubleValue(MASS_SCALING, 0.0D);
    }

    public static double aeroPatchPressureStrength() {
        return doubleValue(AERO_PATCH_PRESSURE_STRENGTH, 1.0D);
    }

    public static double aeroPatchAreaWeightStrength() {
        return doubleValue(AERO_PATCH_AREA_WEIGHT_STRENGTH, 0.65D);
    }

    public static double maxImpulsePerSubstep() {
        return doubleValue(MAX_IMPULSE_PER_SUBSTEP, 900.0D);
    }

    public static double maxAirRelativeVelocityCorrectionPerSubstep() {
        return doubleValue(MAX_AIR_RELATIVE_VELOCITY_CORRECTION_PER_SUBSTEP, 0.25D);
    }

    public static boolean enableDifferentialPressureTorque() {
        return booleanValue(ENABLE_DIFFERENTIAL_PRESSURE_TORQUE, true);
    }

    public static double differentialPressureTorqueStrength() {
        return doubleValue(DIFFERENTIAL_PRESSURE_TORQUE_STRENGTH, 0.45D);
    }

    public static double maxDifferentialTorqueImpulse() {
        return doubleValue(MAX_DIFFERENTIAL_TORQUE_IMPULSE, 180.0D);
    }

    public static int maxAeroPatchSamplesPerObject() {
        return intValue(MAX_AERO_PATCH_SAMPLES_PER_OBJECT, 512);
    }

    public static double minAeroPatchDetailPercent() {
        return doubleValue(MIN_AERO_PATCH_DETAIL_PERCENT, 0.05D);
    }

    public static int minAeroPatchCount() {
        return intValue(MIN_AERO_PATCH_COUNT, 6);
    }

    public static int maxCachedAeroPatches() {
        return intValue(MAX_CACHED_AERO_PATCHES, 4096);
    }

    public static boolean enableTornadoUpdraftModel() {
        return booleanValue(ENABLE_TORNADO_UPDRAFT_MODEL, true);
    }

    public static double tornadoUpdraftThreshold() {
        return doubleValue(TORNADO_UPDRAFT_THRESHOLD, 35.0D);
    }

    public static double tornadoUpdraftStrength() {
        return doubleValue(TORNADO_UPDRAFT_STRENGTH, 0.35D);
    }

    public static double maxTornadoUpdraft() {
        return doubleValue(MAX_TORNADO_UPDRAFT, 30.0D);
    }

    public static double tornadoUpdraftLiftHeight() {
        return doubleValue(TORNADO_UPDRAFT_LIFT_HEIGHT, 28.0D);
    }

    public static double tornadoUpdraftHeightNoise() {
        return doubleValue(TORNADO_UPDRAFT_HEIGHT_NOISE, 16.0D);
    }

    public static double tornadoUpdraftFadeStartRatio() {
        return doubleValue(TORNADO_UPDRAFT_FADE_START_RATIO, 0.65D);
    }

    public static double tornadoGustStrength() {
        return doubleValue(TORNADO_GUST_STRENGTH, 0.15D);
    }

    public static double tornadoVerticalGustStrength() {
        return doubleValue(TORNADO_VERTICAL_GUST_STRENGTH, 0.14D);
    }

    public static int tornadoGustScaleTicks() {
        return intValue(TORNADO_GUST_SCALE_TICKS, 55);
    }

    public static double tornadoGustSpatialScale() {
        return doubleValue(TORNADO_GUST_SPATIAL_SCALE, 40.0D);
    }

    public static int bodyWindSampleIntervalTicks() {
        return intValue(BODY_WIND_SAMPLE_INTERVAL_TICKS, 5);
    }

    public static int maxWindSamplesPerTick() {
        return intValue(MAX_WIND_SAMPLES_PER_TICK, 512);
    }

    public static int maxFallbackSurfaceWindSamples() {
        return intValue(MAX_FALLBACK_SURFACE_WIND_SAMPLES, 12);
    }

    public static boolean enableEdgeWindSampling() {
        return booleanValue(ENABLE_EDGE_WIND_SAMPLING, true);
    }

    public static double edgeWindSampleMargin() {
        return doubleValue(EDGE_WIND_SAMPLE_MARGIN, 2.0D);
    }

    public static boolean debugLogging() {
        return booleanValue(DEBUG_LOGGING, false);
    }

    private static boolean booleanValue(final ModConfigSpec.BooleanValue value, final boolean fallback) {
        final Object raw = value.get();
        return raw instanceof Boolean bool ? bool : fallback;
    }

    private static int intValue(final ModConfigSpec.IntValue value, final int fallback) {
        final Object raw = value.get();
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static double doubleValue(final ModConfigSpec.DoubleValue value, final double fallback) {
        final Object raw = value.get();
        return raw instanceof Number number ? number.doubleValue() : fallback;
    }
}
