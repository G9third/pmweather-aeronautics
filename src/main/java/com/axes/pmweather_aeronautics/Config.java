package com.axes.pmweather_aeronautics;
import net.neoforged.neoforge.common.ModConfigSpec;
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    static {
        BUILDER.comment("PMWeather Aeronautics config schema: 0.9.0b cleanup-2tick");
        BUILDER.push("general");
    }
    public static final ModConfigSpec.DoubleValue WIND_THRESHOLD = BUILDER
            .comment("Minimum PMWeather wind-vector magnitude before effects are applied. This stays in PMWeather/mph-style units even though physics wind is converted internally.")
            .translation("pmweather_aeronautics.configuration.windThreshold")
            .defineInRange("windThreshold", 8.0D, 0.0D, 300.0D);
    public static final ModConfigSpec.BooleanValue ENABLE_TORNADO_SUCTION = BUILDER
            .comment("Allow ProtoManly's Weather native tornadic wind vectors, including their real vertical component, in sampled wind.")
            .translation("pmweather_aeronautics.configuration.enableTornadoSuction")
            .define("enableTornadoSuction", true);
    static {
        BUILDER.pop();
        BUILDER.push("airflow_lift");
    }
    public static final ModConfigSpec.BooleanValue ENABLE_AIRFLOW_LIFT = BUILDER
            .comment("Inject local PMWeather wind into Sable lift/drag calculations. Nearby compatible lift providers may share a component-local PMWeather probe, but every provider still keeps its own Sable lift/drag calculation at its own position.")
            .translation("pmweather_aeronautics.configuration.enableAirflowLift")
            .define("enableAirflowLift", true);
    public static final ModConfigSpec.DoubleValue AIRFLOW_INFLUENCE = BUILDER
            .comment("How much local PMWeather wind is treated as aerodynamic airflow for Sable lift providers. 1.0 = realistic converted PMWeather wind speed.")
            .translation("pmweather_aeronautics.configuration.airflowInfluence")
            .defineInRange("airflowInfluence", 1.0D, 0.0D, 40.0D);
    public static final ModConfigSpec.IntValue AIRFLOW_WIND_SAMPLE_INTERVAL_TICKS = BUILDER
            .comment("How often component-local lift-provider airflow probes ask PMWeather for fresh wind, in game ticks. Cached wind is reused between updates.")
            .translation("pmweather_aeronautics.configuration.airflowWindSampleIntervalTicks")
            .defineInRange("airflowWindSampleIntervalTicks", 2, 1, 200);
    public static final ModConfigSpec.IntValue AIRFLOW_REGION_EDGE_BLOCKS = BUILDER
            .comment("Target edge length, in lift-provider blocks, for component-local airflow probe regions before the global wind budget applies extra LOD. 1 approaches one probe per provider; 4 groups nearby compatible lift providers while every provider still keeps its own Sable force calculation.")
            .translation("pmweather_aeronautics.configuration.airflowRegionEdgeBlocks")
            .defineInRange("airflowRegionEdgeBlocks", 4, 1, 32);
    public static final ModConfigSpec.IntValue MAX_AIRFLOW_SAMPLES_PER_OBJECT = BUILDER
            .comment("Soft maximum target number of PMWeather airflow probes for one Sable sub-level before the global sample budget is divided between body patches and airflow. Per-component minimum representation may raise the target above this value when needed. This limits queries only; it does not remove lift providers or their Sable lift/drag forces.")
            .translation("pmweather_aeronautics.configuration.maxAirflowSamplesPerObject")
            .defineInRange("maxAirflowSamplesPerObject", 32, 1, 8192);
    public static final ModConfigSpec.IntValue MIN_AIRFLOW_SAMPLES_PER_COMPONENT = BUILDER
            .comment("Minimum target probes per connected, orientation/signature-compatible lift-provider component while budget permits. 1 keeps each separate lift-provider component represented before extra detail is allocated.")
            .translation("pmweather_aeronautics.configuration.minAirflowSamplesPerComponent")
            .defineInRange("minAirflowSamplesPerComponent", 1, 1, 64);
    public static final ModConfigSpec.DoubleValue MIN_AIRFLOW_SAMPLE_RATIO = BUILDER
            .comment("Minimum desired airflow-probe ratio relative to discovered lift providers before the global hard budget applies. 0.5 means 10 lift providers target at least 5 PMWeather probes. This changes atmospheric sampling density only; every provider still performs its own Sable lift/drag calculation.")
            .translation("pmweather_aeronautics.configuration.minAirflowSampleRatio")
            .defineInRange("minAirflowSampleRatio", 0.5D, 0.0D, 1.0D);
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
            .comment("Main whole-body wind strength after PMWeather mph-style wind is converted to block/second physics speed. Default 0.06 is tuned for the quadratic surface-pressure solver and Sable's kpg block mass scale. Tornado wind still ramps with wind speed squared, but normal 10-15 mph wind is calmer than the 0.7.5e 0.1 default.")
            .translation("pmweather_aeronautics.configuration.windInfluence")
            .defineInRange("windInfluence", 0.06D, 0.0D, 100.0D);
    public static final ModConfigSpec.DoubleValue MASS_SCALING = BUILDER
            .comment("Optional extra mass damping for body wind. 0.0 = no extra damping, 1.0 = strongest damping. Sable physics already handles real mass.")
            .translation("pmweather_aeronautics.configuration.massScaling")
            .defineInRange("massScaling", 0.0D, 0.0D, 1.0D);
    public static final ModConfigSpec.DoubleValue AERO_PATCH_PRESSURE_STRENGTH = BUILDER
            .comment("Strength of the quadratic exterior surface-pressure solver. This multiplies dynamic wind pressure after PMWeather mph-style wind is converted to physics speed.")
            .translation("pmweather_aeronautics.configuration.aeroPatchPressureStrength")
            .defineInRange("aeroPatchPressureStrength", 1.0D, 0.0D, 5.0D);
    public static final ModConfigSpec.DoubleValue AERO_PATCH_AREA_WEIGHT_STRENGTH = BUILDER
            .comment("How strongly exterior patch area affects body wind force. 1.0 = physical area weighting, so larger exposed faces catch more wind. 0.0 = equal patch weighting for old tuning.")
            .translation("pmweather_aeronautics.configuration.aeroPatchAreaWeightStrength")
            .defineInRange("aeroPatchAreaWeightStrength", 1.0D, 0.0D, 1.0D);
    public static final ModConfigSpec.DoubleValue MAX_IMPULSE_PER_SUBSTEP = BUILDER
            .comment("High safety cap on the linear impulse applied to a sub-level during each Sable physics substep. This is intended to catch extreme spikes, not tune normal wind strength.")
            .translation("pmweather_aeronautics.configuration.maxImpulsePerSubstep")
            .defineInRange("maxImpulsePerSubstep", 2000.0D, 0.0D, 100000.0D);
    public static final ModConfigSpec.DoubleValue MAX_AIR_RELATIVE_VELOCITY_CORRECTION_PER_SUBSTEP = BUILDER
            .comment("Maximum fraction of the current air-relative normal velocity that body wind pressure may correct in one Sable physics substep. Default 0.5 keeps protection against feedback/overshoot while leaving normal wind strength controlled by windInfluence.")
            .translation("pmweather_aeronautics.configuration.maxAirRelativeVelocityCorrectionPerSubstep")
            .defineInRange("maxAirRelativeVelocityCorrectionPerSubstep", 0.5D, 0.0D, 1.0D);
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
            .defineInRange("differentialPressureTorqueStrength", 0.6D, 0.0D, 2.0D);
    public static final ModConfigSpec.DoubleValue MAX_DIFFERENTIAL_TORQUE_IMPULSE = BUILDER
            .comment("High safety cap for added uneven-pressure torque impulse per Sable physics substep. This is intended to catch extreme spin spikes while leaving normal uneven-pressure torque mostly unmodified.")
            .translation("pmweather_aeronautics.configuration.maxDifferentialTorqueImpulse")
            .defineInRange("maxDifferentialTorqueImpulse", 1000.0D, 0.0D, 100000.0D);
    static {
        BUILDER.pop();
        BUILDER.push("aero_patch_sampling");
    }
    public static final ModConfigSpec.IntValue MAX_AERO_PATCH_SAMPLES_PER_OBJECT = BUILDER
            .comment("Maximum full-surface aero patches from one Sable object that may request fresh PMWeather wind during one body wind update when the global budget allows it.")
            .translation("pmweather_aeronautics.configuration.maxAeroPatchSamplesPerObject")
            .defineInRange("maxAeroPatchSamplesPerObject", 32, 0, 8192);
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
        BUILDER.push("performance");
    }
    public static final ModConfigSpec.IntValue BODY_WIND_SAMPLE_INTERVAL_TICKS = BUILDER
            .comment("How often each Sable sub-level asks PMWeather for body-push wind samples, in game ticks. Cached raw wind is reused between samples and Sable physics substeps. Default 2 = ten fresh updates per second at 20 TPS.")
            .translation("pmweather_aeronautics.configuration.bodyWindSampleIntervalTicks")
            .defineInRange("bodyWindSampleIntervalTicks", 2, 1, 200);
    public static final ModConfigSpec.IntValue MAX_WIND_SAMPLES_PER_TICK = BUILDER
            .comment("Global safety budget for fresh PMWeather wind queries per server tick. Body exterior patches and component-local lift-provider airflow probes share this budget, reduce spatial detail fairly, deduplicate exact coordinates, then fall back to cached/zero wind if the hard limit is still reached.")
            .translation("pmweather_aeronautics.configuration.maxWindSamplesPerTick")
            .defineInRange("maxWindSamplesPerTick", 128, 16, 100000);
    public static final ModConfigSpec.DoubleValue EDGE_WIND_SAMPLE_MARGIN = BUILDER
            .comment("Distance outside each exposed body face where PMWeather wind is sampled. Increase only if nearby contraption blocks interfere with exterior sampling.")
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
        return intValue(AIRFLOW_WIND_SAMPLE_INTERVAL_TICKS, 2);
    }
    public static int airflowRegionEdgeBlocks() {
        return intValue(AIRFLOW_REGION_EDGE_BLOCKS, 4);
    }
    public static int maxAirflowSamplesPerObject() {
        return intValue(MAX_AIRFLOW_SAMPLES_PER_OBJECT, 32);
    }
    public static int minAirflowSamplesPerComponent() {
        return intValue(MIN_AIRFLOW_SAMPLES_PER_COMPONENT, 1);
    }
    public static double minAirflowSampleRatio() {
        return doubleValue(MIN_AIRFLOW_SAMPLE_RATIO, 0.5D);
    }
    public static boolean enableBodyPush() {
        return booleanValue(ENABLE_BODY_PUSH, true);
    }
    public static boolean enableBodyRelativeWindDrag() {
        return booleanValue(ENABLE_BODY_RELATIVE_WIND_DRAG, true);
    }
    public static double windInfluence() {
        return doubleValue(WIND_INFLUENCE, 0.06D);
    }
    public static double massScaling() {
        return doubleValue(MASS_SCALING, 0.0D);
    }
    public static double aeroPatchPressureStrength() {
        return doubleValue(AERO_PATCH_PRESSURE_STRENGTH, 1.0D);
    }
    public static double aeroPatchAreaWeightStrength() {
        return doubleValue(AERO_PATCH_AREA_WEIGHT_STRENGTH, 1.0D);
    }
    public static double maxImpulsePerSubstep() {
        return doubleValue(MAX_IMPULSE_PER_SUBSTEP, 2000.0D);
    }
    public static double maxAirRelativeVelocityCorrectionPerSubstep() {
        return doubleValue(MAX_AIR_RELATIVE_VELOCITY_CORRECTION_PER_SUBSTEP, 0.5D);
    }
    public static boolean enableDifferentialPressureTorque() {
        return booleanValue(ENABLE_DIFFERENTIAL_PRESSURE_TORQUE, true);
    }
    public static double differentialPressureTorqueStrength() {
        return doubleValue(DIFFERENTIAL_PRESSURE_TORQUE_STRENGTH, 0.6D);
    }
    public static double maxDifferentialTorqueImpulse() {
        return doubleValue(MAX_DIFFERENTIAL_TORQUE_IMPULSE, 1000.0D);
    }
    public static int maxAeroPatchSamplesPerObject() {
        return intValue(MAX_AERO_PATCH_SAMPLES_PER_OBJECT, 32);
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
    public static int bodyWindSampleIntervalTicks() {
        return intValue(BODY_WIND_SAMPLE_INTERVAL_TICKS, 2);
    }
    public static int maxWindSamplesPerTick() {
        return intValue(MAX_WIND_SAMPLES_PER_TICK, 128);
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
