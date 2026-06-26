package com.axes.pmweather_aeronautics;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class DebugWindCommand {
    private static final Set<UUID> LIVE_SAMPLE_MONITORS = new HashSet<>();

    private DebugWindCommand() {
    }

    public static void register(final RegisterCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        registerRoot(dispatcher, "pmaero");
    }

    public static void onServerTick(final ServerTickEvent.Post event) {
        if (LIVE_SAMPLE_MONITORS.isEmpty()) {
            return;
        }

        final MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) {
            return;
        }

        final Component message = Component.literal(formatSampleStatsActionBar(WeatherWindSampler.sampleStatsSnapshot()));
        final Iterator<UUID> iterator = LIVE_SAMPLE_MONITORS.iterator();
        while (iterator.hasNext()) {
            final UUID id = iterator.next();
            final ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                iterator.remove();
                continue;
            }
            player.displayClientMessage(message, true);
        }
    }

    private static void registerRoot(final CommandDispatcher<CommandSourceStack> dispatcher, final String root) {
        dispatcher.register(Commands.literal(root)
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal("wind")
                        .executes(context -> showWind(context.getSource())))
                .then(Commands.literal("samples")
                        .executes(context -> showSampleStats(context.getSource()))
                        .then(Commands.literal("live")
                                .executes(context -> toggleLiveSampleStats(context.getSource()))
                                .then(Commands.literal("on")
                                        .executes(context -> setLiveSampleStats(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setLiveSampleStats(context.getSource(), false))))
                        .then(Commands.literal("rate")
                                .executes(context -> showSampleStats(context.getSource()))))
                .then(Commands.literal("winddebug")
                        .executes(context -> WindDebugFile.status(context.getSource()))
                        .then(Commands.literal("start")
                                .executes(context -> WindDebugFile.start(context.getSource())))
                        .then(Commands.literal("stop")
                                .executes(context -> WindDebugFile.stop(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> WindDebugFile.status(context.getSource())))));
    }

    private static int showWind(final CommandSourceStack source) throws CommandSyntaxException {
        final ServerPlayer player = source.getPlayerOrException();
        final ServerLevel level = player.serverLevel();
        final Vec3 position = player.position();
        final Vec3 rawWind = WeatherWindSampler.sampleRawWindAt(level, position);

        final double horizontal = horizontalLength(rawWind);
        final double total = rawWind.length();
        final double updraftEstimate = estimateBridgeUpdraft(horizontal);
        final double estimatedFinalY = rawWind.y + updraftEstimate;

        source.sendSuccess(() -> Component.literal("PMWeather wind at your position"), false);
        source.sendSuccess(() -> Component.literal(format(
                "pos=(%.1f, %.1f, %.1f) raw=(x=%.3f, y=%.3f, z=%.3f)",
                position.x, position.y, position.z, rawWind.x, rawWind.y, rawWind.z)), false);
        source.sendSuccess(() -> Component.literal(format(
                "horizontal=%.3f total=%.3f direction=%s",
                horizontal, total, horizontalDirection(rawWind))), false);

        if (Config.enableTornadoUpdraftModel() && horizontal > Config.tornadoUpdraftThreshold()) {
            source.sendSuccess(() -> Component.literal(format(
                    "bridge tornado model: active, estimated extra updraft=%.3f, estimated final y=%.3f",
                    updraftEstimate, estimatedFinalY)), false);
        } else {
            source.sendSuccess(() -> Component.literal(format(
                    "bridge tornado model: inactive here, threshold=%.3f, enabled=%s",
                    Config.tornadoUpdraftThreshold(), Config.enableTornadoUpdraftModel())), false);
        }

        return 1;
    }

    private static int showSampleStats(final CommandSourceStack source) {
        final WeatherWindSampler.SampleStats stats = WeatherWindSampler.sampleStatsSnapshot();
        source.sendSuccess(() -> Component.literal("PMWeather Aeronautics sample monitor"), false);
        source.sendSuccess(() -> Component.literal(format(
                "last %.1fs: fresh=%d/s, requested=%d/s, cacheHits=%d/s, budgetFallbacks=%d/s, zeroFallbacks=%d/s",
                stats.rateSeconds(), stats.rateFreshQueries(), stats.rateRequestedSamples(), stats.rateCacheHits(),
                stats.rateBudgetFallbacks(), stats.rateZeroFallbacks())), false);
        source.sendSuccess(() -> Component.literal(format(
                "current tick: fresh=%d/%d, requested=%d, cacheHits=%d, budgetFallbacks=%d, activeObjects=%d",
                stats.currentFreshQueries(), stats.hardBudget(), stats.currentRequestedSamples(), stats.currentCacheHits(),
                stats.currentBudgetFallbacks(), stats.activeBodyObjectsThisTick())), false);
        source.sendSuccess(() -> Component.literal(format(
                "smart patch target: last=%d, min=%d, max=%d, perObjectMax=%d, detailFloor=%.1f%%/%d patches",
                stats.lastSurfaceSampleTarget(), stats.minSurfaceSampleTarget(), stats.maxSurfaceSampleTarget(),
                Config.maxAeroPatchSamplesPerObject(), Config.minAeroPatchDetailPercent() * 100.0D, Config.minAeroPatchCount())), false);
        source.sendSuccess(() -> Component.literal(
                "live updates: /pmaero samples live on  |  /pmaero samples live off"), false);
        return 1;
    }

    private static int toggleLiveSampleStats(final CommandSourceStack source) throws CommandSyntaxException {
        final ServerPlayer player = source.getPlayerOrException();
        return setLiveSampleStats(source, !LIVE_SAMPLE_MONITORS.contains(player.getUUID()));
    }

    private static int setLiveSampleStats(final CommandSourceStack source, final boolean enabled) throws CommandSyntaxException {
        final ServerPlayer player = source.getPlayerOrException();
        if (enabled) {
            LIVE_SAMPLE_MONITORS.add(player.getUUID());
            source.sendSuccess(() -> Component.literal("PMWeather Aeronautics live sample monitor enabled."), false);
            player.displayClientMessage(Component.literal(formatSampleStatsActionBar(WeatherWindSampler.sampleStatsSnapshot())), true);
        } else {
            LIVE_SAMPLE_MONITORS.remove(player.getUUID());
            source.sendSuccess(() -> Component.literal("PMWeather Aeronautics live sample monitor disabled."), false);
        }
        return 1;
    }

    private static String formatSampleStatsActionBar(final WeatherWindSampler.SampleStats stats) {
        return format(
                "PMWA patches: %d/%d fresh/tick | %d/s fresh | %d/s req | %d/s cached | %d/s capped | obj=%d | target=%d",
                stats.currentFreshQueries(), stats.hardBudget(), stats.rateFreshQueries(), stats.rateRequestedSamples(),
                stats.rateCacheHits(), stats.rateBudgetFallbacks(), stats.activeBodyObjectsThisTick(),
                stats.lastSurfaceSampleTarget());
    }

    private static double estimateBridgeUpdraft(final double horizontalSpeed) {
        if (!Config.enableTornadoUpdraftModel()) {
            return 0.0D;
        }

        final double threshold = Config.tornadoUpdraftThreshold();
        if (horizontalSpeed <= threshold) {
            return 0.0D;
        }

        return Math.min(Config.maxTornadoUpdraft(), (horizontalSpeed - threshold) * Config.tornadoUpdraftStrength());
    }

    private static double horizontalLength(final Vec3 vec) {
        return Math.sqrt(vec.x * vec.x + vec.z * vec.z);
    }

    private static String horizontalDirection(final Vec3 vec) {
        final double horizontal = horizontalLength(vec);
        if (horizontal <= 1.0e-6D) {
            return "calm";
        }

        final String eastWest = vec.x > 0.0D ? "east" : "west";
        final String northSouth = vec.z > 0.0D ? "south" : "north";
        final double ax = Math.abs(vec.x);
        final double az = Math.abs(vec.z);
        if (ax > az * 2.0D) {
            return "toward " + eastWest;
        }
        if (az > ax * 2.0D) {
            return "toward " + northSouth;
        }
        return "toward " + northSouth + "-" + eastWest;
    }

    private static String format(final String pattern, final Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
