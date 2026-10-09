package com.axes.pmweather_aeronautics;

import java.util.Arrays;

/**
 * Generic virtual lifting surfaces. No IV, block, contraption or rigid-body ownership.
 * All positions, axes, flows and returned forces share the caller's Cartesian frame.
 * SI units (metres, seconds, kilograms, radians) are used throughout.
 *
 * <p>Input per surface: point[0..2], chord axis[3..5], span axis[6..8], normal[9..11],
 * area[12], span[13], chord[14], aspect ratio[15], authored deflection[16],
 * physical relative velocity[17..19], adjusted relative velocity[20..22],
 * use adjusted flow[23], prior separation[24] (NaN initializes from physical flow),
 * dt[25], advance state[26], lift slope[27], CLmax[28], profile/form CD[29],
 * reserved zero[30], wetted/reference area ratio[31] (zero uses supplied profile CD),
 * density[32], zero-lift CL[33], induced CD factor[34] (NaN derives from aspect ratio).
 * Output: force[0..2], application point[3..5], CL[6], CD[7], physical alpha[8],
 * separation[9], target separation[10], physical chord/normal/span flow[11..13],
 * attached-flow alpha[14]. Buffers belong to the caller; evaluation allocates nothing.
 */
public final class ExternalLiftingSurfaceApi {
    public static final int API_VERSION = 2;
    public static final int INPUT_STRIDE = 35;
    public static final int OUTPUT_STRIDE = 15;
    private static final double CD90 = 1.17;
    private static final double VISCOSITY = 1.81E-5;
    private static final double SPAN_EFFICIENCY = 0.82;

    private ExternalLiftingSurfaceApi() {}

    /** Invalid inputs fail the entire batch; the output is cleared, never partially usable. */
    public static boolean evaluatePackedInto(double[] input, double[] output) {
        if (output != null && output != input) Arrays.fill(output, 0.0);
        if (input == null || output == null || input.length % INPUT_STRIDE != 0
            || output.length != input.length / INPUT_STRIDE * OUTPUT_STRIDE || input == output) {
            return false;
        }
        for (int i = 0, o = 0; i < input.length; i += INPUT_STRIDE, o += OUTPUT_STRIDE) {
            if (!evaluate(input, i, output, o)) {
                Arrays.fill(output, 0.0);
                return false;
            }
        }
        for (double value : output) if (!Double.isFinite(value)) {
            Arrays.fill(output, 0.0);
            return false;
        }
        return true;
    }

    private static boolean evaluate(double[] a, int i, double[] out, int o) {
        for (int k = 0; k < INPUT_STRIDE; k++) {
            if ((k == 24 || k == 34) && Double.isNaN(a[i + k])) continue;
            if (!Double.isFinite(a[i + k])) return false;
        }
        if ((a[i + 23] != 0.0 && a[i + 23] != 1.0)
            || (a[i + 26] != 0.0 && a[i + 26] != 1.0) || a[i + 30] != 0.0) return false;
        double area = a[i + 12], chordLength = a[i + 14], density = a[i + 32];
        if (area < 0 || chordLength <= 0 || a[i + 13] <= 0 || a[i + 15] <= 0
            || density < 0 || a[i + 25] < 0 || a[i + 27] <= 0 || a[i + 28] <= 0
            || a[i + 29] < 0 || a[i + 31] < 0
            || (Double.isFinite(a[i + 34]) && a[i + 34] < 0)) return false;
        double cx = a[i + 3], cy = a[i + 4], cz = a[i + 5];
        double sx = a[i + 6], sy = a[i + 7], sz = a[i + 8];
        double nx = a[i + 9], ny = a[i + 10], nz = a[i + 11];
        double clen = length(cx, cy, cz), slen = length(sx, sy, sz), nlen = length(nx, ny, nz);
        if (clen < 1E-9 || slen < 1E-9 || nlen < 1E-9) return false;
        cx /= clen; cy /= clen; cz /= clen;
        sx /= slen; sy /= slen; sz /= slen;
        nx /= nlen; ny /= nlen; nz /= nlen;
        // Reject a degenerate or left-handed surface rather than inventing an axis.
        double handedness = (cy * sz - cz * sy) * nx + (cz * sx - cx * sz) * ny
            + (cx * sy - cy * sx) * nz;
        if (handedness < 0.99 || Math.abs(cx * sx + cy * sy + cz * sz) > 0.01
            || Math.abs(cx * nx + cy * ny + cz * nz) > 0.01
            || Math.abs(sx * nx + sy * ny + sz * nz) > 0.01) return false;
        double vx = a[i + 17], vy = a[i + 18], vz = a[i + 19];
        double pc = vx * cx + vy * cy + vz * cz;
        double pn = vx * nx + vy * ny + vz * nz;
        double ps = vx * sx + vy * sy + vz * sz;
        double speed = Math.hypot(pc, pn);
        double physicalAlpha = plateAngle(Math.atan2(-pn, pc) + a[i + 16]);
        double slope = a[i + 27], maximum = a[i + 28], zero = a[i + 33];
        double target = speed < 0.25 ? 0.0 : pc < 0.0 ? 1.0 : smooth(
            Math.max(0.0, (Math.abs(zero + slope * physicalAlpha) - maximum) / slope)
                / Math.toRadians(12.0));
        double prior = Double.isNaN(a[i + 24]) ? target : clamp(a[i + 24]);
        double separation = prior;
        if (a[i + 26] != 0.0) {
            double exponent = speed < 0.25 ? a[i + 25] / 1.25
                : speed * a[i + 25] / chordLength
                    / (target > prior ? (pc < 0.0 ? 1.5 : 4.0) : 6.0);
            separation = clamp(prior + (target - prior) * -Math.expm1(-exponent));
        }
        out[o + 3] = a[i]; out[o + 4] = a[i + 1]; out[o + 5] = a[i + 2];
        out[o + 8] = physicalAlpha; out[o + 9] = separation; out[o + 10] = target;
        out[o + 11] = pc; out[o + 12] = pn; out[o + 13] = ps;
        if (area == 0.0 || density == 0.0) return true;

        // Induced flow can change attached lift; only physical flow drove the stall state above.
        double an = a[i + 23] == 0.0 ? pn
            : a[i + 20] * nx + a[i + 21] * ny + a[i + 22] * nz;
        double effectiveN = pn + (an - pn) * (1.0 - separation);
        double fx = cx * pc + nx * effectiveN;
        double fy = cy * pc + ny * effectiveN;
        double fz = cz * pc + nz * effectiveN;
        double projectedSpeed = length(fx, fy, fz);
        double alpha = plateAngle(Math.atan2(-effectiveN, pc) + a[i + 16]);
        out[o + 14] = alpha;
        double tx = vx - nx * pn, ty = vy - ny * pn, tz = vz - nz * pn;
        double tangentialSpeed = length(tx, ty, tz);
        boolean skin = a[i + 31] > 0.0;
        double skinCd = skin ? skinFriction(density, tangentialSpeed, chordLength) * a[i + 31] : a[i + 29];
        double formCd = skin ? a[i + 29] : 0.0;
        double profile = skinCd + formCd;
        double linear = zero + slope * alpha;
        double magnitude = Math.abs(linear), start = maximum * 0.85;
        double attached = magnitude <= start ? magnitude : magnitude >= maximum ? maximum
            : magnitude + (maximum - magnitude) * smooth((magnitude - start) / (maximum - start));
        attached = Math.copySign(attached, linear);
        double induced = Double.isNaN(a[i + 34])
            ? 1.0 / (Math.PI * Math.max(1.0, a[i + 15]) * SPAN_EFFICIENCY) : a[i + 34];
        double sin = Math.sin(alpha), cos = Math.cos(alpha);
        double cl = attached + (CD90 * sin * cos - attached) * separation;
        double attachedCd = profile + induced * attached * attached;
        double cd = Math.max(0.0, attachedCd + (CD90 * sin * sin + profile * cos * cos - attachedCd) * separation);
        double lx = fy * sz - fz * sy, ly = fz * sx - fx * sz, lz = fx * sy - fy * sx;
        double liftLength = length(lx, ly, lz);
        double qArea = speed < 0.25 ? 0.0 : 0.5 * density * projectedSpeed * projectedSpeed * area;
        double liftScale = liftLength > 1E-12 ? qArea * cl / liftLength : 0.0;
        double dragScale = projectedSpeed > 1E-12 ? -qArea * Math.max(0.0, cd - profile) / projectedSpeed : 0.0;
        double fullSpeed = length(vx, vy, vz);
        double skinScale = -0.5 * density * area * skinCd * (skin ? tangentialSpeed : fullSpeed);
        double formScale = -0.5 * density * area * formCd * fullSpeed;
        out[o] = lx * liftScale + fx * dragScale + (skin ? tx : vx) * skinScale + vx * formScale;
        out[o + 1] = ly * liftScale + fy * dragScale + (skin ? ty : vy) * skinScale + vy * formScale;
        out[o + 2] = lz * liftScale + fz * dragScale + (skin ? tz : vz) * skinScale + vz * formScale;
        out[o + 6] = speed < 0.25 ? 0.0 : cl; out[o + 7] = cd;
        for (int k = 0; k < OUTPUT_STRIDE; k++) if (!Double.isFinite(out[o + k])) return false;
        return true;
    }

    private static double skinFriction(double density, double speed, double chord) {
        if (speed <= 1E-9 || density <= 0.0) return 0.0;
        double re = Math.max(1E4, density * speed * chord / VISCOSITY);
        return 0.455 / Math.pow(Math.log10(re), 2.58);
    }

    private static double length(double x, double y, double z) { return Math.hypot(Math.hypot(x, y), z); }
    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
    private static double smooth(double value) { double t = clamp(value); return t * t * (3.0 - 2.0 * t); }
    private static double plateAngle(double angle) {
        double wrapped = angle % Math.PI;
        if (wrapped > Math.PI * 0.5) wrapped -= Math.PI;
        if (wrapped < -Math.PI * 0.5) wrapped += Math.PI;
        return wrapped;
    }
}
