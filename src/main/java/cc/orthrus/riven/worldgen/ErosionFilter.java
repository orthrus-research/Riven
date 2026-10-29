/*
 * Adapted from Rune Skovbo Johansen's Advanced Terrain Erosion Filter (2026),
 * and Luke Mitchell's CPU implementation, revision 57e79cc331d17e2a45c897bea9c4c022d1fc5551.
 * Phacelle Noise copyright (c) 2025 Rune Skovbo Johansen.
 * Java adaptation (c) 2026 Riven contributors: integer seed hashing, block-unit
 * controls, caller-owned scratch, bounded displacement and slope admission.
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. See META-INF/licenses/erosion/MPL-2.0.txt and THIRD-PARTY-NOTICES.txt.
 */
package cc.orthrus.riven.worldgen;

/** Point-local erosion in nominal horizontal units. TerrainMap applies the physical scale.
 * Steering and ridge tendency are not measured slope or drainage. */
public final class ErosionFilter {
    public static final int OCTAVES = 3;
    private static final double TAU = Math.PI * 2;
    private static final double CELL_SCALE = 0.7;
    private static final double CUTOFF = Math.exp(-4.5);
    private final long seed;
    private final double wavelength, firstAmplitude, rounding;

    public ErosionFilter(long worldSeed, TerrainHeightReference heights) {
        seed = SeededNoise.mix(worldSeed ^ 0x45524f53494f4e31L);
        wavelength = heights.feature("ridge_spur").width.targetAverage;
        firstAmplitude = heights.feature("ridge_spur").magnitude.targetAverage / 2;
        rounding = heights.feature("terrace").magnitude.min / 2;
    }

    public static final class Result implements StageResult {
        public double displacement, coarseDisplacement, ridgeTendency;
        public int octavesEvaluated, kernelVisits;
        private double cosine, sine;

        /** Erosion displaces its surface rather than replacing it: no displacement, no work. */
        @Override
        public void clear(double surface) {
            displacement = coarseDisplacement = ridgeTendency = 0;
            octavesEvaluated = kernelVisits = 0;
        }
    }

    public void apply(
            double x,
            double z,
            double gradientX,
            double gradientZ,
            double fadeTarget,
            double headroom,
            int octaves,
            Result out) {
        out.displacement = 0;
        out.coarseDisplacement = 0;
        out.ridgeTendency = 0;
        out.octavesEvaluated = 0;
        out.kernelVisits = 0;
        if (octaves < 0 || octaves > OCTAVES)
            throw new IllegalArgumentException("Erosion octave count");
        double slope = Math.hypot(gradientX, gradientZ);
        double admission = smooth((slope - 0.025) / 0.30);
        if (admission == 0 || octaves == 0 || headroom <= 0) return;
        double requested = firstAmplitude * (2 - Math.scalb(1.0, 1 - octaves));
        double budget = Math.min(requested, headroom * 0.75) * admission;
        double amplitude = budget / (2 - Math.scalb(1.0, 1 - octaves));
        double gx = gradientX / slope * 0.4, gz = gradientZ / slope * 0.4;
        double mask = easeOut(slope * 2), ridgeMask = mask, ridgeFade = fadeTarget;
        double wave = wavelength;
        for (int octave = 0; octave < octaves; octave++) {
            double length = Math.hypot(gx, gz),
                    sideX = -gz / length * TAU / wave,
                    sideZ = gx / length * TAU / wave;
            phacelle(x, z, sideX, sideZ, wave, octave, out);
            // Straight-gully steering is unmasked and deliberately approximate.
            gx -= Math.signum(out.sine) * sideX * amplitude * 0.5;
            gz -= Math.signum(out.sine) * sideZ * amplitude * 0.5;
            double gully = lerp(fadeTarget, out.cosine * 0.5, mask);
            out.displacement += gully * amplitude;
            if (octave == 0) out.coarseDisplacement = out.displacement;
            fadeTarget = gully;
            double round = lerp(rounding * 3, rounding, clamp(out.cosine + 0.5)) * TAU / wave;
            double newMask = easeOut(smoothStart(Math.abs(out.sine) * 1.25, round));
            mask = (1 - Math.pow(1 - clamp(mask), 1.5)) * newMask;
            ridgeFade = lerp(ridgeFade, out.cosine, ridgeMask);
            ridgeMask *= easeOut(Math.abs(out.sine) * 1.5);
            amplitude *= 0.5;
            wave *= 0.5;
            out.octavesEvaluated++;
        }
        out.ridgeTendency = ridgeFade * (1 - ridgeMask);
    }

    private void phacelle(
            double x, double z, double sideX, double sideZ, double wave, int octave, Result out) {
        double cell = wave * CELL_SCALE, ux = x / cell, uz = z / cell;
        int ix = (int) Math.floor(ux), iz = (int) Math.floor(uz);
        double fx = ux - ix, fz = uz - iz, cosine = 0, sine = 0, total = 0;
        for (int dz = -1; dz <= 2; dz++)
            for (int dx = -1; dx <= 2; dx++) {
                out.kernelVisits++;
                long id =
                        SeededNoise.cell(
                                seed ^ ((long) octave * 0x9e3779b97f4a7c15L), ix + dx, iz + dz);
                double rx = fx - dx - (SeededNoise.unit(id) - 0.5);
                double rz = fz - dz - (SeededNoise.unit(SeededNoise.mix(id)) - 0.5);
                double weight = Math.max(0, Math.exp(-2 * (rx * rx + rz * rz)) - CUTOFF);
                if (weight == 0) continue;
                double phase = (rx * sideX + rz * sideZ) * cell + TAU * 0.25;
                cosine += Math.cos(phase) * weight;
                sine += Math.sin(phase) * weight;
                total += weight;
            }
        cosine /= total;
        sine /= total;
        double magnitude = Math.max(0.5, Math.hypot(cosine, sine));
        out.cosine = cosine / magnitude;
        out.sine = sine / magnitude;
    }

    private static double smoothStart(double value, double rounding) {
        return value >= rounding ? value - rounding * 0.5 : value * value / (2 * rounding);
    }

    private static double smooth(double value) {
        double t = clamp(value);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double easeOut(double value) {
        double t = 1 - clamp(value);
        return 1 - t * t;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
