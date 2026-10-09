package com.axes.pmweather_aeronautics;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Optional, server-authoritative wind sample channel for the compact client HUD. */
public final class WindMonitorNetwork {
    private static volatile Reading latestClientReading = Reading.UNAVAILABLE;
    private static final java.util.Map<ServerPlayer, CachedReading> SERVER = new java.util.WeakHashMap<>();
    private record CachedReading(net.minecraft.server.level.ServerLevel level, long tick,
                                 net.minecraft.world.phys.Vec3 wind, boolean valid) {}
    private static volatile java.lang.reflect.Method pmivStatusMethod;
    private static volatile boolean pmivStatusMethodResolved;

    private WindMonitorNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.CODEC, WindMonitorNetwork::handleRequest);
        registrar.playToClient(ReadingPayload.TYPE, ReadingPayload.CODEC, WindMonitorNetwork::handleReading);
        registrar.playToClient(StatusPayload.TYPE, StatusPayload.CODEC, WindMonitorNetwork::handleStatus);
        registrar.playToClient(TestFieldPayload.TYPE, TestFieldPayload.CODEC, WindMonitorNetwork::handleTestField);
    }

    private static void handleRequest(RequestPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var level = player.serverLevel();
        long tick = level.getGameTime();
        CachedReading cached = SERVER.get(player);
        if (cached == null || cached.level() != level || tick - cached.tick() >= 2L || tick < cached.tick()) {
            net.minecraft.world.phys.Vec3 sample;
            boolean valid;
            try {
                var point = WindSamplePosition.exposedWorldPoint(level, player);
                sample = PMWeatherWindApi.sampleRawMph(level, point, PMWeatherWindApi.AIRCRAFT_ATMOSPHERE);
                valid = sample != null && Double.isFinite(sample.x) && Double.isFinite(sample.y) && Double.isFinite(sample.z);
            } catch (RuntimeException | LinkageError failure) {
                sample = net.minecraft.world.phys.Vec3.ZERO;
                valid = false;
            }
            cached = new CachedReading(level, tick, valid ? sample : net.minecraft.world.phys.Vec3.ZERO, valid);
            SERVER.put(player, cached);
        }
        context.reply(new ReadingPayload(request.requestId(), tick,
            cached.wind().x, cached.wind().y, cached.wind().z, cached.valid()));
    }

    private static void handleReading(ReadingPayload payload, IPayloadContext context) {
        WindVector wind = new WindVector(payload.windX(), payload.windY(), payload.windZ());
        latestClientReading = new Reading(payload.available() && wind.isFinite(), payload.requestId(),
            payload.serverGameTime(), wind.isFinite() ? wind : WindVector.ZERO);
    }

    private static void handleStatus(StatusPayload payload, IPayloadContext context) {
        LiveWindMonitor.setTestStatus(payload.phase(), payload.progress());
        java.lang.reflect.Method method = pmivStatusMethod();
        if (method != null) {
            try {
                method.invoke(null, payload.phase(), payload.progress());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // PMIV may unload or finish client initialization between status packets.
            }
        }
    }

    private static void handleTestField(TestFieldPayload payload, IPayloadContext context) {
        AerowindTest.acceptClientField(payload, context.player().level());
    }

    private static java.lang.reflect.Method pmivStatusMethod() {
        if (pmivStatusMethodResolved) return pmivStatusMethod;
        synchronized (WindMonitorNetwork.class) {
            if (pmivStatusMethodResolved) return pmivStatusMethod;
            pmivStatusMethodResolved = true;
            try {
                if (net.neoforged.fml.ModList.get().isLoaded("pmweather_iv")) {
                    Class<?> monitor = Class.forName("com.g9third.pmweatheriv.client.ClientWindMonitor", false,
                        WindMonitorNetwork.class.getClassLoader());
                    pmivStatusMethod = monitor.getMethod("setTestStatus", String.class, String.class);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                pmivStatusMethod = null;
            }
            return pmivStatusMethod;
        }
    }

    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        SERVER.clear();
    }

    public static void sendTestStatus(ServerPlayer player, String phase, String progress) {
        if (net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
            player.connection, StatusPayload.TYPE.id())) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new StatusPayload(phase, progress));
        }
    }

    public static void sendTestField(ServerPlayer player, TestFieldPayload payload) {
        if (net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
            player.connection, TestFieldPayload.TYPE.id())) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static Reading latestClientReading() { return latestClientReading; }
    public static void clearClientReading() { latestClientReading = Reading.UNAVAILABLE; }
    public static void clearClientTestField() { AerowindTest.clearClientField(); }

    public record Reading(boolean available, long requestId, long serverGameTime, WindVector wind) {
        public static final Reading UNAVAILABLE = new Reading(false, Long.MIN_VALUE, Long.MIN_VALUE, WindVector.ZERO);
    }

    public record StatusPayload(String phase, String progress) implements CustomPacketPayload {
        public static final Type<StatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            PMWeatherAeronautics.MODID, "weather_test_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatusPayload> CODEC = new StreamCodec<>() {
            public StatusPayload decode(RegistryFriendlyByteBuf buffer) {
                return new StatusPayload(buffer.readUtf(128), buffer.readUtf(128));
            }
            public void encode(RegistryFriendlyByteBuf buffer, StatusPayload value) {
                buffer.writeUtf(value.phase(), 128); buffer.writeUtf(value.progress(), 128);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Bounded single-field snapshot; this intentionally uses a new payload id and codec. */
    public record TestFieldPayload(boolean active, String dimension, boolean stress, int scenarioIndex,
                                   double originX, double originY, double originZ,
                                   double forwardX, double forwardZ, int elapsedTicks, int phaseTicks,
                                   int fadeTicks, long serverGameTick) implements CustomPacketPayload {
        public static final Type<TestFieldPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            PMWeatherAeronautics.MODID, "weather_test_field"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TestFieldPayload> CODEC = new StreamCodec<>() {
            @Override
            public TestFieldPayload decode(RegistryFriendlyByteBuf buffer) {
                return new TestFieldPayload(buffer.readBoolean(), buffer.readUtf(64), buffer.readBoolean(),
                    buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarLong());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, TestFieldPayload value) {
                buffer.writeBoolean(value.active());
                buffer.writeUtf(value.dimension(), 64);
                buffer.writeBoolean(value.stress());
                buffer.writeVarInt(value.scenarioIndex());
                buffer.writeDouble(value.originX());
                buffer.writeDouble(value.originY());
                buffer.writeDouble(value.originZ());
                buffer.writeDouble(value.forwardX());
                buffer.writeDouble(value.forwardZ());
                buffer.writeVarInt(value.elapsedTicks());
                buffer.writeVarInt(value.phaseTicks());
                buffer.writeVarInt(value.fadeTicks());
                buffer.writeVarLong(value.serverGameTick());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestPayload(long requestId) implements CustomPacketPayload {
        public static final Type<RequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            PMWeatherAeronautics.MODID, "wind_monitor_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = new StreamCodec<>() {
            public RequestPayload decode(RegistryFriendlyByteBuf buffer) { return new RequestPayload(buffer.readVarLong()); }
            public void encode(RegistryFriendlyByteBuf buffer, RequestPayload value) { buffer.writeVarLong(value.requestId()); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ReadingPayload(long requestId, long serverGameTime, double windX, double windY,
                                 double windZ, boolean available) implements CustomPacketPayload {
        public static final Type<ReadingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            PMWeatherAeronautics.MODID, "wind_monitor_reading"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReadingPayload> CODEC = new StreamCodec<>() {
            public ReadingPayload decode(RegistryFriendlyByteBuf buffer) {
                return new ReadingPayload(buffer.readVarLong(), buffer.readVarLong(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readBoolean());
            }
            public void encode(RegistryFriendlyByteBuf buffer, ReadingPayload value) {
                buffer.writeVarLong(value.requestId()); buffer.writeVarLong(value.serverGameTime());
                buffer.writeDouble(value.windX()); buffer.writeDouble(value.windY()); buffer.writeDouble(value.windZ());
                buffer.writeBoolean(value.available());
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
