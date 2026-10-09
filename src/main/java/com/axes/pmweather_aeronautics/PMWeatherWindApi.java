package com.axes.pmweather_aeronautics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Public PMWeather 3-D wind bridge shared by Sable integrations.
 *
 * <p>The returned vectors remain in PMWeather's mph-style units until explicitly converted with
 * {@link #toPhysicsWind(Vec3)}.  Native combined X/Z and existing Y are retained. Supercell tornado Y evaluated
 * by the native engine is added separately because PMWeather 0.17.14-0.17.16 discards it.</p>
 *
 * <p>The external API is intentionally independent from PMAero's body-force tuning, sample budget,
 * temporal interpolation, mass scaling, and force caps.  A caller that already owns a same-tick
 * physics frame can therefore share exactly one PMWeather interpretation without inheriting the
 * Create-Aeronautics scheduling policy.</p>
 */
public final class PMWeatherWindApi {
    /** Ordinary exposed-world PMWeather sampling. */
    public static final SampleOptions SOURCE_NATIVE = new SampleOptions(true, true, true, false);
    /** Continuous atmosphere used for aircraft gradient probes; binary shelter is deliberately excluded. */
    public static final SampleOptions AIRCRAFT_ATMOSPHERE = new SampleOptions(true, true, false, true);

    /** Packed detailed-result stride used by {@link #sampleRawBatchPackedMph}. */
    public static final int API_VERSION = 2;
    public static final int WIND_IMPLEMENTATION_REVISION = 3;
    public static final int PACKED_RESULT_STRIDE = 13;

    private PMWeatherWindApi() {
    }

    /** One owner-tick batch at aircraft force points; caller owns both primitive buffers. */
    public static boolean sampleAircraftAtmosphereInto(ServerLevel level, double[] xyz, double[] output) {
        if (output != null && output != xyz) java.util.Arrays.fill(output, 0.0);
        if (level == null || xyz == null || output == null || xyz.length % 3 != 0
            || output.length != xyz.length / 3 * PACKED_RESULT_STRIDE || xyz == output) return false;
        for (double value : xyz) if (!Double.isFinite(value)) return false;
        return WeatherWindField.sampleAircraftInto(level, xyz, output);
    }

    /** Vector-only owner-tick API: output is XYZ triples, without diagnostic storm scans. */
    public static final int VECTOR_RESULT_STRIDE = 3;
    public static boolean sampleAircraftWindInto(ServerLevel level, double[] xyz, double[] output) {
        if (output != null && output != xyz) java.util.Arrays.fill(output, 0.0D);
        if (level == null || xyz == null || output == null || xyz.length % 3 != 0
                || output.length != xyz.length || xyz == output) return false;
        for (double value : xyz) if (!Double.isFinite(value)) return false;
        return WeatherWindField.sampleAircraftVectorsInto(level, xyz, output);
    }

    /** Resolve one source-native 3-D wind vector in PMWeather/mph-style units. */
    public static Vec3 sampleRawMph(final ServerLevel level, final Vec3 worldPosition) {
        return sampleRawMph(level, worldPosition, SOURCE_NATIVE);
    }

    /** Resolve one source-native 3-D wind vector with explicit PMWeather exposure semantics. */
    public static Vec3 sampleRawMph(final ServerLevel level,
                                    final Vec3 worldPosition,
                                    final SampleOptions options) {
        if (level == null || worldPosition == null) {
            return Vec3.ZERO;
        }
        return WeatherWindField.sampleRawWindAt(level, worldPosition, safeOptions(options));
    }

    /** Resolve many positions using one captured PMWeather storm snapshot and terrain-height cache. */
    public static List<Vec3> sampleRawBatchMph(final ServerLevel level, final List<Vec3> worldPositions) {
        return sampleRawBatchMph(level, worldPositions, SOURCE_NATIVE);
    }

    /** Same as {@link #sampleRawBatchMph(ServerLevel, List)} with explicit source/exposure flags. */
    public static List<Vec3> sampleRawBatchMph(final ServerLevel level,
                                               final List<Vec3> worldPositions,
                                               final SampleOptions options) {
        return WeatherWindField.sampleRawWindBatchAt(level, worldPositions, safeOptions(options));
    }

    /**
     * Reflection-friendly bulk API for integrations that intentionally do not compile against
     * PMWeather Aeronautics. Input is XYZ triples. Output uses {@link #PACKED_RESULT_STRIDE} values
     * per input point:
     * <pre>
     *  0..2  wind X/Y/Z mph
     *  3     native supercell vertical correction used (1/0)
     *  4     storm snapshot count
     *  5     tornadic storm snapshot count
     *  6     maximum storm stage
     *  7     nearest storm horizontal distance (m, NaN if none)
     *  8     nearest storm stage
     *  9     nearest storm tornadic (1/0)
     * 10     nearest storm width (m)
     * 11     reserved influence radius (NaN: not exposed by native API)
     * 12     nearest storm windspeed (mph)
     * </pre>
     */
    public static double[] sampleRawBatchPackedMph(final ServerLevel level,
                                                    final double[] xyz,
                                                    final boolean includeStorms,
                                                    final boolean includeTornadoes,
                                                    final boolean respectShelter,
                                                    final boolean atmosphericField) {
        if (level == null || xyz == null || xyz.length == 0 || xyz.length % 3 != 0) {
            return new double[0];
        }
        final SampleOptions options = new SampleOptions(
                includeStorms, includeTornadoes, respectShelter, atmosphericField
        );
        final List<Vec3> positions = new ArrayList<>(xyz.length / 3);
        for (int i = 0; i < xyz.length; i += 3) {
            positions.add(new Vec3(xyz[i], xyz[i + 1], xyz[i + 2]));
        }
        final List<WeatherWindField.RawWindSample> samples =
                WeatherWindField.sampleRawWindDetailedBatchAt(level, positions, options);
        final double[] packed = new double[samples.size() * PACKED_RESULT_STRIDE];
        for (int i = 0; i < samples.size(); i++) {
            final WeatherWindField.RawWindSample sample = samples.get(i);
            final int base = i * PACKED_RESULT_STRIDE;
            writePacked(sample, packed, base);
        }
        return packed;
    }

    static void writePacked(WeatherWindField.RawWindSample sample, double[] output, int base) {
        output[base] = sample.wind().x; output[base + 1] = sample.wind().y; output[base + 2] = sample.wind().z;
        output[base + 3] = sample.nativeTornadoVectorUsed() ? 1.0D : 0.0D;
        output[base + 4] = sample.stormSnapshotCount();
        output[base + 5] = sample.tornadicStormSnapshotCount();
        output[base + 6] = sample.maximumStormStage();
        output[base + 7] = sample.nearestStormDistanceMeters();
        output[base + 8] = sample.nearestStormStage();
        output[base + 9] = sample.nearestStormTornadic() ? 1.0D : 0.0D;
        output[base + 10] = sample.nearestStormWidthMeters();
        output[base + 11] = sample.nearestStormTornadoInfluenceRadiusMeters();
        output[base + 12] = sample.nearestStormWindspeedMph();
    }

    /** Convert PMWeather/mph-style X/Y/Z to Sable block-per-second style X/Y/Z. */
    public static Vec3 toPhysicsWind(final Vec3 rawWindMph) {
        return WeatherWindField.pmweatherWindToPhysicsWind(rawWindMph);
    }

    /** Convenience diagnostics without projecting away vertical wind. */
    public static WindComponents components(final Vec3 rawWindMph) {
        final Vec3 safe = rawWindMph == null ? Vec3.ZERO : rawWindMph;
        final double horizontal = Math.hypot(safe.x, safe.z);
        return new WindComponents(safe, horizontal, safe.y, safe.length());
    }

    private static SampleOptions safeOptions(final SampleOptions options) {
        return options == null ? SOURCE_NATIVE : options;
    }

    public record SampleOptions(boolean includeStorms,
                                boolean includeTornadoes,
                                boolean respectShelter,
                                boolean atmosphericField) {
    }

    public record WindComponents(Vec3 vectorMph, double horizontalMph, double verticalMph, double totalMph) {
    }
}
