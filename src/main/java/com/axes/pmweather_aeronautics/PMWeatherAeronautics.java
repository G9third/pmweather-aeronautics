package com.axes.pmweather_aeronautics;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(PMWeatherAeronautics.MODID)
public final class PMWeatherAeronautics {
    public static final String MODID = "pmweather_aeronautics";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PMWeatherAeronautics(final IEventBus modBus, final ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Sable fires this once for each physics substep, which is the correct place to add impulses.
        SableEventPlatform.INSTANCE.onPhysicsTick(WeatherForceApplier::onSablePrePhysicsTick);
    }
}
