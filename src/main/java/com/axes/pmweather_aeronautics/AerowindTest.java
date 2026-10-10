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
import net.neoforged.fml.ModList;

/** Temporary wind profiles for repeatable Aeronautics and PMIV vehicle checks. */
public final class AerowindTest {
    private static final double MAX_RADIUS_SQUARED = 256.0 * 256.0;
    private static final int CLIENT_FIELD_STALE_TICKS = 40;
    private static Session session;
    private static volatile Field active;
    private static volatile ClientField clientField;

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

    /** Client-side analytic counterpart for particles and cached wind consumers. */
    public static Vec3 sampleClient(Level level, Vec3 point) {
        ClientField field = clientField;
        if (field == null || level == null || !level.isClientSide || !finite(point)
            || !level.dimension().location().toString().equals(field.dimension())) return null;
        long sinceReceipt = level.getGameTime() - field.clientReceiveTick();
        if (sinceReceipt < 0L || sinceReceipt > CLIENT_FIELD_STALE_TICKS) {
            if (clientField == field) clientField = null;
            return null;
        }
        double elapsedTicks = Math.max(0.0, field.elapsedTicks() + sinceReceipt);
        double elapsedSeconds = elapsedTicks / 20.0;
        Vec3 origin = field.origin().add(field.originPerTick().scale(Math.min(sinceReceipt, 10L)));
        if (point.distanceToSqr(origin) > MAX_RADIUS_SQUARED) return null;
        double phaseSeconds = field.phaseTicks() / 20.0;
        return field.scenario().sample(point.subtract(origin), field.forward(), elapsedSeconds, phaseSeconds)
            .scale(WeatherFieldMath.phaseStrength(elapsedSeconds, phaseSeconds, field.fadeTicks() / 20.0));
    }

    /** Install a bounded network snapshot on clients; the server never calls this method. */
    public static void acceptClientField(WindMonitorNetwork.TestFieldPayload payload, Level level) {
        if (payload == null || !payload.active() || level == null || !level.isClientSide
            || !level.dimension().location().toString().equals(payload.dimension())) {
            clearClientField();
            return;
        }
        var scenarios = payload.stress() ? WindScenario.STRESS : WindScenario.FLIGHT;
        if (payload.scenarioIndex() < 0 || payload.scenarioIndex() >= scenarios.size()
            || payload.phaseTicks() <= 0 || payload.elapsedTicks() < 0) {
            clearClientField();
            return;
        }
        Vec3 origin = new Vec3(payload.originX(), payload.originY(), payload.originZ());
        Vec3 forward = WeatherFieldMath.phaseHeading(new Vec3(payload.forwardX(), 0.0, payload.forwardZ()));
        if (!finite(origin) || !finite(forward)) {
            clearClientField();
            return;
        }
        ClientField previous = clientField;
        Vec3 originPerTick = Vec3.ZERO;
        if (previous != null && previous.dimension().equals(payload.dimension())
            && previous.stress() == payload.stress() && previous.phaseIndex() == payload.scenarioIndex()) {
            long serverTicks = payload.serverGameTick() - previous.serverGameTick();
            if (serverTicks > 0L && serverTicks <= 40L) {
                originPerTick = origin.subtract(previous.origin()).scale(1.0 / serverTicks);
            }
        }
        clientField = new ClientField(payload.dimension(), payload.stress(), payload.scenarioIndex(), origin,
            originPerTick, forward, scenarios.get(payload.scenarioIndex()), payload.elapsedTicks(),
            payload.phaseTicks(), payload.fadeTicks(), payload.serverGameTick(), level.getGameTime());
    }

    public static void clearClientField() {
        clientField = null;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        registerTestRoot(event, "aerowind");
        registerTestRoot(event, "pmaero");
        if (ModList.get().isLoaded("pmweather_iv")) registerTestRoot(event, "pmiv");
    }

    private static void registerTestRoot(RegisterCommandsEvent event, String rootName) {
        var root = Commands.literal(rootName);
        var test = Commands.literal("test")
            .requires(source -> source.hasPermission(2))
            .executes(c -> status(c.getSource()));
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
            ServerPlayer owner = session.level.getServer().getPlayerList().getPlayer(session.owner);
            if (advance(owner)) announcePhase();
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
        WindSamplePosition.RiderSample rider = WindSamplePosition.resolve(player);
        session = new Session(player.serverLevel(), player.getUUID(),
            rider.worldPoint(), rider.forward(), secondsPerPhase, stress);
        updateField();
        announce("Started " + (stress ? "stress" : "flight") + " sequence (" + clock(session.totalSeconds())
            + "). Use /pmaero live on to show wind; /pmaero test stop to end.");
        announcePhase();
        return 1;
    }

    private static void tick(ServerTickEvent.Pre event) {
        Session s = session;
        if (s == null || event.getServer() != s.level.getServer()) return;
        ServerPlayer owner = event.getServer().getPlayerList().getPlayer(s.owner);
        if (owner == null || owner.serverLevel() != s.level) { stop("operator left the test area"); return; }
        s.origin = WindSamplePosition.worldPoint(owner);
        if (++s.tick >= s.phaseTicks) {
            if (advance(owner)) announcePhase();
            return;
        }
        updateField();
        if (s.tick % 20 == 0) sendStatus();
        if (s.tick % 10 == 0) sendClientFields();
    }

    private static boolean advance(ServerPlayer owner) {
        if (session.phase + 1 >= session.scenarios.size()) {
            stop("test completed");
            return false;
        }
        session.phase++;
        session.tick = 0;
        if (owner != null && owner.serverLevel() == session.level) {
            WindSamplePosition.RiderSample rider = WindSamplePosition.resolve(owner);
            session.origin = rider.worldPoint();
            session.forward = WeatherFieldMath.phaseHeading(rider.forward());
        }
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
            + s.scenario().label() + " (" + (s.phaseTicks / 20) + "s).");
        sendStatus();
        sendClientFields();
    }

    private static void sendStatus() {
        Session s = session;
        if (s == null) return;
        String phase = "TEST " + (s.phase + 1) + "/" + s.scenarios.size() + "  " + s.scenario().label();
        String progress = clock((s.phase * s.phaseTicks + s.tick) / 20) + "/" + clock(s.totalSeconds())
            + "  Next: " + (s.phase + 1 < s.scenarios.size() ? s.scenarios.get(s.phase + 1).label() : "finish");
        Vec3 origin = s.origin;
        for (ServerPlayer player : s.level.players()) {
            if (WindSamplePosition.worldPoint(player).distanceToSqr(origin) <= MAX_RADIUS_SQUARED
                || player.getUUID().equals(s.owner))
                WindMonitorNetwork.sendTestStatus(player, phase, progress);
        }
    }

    private static void sendClientFields() {
        Session s = session;
        if (s == null) return;
        String dimension = s.level.dimension().location().toString();
        int fadeTicks = (int) Math.round(Math.min(3.0, (s.phaseTicks / 20.0) / 4.0) * 20.0);
        for (ServerPlayer player : s.level.players()) {
            boolean nearby = WindSamplePosition.worldPoint(player).distanceToSqr(s.origin) <= MAX_RADIUS_SQUARED;
            WindMonitorNetwork.sendTestField(player, new WindMonitorNetwork.TestFieldPayload(
                nearby || player.getUUID().equals(s.owner), dimension, s.stress, s.phase,
                s.origin.x, s.origin.y, s.origin.z, s.forward.x, s.forward.z,
                s.tick, s.phaseTicks, fadeTicks, s.level.getGameTime()
            ));
        }
    }

    private static void clearClientFields(Session old) {
        String dimension = old.level.dimension().location().toString();
        WindMonitorNetwork.TestFieldPayload stopped = new WindMonitorNetwork.TestFieldPayload(
            false, dimension, old.stress, old.phase, 0, 0, 0, 0, 1, 0, 0, 0, old.level.getGameTime()
        );
        for (ServerPlayer player : old.level.players()) WindMonitorNetwork.sendTestField(player, stopped);
    }

    private static int status(CommandSourceStack source) {
        Session s = session;
        String message = s == null ? "Wind test idle. /pmaero test start [secondsPerPhase]; /pmaero test stress; /pmaero test list."
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
            clearClientFields(old);
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

    private record ClientField(String dimension, boolean stress, int phaseIndex, Vec3 origin,
                               Vec3 originPerTick, Vec3 forward, WindScenario scenario,
                               int elapsedTicks, int phaseTicks, int fadeTicks,
                               long serverGameTick, long clientReceiveTick) {}

    private static final class Session {
        final ServerLevel level; final UUID owner; Vec3 origin; Vec3 forward;
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
