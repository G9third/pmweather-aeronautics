package com.axes.pmweather_aeronautics;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

/**
 * Writes detailed Sable/PMWeather wind diagnostics to a fresh CSV file for each debug start.
 *
 * This is intentionally command-controlled instead of always-on. It can produce a lot of data,
 * but it makes the remaining rotation bug observable without adding any artificial damping or
 * changing physics behavior.
 */
final class WindDebugFile {
    private static final Path DEBUG_PATH = Paths.get("logs", "pmweather_aeronautics_sable_wind_debug.csv");
    private static BufferedWriter writer;
    private static boolean enabled;
    private static String sessionId = "none";
    private static long lastFlushTick = Long.MIN_VALUE;

    private WindDebugFile() {
    }

    static boolean isEnabled() {
        return enabled;
    }

    static Path path() {
        return DEBUG_PATH;
    }

    static int start(final CommandSourceStack source) {
        try {
            openWriter();
            enabled = true;
            sessionId = String.valueOf(System.currentTimeMillis());
            writeHeaderIfNeeded();
            writeRawLine("MARK," + sessionId + "," + Instant.now() + ",debug-start");
            flush();
            source.sendSuccess(() -> Component.literal("PMWeather Aeronautics wind debug file enabled: " + DEBUG_PATH), false);
            source.sendSuccess(() -> Component.literal("Reproduce the spin for 5-20 seconds, then run /pmaero winddebug stop and send the CSV."), false);
            return 1;
        } catch (final IOException e) {
            enabled = false;
            closeQuietly();
            source.sendFailure(Component.literal("Could not start PMWeather Aeronautics wind debug file: " + e.getMessage()));
            return 0;
        }
    }

    static int stop(final CommandSourceStack source) {
        if (!enabled && writer == null) {
            source.sendSuccess(() -> Component.literal("PMWeather Aeronautics wind debug file was not running."), false);
            return 1;
        }

        try {
            writeRawLine("MARK," + sessionId + "," + Instant.now() + ",debug-stop");
            flush();
        } catch (final IOException ignored) {
            // Closing below is more important than reporting a secondary write failure.
        }

        enabled = false;
        closeQuietly();
        source.sendSuccess(() -> Component.literal("PMWeather Aeronautics wind debug file stopped: " + DEBUG_PATH), false);
        return 1;
    }

    static int status(final CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("PMWeather Aeronautics wind debug file: " + (enabled ? "enabled" : "disabled")), false);
        source.sendSuccess(() -> Component.literal("Path: " + DEBUG_PATH), false);
        return 1;
    }

    static void recordObject(final long tick,
                             final String subLevelId,
                             final double timeStep,
                             final double mass,
                             final Vector3dc localCenterOfMass,
                             final Vector3dc worldCenterOfMass,
                             final Vector3dc linearVelocity,
                             final Vector3dc angularVelocity,
                             final int totalSamples,
                             final int appliedProfileSamples,
                             final int appliedCenter,
                             final double strongestSpeed,
                             final Vector3dc netLocalForce,
                             final Vector3dc netLocalTorque,
                             final int windwardSamples,
                             final int pressureGroups,
                             final WeatherWindSampler.SampleStats stats) {
        if (!enabled) {
            return;
        }

        try {
            writeCsv(
                    "object",
                    tick,
                    subLevelId,
                    "",
                    "",
                    timeStep,
                    mass,
                    localCenterOfMass.x(), localCenterOfMass.y(), localCenterOfMass.z(),
                    worldCenterOfMass.x(), worldCenterOfMass.y(), worldCenterOfMass.z(),
                    linearVelocity.x(), linearVelocity.y(), linearVelocity.z(), linearVelocity.length(),
                    angularVelocity.x(), angularVelocity.y(), angularVelocity.z(), angularVelocity.length(),
                    totalSamples,
                    appliedProfileSamples,
                    appliedCenter,
                    windwardSamples,
                    pressureGroups,
                    strongestSpeed,
                    netLocalForce.x(), netLocalForce.y(), netLocalForce.z(), netLocalForce.length(),
                    netLocalTorque.x(), netLocalTorque.y(), netLocalTorque.z(), netLocalTorque.length(),
                    stats.currentFreshQueries(), stats.hardBudget(), stats.currentRequestedSamples(), stats.currentCacheHits(),
                    stats.currentBudgetFallbacks(), stats.currentZeroFallbacks(), stats.activeBodyObjectsThisTick(),
                    stats.lastSurfaceSampleTarget(), stats.minSurfaceSampleTarget(), stats.maxSurfaceSampleTarget(),
                    Config.windInfluence(), Config.aeroPatchPressureStrength(), Config.windThreshold(),
                    Config.maxImpulsePerSubstep(), Config.maxAeroPatchSamplesPerObject(), Config.minAeroPatchCount()
            );
            flushOccasionally(tick);
        } catch (final IOException e) {
            disableAfterError(e);
        }
    }

    static void recordSample(final long tick,
                             final String subLevelId,
                             final int sampleIndex,
                             final WeatherWindSampler.WindSample sample,
                             final Vec3 finalWind,
                             final Vector3dc relativeWind,
                             final Vector3dc pressureVectorWorld,
                             final double profileThreshold,
                             final double surfaceSpeed,
                             final double shareWeight,
                             final double magnitude,
                             final Vector3dc localApplicationPoint,
                             final Vector3dc localPressureCenter,
                             final Vector3dc localImpulse) {
        if (!enabled) {
            return;
        }

        try {
            writeCsv(
                    "sample",
                    tick,
                    subLevelId,
                    sampleIndex,
                    roleName(sample.surfaceRole()),
                    sample.areaWeight(),
                    shareWeight,
                    profileThreshold,
                    surfaceSpeed,
                    magnitude,
                    sample.samplePosition().x, sample.samplePosition().y, sample.samplePosition().z,
                    sample.applicationPosition().x, sample.applicationPosition().y, sample.applicationPosition().z,
                    sample.pressureCenterPosition().x, sample.pressureCenterPosition().y, sample.pressureCenterPosition().z,
                    sample.outwardNormal().x, sample.outwardNormal().y, sample.outwardNormal().z,
                    sample.wind().x, sample.wind().y, sample.wind().z, sample.wind().length(),
                    finalWind.x, finalWind.y, finalWind.z, finalWind.length(),
                    relativeWind.x(), relativeWind.y(), relativeWind.z(), relativeWind.length(),
                    pressureVectorWorld.x(), pressureVectorWorld.y(), pressureVectorWorld.z(), pressureVectorWorld.length(),
                    localApplicationPoint.x(), localApplicationPoint.y(), localApplicationPoint.z(),
                    localPressureCenter.x(), localPressureCenter.y(), localPressureCenter.z(),
                    localImpulse.x(), localImpulse.y(), localImpulse.z(), localImpulse.length()
            );
        } catch (final IOException e) {
            disableAfterError(e);
        }
    }

    static void recordGroup(final long tick,
                            final String subLevelId,
                            final int role,
                            final int entryCount,
                            final Vector3dc localCenterOfMass,
                            final Vector3dc localPressureCenter,
                            final Vector3dc totalLocalImpulse,
                            final Vector3dc localTorque) {
        if (!enabled) {
            return;
        }

        try {
            writeCsv(
                    "side",
                    tick,
                    subLevelId,
                    roleName(role),
                    entryCount,
                    localCenterOfMass.x(), localCenterOfMass.y(), localCenterOfMass.z(),
                    localPressureCenter.x(), localPressureCenter.y(), localPressureCenter.z(),
                    totalLocalImpulse.x(), totalLocalImpulse.y(), totalLocalImpulse.z(), totalLocalImpulse.length(),
                    localTorque.x(), localTorque.y(), localTorque.z(), localTorque.length()
            );
        } catch (final IOException e) {
            disableAfterError(e);
        }
    }

    private static void openWriter() throws IOException {
        if (writer != null) {
            return;
        }
        final Path parent = DEBUG_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(
                DEBUG_PATH,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static void writeHeaderIfNeeded() throws IOException {
        if (!Files.exists(DEBUG_PATH) || Files.size(DEBUG_PATH) == 0L) {
            writeRawLine("# PMWeather Aeronautics Sable wind debug CSV");
            writeRawLine("# Rows are intentionally variable-width. rowType is the first column.");
            writeRawLine("# object rows: rowType,tick,subLevelId,... net force/torque and current sampling counters.");
            writeRawLine("# sample rows: rowType,tick,subLevelId,sampleIndex,side,... raw/final/relative wind and local impulse.");
            writeRawLine("# side rows: rowType,tick,subLevelId,side,... center-of-pressure impulse and local torque.");
        }
    }

    private static void writeCsv(final Object... columns) throws IOException {
        final StringBuilder builder = new StringBuilder(512);
        builder.append(sessionId);
        for (final Object column : columns) {
            builder.append(',');
            appendColumn(builder, column);
        }
        writeRawLine(builder.toString());
    }

    private static void appendColumn(final StringBuilder builder, final Object column) {
        if (column == null) {
            return;
        }
        if (column instanceof Number number) {
            builder.append(formatNumber(number.doubleValue()));
            return;
        }
        final String value = String.valueOf(column);
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            builder.append('"').append(value.replace("\"", "\"\"")).append('"');
        } else {
            builder.append(value);
        }
    }

    private static String formatNumber(final double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String roleName(final int role) {
        return switch (role) {
            case 1 -> "roof";
            case 2 -> "west";
            case 3 -> "east";
            case 4 -> "north";
            case 5 -> "south";
            case 6 -> "bottom";
            default -> role == 0 ? "center" : "role_" + role;
        };
    }

    private static void writeRawLine(final String line) throws IOException {
        openWriter();
        writer.write(line);
        writer.newLine();
    }

    private static void flushOccasionally(final long tick) throws IOException {
        if (tick != lastFlushTick && tick % 20L == 0L) {
            lastFlushTick = tick;
            flush();
        }
    }

    private static void flush() throws IOException {
        if (writer != null) {
            writer.flush();
        }
    }

    private static void disableAfterError(final IOException e) {
        enabled = false;
        closeQuietly();
        PMWeatherAeronautics.LOGGER.error("PMWeather Aeronautics wind debug file disabled after write failure", e);
    }

    private static void closeQuietly() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (final IOException ignored) {
        } finally {
            writer = null;
        }
    }
}
