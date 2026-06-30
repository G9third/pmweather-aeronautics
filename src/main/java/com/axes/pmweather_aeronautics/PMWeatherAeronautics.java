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

@Mod(PMWeatherAeronautics.MODID)
public final class PMWeatherAeronautics {
    public static final String MODID = "pmweather_aeronautics";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PMWeatherAeronautics(final IEventBus modBus, final ModContainer modContainer) {
        backupOutdatedCommonConfigIfNeeded();
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Sable fires this once for each physics sub-step, which is the right time to add impulses.
        SableEventPlatform.INSTANCE.onPhysicsTick(WeatherForceApplier::onSablePrePhysicsTick);

        NeoForge.EVENT_BUS.addListener(DebugWindCommand::register);
        NeoForge.EVENT_BUS.addListener(DebugWindCommand::onServerTick);
    }

    private static void backupOutdatedCommonConfigIfNeeded() {
        final Path configFile = FMLPaths.CONFIGDIR.get().resolve(MODID + "-common.toml");

        if (!Files.isRegularFile(configFile)) {
            return;
        }

        final String contents;
        try {
            contents = Files.readString(configFile);
        } catch (final IOException exception) {
            LOGGER.warn("Could not read PMWeather Aeronautics config for 0.7.3 reset check: {}", configFile, exception);
            return;
        }

        if (!looksLikePreRealisticWindConfig(contents)) {
            LOGGER.debug("PMWeather Aeronautics config does not look like an older reset target. Leaving it untouched.");
            return;
        }

        final Path backupFile = nextConfigResetBackupPath(configFile);
        try {
            Files.move(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("PMWeather Aeronautics 0.7.3 moved an older 0.7.x config to {}. A fresh wind-0.1 config will be generated.", backupFile.getFileName());
        } catch (final IOException exception) {
            LOGGER.warn("Could not move old PMWeather Aeronautics config to {}. Delete {} manually if the 0.7.3 config does not regenerate cleanly.", backupFile, configFile, exception);
        }
    }

    private static boolean looksLikePreRealisticWindConfig(final String contents) {
        if (contents.contains("PMWeather Aeronautics config schema: 0.7.3 wind-0.1 edge-case caps")) {
            return false;
        }

        return containsAny(contents,
                // 0.5.x / 0.6.x removed or renamed settings.
                "turbulenceMultiplier",
                "surfaceShearFactor",
                "surfaceTorqueFactor",
                "surfaceDifferentialThresholdRatio",
                "aerodynamicProfileResolution",
                "aerodynamicProfileFullTorqueInertia",
                "aerodynamicProfileMinTorqueScale",
                "aerodynamicProfileMaxTorqueImpulse",
                "aerodynamicProfileMinTorqueInertia",
                "maxSurfaceWindSamples",
                "minSurfaceWindSamplesWhenBudgeted",
                "aerodynamicProfileStrength",
                "surfaceAreaWeightStrength",
                // 0.7.0 / 0.7.1 generated config comment before the internal mph-to-block/second conversion.
                "PMWeather 0.16 tornado wind commonly reaches roughly 30-80+ in its own vector units",
                "1.0 = realistic baseline, 2.0 = twice realistic strength",
                "This is a physical impulse overshoot limiter, not small-object damping.",
                // 0.7.2 generated config comments before the 0.7.3 reset.
                "0.1 is the default because Sable mass is a lightweight gameplay scale",
                "0.5 keeps some protection against feedback/overshoot while leaving normal wind response mostly controlled by windInfluence"
        );
    }

    private static boolean containsAny(final String contents, final String... needles) {
        for (final String needle : needles) {
            if (contents.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Path nextConfigResetBackupPath(final Path configFile) {
        final Path directory = configFile.getParent();
        final String baseName = MODID + "-common.pre-0_7_3-wind-0_1-reset.toml.bak";
        Path candidate = directory.resolve(baseName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        candidate = directory.resolve(MODID + "-common.pre-0_7_3-wind-0_1-reset." + System.currentTimeMillis() + ".toml.bak");
        return candidate;
    }
}
