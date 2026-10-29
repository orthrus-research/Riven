package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;

/** Finite seeded landforms. No chunk reads, caches, workers or mutable map-owned scratch. */
public final class RegionalLandmarks {
    public static final int VERSION = 2;

    public enum Family {
        CAPPED_HILL(0x4341505045443031L),
        CURVED_RIDGE(0x4355525645443031L),
        RIMMED_BASIN(0x424153494e303031L),
        FAN_APRON(0x46414e3030303031L),
        BRANCHING_RAVINE(0x524156494e453031L),
        SCULPTED_DUNE(0x44554e4530303031L);
        final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    private static final Family[] FAMILIES = Family.values();
    private static final Definition DEFINITION = Definition.load();
    public static final String FINGERPRINT = DEFINITION.fingerprint;
    private final long seed;
    private final Sites sites;
    private final Profile[] profiles = new Profile[FAMILIES.length];
    public final double spacing, maximumRadius;

    public RegionalLandmarks(long worldSeed, TerrainHeightReference heights, Sites sites) {
        seed = SeededNoise.mix(worldSeed ^ DEFINITION.variationSeed);
        this.sites = sites;
        spacing = sites.spacing;
        maximumRadius = sites.maximumRadius;
        for (Family family : FAMILIES)
            profiles[family.ordinal()] =
                    new Profile(DEFINITION.profiles.getAsJsonObject(family.name()), heights);
    }

    /** Caller-owned diagnostics and anchor scratch; dominant is null where no landmark contributes. */
    public static final class Result implements StageResult {
        public double height,
                rawDisplacement,
                appliedDisplacement,
                dominantContribution,
                anchorX,
                anchorZ;
        public Family dominant;
        public ClimateRegion anchorRegion;
        public int variant, overlaps;
        public long featureId;
        public final double[] contributions = new double[FAMILIES.length];
        private final TerrainMap.BaseSample anchorBase = new TerrainMap.BaseSample();
        private final double[] weights = new double[FAMILIES.length];

        public void clear(double value) {
            height = value;
            rawDisplacement = appliedDisplacement = dominantContribution = anchorX = anchorZ = 0;
            dominant = null;
            anchorRegion = null;
            variant = -1;
            overlaps = 0;
            featureId = 0;
            Arrays.fill(contributions, 0);
        }
    }

    public void apply(
            double x,
            double z,
            IntentSampler intent,
            TerrainMap.BaseSample local,
            double original,
            double min,
            double max,
            Result out) {
        ClimateMap.Sample localClimate = local.climate;
        out.clear(original);
        int ix = (int) Math.floor(x / spacing), iz = (int) Math.floor(z / spacing);
        // Centers stay inside their cells and no radius reaches a full cell. Thus 3x3
        // includes every intersecting footprint, also at negative coordinates and seams.
        for (int dz = -sites.searchRadius; dz <= sites.searchRadius; dz++)
            for (int dx = -sites.searchRadius; dx <= sites.searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long anchor = sites.key(Sites.Lane.LANDMARK, cx, cz);
                double ax = sites.x(cx, anchor), az = sites.z(cz, anchor);
                if (Math.abs(ax) > 29999984 || Math.abs(az) > 29999984) continue;
                double px = x - ax, pz = z - az;
                if (px * px + pz * pz >= maximumRadius * maximumRadius) continue;
                if (!sites.admits(cx, cz, anchor, Sites.Lane.LANDMARK)) continue;
                intent.sample(ax, az, out.anchorBase);
                if (out.anchorBase.ocean.marine) continue;
                TerrainMap.BaseSample base = out.anchorBase;
                double slope = Math.hypot(base.baseDx, base.baseDz);
                double[] regional =
                        DEFINITION.regionWeights[out.anchorBase.climate.region.ordinal()];
                for (Family family : FAMILIES) {
                    out.weights[family.ordinal()] =
                            regional[family.ordinal()]
                                    * profiles[family.ordinal()].terrain.fit(base);
                }
                int choice = Rules.choose(out.weights, random(anchor, 104));
                Family chosen = choice < 0 ? null : FAMILIES[choice];
                if (chosen == null) continue;
                // Explicit type salts, never enum ordinals or a shared Random stream, identify
                // instances.
                long id = SeededNoise.mix(anchor ^ seed ^ chosen.salt);
                Profile profile = profiles[chosen.ordinal()];
                double rx = profile.radius * (0.65 + 0.95 * random(id, 5));
                double aspect =
                        chosen == Family.CURVED_RIDGE || chosen == Family.BRANCHING_RAVINE
                                ? 1.8 + 1.2 * random(id, 6)
                                : 0.8 + 0.65 * random(id, 6);
                double rz = rx * aspect, resize = Math.min(1, maximumRadius / Math.max(rx, rz));
                rx *= resize;
                rz *= resize;
                double angle = random(id, 7) * Math.PI * 2;
                if ((chosen == Family.FAN_APRON || chosen == Family.BRANCHING_RAVINE)
                        && slope > 0.025)
                    angle = Math.atan2(base.baseDx, -base.baseDz) + (random(id, 7) - 0.5) * 1.2;
                double c = Math.cos(angle), s = Math.sin(angle);
                double u = (px * c + pz * s) / rx, v = (-px * s + pz * c) / rz;
                double r2 = u * u + v * v;
                if (r2 >= 1) continue;
                int variant = Math.min(2, (int) (random(id, 8) * 3));
                double amplitude = profile.amplitude * (0.65 + 0.7 * random(id, 9));
                double room =
                        chosen == Family.RIMMED_BASIN || chosen == Family.BRANCHING_RAVINE
                                ? base.baseHeight - base.minimumY
                                : base.maximumY - base.baseHeight;
                // Resize relief before evaluation; the final smooth envelope also handles overlap
                // and the continuously varying parent limits across a footprint.
                amplitude = Math.min(amplitude, Math.max(0, room) * 0.8);
                if (amplitude < profile.minimum * 0.35) continue;
                double support = 1 - smooth((Math.sqrt(r2) - 0.68) / 0.32);
                double skew = (random(id, 10) - 0.5) * 0.5;
                boolean capped =
                        out.anchorBase.climate.moistureBand == MoistureBand.DRY
                                && out.anchorBase.climate.celsius >= 5;
                double value = shape(chosen, variant, u, v, skew, id, capped);
                double admission = profile.terrain.fit(local);
                if (chosen == Family.SCULPTED_DUNE) {
                    // Keep sand forms inside hot dry eligibility, without a block-height seam
                    // at either categorical threshold. Anchor climate still fixes their identity.
                    admission *=
                            smooth((localClimate.celsius - 25) / 3)
                                    * smooth((0.35 - localClimate.moisture) / 0.08);
                }
                double delta = amplitude * value * support * admission;
                if (chosen == Family.CAPPED_HILL && capped && variant != 1) {
                    // A small cap approaches a seeded plane, while its skirt joins existing
                    // terrain.
                    double cap = 1 - smooth((Math.sqrt(r2) - 0.22) / 0.35);
                    double plane = base.baseHeight + base.baseDx * px + base.baseDz * pz;
                    delta += cap * support * 0.8 * (plane - original) * admission;
                }
                out.rawDisplacement += delta;
                out.contributions[chosen.ordinal()] += delta;
                if (Math.abs(delta) > 1e-8) out.overlaps++;
                if (Math.abs(delta) > Math.abs(out.dominantContribution)) {
                    out.dominant = chosen;
                    out.variant = variant;
                    out.featureId = id;
                    out.dominantContribution = delta;
                    out.anchorX = ax;
                    out.anchorZ = az;
                    out.anchorRegion = out.anchorBase.climate.region;
                }
            }
        double room = out.rawDisplacement >= 0 ? max - original : original - min;
        out.appliedDisplacement = room <= 0 ? 0 : room * Math.tanh(out.rawDisplacement / room);
        out.height = original + out.appliedDisplacement;
    }

    private static double shape(
            Family family, int variant, double u, double v, double skew, long id, boolean capped) {
        double r = Math.sqrt(u * u + v * v), theta = Math.atan2(v, u);
        double bend = 0.18 + 0.3 * random(id, 11), width = 0.12 + 0.12 * random(id, 12);
        switch (family) {
            case CAPPED_HILL:
                double radius =
                        r * (1 + 0.1 * Math.sin(3 * theta + skew * 9) + 0.06 * Math.sin(5 * theta));
                if (variant == 1) return Math.pow(Math.max(0, 1 - radius * radius), 1.3);
                if (!capped) {
                    if (variant == 0) return Math.pow(Math.max(0, 1 - radius), 1.2);
                    return Math.max(
                            Math.exp(-5 * (square(u - 0.3) + square(v + 0.1))),
                            0.8 * Math.exp(-7 * (square(u + 0.35) + square(v - 0.2))));
                }
                if (variant == 2)
                    radius =
                            Math.sqrt(
                                    (u + 0.13 * Math.sin(v * 7)) * (u + 0.13 * Math.sin(v * 7))
                                            + v * v);
                return 1 - smooth((radius - 0.30 - skew * 0.15) / 0.45);
            case CURVED_RIDGE:
                if (variant == 1) {
                    double ring = Math.exp(-square((r - 0.53) / width));
                    double opening = smooth((v + 0.45) / 0.5);
                    return ring * opening;
                }
                double curve = bend * (v * v * 2 - 0.6) + skew * v;
                double ridge = Math.exp(-square((u - curve) / width));
                if (variant == 2)
                    ridge += 0.7 * Math.exp(-square((u + 0.4 + curve) / (width * 0.8)));
                return ridge * (0.65 + 0.35 * Math.cos(v * 2));
            case RIMMED_BASIN:
                double ellipse = Math.sqrt(square(u + skew * v * v) + v * v);
                double bowl = -Math.exp(-square(ellipse / 0.49));
                double rim = 0.48 * Math.exp(-square((ellipse - 0.63) / (width * 0.8)));
                double gap = variant == 1 ? smooth((v + 0.55) / 0.5) : 1;
                if (variant == 2) rim *= 0.7 + 0.3 * Math.cos(theta * 3 + skew * 7);
                return bowl + rim * gap;
            case FAN_APRON:
                double along = (v + 0.7) / 1.5;
                double cone =
                        1 - smooth(Math.abs(u - skew * along) / (0.12 + 0.7 * Math.max(0, along)));
                double ribs =
                        0.78
                                + 0.22
                                        * Math.cos(
                                                (u / (0.22 + Math.max(0, along)))
                                                        * Math.PI
                                                        * (variant + 3));
                return cone * smooth(along / 0.18) * (1 - smooth((along - 0.25) / 0.75)) * ribs;
            case BRANCHING_RAVINE:
                double center =
                        skew * Math.sin(v * 4) + 0.08 * Math.sin(v * 9 + random(id, 13) * 6);
                double cut = Math.exp(-square((u - center) / (width * 0.65)));
                int branches = variant + 2;
                for (int i = 0; i < branches; i++) {
                    double start = -0.5 + i * 0.8 / branches;
                    double side = (i % 2 == 0 ? 1 : -1) * (0.5 + 0.3 * random(id, 20 + i));
                    double progress = (v - start) / (0.8 - start);
                    double branch = center + side * progress;
                    double taper = smooth(progress / 0.18) * (1 - smooth((progress - 0.65) / 0.35));
                    cut = Math.max(cut, Math.exp(-square((u - branch) / (width * 0.45))) * taper);
                }
                return -cut * (0.65 + 0.35 * smooth((v + 1) / 2));
            case SCULPTED_DUNE:
                if (variant == 1) {
                    double arms = 0;
                    int count = 3 + (int) (random(id, 14) * 3);
                    for (int i = 0; i < count; i++) {
                        double a = i * Math.PI * 2 / count,
                                across = -u * Math.sin(a) + v * Math.cos(a),
                                alongArm = u * Math.cos(a) + v * Math.sin(a);
                        arms =
                                Math.max(
                                        arms,
                                        Math.exp(-square(across / (width * (1 - r * 0.5))))
                                                * smooth((alongArm + 0.08) / 0.2));
                    }
                    return arms * (1 - smooth(r));
                }
                double crest = 0.38 + 0.42 * u * u + skew * u;
                double d = v - crest + 0.2;
                double crescent =
                        Math.exp(-square(d / (d < 0 ? 0.28 : 0.10))) * (1 - smooth(Math.abs(u)));
                if (variant == 2)
                    crescent =
                            Math.max(
                                    crescent,
                                    0.7 * Math.exp(-square((v + 0.38 + 0.2 * u * u) / 0.16)));
                return crescent;
            default:
                throw new AssertionError(family);
        }
    }

    private static double random(long id, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(id + parameter * 0x9e3779b97f4a7c15L));
    }

    private static double square(double x) {
        return x * x;
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static final class Profile {
        final double radius, amplitude, minimum;
        final Terrain.Admission terrain;

        Profile(JsonObject json, TerrainHeightReference heights) {
            terrain = Terrain.Admission.create(json);
            TerrainHeightReference.Feature reference =
                    heights.feature(json.get("reference").getAsString());
            radius =
                    reference.width.targetAverage
                            * TerrainScale.HORIZONTAL
                            * positive(json, "widthScale")
                            / 2;
            amplitude = reference.magnitude.targetAverage * positive(json, "magnitudeScale");
            minimum = reference.magnitude.min;
        }
    }

    private static final class Definition {
        final String fingerprint;
        final long variationSeed;
        final JsonObject profiles;
        final double[][] regionWeights = new double[ClimateRegion.values().length][FAMILIES.length];

        Definition(JsonObject json, String fingerprint) {
            if (json.get("schema").getAsInt() != 1)
                throw new IllegalStateException("Landmark schema");
            this.fingerprint = fingerprint;
            variationSeed =
                    Long.parseUnsignedLong(
                            json.get("variationSeed").getAsString().substring(2), 16);
            profiles = json.getAsJsonObject("profiles");
            for (ClimateRegion region : ClimateRegion.values()) {
                JsonArray row = json.getAsJsonObject("regionWeights").getAsJsonArray(region.name());
                if (row == null || row.size() != FAMILIES.length)
                    throw new IllegalStateException("Landmark weights " + region);
                double sum = 0;
                for (int i = 0; i < FAMILIES.length; i++) {
                    double w = row.get(i).getAsDouble();
                    if (!Double.isFinite(w) || w < 0)
                        throw new IllegalStateException("Landmark weight");
                    regionWeights[region.ordinal()][i] = w;
                    sum += w;
                }
                if (sum <= 0)
                    throw new IllegalStateException("Empty landmark eligibility " + region);
            }
        }

        static Definition load() {
            Rules rules = Rules.load("riven-landmarks.json", 1);
            return new Definition(rules.json, rules.fingerprint);
        }
    }

    private static double positive(JsonObject json, String key) {
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value <= 0) throw new IllegalStateException(key);
        return value;
    }
}
