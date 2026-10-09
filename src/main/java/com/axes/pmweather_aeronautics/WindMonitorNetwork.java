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

    private WindMonitorNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.CODEC, WindMonitorNetwork::handleRequest);
        registrar.playToClient(ReadingPayload.TYPE, ReadingPayload.CODEC, WindMonitorNetwork::handleReading);
        registrar.playToClient(StatusPayload.TYPE, StatusPayload.CODEC, WindMonitorNetwork::handleStatus);
    }

    private static void handleRequest(RequestPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        net.minecraft.world.phys.Vec3 sample;
        boolean valid;
        try {
            sample = PMWeatherWindApi.sampleRawMph(player.serverLevel(),
                new net.minecraft.world.phys.Vec3(player.getX(), player.getEyeY(), player.getZ()));
            valid = sample != null && Double.isFinite(sample.x) && Double.isFinite(sample.y) && Double.isFinite(sample.z);
        } catch (RuntimeException | LinkageError failure) {
            sample = net.minecraft.world.phys.Vec3.ZERO;
            valid = false;
        }
        context.reply(new ReadingPayload(request.requestId(), player.serverLevel().getGameTime(),
            sample.x, sample.y, sample.z, valid));
    }

    private static void handleReading(ReadingPayload payload, IPayloadContext context) {
        WindVector wind = new WindVector(payload.windX(), payload.windY(), payload.windZ());
        latestClientReading = new Reading(payload.available() && wind.isFinite(), payload.requestId(),
            payload.serverGameTime(), wind.isFinite() ? wind : WindVector.ZERO);
    }

    private static void handleStatus(StatusPayload payload, IPayloadContext context) {
        LiveWindMonitor.setTestStatus(payload.phase(), payload.progress());
        if (net.neoforged.fml.ModList.get().isLoaded("pmweather_iv")) {
            try {
                Class<?> monitor = Class.forName("com.g9third.pmweatheriv.client.ClientWindMonitor");
                monitor.getMethod("setTestStatus", String.class, String.class)
                    .invoke(null, payload.phase(), payload.progress());
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // The optional PMIV HUD can be absent or still loading during client startup.
            }
        }
    }

    public static void sendTestStatus(ServerPlayer player, String phase, String progress) {
        if (net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(
            player.connection, StatusPayload.TYPE.id())) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new StatusPayload(phase, progress));
        }
    }

    public static Reading latestClientReading() { return latestClientReading; }
    public static void clearClientReading() { latestClientReading = Reading.UNAVAILABLE; }

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
