package com.axes.pmweather_aeronautics;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Local client preference for ambient particle wind. */
public final class ParticleWindConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("particleWind");
        ENABLED = builder.comment("Let nearby detached visual particles drift with PMWeather wind.")
            .define("enabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ParticleWindConfig() {}
}
