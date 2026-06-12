package com.axes.pmweather_aeronautics;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue WIND_THRESHOLD = BUILDER
            .comment("Minimum PMWeather wind-vector magnitude before effects are applied. PMWeather's own units are used.")
            .translation("pmweather_aeronautics.configuration.windThreshold")
            .defineInRange("windThreshold", 35.0D, 0.0D, 300.0D);

    public static final ModConfigSpec.BooleanValue ENABLE_AIRFLOW_LIFT = BUILDER
            .comment("Inject PMWeather wind into Sable lift/drag calculations so wings see relative airflow.")
            .translation("pmweather_aeronautics.configuration.enableAirflowLift")
            .define("enableAirflowLift", true);

    public static final ModConfigSpec.DoubleValue AIRFLOW_INFLUENCE = BUILDER
            .comment("How much PMWeather wind is treated as aerodynamic airflow for Sable lift providers. 1.0 = full sampled wind.")
            .translation("pmweather_aeronautics.configuration.airflowInfluence")
            .defineInRange("airflowInfluence", 1.0D, 0.0D, 10.0D);

    public static final ModConfigSpec.BooleanValue ENABLE_BODY_PUSH = BUILDER
            .comment("Also push the whole Sable sub-level center of mass. Disable for pure lift/drag integration.")
            .translation("pmweather_aeronautics.configuration.enableBodyPush")
            .define("enableBodyPush", true);

    public static final ModConfigSpec.DoubleValue WIND_INFLUENCE = BUILDER
            .comment("Whole-body wind push multiplier. Only used when enableBodyPush is true.")
            .translation("pmweather_aeronautics.configuration.windInfluence")
            .defineInRange("windInfluence", 0.025D, 0.0D, 10.0D);

    public static final ModConfigSpec.DoubleValue MASS_SCALING = BUILDER
            .comment("How strongly mass dampens wind. 1.0 = linear, 0.5 = square-root curve.")
            .translation("pmweather_aeronautics.configuration.massScaling")
            .defineInRange("massScaling", 0.65D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue MAX_IMPULSE_PER_SUBSTEP = BUILDER
            .comment("Safety cap on the linear impulse applied to a sub-level during each Sable physics substep.")
            .translation("pmweather_aeronautics.configuration.maxImpulsePerSubstep")
            .defineInRange("maxImpulsePerSubstep", 60.0D, 0.0D, 100000.0D);

    public static final ModConfigSpec.DoubleValue TURBULENCE_MULTIPLIER = BUILDER
            .comment("Random angular impulse multiplier in high wind. 0 disables turbulence torque.")
            .translation("pmweather_aeronautics.configuration.turbulenceMultiplier")
            .defineInRange("turbulenceMultiplier", 0.008D, 0.0D, 10.0D);

    public static final ModConfigSpec.BooleanValue ENABLE_TORNADO_SUCTION = BUILDER
            .comment("Allow ProtoManly's Weather tornado/suction effects in the sampled wind vector.")
            .translation("pmweather_aeronautics.configuration.enableTornadoSuction")
            .define("enableTornadoSuction", true);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Log sampled wind and impulses occasionally. Very noisy when enabled.")
            .translation("pmweather_aeronautics.configuration.debugLogging")
            .define("debugLogging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
