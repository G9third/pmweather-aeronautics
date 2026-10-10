package com.axes.pmweather_aeronautics;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Local client preference for PMWeather-IV custom-particle wind. */
public final class ParticleWindConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("particleWind");
        ENABLED = builder.comment("Let nearby PMWeather-IV custom particles drift with PMWeather wind.")
            .define("enabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ParticleWindConfig() {}
}
