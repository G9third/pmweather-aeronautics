package com.axes.pmweather_aeronautics;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Bounded server-authoritative native 3-D wind samples for nearby client particles. */
public final class ParticleWindNetwork {
    public static final int MAX_POINTS_PER_BATCH = 8;
    private static final double MAX_PLAYER_DISTANCE_SQUARED = 96.0D * 96.0D;
    private static final int MAX_GLOBAL_SAMPLES_PER_TICK = 32;
    private static final long MIN_PLAYER_BATCH_INTERVAL_TICKS = 4L;
    private static final int MAX_PENDING_BATCHES = 64;
    private static final long MAX_PENDING_AGE_TICKS = 20L;
    private static final Map<ServerPlayer, RateState> PLAYER_RATES = new WeakHashMap<>();
    private static final Map<UUID, QueuedRequest> PENDING = new HashMap<>();
    private static UUID lastServedPlayer;
    private static long globalTick = Long.MIN_VALUE;
    private static int globalSamples;
    private static volatile java.lang.reflect.Method clientResponseMethod;
    private static volatile boolean clientResponseMethodResolved;

    private ParticleWindNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.CODEC, ParticleWindNetwork::handleRequest);
        registrar.playToClient(ResponsePayload.TYPE, ResponsePayload.CODEC, ParticleWindNetwork::handleResponse);
    }

    /** Called only by the client tick batcher; the codec owns an immutable bounded copy. */
    public static boolean sendClientRequest(long requestId, int count, double[] coordinates) {
        if (requestId <= 0L || count < 1 || count > MAX_POINTS_PER_BATCH
                || coordinates == null || coordinates.length < count * 3) return false;
        try {
            PacketDistributor.sendToServer(new RequestPayload(requestId, count, coordinates));
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void handleRequest(RequestPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> enqueueRequest(request, player));
    }

    private static void enqueueRequest(RequestPayload request, ServerPlayer player) {
        int count = request.count();
        double[] coordinates = request.coordinates();
        ServerLevel level = player.serverLevel();
        if (player.getServer() == null) return;
        long tick = player.getServer().overworld().getGameTime();
        UUID playerId = player.getUUID();
        RateState previousRate = PLAYER_RATES.get(player);
        boolean rateAllowed = previousRate == null || previousRate.level() != level
                || tick < previousRate.tick() || tick - previousRate.tick() >= MIN_PLAYER_BATCH_INTERVAL_TICKS;
        boolean valid = request.requestId() > 0L && count >= 1 && count <= MAX_POINTS_PER_BATCH
                && coordinates.length == count * 3 && rateAllowed
                && !PENDING.containsKey(playerId)
                && PENDING.size() < MAX_PENDING_BATCHES;
        if (valid) {
            Vec3 playerPosition = player.position();
            for (int i = 0; i < count; i++) {
                int base = i * 3;
                double x = coordinates[base];
                double y = coordinates[base + 1];
                double z = coordinates[base + 2];
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                        || Math.abs(x) > 30_000_000.0D || Math.abs(z) > 30_000_000.0D
                        || y < -2048.0D || y > 4096.0D) {
                    valid = false;
                    break;
                }
                Vec3 point = new Vec3(x, y, z);
                if (point.distanceToSqr(playerPosition) > MAX_PLAYER_DISTANCE_SQUARED) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            if (rateAllowed) PLAYER_RATES.put(player, new RateState(level, tick));
            sendUnavailable(player, request.requestId(), count);
            return;
        }
        PLAYER_RATES.put(player, new RateState(level, tick));
        PENDING.put(playerId, new QueuedRequest(level, request.requestId(), count, coordinates, tick));
    }

    /** Processes queued cell batches in a rotating player order so crowded servers share the cap. */
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long tick = event.getServer().overworld().getGameTime();
        if (globalTick != tick || tick < globalTick) {
            globalTick = tick;
            globalSamples = 0;
        }
        if (PENDING.isEmpty()) return;

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            PENDING.clear();
            return;
        }
        int start = 0;
        if (lastServedPlayer != null) {
            for (int i = 0; i < players.size(); i++) {
                if (players.get(i).getUUID().equals(lastServedPlayer)) {
                    start = (i + 1) % players.size();
                    break;
                }
            }
        }
        for (int offset = 0; offset < players.size(); offset++) {
            ServerPlayer player = players.get((start + offset) % players.size());
            UUID playerId = player.getUUID();
            QueuedRequest queued = PENDING.get(playerId);
            if (queued == null) continue;
            if (queued.level() != player.serverLevel() || tick < queued.receivedTick()
                    || tick - queued.receivedTick() > MAX_PENDING_AGE_TICKS) {
                PENDING.remove(playerId);
                sendUnavailable(player, queued.requestId(), queued.count());
                continue;
            }
            if (globalSamples + queued.count() > MAX_GLOBAL_SAMPLES_PER_TICK) continue;
            processQueued(queued, player);
            PENDING.remove(playerId);
            globalSamples += queued.count();
            lastServedPlayer = playerId;
        }
    }

    private static void processQueued(QueuedRequest queued, ServerPlayer player) {
        double[] winds = new double[queued.count() * 3];
        boolean[] available = new boolean[queued.count()];
        ArrayList<Vec3> positions = new ArrayList<>(queued.count());
        double[] coordinates = queued.coordinates();
        for (int i = 0; i < queued.count(); i++) {
            int base = i * 3;
            positions.add(new Vec3(coordinates[base], coordinates[base + 1], coordinates[base + 2]));
        }
        try {
            var sampled = PMWeatherWindApi.sampleRawBatchMph(queued.level(), positions, PMWeatherWindApi.SOURCE_NATIVE);
            if (sampled.size() == queued.count()) {
                for (int i = 0; i < queued.count(); i++) {
                    Vec3 wind = sampled.get(i);
                    if (wind != null && Double.isFinite(wind.x) && Double.isFinite(wind.y)
                            && Double.isFinite(wind.z)) {
                        winds[i * 3] = wind.x;
                        winds[i * 3 + 1] = wind.y;
                        winds[i * 3 + 2] = wind.z;
                        available[i] = true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            Arrays.fill(available, false);
        }
        sendResponse(player, new ResponsePayload(queued.requestId(), queued.count(), winds, available));
    }

    private static void sendUnavailable(ServerPlayer player, long requestId, int count) {
        if (requestId <= 0L || count < 1 || count > MAX_POINTS_PER_BATCH) return;
        sendResponse(player, new ResponsePayload(requestId, count, new double[count * 3], new boolean[count]));
    }

    private static void sendResponse(ServerPlayer player, ResponsePayload payload) {
        if (player.isRemoved()) return;
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (RuntimeException | LinkageError ignored) {
            // A disconnect during the response is harmless; the client request expires and retries.
        }
    }

    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        PENDING.clear();
        PLAYER_RATES.clear();
        globalTick = Long.MIN_VALUE;
        globalSamples = 0;
        lastServedPlayer = null;
    }

    public static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            PLAYER_RATES.remove(player);
        }
    }

    /** Allocates only when explicitly requested for diagnostics. */
    public static ServerMetrics metrics() {
        return new ServerMetrics(globalTick, globalSamples, PENDING.size(), MAX_GLOBAL_SAMPLES_PER_TICK,
            MAX_PENDING_BATCHES);
    }

    private static void handleResponse(ResponsePayload response, IPayloadContext context) {
        if (!Dist.CLIENT.equals(FMLEnvironment.dist)) return;
        context.enqueueWork(() -> {
            java.lang.reflect.Method method = clientResponseMethod();
            if (method == null) return;
            try {
                method.invoke(null, response.requestId(), response.count(), response.winds(), response.available());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // The client world can unload between packet delivery and queued work.
            }
        });
    }

    private static java.lang.reflect.Method clientResponseMethod() {
        if (clientResponseMethodResolved) return clientResponseMethod;
        synchronized (ParticleWindNetwork.class) {
            if (clientResponseMethodResolved) return clientResponseMethod;
            clientResponseMethodResolved = true;
            try {
                Class<?> client = Class.forName("com.axes.pmweather_aeronautics.ParticleWindClient", false,
                    ParticleWindNetwork.class.getClassLoader());
                clientResponseMethod = client.getMethod("acceptServerSamples", long.class, int.class,
                    double[].class, boolean[].class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                clientResponseMethod = null;
            }
            return clientResponseMethod;
        }
    }

    private record RateState(ServerLevel level, long tick) {}
    public record ServerMetrics(long serverTick, int samplesThisTick, int queuedBatches,
                                int sampleBudgetPerTick, int queueLimit) {}
    private record QueuedRequest(ServerLevel level, long requestId, int count,
                                 double[] coordinates, long receivedTick) {
        private QueuedRequest {
            coordinates = coordinates.clone();
        }
        @Override public double[] coordinates() { return coordinates.clone(); }
    }

    public record RequestPayload(long requestId, int count, double[] coordinates) implements CustomPacketPayload {
        public static final Type<RequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            PMWeatherAeronautics.MODID, "particle_wind_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = new StreamCodec<>() {
            @Override public RequestPayload decode(RegistryFriendlyByteBuf buffer) {
                long id = buffer.readVarLong();
                int count = buffer.readVarInt();
                if (id <= 0L || count < 1 || count > MAX_POINTS_PER_BATCH) throw new DecoderException("Invalid particle wind request size");
                double[] coordinates = new double[count * 3];
                for (int i = 0; i < coordinates.length; i++) coordinates[i] = buffer.readDouble();
                return new RequestPayload(id, count, coordinates);
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, RequestPayload value) {
                double[] coordinates = value.coordinates();
                validate(value.count(), coordinates, null);
                if (value.requestId() <= 0L) throw new EncoderException("Invalid particle wind request id");
                buffer.writeVarLong(value.requestId());
                buffer.writeVarInt(value.count());
                for (double coordinate : coordinates) buffer.writeDouble(coordinate);
            }
        };
        public RequestPayload {
            coordinates = coordinates == null ? new double[0] : coordinates.clone();
        }
        @Override public double[] coordinates() { return coordinates.clone(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ResponsePayload(long requestId, int count, double[] winds, boolean[] available) implements CustomPacketPayload {
        public static final Type<ResponsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            PMWeatherAeronautics.MODID, "particle_wind_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ResponsePayload> CODEC = new StreamCodec<>() {
            @Override public ResponsePayload decode(RegistryFriendlyByteBuf buffer) {
                long id = buffer.readVarLong();
                int count = buffer.readVarInt();
                if (id <= 0L || count < 1 || count > MAX_POINTS_PER_BATCH) throw new DecoderException("Invalid particle wind response size");
                double[] winds = new double[count * 3];
                boolean[] available = new boolean[count];
                for (int i = 0; i < count; i++) {
                    available[i] = buffer.readBoolean();
                    winds[i * 3] = buffer.readDouble();
                    winds[i * 3 + 1] = buffer.readDouble();
                    winds[i * 3 + 2] = buffer.readDouble();
                }
                return new ResponsePayload(id, count, winds, available);
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, ResponsePayload value) {
                double[] winds = value.winds();
                boolean[] available = value.available();
                validate(value.count(), winds, available);
                if (value.requestId() <= 0L) throw new EncoderException("Invalid particle wind response id");
                buffer.writeVarLong(value.requestId());
                buffer.writeVarInt(value.count());
                for (int i = 0; i < value.count(); i++) {
                    buffer.writeBoolean(available[i]);
                    buffer.writeDouble(winds[i * 3]);
                    buffer.writeDouble(winds[i * 3 + 1]);
                    buffer.writeDouble(winds[i * 3 + 2]);
                }
            }
        };
        public ResponsePayload {
            winds = winds == null ? new double[0] : winds.clone();
            available = available == null ? new boolean[0] : available.clone();
        }
        @Override public double[] winds() { return winds.clone(); }
        @Override public boolean[] available() { return available.clone(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static void validate(int count, double[] vectors, boolean[] available) {
        if (count < 1 || count > MAX_POINTS_PER_BATCH || vectors == null || vectors.length != count * 3
                || available != null && available.length != count) throw new EncoderException("Invalid particle wind payload");
    }
}
