package com.axes.pmweather_aeronautics;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Real network codec checks, including the one-point particle batch that previously disconnected clients. */
public final class PacketCodecRegression {
    private static int checks;
    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T original) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, original);
            T result = codec.decode(buffer);
            require(buffer.readableBytes() == 0, "packet left unread bytes");
            return result;
        } finally { buffer.release(); }
    }
    private static void rejected(Runnable operation) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException | io.netty.handler.codec.CodecException expected) { rejected = true; }
        require(rejected, "malformed packet accepted");
    }
    public static void main(String[] args) {
        for (int count = 1; count <= 8; count++) {
            double[] points = new double[count * 3];
            boolean[] available = new boolean[count];
            for (int i = 0; i < points.length; i++) points[i] = i - 4.25;
            for (int i = 0; i < count; i++) available[i] = i % 2 == 0;
            ParticleWindNetwork.RequestPayload request = new ParticleWindNetwork.RequestPayload(19, count, points);
            ParticleWindNetwork.ResponsePayload response = new ParticleWindNetwork.ResponsePayload(19, count, points, available);
            ParticleWindNetwork.RequestPayload decodedRequest = roundTrip(ParticleWindNetwork.RequestPayload.CODEC, request);
            ParticleWindNetwork.ResponsePayload decodedResponse = roundTrip(ParticleWindNetwork.ResponsePayload.CODEC, response);
            require(decodedRequest.requestId() == 19 && decodedRequest.count() == count
                    && Arrays.equals(decodedRequest.coordinates(), points), "request changed fields");
            require(decodedResponse.requestId() == 19 && decodedResponse.count() == count
                    && Arrays.equals(decodedResponse.winds(), points)
                    && Arrays.equals(decodedResponse.available(), available), "response changed fields");
            points[0] = 1234; available[0] = false;
            require(request.coordinates()[0] == -4.25 && response.winds()[0] == -4.25
                    && response.available()[0], "constructor did not copy arrays");
            request.coordinates()[0] = 4567; response.winds()[0] = 4567; response.available()[0] = false;
            require(request.coordinates()[0] == -4.25 && response.winds()[0] == -4.25
                    && response.available()[0], "accessor exposed arrays");
        }
        for (int count : new int[]{0, 9, Integer.MAX_VALUE}) {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                buffer.writeVarLong(1); buffer.writeVarInt(count);
                rejected(() -> ParticleWindNetwork.RequestPayload.CODEC.decode(buffer));
                buffer.readerIndex(0);
                rejected(() -> ParticleWindNetwork.ResponsePayload.CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
        rejected(() -> roundTrip(ParticleWindNetwork.RequestPayload.CODEC,
                new ParticleWindNetwork.RequestPayload(1, 1, new double[24])));
        rejected(() -> roundTrip(ParticleWindNetwork.RequestPayload.CODEC,
                new ParticleWindNetwork.RequestPayload(0, 1, new double[3])));
        rejected(() -> roundTrip(ParticleWindNetwork.ResponsePayload.CODEC,
                new ParticleWindNetwork.ResponsePayload(1, 1, new double[3], new boolean[8])));
        var test = new WindMonitorNetwork.TestFieldPayload(true, "minecraft:overworld", false,
                3, 2.5, 5.5, -7.5, 0.6, -0.8, 123, 300, 60, 990);
        require(roundTrip(WindMonitorNetwork.TestFieldPayload.CODEC, test).equals(test), "test snapshot changed fields");
        for (boolean valid : new boolean[]{false, true}) {
            var reading = new WindMonitorNetwork.ReadingPayload(11, 999, 20, -15, 30, valid);
            require(roundTrip(WindMonitorNetwork.ReadingPayload.CODEC, reading).equals(reading), "HUD availability changed");
        }
        var request = new WindMonitorNetwork.RequestPayload(Long.MAX_VALUE);
        require(roundTrip(WindMonitorNetwork.RequestPayload.CODEC, request).equals(request), "HUD request changed");
        System.out.println("PacketCodecRegression: " + checks + " assertions passed");
    }
}
