package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.Arrays;

/** Independently authored regional shape, roughening and spectral smoothing passes.
 * Four seeded profiles blend in absolute coordinates. No neighbor terrain or world access. */
public final class RegionalTerrainPasses {
    public static final int VERSION = 1;

    public enum Formation {
        HUMMOCKS,
        KNOLLS,
        RIDGES,
        BENCHES,
        DUNES
    }

    private static final Formation[] KINDS = Formation.values();
    private static final Definition DEFINITION = Definition.load();
    public static final String FINGERPRINT = DEFINITION.fingerprint;
    private final long seed;
    private final SeededNoise shapeA,
            shapeB,
            shapeC,
            warpX,
            warpZ,
            roughA,
            roughB,
            controlsA,
            controlsB;
    private final Profile[] profiles = new Profile[KINDS.length];
    private final double regionSpan, warpAmplitude, roughAmplitude, roughWave;

    RegionalTerrainPasses(long worldSeed, TerrainHeightReference heights) {
        seed = SeededNoise.mix(worldSeed ^ DEFINITION.variationSeed);
        shapeA = noise(101);
        shapeB = noise(202);
        shapeC = noise(303);
        warpX = noise(404);
        warpZ = noise(505);
        roughA = noise(606);
        roughB = noise(707);
        controlsA = noise(808);
        controlsB = noise(909);
        for (Formation kind : KINDS)
            profiles[kind.ordinal()] =
                    new Profile(DEFINITION.profiles.getAsJsonObject(kind.name()), heights);
        regionSpan =
                heights.feature("inland_plain").width.targetAverage
                        * DEFINITION.regionSpan
                        * TerrainScale.HORIZONTAL;
        warpAmplitude = heights.feature("knoll").width.min * TerrainScale.HORIZONTAL;
        roughAmplitude = heights.feature("terrace").magnitude.targetAverage / 2;
        roughWave = heights.feature("knoll").width.min * TerrainScale.HORIZONTAL;
    }

    private SeededNoise noise(long salt) {
        return new SeededNoise(SeededNoise.mix(seed ^ salt));
    }

    /** Caller-owned scratch and exposed stage observations; weights describe blended profiles. */
    public static final class Result {
        public double formation,
                formationFine,
                roughening,
                rougheningFine,
                smoothing,
                smoothingStrength,
                rougheningStrength;
        public double afterFormation, afterRoughening, height, appliedDisplacement;
        public Formation dominant;
        public long permutation;
        public final double[] weights = new double[KINDS.length];
        private final double[] admission = new double[KINDS.length];
        private double coarse, fine;

        public void clear(double value) {
            formation =
                    formationFine =
                            roughening =
                                    rougheningFine =
                                            smoothing = smoothingStrength = rougheningStrength = 0;
            afterFormation = afterRoughening = height = value;
            appliedDisplacement = 0;
            dominant = null;
            permutation = 0;
            Arrays.fill(weights, 0);
        }
    }

    public void apply(
            double x,
            double z,
            ClimateMap.Sample climate,
            Terrain.Sample land,
            double originalHeight,
            double originalFine,
            double minimumY,
            double maximumY,
            Result out) {
        Arrays.fill(out.weights, 0);
        out.formation = 0;
        out.formationFine = 0;
        out.permutation = 0;
        double mountain = land.high, hill = land.middle + land.high;
        double steep = land.rugged;
        double dry = 1 - smooth((climate.moisture - 0.20) / (MoistureBand.DRY_LIMIT - 0.20));
        double warm = smooth((climate.celsius - 15) / 6), hot = smooth((climate.celsius - 25) / 4);
        double[] scores = out.admission;
        scores[0] = land.low * .8 + land.middle * .15;
        scores[1] = land.middle * (0.5 + 0.5 * climate.moisture);
        scores[2] = mountain * (0.2 + 0.8 * steep);
        scores[3] = warm * dry * hill * (0.4 + 0.6 * steep);
        scores[4] = hot * dry * (1 - steep) * (land.low + land.middle) * 1.8;
        double total = 0;
        for (double score : scores) total += score;
        for (int i = 0; i < scores.length; i++) scores[i] /= Math.max(1, total);

        double gx = x / regionSpan, gz = z / regionSpan;
        int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
        double u = smooth(gx - ix), v = smooth(gz - iz);
        double wx =
                x + warpAmplitude * warpX.sample(x / (regionSpan * 1.5), z / (regionSpan * 1.5));
        double wz =
                z + warpAmplitude * warpZ.sample(x / (regionSpan * 1.5), z / (regionSpan * 1.5));
        double largest = -1;
        for (int dz = 0; dz < 2; dz++)
            for (int dx = 0; dx < 2; dx++) {
                double patchWeight = (dx == 0 ? 1 - u : u) * (dz == 0 ? 1 - v : v);
                if (patchWeight == 0) continue;
                long key = SeededNoise.cell(seed, ix + dx, iz + dz),
                        second = SeededNoise.mix(key),
                        third = SeededNoise.mix(second);
                double lottery = 0.05 + 0.90 * SeededNoise.unit(key), cdf = 0;
                double angle = SeededNoise.unit(second) * Math.PI * 2,
                        c = Math.cos(angle),
                        s = Math.sin(angle);
                double rx = wx * c - wz * s, rz = wx * s + wz * c;
                double scale = 0.7 + 0.6 * SeededNoise.unit(third);
                double strength = 0.7 + 0.6 * SeededNoise.unit(SeededNoise.mix(third));
                double phase = SeededNoise.unit(SeededNoise.mix(key ^ 0x5048415345L)) * 31;
                for (int i = 0; i < KINDS.length; i++) {
                    double next = cdf + scores[i];
                    // Smooth weighted lottery: eligibility can vary without a hard family switch.
                    double weight =
                            (smooth((next - lottery) / 0.08 + 0.5)
                                            - smooth((cdf - lottery) / 0.08 + 0.5))
                                    * patchWeight;
                    cdf = next;
                    if (weight <= 0) continue;
                    shape(KINDS[i], rx, rz, scale, strength, phase, originalHeight, out);
                    out.formation += out.coarse * weight;
                    out.formationFine += out.fine * weight;
                    out.weights[i] += weight;
                    if (weight > largest) {
                        largest = weight;
                        out.permutation = key;
                    }
                }
            }
        int dominant = Rules.choose(out.weights, 0);
        double peak = 0;
        for (int i = 0; i < KINDS.length; i++)
            if (out.weights[i] > peak) {
                peak = out.weights[i];
                dominant = i;
            }
        out.dominant = peak > 0 ? KINDS[dominant] : null;
        // Regional strength controls are independent, continuous fields, not biome steps.
        // Rotation, climate warp and a second scale conceal the value-noise lattice.
        double cx = (0.8 * climate.warpedX - 0.6 * climate.warpedZ) / regionSpan;
        double cz = (0.6 * climate.warpedX + 0.8 * climate.warpedZ) / regionSpan;
        double roughControl =
                (controlsA.sample(cx + 0.37, cz + 0.71)
                                + 0.35 * controlsB.sample(cx / 0.53 + 0.43, cz / 0.53 + 0.29))
                        / 1.35;
        double smoothControl =
                (controlsB.sample(cx / 1.5 + 0.83, cz / 1.5 + 0.19)
                                + 0.35 * controlsA.sample(cx / 0.83 + 0.61, cz / 0.83 + 0.47))
                        / 1.35;
        out.rougheningStrength =
                lerp(DEFINITION.roughMin, DEFINITION.roughMax, smooth(0.5 + 0.5 * roughControl))
                        * (0.45 + 0.55 * mountain + 0.2 * dry);
        out.smoothingStrength =
                lerp(DEFINITION.smoothMin, DEFINITION.smoothMax, smooth(0.5 + 0.5 * smoothControl))
                        * (0.45 + 0.55 * climate.moisture)
                        * (1 - 0.4 * mountain);
        roughen(wx, wz, out);
        // The detail component is explicitly separated: broad terrain and primary shapes survive.
        out.smoothing =
                smoothDetail(
                        originalFine + out.formationFine + out.rougheningFine,
                        out.smoothingStrength);
        double formed = out.formation + out.formationFine;
        out.afterFormation = bounded(originalHeight, formed, minimumY, maximumY);
        out.afterRoughening = bounded(originalHeight, formed + out.roughening, minimumY, maximumY);
        out.height =
                bounded(
                        originalHeight,
                        formed + out.roughening + out.smoothing,
                        minimumY,
                        maximumY);
        out.appliedDisplacement = out.height - originalHeight;
    }

    private void shape(
            Formation kind,
            double x,
            double z,
            double scale,
            double strength,
            double phase,
            double height,
            Result out) {
        Profile p = profiles[kind.ordinal()];
        double wave = p.wave * scale, amp = p.amplitude * strength;
        double ux = x / wave + phase, uz = z / wave + phase * 0.37;
        double n = shapeA.sample(ux, uz);
        double m =
                kind == Formation.HUMMOCKS || kind == Formation.DUNES
                        ? 0
                        : shapeB.sample(ux + 0.47, uz + 0.83);
        switch (kind) {
            case HUMMOCKS:
                out.coarse = amp * n;
                out.fine = amp * p.fineGain * shapeC.sample(ux * 2.7, uz * 2.7);
                break;
            // Paired fields have matching marginal distributions, avoiding an authored upward bias.
            case KNOLLS:
                out.coarse = amp * 1.5 * (n * n - m * m);
                out.fine = amp * p.fineGain * shapeC.sample(ux * 2.5, uz * 2.5);
                break;
            case RIDGES:
                out.coarse = amp * 1.8 * (Math.sqrt(m * m + 0.0025) - Math.sqrt(n * n + 0.0025));
                out.fine =
                        amp
                                * p.fineGain
                                * shapeC.sample(ux * 3, uz * 3)
                                * (1 - 0.7 * smooth(Math.abs(n)));
                break;
            case DUNES:
                double t = ux + 0.22 * n;
                t -= Math.floor(t);
                double crest = 0.62 + 0.20 * (phase / 31);
                double dune = t < crest ? smooth(t / crest) : 1 - smooth((t - crest) / (1 - crest));
                out.coarse = amp * 2 * (dune - 0.5);
                out.fine = amp * p.fineGain * shapeC.sample(ux * 2.5, uz * 2.5);
                break;
            case BENCHES:
                double step = amp * 2,
                        datum = phase * step + step * 0.8 * shapeA.sample(ux / 8, uz / 8);
                double q = (height - datum) / step, level = Math.floor(q), f = q - level;
                out.coarse = datum + step * (level + smooth((f - 0.35) / 0.30)) - height;
                out.fine = amp * p.fineGain * m;
                break;
            default:
                throw new AssertionError(kind);
        }
    }

    private void roughen(double x, double z, Result out) {
        double amp = roughAmplitude * out.rougheningStrength;
        double low =
                amp
                        * 0.7
                        * roughA.sample(
                                (0.8 * x - 0.6 * z) / roughWave, (0.6 * x + 0.8 * z) / roughWave);
        out.rougheningFine =
                amp
                        * 0.3
                        * roughB.sample(
                                (0.6 * x + 0.8 * z) / (roughWave * 0.4),
                                (-0.8 * x + 0.6 * z) / (roughWave * 0.4));
        out.roughening = low + out.rougheningFine;
    }

    /** Spectral attenuation of known detail components; this is not a neighbor-height blur. */
    public static double smoothDetail(double detail, double strength) {
        return -detail * strength;
    }

    private static double bounded(double original, double delta, double min, double max) {
        double room = delta >= 0 ? max - original : original - min;
        return room <= 0 ? original : original + room * Math.tanh(delta / room);
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static final class Profile {
        final double amplitude, wave, fineGain;

        Profile(JsonObject json, TerrainHeightReference heights) {
            TerrainHeightReference.Feature feature =
                    heights.feature(json.get("reference").getAsString());
            amplitude = feature.magnitude.targetAverage * positive(json, "amplitudeScale");
            wave =
                    feature.width.targetAverage
                            * TerrainScale.HORIZONTAL
                            * positive(json, "wavelengthScale");
            fineGain = unit(json, "fineGain");
        }
    }

    private static final class Definition {
        final String fingerprint;
        final long variationSeed;
        final double regionSpan, roughMin, roughMax, smoothMin, smoothMax;
        final JsonObject profiles;

        Definition(JsonObject json, String fingerprint) {
            if (json.get("schema").getAsInt() != 1)
                throw new IllegalStateException("Terrain pass schema");
            this.fingerprint = fingerprint;
            variationSeed =
                    Long.parseUnsignedLong(
                            json.get("variationSeed").getAsString().substring(2), 16);
            regionSpan = positive(json, "regionSpanInPlainWidths");
            profiles = json.getAsJsonObject("profiles");
            JsonObject rough = json.getAsJsonObject("roughening"),
                    smooth = json.getAsJsonObject("smoothing");
            roughMin = nonnegative(rough, "minimumStrength");
            roughMax = nonnegative(rough, "maximumStrength");
            smoothMin = unit(smooth, "minimumStrength");
            smoothMax = unit(smooth, "maximumStrength");
            if (roughMin > roughMax || smoothMin > smoothMax)
                throw new IllegalStateException("Terrain pass strength range");
        }

        static Definition load() {
            Rules rules = Rules.load("riven-terrain-passes.json", 1);
            return new Definition(rules.json, rules.fingerprint);
        }
    }

    private static double positive(JsonObject json, String key) {
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value <= 0) throw new IllegalStateException(key);
        return value;
    }

    private static double nonnegative(JsonObject json, String key) {
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0) throw new IllegalStateException(key);
        return value;
    }

    private static double unit(JsonObject json, String key) {
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalStateException(key);
        return value;
    }
}
