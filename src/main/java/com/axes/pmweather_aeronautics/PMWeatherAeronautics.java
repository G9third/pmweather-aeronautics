package com.axes.pmweather_aeronautics;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
@Mod(PMWeatherAeronautics.MODID)
public final class PMWeatherAeronautics {
    public static final String MODID = "pmweather_aeronautics";
    public static final Logger LOGGER = LogUtils.getLogger();
    public PMWeatherAeronautics(final IEventBus modBus, final ModContainer modContainer) {
        PMWeatherForceGroups.register(modBus);
        modBus.addListener(WindMonitorNetwork::registerPayloads);
        modBus.addListener(ParticleWindNetwork::registerPayloads);
        migrateAdaptiveBatchConfigTo090bIfNeeded();
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ParticleWindConfig.SPEC);
        // Sable fires this once for each physics sub-step, which is the right time to add impulses.
        SableEventPlatform.INSTANCE.onPhysicsTick(WeatherForceApplier::onSablePrePhysicsTick);
        AeroObserver.install(modContainer);
        AerowindTest.registerEvents();
        NeoForge.EVENT_BUS.addListener(WindMonitorNetwork::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ParticleWindNetwork::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(ParticleWindNetwork::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ParticleWindNetwork::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PMWeatherAeronautics::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(PMWeatherAeronautics::onServerStopped);
    }
    private static void migrateAdaptiveBatchConfigTo090bIfNeeded() {
        final Path configFile = FMLPaths.CONFIGDIR.get().resolve(MODID + "-common.toml");
        if (!Files.isRegularFile(configFile)) {
            return;
        }
        final String contents;
        try {
            contents = Files.readString(configFile);
        } catch (final IOException exception) {
            LOGGER.warn("Could not read PMWeather Aeronautics config for 0.9.0b migration check: {}", configFile, exception);
            return;
        }
        if (!contents.contains("PMWeather Aeronautics config schema: 0.8.2b adaptive-airflow-batch")) {
            return;
        }

        String migrated = contents.replace(
                "PMWeather Aeronautics config schema: 0.8.2b adaptive-airflow-batch",
                "PMWeather Aeronautics config schema: 0.9.0b cleanup-2tick"
        );
        // 1 was the old generated default. 0.9.0b intentionally promotes the default to 2 ticks,
        // matching airflow. This one-time schema migration changes only that old default value.
        migrated = migrated.replaceAll(
                "(?m)^([ \\t]*)bodyWindSampleIntervalTicks[ \\t]*=[ \\t]*1[ \\t]*$",
                "$1bodyWindSampleIntervalTicks = 2"
        );
        // These two settings belonged to compatibility/fallback paths that are no longer present.
        migrated = migrated.replaceAll("(?m)^[ \\t]*maxFallbackSurfaceWindSamples[ \\t]*=.*(?:\\R|$)", "");
        migrated = migrated.replaceAll("(?m)^[ \\t]*enableEdgeWindSampling[ \\t]*=.*(?:\\R|$)", "");
        try {
            // Keep the exact pre-migration config even if writing is interrupted.
            Files.copy(configFile, nextConfigResetBackupPath(configFile));
            Files.writeString(configFile, migrated);
            LOGGER.info("PMWeather Aeronautics 0.9.0b migrated the adaptive config in place: body and airflow now both default to 2-tick refresh, and obsolete fallback settings were removed.");
        } catch (final IOException exception) {
            LOGGER.warn("Could not migrate PMWeather Aeronautics config to 0.9.0b. Existing settings will be left untouched: {}", configFile, exception);
        }
    }

    private static Path nextConfigResetBackupPath(final Path configFile) {
        final Path directory = configFile.getParent();
        final String baseName = MODID + "-common.pre-0_8_2b.toml.bak";
        Path candidate = directory.resolve(baseName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        candidate = directory.resolve(MODID + "-common.pre-0_8_2b." + System.currentTimeMillis() + ".toml.bak");
        return candidate;
    }
    private static void clearPhysicsCaches() {
        WeatherForceApplier.clearSession();
        PhysicsTickWindBatch.clearSession();
        WeatherWindField.clearSession();
        AeroSurfaceCache.clearSession();
    }
    private static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel) {
            clearPhysicsCaches();
            AeroObserver.clearSession();
        }
    }
    private static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        clearPhysicsCaches();
        AeroObserver.clearSession();
    }

}
