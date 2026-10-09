package com.axes.pmweather_aeronautics;

/**
 * Generic aerodynamic body-pressure evaluator for arbitrary Sable-owned rigid bodies.
 *
 * <p>This API deliberately knows nothing about Minecraft blocks, ServerSubLevels, IV, or a
 * particular aircraft. The owner supplies semantically classified body pressure patches and the
 * already-resolved local relative airflow at each patch. PMAero owns the pressure/skin-friction
 * law; the integration that owns the Sable body remains responsible for applying the returned
 * force/torque during its physics substep.</p>
 *
 * <p>Collision geometry and aerodynamic semantics are separate on purpose. A voxelized Sable
 * collision shell may contain fuselage, wing and tail geometry after merging, so blindly treating
 * every collision cuboid as body drag can double-count lifting surfaces. Integrations should pass
 * their semantic non-lifting/body pressure surface map here.</p>
 */
public final class ExternalAirframeBodyApi {
    /** point xyz, normal xyz, represented area, two-sided(1/0), relative-air xyz. */
    public static final int API_VERSION = 2;
    public static final int INPUT_STRIDE = 11;
    /** total force xyz, total torque xyz, then five values per patch. */
    public static final int OUTPUT_HEADER_STRIDE = 6;
    /** per patch force xyz, pressure coefficient, effective skin-friction coefficient. */
    public static final int OUTPUT_PATCH_STRIDE = 5;

    private static final double EPSILON = 1.0e-9D;
    private static final double AIR_DYNAMIC_VISCOSITY_PASCAL_SECONDS = 1.81e-5D;
    private static final double MIN_REYNOLDS_NUMBER = 1.0e4D;

    private ExternalAirframeBodyApi() {
    }

    /**
     * Evaluate one arbitrary airframe body for one rigid-body physics state.
     *
     * @param patchData packed {@link #INPUT_STRIDE}-wide patch records
     * @return packed result. Header is aggregate local force + body torque; each patch then has
     *         its local force and coefficients for diagnostics. Invalid input returns an empty array.
     */
    public static double[] evaluatePacked(final double[] patchData,
                                          final double densityKgM3,
                                          final double axialDragCoefficient,
                                          final double crossflowDragCoefficient,
                                          final double bodyWidthMeters,
                                          final double bodyHeightMeters,
                                          final double bodyLengthMeters,
                                          final double pressureWettedAreaM2,
                                          final double centerOfMassX,
                                          final double centerOfMassY,
                                          final double centerOfMassZ) {
        if (patchData == null || patchData.length % INPUT_STRIDE != 0) {
            return new double[0];
        }
        final int patchCount = patchData.length / INPUT_STRIDE;
        final double[] result = new double[OUTPUT_HEADER_STRIDE + patchCount * OUTPUT_PATCH_STRIDE];
        return evaluatePackedInto(
                patchData, result, densityKgM3, axialDragCoefficient, crossflowDragCoefficient,
                bodyWidthMeters, bodyHeightMeters, bodyLengthMeters, pressureWettedAreaM2,
                centerOfMassX, centerOfMassY, centerOfMassZ
        ) ? result : new double[0];
    }

    /** Allocation-free variant for physics owners that keep one reusable buffer per body. */
    public static boolean evaluatePackedInto(final double[] patchData,
                                             final double[] result,
                                             final double densityKgM3,
                                             final double axialDragCoefficient,
                                             final double crossflowDragCoefficient,
                                             final double bodyWidthMeters,
                                             final double bodyHeightMeters,
                                             final double bodyLengthMeters,
                                             final double pressureWettedAreaM2,
                                             final double centerOfMassX,
                                             final double centerOfMassY,
                                             final double centerOfMassZ) {
        if (result != null && result != patchData) java.util.Arrays.fill(result, 0.0D);
        if (patchData == null || patchData.length % INPUT_STRIDE != 0
                || !Double.isFinite(densityKgM3) || densityKgM3 < 0.0
                || !finitePositive(bodyWidthMeters)
                || !finitePositive(bodyHeightMeters)
                || !finitePositive(bodyLengthMeters)
                || !Double.isFinite(centerOfMassX)
                || !Double.isFinite(centerOfMassY)
                || !Double.isFinite(centerOfMassZ)) {
            return false;
        }
        final int patchCount = patchData.length / INPUT_STRIDE;
        final int expectedOutputLength = OUTPUT_HEADER_STRIDE + patchCount * OUTPUT_PATCH_STRIDE;
        if (result == null || result == patchData || result.length != expectedOutputLength) {
            return false;
        }
        if (!Double.isFinite(axialDragCoefficient) || axialDragCoefficient < 0.0
            || !Double.isFinite(crossflowDragCoefficient) || crossflowDragCoefficient < 0.0
            || !Double.isFinite(pressureWettedAreaM2) || pressureWettedAreaM2 < 0.0) return false;
        for (double value : patchData) if (!Double.isFinite(value)) return false;
        for (int i = 0; i < patchData.length; i += INPUT_STRIDE) {
            if (patchData[i + 6] < 0.0 || (patchData[i + 7] != 0.0 && patchData[i + 7] != 1.0)) return false;
            double n = Math.hypot(Math.hypot(patchData[i + 3], patchData[i + 4]), patchData[i + 5]);
            if (patchData[i + 6] > 0.0 && (!Double.isFinite(n) || n <= EPSILON)) return false;
        }
        if (densityKgM3 == 0.0) return true;
        double projectedAreaSum = 0.0D;
        for (int patch = 0; patch < patchCount; patch++) {
            final int base = patch * INPUT_STRIDE;
            final double area = safeNonNegative(patchData[base + 6]);
            projectedAreaSum += area;
        }
        final double safeWettedArea = safeNonNegative(pressureWettedAreaM2);
        final double axialScale = bodyAxialPressureScale(bodyWidthMeters, bodyHeightMeters, bodyLengthMeters);

        double totalFx = 0.0D;
        double totalFy = 0.0D;
        double totalFz = 0.0D;
        double totalTx = 0.0D;
        double totalTy = 0.0D;
        double totalTz = 0.0D;

        for (int patch = 0; patch < patchCount; patch++) {
            final int base = patch * INPUT_STRIDE;
            final int out = OUTPUT_HEADER_STRIDE + patch * OUTPUT_PATCH_STRIDE;

            final double px = patchData[base];
            final double py = patchData[base + 1];
            final double pz = patchData[base + 2];
            double nx = patchData[base + 3];
            double ny = patchData[base + 4];
            double nz = patchData[base + 5];
            final double area = safeNonNegative(patchData[base + 6]);
            final boolean twoSided = patchData[base + 7] > 0.5D;
            final double vx = patchData[base + 8];
            final double vy = patchData[base + 9];
            final double vz = patchData[base + 10];

            if ((!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz) || !Double.isFinite(nx) || !Double.isFinite(ny) || !Double.isFinite(nz) || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) || area <= EPSILON) {
                continue;
            }
            final double normalLength = Math.hypot(Math.hypot(nx, ny), nz);
            if (!Double.isFinite(normalLength) || normalLength <= EPSILON) {
                continue;
            }
            nx /= normalLength;
            ny /= normalLength;
            nz /= normalLength;

            final double transverse = Math.sqrt(nx * nx + ny * ny);
            final double pressureCoefficient =
                    safeNonNegative(axialDragCoefficient) * Math.abs(nz) * axialScale
                            + safeNonNegative(crossflowDragCoefficient) * transverse;
            final double normalSpeed = vx * nx + vy * ny + vz * nz;
            if (!Double.isFinite(normalSpeed)) {
                java.util.Arrays.fill(result, 0.0D);
                return false;
            }
            final double signedPressure = pressureMagnitude(
                    densityKgM3, pressureCoefficient, area, normalSpeed, twoSided
            );
            double fx = nx * signedPressure;
            double fy = ny * signedPressure;
            double fz = nz * signedPressure;

            final double skinArea = safeWettedArea > EPSILON && projectedAreaSum > EPSILON
                    ? safeWettedArea * area / projectedAreaSum
                    : area;
            final double txFlow = vx - nx * normalSpeed;
            final double tyFlow = vy - ny * normalSpeed;
            final double tzFlow = vz - nz * normalSpeed;
            final double tangentialSpeed = Math.sqrt(txFlow * txFlow + tyFlow * tyFlow + tzFlow * tzFlow);
            final double skinCoefficient = turbulentSkinFrictionCoefficient(
                    densityKgM3, tangentialSpeed, bodyLengthMeters
            );
            if (skinCoefficient > 0.0D && tangentialSpeed > EPSILON && skinArea > EPSILON) {
                final double skinScale = -0.5D * densityKgM3 * skinArea * skinCoefficient * tangentialSpeed;
                fx += txFlow * skinScale;
                fy += tyFlow * skinScale;
                fz += tzFlow * skinScale;
            }

            final double effectiveSkinCoefficient = skinCoefficient * skinArea / Math.max(EPSILON, area);
            if ((!Double.isFinite(fx) || !Double.isFinite(fy) || !Double.isFinite(fz) || !Double.isFinite(pressureCoefficient) || !Double.isFinite(effectiveSkinCoefficient))) {
                java.util.Arrays.fill(result, 0.0D);
                return false;
            }
            result[out] = finiteOrZero(fx);
            result[out + 1] = finiteOrZero(fy);
            result[out + 2] = finiteOrZero(fz);
            result[out + 3] = finiteOrZero(pressureCoefficient);
            result[out + 4] = finiteOrZero(effectiveSkinCoefficient);

            final double rx = px - centerOfMassX;
            final double ry = py - centerOfMassY;
            final double rz = pz - centerOfMassZ;
            final double torqueX = ry * fy - rz * fz;
            final double torqueY = rz * fx - rx * fz;
            final double torqueZ = rx * fy - ry * fx;

            totalFx += fx;
            totalFy += fy;
            totalFz += fz;
            totalTx += torqueX;
            totalTy += torqueY;
            totalTz += torqueZ;
        }

        if ((!Double.isFinite(totalFx) || !Double.isFinite(totalFy) || !Double.isFinite(totalFz) || !Double.isFinite(totalTx) || !Double.isFinite(totalTy) || !Double.isFinite(totalTz))) {
            java.util.Arrays.fill(result, 0.0D);
            return false;
        }
        result[0] = finiteOrZero(totalFx);
        result[1] = finiteOrZero(totalFy);
        result[2] = finiteOrZero(totalFz);
        result[3] = finiteOrZero(totalTx);
        result[4] = finiteOrZero(totalTy);
        result[5] = finiteOrZero(totalTz);
        return true;
    }


    private static double pressureMagnitude(final double density,
                                            final double coefficient,
                                            final double area,
                                            final double normalSpeed,
                                            final boolean twoSided) {
        if (coefficient <= 0.0D || area <= 0.0D || !Double.isFinite(normalSpeed)) {
            return 0.0D;
        }
        if (twoSided) {
            if (Math.abs(normalSpeed) <= EPSILON) {
                return 0.0D;
            }
            return -0.5D * density * coefficient * area * normalSpeed * Math.abs(normalSpeed);
        }
        if (normalSpeed <= 0.0D) {
            return 0.0D;
        }
        return -0.5D * density * coefficient * area * normalSpeed * normalSpeed;
    }

    public static double turbulentSkinFrictionCoefficient(final double density,
                                                            final double speedMetersPerSecond,
                                                            final double characteristicLengthMeters) {
        if (!finitePositive(density) || !finitePositive(speedMetersPerSecond)
                || !finitePositive(characteristicLengthMeters)) {
            return 0.0D;
        }
        final double reynolds = Math.max(
                MIN_REYNOLDS_NUMBER,
                density * speedMetersPerSecond * characteristicLengthMeters
                        / AIR_DYNAMIC_VISCOSITY_PASCAL_SECONDS
        );
        final double logarithm = Math.log10(reynolds);
        return finiteOrZero(0.455D / Math.pow(logarithm, 2.58D));
    }

    public static double bodyAxialPressureScale(final double bodyWidthMeters,
                                                 final double bodyHeightMeters,
                                                 final double bodyLengthMeters) {
        if (!finitePositive(bodyWidthMeters) || !finitePositive(bodyHeightMeters)
                || !finitePositive(bodyLengthMeters)) {
            return 1.0D;
        }
        final double equivalentDiameter = Math.sqrt(Math.max(EPSILON, bodyWidthMeters * bodyHeightMeters));
        final double finenessRatio = bodyLengthMeters / equivalentDiameter;
        return 1.0D / Math.max(1.0D, finenessRatio);
    }

    private static boolean finitePositive(final double value) {
        return Double.isFinite(value) && value > 0.0D;
    }

    private static double safeNonNegative(final double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    private static double finiteOrZero(final double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }


}
