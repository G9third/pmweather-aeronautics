package com.axes.pmweather_aeronautics;

import java.util.Arrays;

/** Plain-Java checks run by Gradle check on the user's machine; no Minecraft world is required. */
public final class ExternalApiChecks {
    private static int checks;
    private ExternalApiChecks() {}

    public static void main(String[] args) {
        rotationCovariance();
        areaAndSpeedScaling();
        physicalStallAndInducedFlow();
        substepConvergence();
        reverseAndQuietFlow();
        bodyForceAndMoment();
        invalidBatchClearsOutput();
        spanwiseSkinFriction();
        translatedBodyFrame();
        invalidGeometryAndEmptyBatches();
        System.out.println("External aerodynamic API checks passed: " + checks);
    }

    private static double[] wing() {
        double[] a = new double[ExternalLiftingSurfaceApi.INPUT_STRIDE];
        a[0] = 3.0; a[1] = 1.0; a[2] = -2.0;
        a[5] = 1.0;  // chord +Z
        a[6] = 1.0;  // span +X
        a[10] = 1.0; // normal +Y
        a[12] = 10.0; a[13] = Math.sqrt(80.0); a[14] = 2.0; a[15] = 8.0;
        a[18] = -4.0; a[19] = 40.0;
        System.arraycopy(a, 17, a, 20, 3);
        a[23] = 1.0; a[24] = Double.NaN; a[25] = 0.025; a[26] = 1.0;
        a[27] = 4.8; a[28] = 1.65; a[29] = 0.028;
        a[31] = 0.0; a[32] = 1.225; a[34] = Double.NaN;
        return a;
    }

    private static double[] evaluate(double[] input) {
        double[] out = new double[input.length / ExternalLiftingSurfaceApi.INPUT_STRIDE
            * ExternalLiftingSurfaceApi.OUTPUT_STRIDE];
        require(ExternalLiftingSurfaceApi.evaluatePackedInto(input, out), "valid surface rejected");
        for (double value : out) require(Double.isFinite(value), "non-finite output");
        return out;
    }

    private static void rotationCovariance() {
        double[] input = wing(), original = evaluate(input);
        // Exact cyclic permutation is a proper 3-D rotation; includes a vertical-fin orientation.
        for (int offset : new int[]{0, 3, 6, 9, 17, 20}) {
            double x = input[offset];
            input[offset] = input[offset + 1];
            input[offset + 1] = input[offset + 2];
            input[offset + 2] = x;
        }
        double[] rotated = evaluate(input);
        near(rotated[0], original[1], "rotated force X");
        near(rotated[1], original[2], "rotated force Y");
        near(rotated[2], original[0], "rotated force Z");
        for (int i = 6; i < rotated.length; i++) near(rotated[i], original[i], "invariant coefficient " + i);
        near(rotated[3], input[0], "application point");
    }

    private static void areaAndSpeedScaling() {
        double[] input = wing(), baseline = evaluate(input);
        input[12] *= 2.0;
        double[] doubledArea = evaluate(input);
        for (int i = 0; i < 3; i++) near(doubledArea[i], baseline[i] * 2.0, "linear area scaling");
        input = wing();
        for (int i = 17; i < 23; i++) input[i] *= 2.0;
        double[] doubledSpeed = evaluate(input);
        for (int i = 0; i < 3; i++) near(doubledSpeed[i], baseline[i] * 4.0, "quadratic speed scaling");
        require(baseline[1] > 0.0, "positive alpha must produce positive normal lift");
        double dragPower = baseline[0] * input[17] + baseline[1] * input[18] + baseline[2] * input[19];
        require(dragPower < 0.0, "unpowered surface must oppose relative-flow energy");
    }

    private static void physicalStallAndInducedFlow() {
        double[] input = wing(), baseline = evaluate(input);
        input[21] += 3.0;
        double[] induced = evaluate(input);
        near(induced[8], baseline[8], "induced flow must not change physical alpha");
        near(induced[9], baseline[9], "induced flow must not change separation");
        near(induced[10], baseline[10], "induced flow must not change separation target");
        require(induced[6] < baseline[6], "downwash should reduce attached tail CL");
        input[24] = 1.0; input[26] = 0.0;
        double[] separatedAdjusted = evaluate(input);
        input[23] = 0.0;
        double[] separatedPhysical = evaluate(input);
        for (int i = 0; i < 3; i++) near(separatedAdjusted[i], separatedPhysical[i], "separated flow ignores relief");
    }

    private static void substepConvergence() {
        double[] full = wing();
        full[18] = -30.0; full[19] = 20.0;
        System.arraycopy(full, 17, full, 20, 3);
        full[24] = 0.2; full[25] = 0.05;
        double expected = evaluate(full)[9];
        double[] halves = full.clone();
        halves[25] = 0.025;
        halves[24] = evaluate(halves)[9];
        near(evaluate(halves)[9], expected, "convective separation independent of substep split");
        full[26] = 0.0;
        near(evaluate(full)[9], 0.2, "owner preview must not advance separation");
    }

    private static void reverseAndQuietFlow() {
        double[] input = wing();
        input[18] = 0.0; input[19] = -40.0;
        System.arraycopy(input, 17, input, 20, 3);
        double[] reverse = evaluate(input);
        near(reverse[9], 1.0, "reverse flow separation");
        require(reverse[2] > 0.0, "reverse-flow drag direction");
        Arrays.fill(input, 17, 23, 0.0);
        input[24] = 0.8;
        double[] quiet = evaluate(input);
        for (int i = 0; i < 3; i++) near(quiet[i], 0.0, "quiet force");
        require(quiet[9] < 0.8 && quiet[9] >= 0.0, "quiet separation relaxation");
    }

    private static void bodyForceAndMoment() {
        // Normal +X, transverse body pressure, no tangential skin flow.
        double[] patch = {0, 2, 0, 1, 0, 0, 3, 1, 10, 0, 0};
        double[] out = new double[11];
        require(ExternalAirframeBodyApi.evaluatePackedInto(patch, out, 1.2, 0.18, 1.0,
            2, 2, 8, 3, 0, 0, 0), "body patch rejected");
        near(out[0], -180.0, "body pressure SI conversion");
        near(out[5], 360.0, "body r cross F moment");
        patch[8] = -10.0;
        require(ExternalAirframeBodyApi.evaluatePackedInto(patch, out, 1.2, 0.18, 1.0,
            2, 2, 8, 3, 0, 0, 0), "reverse body patch rejected");
        near(out[0], 180.0, "two-sided pressure reversal");
        near(out[5], -360.0, "two-sided moment reversal");
    }

    private static void invalidBatchClearsOutput() {
        double[] first = wing();
        double[] batch = new double[first.length * 2];
        System.arraycopy(first, 0, batch, 0, first.length);
        System.arraycopy(first, 0, batch, first.length, first.length);
        batch[first.length + 17] = Double.NaN;
        double[] output = new double[ExternalLiftingSurfaceApi.OUTPUT_STRIDE * 2];
        Arrays.fill(output, 123.0);
        require(!ExternalLiftingSurfaceApi.evaluatePackedInto(batch, output), "invalid batch accepted");
        for (double value : output) near(value, 0.0, "partial invalid result leaked");
        first[12] = -1.0;
        require(!ExternalLiftingSurfaceApi.evaluatePackedInto(first,
            new double[ExternalLiftingSurfaceApi.OUTPUT_STRIDE]), "negative area accepted");
    }

    private static void spanwiseSkinFriction() {
        double[] input = wing();
        Arrays.fill(input, 17, 23, 0.0);
        input[17] = input[20] = 20.0;
        input[31] = 2.0; input[29] = 0.0;
        double[] out = evaluate(input);
        require(out[0] < 0.0, "pure span flow must retain skin friction");
        near(out[1], 0.0, "pure span flow cannot create normal lift");
        near(out[2], 0.0, "pure span flow cannot create chord force");
        near(out[6], 0.0, "quiet chord-normal flow CL");
        double originalDrag = out[0];
        for (int offset : new int[]{0, 3, 6, 9, 17, 20}) {
            double x = input[offset];
            input[offset] = input[offset + 1];
            input[offset + 1] = input[offset + 2];
            input[offset + 2] = x;
        }
        near(evaluate(input)[2], originalDrag, "rotated fin span skin friction");
    }

    private static void translatedBodyFrame() {
        double[] patch = {0, 2, 0, 1, 0, 0, 3, 1, 10, 0, 0};
        double[] original = new double[11], translated = new double[11];
        require(ExternalAirframeBodyApi.evaluatePackedInto(patch, original, 1.2, 0.18, 1,
            2, 2, 8, 3, 0, 0, 0), "body reference rejected");
        patch[0] += 7; patch[1] -= 5; patch[2] += 11;
        require(ExternalAirframeBodyApi.evaluatePackedInto(patch, translated, 1.2, 0.18, 1,
            2, 2, 8, 3, 7, -5, 11), "translated body rejected");
        for (int i = 0; i < 6; i++) near(translated[i], original[i], "uniform origin translation");
    }

    private static void invalidGeometryAndEmptyBatches() {
        require(ExternalLiftingSurfaceApi.API_VERSION == 2 && ExternalAirframeBodyApi.API_VERSION == 2,
            "packed API version contract");
        require(ExternalLiftingSurfaceApi.evaluatePackedInto(new double[0], new double[0]), "empty lift batch");
        double[] empty = new double[6]; Arrays.fill(empty, 123.0);
        require(ExternalAirframeBodyApi.evaluatePackedInto(new double[0], empty, 1.2, 0.18, 1,
            2, 2, 8, 0, 0, 0, 0), "empty body batch");
        for (double value : empty) near(value, 0, "empty body result");
        for (int field : new int[]{6, 7, 3}) {
            double[] patch = {0, 2, 0, 1, 0, 0, 3, 1, 10, 0, 0};
            patch[field] = field == 6 ? -1 : field == 7 ? 0.5 : 0;
            double[] result = new double[11]; Arrays.fill(result, 123.0);
            require(!ExternalAirframeBodyApi.evaluatePackedInto(patch, result, 1.2, 0.18, 1,
                2, 2, 8, 3, 0, 0, 0), "invalid body geometry accepted");
            for (double value : result) near(value, 0, "invalid body output not cleared");
        }
        for (int field : new int[]{23, 26, 30}) {
            double[] input = wing(); input[field] = 0.5;
            double[] result = new double[15]; Arrays.fill(result, 123.0);
            require(!ExternalLiftingSurfaceApi.evaluatePackedInto(input, result), "invalid lift flag accepted");
            for (double value : result) near(value, 0, "invalid lift flag output not cleared");
        }
        double[] alias = wing(), before = alias.clone();
        require(!ExternalLiftingSurfaceApi.evaluatePackedInto(alias, alias), "aliased buffers accepted");
        require(Arrays.equals(alias, before), "rejected alias mutated caller input");
        double[] vacuum = wing(); vacuum[32] = 0.0;
        double[] zero = evaluate(vacuum);
        for (int i = 0; i < 3; i++) near(zero[i], 0, "vacuum force");
    }

    private static void near(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= 1E-8 * Math.max(1.0, Math.abs(expected)),
            message + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
