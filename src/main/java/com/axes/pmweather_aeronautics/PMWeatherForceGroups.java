package com.axes.pmweather_aeronautics;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers PMWeather Aeronautics forces in Sable's force-group registry.
 *
 * Create Aeronautics / Sable simulated diagrams serialize queued force groups by registry id.
 * Using an ad-hoc new ForceGroup works for physics, but it is not encodable in diagram packets.
 */
final class PMWeatherForceGroups {
    private static final DeferredRegister<ForceGroup> FORCE_GROUPS = DeferredRegister.create(
            ForceGroups.REGISTRY_KEY,
            PMWeatherAeronautics.MODID
    );

    private static final DeferredHolder<ForceGroup, ForceGroup> WEATHER_WIND = FORCE_GROUPS.register(
            "weather_wind",
            () -> new ForceGroup(
                    Component.translatable("force_group.pmweather_aeronautics.weather_wind"),
                    Component.translatable("force_group.pmweather_aeronautics.weather_wind.description"),
                    0x5fa8ff,
                    true
            )
    );

    private PMWeatherForceGroups() {
    }

    static void register(final IEventBus modBus) {
        FORCE_GROUPS.register(modBus);
    }

    static ForceGroup weatherWind() {
        return WEATHER_WIND.get();
    }
}
