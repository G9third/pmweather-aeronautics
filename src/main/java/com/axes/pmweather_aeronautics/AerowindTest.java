package com.axes.pmweather_aeronautics;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Temporary wind profiles for repeatable Aeronautics and PMIV vehicle checks. */
public final class AerowindTest {
    private static final double MAX_RADIUS_SQUARED = 256.0 * 256.0;
    private static Session session;
    private static volatile Field active;

    private AerowindTest() {}

    public static void registerEvents() {
        NeoForge.EVENT_BUS.addListener(AerowindTest::registerCommands);
        NeoForge.EVENT_BUS.addListener(AerowindTest::tick);
        NeoForge.EVENT_BUS.addListener(AerowindTest::stopping);
    }

    public static Vec3 sample(Level level, Vec3 point) {
        Field field = active;
        if (field == null || level != field.level || point == null
                || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || point.distanceToSqr(field.origin) > MAX_RADIUS_SQUARED) return null;
        return field.scenario.sample(point.subtract(field.origin), field.forward,
            field.tick / 20.0, field.phaseTicks / 20.0)
            .scale(WeatherFieldMath.phaseStrength(field.tick / 20.0,
                field.phaseTicks / 20.0, field.fadeSeconds));
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("aerowind").requires(source -> source.hasPermission(2));
        var test = Commands.literal("test").executes(c -> status(c.getSource()));
        test.then(Commands.literal("start")
            .executes(c -> start(c.getSource(), 15, false))
            .then(Commands.argument("secondsPerPhase", IntegerArgumentType.integer(5, 18))
                .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "secondsPerPhase"), false))));
        test.then(Commands.literal("stress")
            .executes(c -> start(c.getSource(), 15, true))
            .then(Commands.argument("secondsPerPhase", IntegerArgumentType.integer(5, 21))
                .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "secondsPerPhase"), true))));
        test.then(Commands.literal("stop").executes(c -> { stop("manual stop"); return status(c.getSource()); }));
        test.then(Commands.literal("status").executes(c -> status(c.getSource())));
        test.then(Commands.literal("next").executes(c -> {
            if (session == null) return fail(c.getSource(), "No wind test is active.");
            if (advance()) announcePhase();
            return 1;
        }));
        test.then(Commands.literal("list").executes(c -> list(c.getSource(), false)));
        test.then(Commands.literal("list").then(Commands.literal("stress").executes(c -> list(c.getSource(), true))));
        root.then(test);
        event.getDispatcher().register(root);
    }

    private static int start(CommandSourceStack source, int secondsPerPhase, boolean stress) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return fail(source, "Run the wind test in a world as a player.");
        int phaseCount = (stress ? WindScenario.STRESS : WindScenario.FLIGHT).size();
        if (secondsPerPhase * phaseCount > 300)
            return fail(source, "The complete test must fit within five minutes; reduce secondsPerPhase.");
        stop("replaced by a new test");
        session = new Session(player.serverLevel(), player.getUUID(), player.position(), heading(player), secondsPerPhase, stress);
        updateField();
        announce("Started " + (stress ? "stress" : "flight") + " sequence (" + clock(session.totalSeconds())
            + "). Use /aerowind live on to show wind and phase; /aerowind test stop to end.");
        announcePhase();
        return 1;
    }

    private static Vec3 heading(ServerPlayer player) {
        double yaw = Math.toRadians(player.getYRot());
        return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    private static void tick(ServerTickEvent.Pre event) {
        Session s = session;
        if (s == null || event.getServer() != s.level.getServer()) return;
        ServerPlayer owner = event.getServer().getPlayerList().getPlayer(s.owner);
        if (owner == null || owner.serverLevel() != s.level) { stop("operator left the test area"); return; }
        if (++s.tick >= s.phaseTicks) {
            if (advance()) announcePhase();
            return;
        }
        updateField();
        if (s.tick % 20 == 0) sendStatus();
    }

    private static boolean advance() {
        if (session.phase + 1 >= session.scenarios.size()) {
            stop("test completed");
            return false;
        }
        session.phase++;
        session.tick = 0;
        updateField();
        return true;
    }

    private static void updateField() {
        Session s = session;
        if (s == null) { active = null; return; }
        active = new Field(s.level, s.origin, s.forward, s.scenario(), s.tick, s.phaseTicks, Math.min(3.0, (s.phaseTicks / 20.0) / 4.0));
    }

    private static void announcePhase() {
        Session s = session;
        if (s == null) return;
        announce("Phase " + (s.phase + 1) + "/" + s.scenarios.size() + ": "
            + s.scenario().description() + " (" + (s.phaseTicks / 20) + "s).");
        sendStatus();
    }

    private static void sendStatus() {
        Session s = session;
        if (s == null) return;
        String phase = "TEST " + (s.phase + 1) + "/" + s.scenarios.size() + "  " + s.scenario().label();
        String progress = clock((s.phase * s.phaseTicks + s.tick) / 20) + "/" + clock(s.totalSeconds())
            + "  Next: " + (s.phase + 1 < s.scenarios.size() ? s.scenarios.get(s.phase + 1).label() : "finish");
        for (ServerPlayer player : s.level.players()) {
            if (player.position().distanceToSqr(s.origin) <= MAX_RADIUS_SQUARED || player.getUUID().equals(s.owner))
                WindMonitorNetwork.sendTestStatus(player, phase, progress);
        }
    }

    private static int status(CommandSourceStack source) {
        Session s = session;
        String message = s == null ? "Wind test idle. /aerowind test start [secondsPerPhase]; /aerowind test stress; /aerowind test list."
            : "Wind test " + (s.stress ? "stress" : "flight") + " " + (s.phase + 1) + "/" + s.scenarios.size()
                + ": " + s.scenario().label() + ", " + clock((s.phaseTicks - s.tick + 19) / 20) + "s left.";
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int list(CommandSourceStack source, boolean stress) {
        var scenarios = stress ? WindScenario.STRESS : WindScenario.FLIGHT;
        source.sendSuccess(() -> Component.literal((stress ? "Stress" : "Flight") + " wind test: "
            + scenarios.size() + " phases. Default total: " + clock(scenarios.size() * 15) + "."), false);
        for (int i = 0; i < scenarios.size(); i++) {
            String line = (i + 1) + ". " + scenarios.get(i).label() + " — " + scenarios.get(i).description();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return scenarios.size();
    }

    private static void announce(String text) {
        if (session != null) for (ServerPlayer player : session.level.players())
            player.sendSystemMessage(Component.literal("[Aerowind] " + text));
    }

    private static void stop(String reason) {
        Session old = session;
        active = null;
        if (old != null) {
            for (ServerPlayer player : old.level.players()) {
                player.sendSystemMessage(Component.literal("[Aerowind] Wind test stopped: " + reason + "."));
                WindMonitorNetwork.sendTestStatus(player, "", "");
            }
        }
        session = null;
    }

    private static void stopping(ServerStoppingEvent event) { stop("server stopping"); }
    private static int fail(CommandSourceStack source, String message) { source.sendFailure(Component.literal(message)); return 0; }
    private static String clock(int seconds) { return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }

    private record Field(ServerLevel level, Vec3 origin, Vec3 forward, WindScenario scenario,
                         int tick, int phaseTicks, double fadeSeconds) {}

    private static final class Session {
        final ServerLevel level; final UUID owner; final Vec3 origin; final Vec3 forward;
        final int phaseTicks; final boolean stress; final java.util.List<WindScenario> scenarios;
        int phase; int tick;
        Session(ServerLevel level, UUID owner, Vec3 origin, Vec3 forward, int secondsPerPhase, boolean stress) {
            this.level=level; this.owner=owner; this.origin=origin; this.forward=forward;
            this.phaseTicks=secondsPerPhase*20; this.stress=stress;
            this.scenarios=stress ? WindScenario.STRESS : WindScenario.FLIGHT;
        }
        int totalSeconds() { return phaseTicks / 20 * scenarios.size(); }
        WindScenario scenario() { return scenarios.get(phase); }
        double phaseSeconds() { return phaseTicks / 20.0; }
    }
}
