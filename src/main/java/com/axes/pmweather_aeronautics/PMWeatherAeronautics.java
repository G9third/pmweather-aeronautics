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
import java.nio.file.StandardCopyOption;
import java.util.List;

@Mod(PMWeatherAeronautics.MODID)
public final class PMWeatherAeronautics {
    public static final String MODID = "pmweather_aeronautics";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final List<String> LEGACY_CONFIG_KEYS = List.of(
            "turbulenceMultiplier",
            "surfaceShearFactor",
            "surfaceTorqueFactor",
            "surfaceDifferentialThresholdRatio",
            "aerodynamicProfileResolution",
            "aerodynamicProfileFullTorqueInertia",
            "aerodynamicProfileMinTorqueScale",
            "aerodynamicProfileMaxTorqueImpulse",
            "aerodynamicProfileMinTorqueInertia",
            "minSurfaceWindSamplesWhenBudgeted",
            "aerodynamicProfileStrength",
            "surfaceAreaWeightStrength",
            "maxSurfaceWindSamples"
    );

    public PMWeatherAeronautics(final IEventBus modBus, final ModContainer modContainer) {
        backupLegacyCommonConfigIfNeeded();
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Sable fires this once for each physics sub-step, which is the right time to add impulses.
        SableEventPlatform.INSTANCE.onPhysicsTick(WeatherForceApplier::onSablePrePhysicsTick);

        NeoForge.EVENT_BUS.addListener(DebugWindCommand::register);
        NeoForge.EVENT_BUS.addListener(DebugWindCommand::onServerTick);
    }

    private static void backupLegacyCommonConfigIfNeeded() {
        final Path configFile = FMLPaths.CONFIGDIR.get().resolve(MODID + "-common.toml");
        if (!Files.isRegularFile(configFile)) {
            return;
        }

        final String contents;
        try {
            contents = Files.readString(configFile);
        } catch (final IOException exception) {
            LOGGER.warn("Could not read PMWeather Aeronautics config for 0.7 migration check: {}", configFile, exception);
            return;
        }

        boolean legacy = false;
        for (final String key : LEGACY_CONFIG_KEYS) {
            if (contents.contains(key)) {
                legacy = true;
                break;
            }
        }

        if (!legacy) {
            return;
        }

        final Path backupFile = nextLegacyBackupPath(configFile);
        try {
            Files.move(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("PMWeather Aeronautics 0.7 detected an older config and moved it to {}. A clean 0.7 config will be generated.", backupFile.getFileName());
        } catch (final IOException exception) {
            LOGGER.warn("Could not move old PMWeather Aeronautics config to {}. Delete {} manually if the 0.7 config does not regenerate cleanly.", backupFile, configFile, exception);
        }
    }

    private static Path nextLegacyBackupPath(final Path configFile) {
        final Path directory = configFile.getParent();
        final String baseName = MODID + "-common.legacy-0_6.toml.bak";
        Path candidate = directory.resolve(baseName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        candidate = directory.resolve(MODID + "-common.legacy-0_6." + System.currentTimeMillis() + ".toml.bak");
        return candidate;
    }
}
