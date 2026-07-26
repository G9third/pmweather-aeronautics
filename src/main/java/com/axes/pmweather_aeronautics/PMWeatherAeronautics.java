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
        PMWeatherForceGroups.register(modBus);

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
            LOGGER.warn("Could not read PMWeather Aeronautics config for 0.8.2 reset check: {}", configFile, exception);
            return;
        }

        if (!looksLikePreRealisticWindConfig(contents)) {
            LOGGER.debug("PMWeather Aeronautics config does not look like an older reset target. Leaving it untouched.");
            return;
        }

        final Path backupFile = nextConfigResetBackupPath(configFile);
        try {
            Files.move(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("PMWeather Aeronautics 0.8.2 moved an older config to {}. A fresh tuned-sampling config will be generated.", backupFile.getFileName());
        } catch (final IOException exception) {
            LOGGER.warn("Could not move old PMWeather Aeronautics config to {}. Delete {} manually if the 0.8.2 config does not regenerate cleanly.", backupFile, configFile, exception);
        }
    }

    private static boolean looksLikePreRealisticWindConfig(final String contents) {
        if (contents.contains("PMWeather Aeronautics config schema: 0.8.2 tuned-sampling-defaults")) {
            return false;
        }

        if (contents.contains("[ground_drag]")
                || contents.contains("groundedHorizontalWindMultiplier")
                || contents.contains("groundLinearDragStrength")
                || contents.contains("groundAngularDragStrength")) {
            return true;
        }

        return containsAny(contents,
                // 0.8.0 / 0.8.1 generated configs before the 0.8.2 sampling defaults.
                "PMWeather Aeronautics config schema: 0.8.0 wind-0.06-quadratic-surface-wind",
                "maxAeroPatchSamplesPerObject = 512",
                "bodyWindSampleIntervalTicks = 5",
                "maxWindSamplesPerTick = 512",
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
                // 0.7.5e generated configs before 0.8.0 lowered body wind to 0.06.
                "PMWeather Aeronautics config schema: 0.7.5e wind-0.1-balanced-tornado-lift",
                "Default 0.1 is tuned for the quadratic surface-pressure solver",
                "windInfluence = 0.1",
                // 0.7.5d generated configs before balancing tornado lift for the new quadratic body pressure.
                "PMWeather Aeronautics config schema: 0.7.5d wind-0.005-quadratic-tornado-lift",
                "Default 0.005 is a gameplay-realistic value for Sable's kpg block mass scale",
                "tornadoUpdraftPressureStrength = 40.0",
                "windInfluence = 0.005",
                // 0.7.5c generated configs before realistic body wind and tornado updraft pressure strength.
                "PMWeather Aeronautics config schema: 0.7.5c quadratic-pressure-no-ground-drag",
                "Default 0.2 is tuned for Sable's lightweight gameplay mass scale",
                "windInfluence = 0.2",
                // 0.7.5b generated configs before quadratic pressure and before removing ground drag.
                "PMWeather Aeronautics config schema: 0.7.5b wind-0.2 updraft-0.5 grounded-horizontal-resistance",
                // 0.7.5a generated configs before grounded horizontal wind-force resistance.
                "PMWeather Aeronautics config schema: 0.7.5a wind-0.2 updraft-0.5 bottom-updraft-high-ground-drag",
                // 0.7.5 generated configs before the high-ground-drag reset.
                "PMWeather Aeronautics config schema: 0.7.5 wind-0.2 updraft-0.5 bottom-updraft-ground-drag",
                "groundLinearDragStrength = 3.0",
                "groundAngularDragStrength = 2.0",
                "maxGroundDragImpulsePerSubstep = 2000.0",
                "maxGroundDragTorqueImpulsePerSubstep = 1000.0",
                // 0.7.3d generated configs before the public 0.7.5 rename/reset.
                "PMWeather Aeronautics config schema: 0.7.3d wind-0.2 updraft-0.5 bottom-updraft-ground-drag",
                // 0.7.3c generated configs before grounded drag was added.
                "PMWeather Aeronautics config schema: 0.7.3c wind-0.2 updraft-0.5 bottom-updraft-solver",
                "bottom-updraft-solver",
                // 0.7.3b wind-0.2/updraft-0.5 generated configs before bottom-updraft solver reset.
                "PMWeather Aeronautics config schema: 0.7.3b wind-0.2 updraft-0.5 edge-case caps",
                "Default 0.5 gives stronger tornado lift than earlier 0.7.3 builds",
                // 0.7.3a wind-0.2/updraft-0.4 generated configs before the same-version 0.7.3b updraft reset.
                "PMWeather Aeronautics config schema: 0.7.3a wind-0.2 updraft-0.4 edge-case caps",
                "Default 0.4 gives slightly stronger tornado lift than 0.7.3 wind-0.1 builds",
                "tornadoUpdraftStrength = 0.4",
                // 0.7.3 wind-0.1 generated configs before the same-version 0.7.3a tuning reset.
                "PMWeather Aeronautics config schema: 0.7.3 wind-0.1 edge-case caps",
                "Default 0.1 is tuned for Sable's lightweight gameplay mass scale",
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
        final String baseName = MODID + "-common.pre-0_8_2.toml.bak";
        Path candidate = directory.resolve(baseName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        candidate = directory.resolve(MODID + "-common.pre-0_8_2." + System.currentTimeMillis() + ".toml.bak");
        return candidate;
    }
}
