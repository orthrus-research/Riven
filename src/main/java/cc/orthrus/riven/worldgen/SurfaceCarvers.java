package cc.orthrus.riven.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;

/** Finite surface cuts, evaluated independently of chunk loading and preview resolution. */
public final class SurfaceCarvers {
    public enum Kind {
        V_RAVINE(1),
        U_TROUGH(2),
        SINUOUS_GORGE(3),
        BRANCHING_GULLY(4),
        SADDLE_PASS(5),
        HEADWALL_BOWL(6),
        FALL_LINE_CHUTE(7),
        BREACHED_HOLLOW(8),
        CIRQUE(9),
        HANGING_TRIBUTARY(10),
        BASIN_STAIRCASE(11),
        SNOW_PATCH_HOLLOWS(12),
        SLOT_CANYON(13),
        BADLAND_DISSECTION(14),
        BRAIDED_WASH(15),
        BOX_COULEE(16),
        DEFLATION_HOLLOW(17),
        INTERDUNE_SWALES(18),
        CONVERGENT_HOLLOWS(19),
        SCALLOPED_SCARP(20),
        SLOPE_FAILURE_SCAR(21),
        VALLEY_BENCHES(22),
        GRABEN_TROUGH(23),
        EN_ECHELON_CLEFTS(24);
        public final int id;

        Kind(int id) {
            this.id = id;
        }

        static Kind byId(int id) {
            for (Kind k : KINDS) if (k.id == id) return k;
            throw new IllegalArgumentException("Carver ID " + id);
        }
    }

    private static final Kind[] KINDS = Kind.values();
    private static final Definition DEFINITION = Definition.load();
    public static final String FINGERPRINT = DEFINITION.fingerprint;
    private final long seed;
    private final Sites sites;
    private final PlateMap plates;
    private final Profile[] profiles = new Profile[KINDS.length];
    public final double spacing, maximumRadius;
    private final int searchRadius;

    public SurfaceCarvers(
            long worldSeed, TerrainHeightReference heights, Sites sites, PlateMap plates) {
        seed = SeededNoise.mix(worldSeed ^ DEFINITION.variationSeed);
        this.sites = sites;
        this.plates = plates;
        spacing = sites.spacing;
        maximumRadius = sites.maximumRadius;
        searchRadius = sites.searchRadius;
        for (JsonElement e : DEFINITION.profiles) {
            JsonObject json = e.getAsJsonObject();
            Kind kind = Kind.byId(json.get("id").getAsInt());
            if (profiles[kind.ordinal()] != null)
                throw new IllegalStateException("Duplicate carver " + kind);
            profiles[kind.ordinal()] = new Profile(kind, json, heights);
        }
        for (Profile p : profiles) if (p == null) throw new IllegalStateException("Missing carver");
    }

    public static final class Result implements StageResult {
        public double height, rawCut, appliedCut, dominantCut, anchorX, anchorZ;
        public Kind dominant;
        public ClimateRegion anchorRegion;
        public long featureId;
        public int overlaps;
        public final double[] cuts = new double[KINDS.length];
        private final double[] weights = new double[KINDS.length];
        private final TerrainMap.BaseSample base = new TerrainMap.BaseSample();

        public void clear(double value) {
            height = value;
            rawCut = appliedCut = dominantCut = anchorX = anchorZ = 0;
            dominant = null;
            anchorRegion = null;
            featureId = 0;
            overlaps = 0;
            Arrays.fill(cuts, 0);
        }

        private final double[] ax = new double[32],
                az = new double[32],
                bx = new double[32],
                bz = new double[32],
                width = new double[32];
    }

    public void apply(
            double x, double z, IntentSampler intent, TerrainMap.Sample surface, Result out) {
        double original = surface.height;
        out.clear(original);
        int ix = (int) Math.floor(x / spacing), iz = (int) Math.floor(z / spacing);
        // Centers stay in their cells. The search derives from the maximum rotated
        // support radius, so changing corridor length cannot silently truncate seams.
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long cell = sites.key(Sites.Lane.CARVER, cx, cz);
                double ax = sites.x(cx, cell), az = sites.z(cz, cell);
                if (Math.abs(ax) > 29999984 || Math.abs(az) > 29999984) continue;
                double px = x - ax, pz = z - az;
                if (px * px + pz * pz >= maximumRadius * maximumRadius) continue;
                if (!sites.admits(
                        cx,
                        cz,
                        cell,
                        Sites.Lane.CARVER,
                        (ex, ez, k) -> eligible(ex, ez, k, intent))) continue;
                intent.sample(ax, az, out.base);
                if (out.base.ocean.marine) continue;
                double slope = Math.hypot(out.base.baseDx, out.base.baseDz);
                PlateMap.Sample plate = weigh(out.base, ax, az, out.weights);
                int choice = Rules.choose(out.weights, random(cell, 104));
                Profile chosen = choice < 0 ? null : profiles[choice];
                if (chosen == null) continue;
                Kind kind = chosen.kind;
                long id = SeededNoise.mix(cell ^ seed ^ chosen.salt);
                double rx = chosen.radius * (.75 + .65 * random(id, 5));
                double rz = Math.max(rx * 1.6, spacing * .43) * (.8 + .5 * random(id, 6));
                if (kind == Kind.HEADWALL_BOWL
                        || kind == Kind.CIRQUE
                        || kind == Kind.DEFLATION_HOLLOW
                        || kind == Kind.BREACHED_HOLLOW) rz = rx * (.8 + .55 * random(id, 6));
                if (kind == Kind.BRANCHING_GULLY
                        || kind == Kind.BADLAND_DISSECTION
                        || kind == Kind.SNOW_PATCH_HOLLOWS
                        || kind == Kind.SCALLOPED_SCARP) rx = rz;
                double resize = Math.min(1, maximumRadius / Math.max(rx, rz));
                rx *= resize;
                rz *= resize;
                double angle = random(id, 7) * Math.PI * 2;
                if (slope > .025)
                    angle =
                            Math.atan2(out.base.baseDx, -out.base.baseDz)
                                    + (random(id, 7) - .5) * 1.1;
                if (kind.id >= 23 && plate != null)
                    angle =
                            Math.atan2(-plate.velocityX, plate.velocityZ)
                                    + (random(id, 7) - .5) * .5;
                double c = Math.cos(angle),
                        s = Math.sin(angle),
                        u = (px * c + pz * s) / rx,
                        v = (-px * s + pz * c) / rz;
                double radius = Math.sqrt(u * u + v * v);
                if (radius >= 1) continue;
                double room = out.base.baseHeight - out.base.minimumY;
                double depth =
                        Math.min(chosen.depth * (.7 + .6 * random(id, 8)), Math.max(0, room) * .85);
                if (depth < chosen.minimum * .25) continue;
                double admission = chosen.terrain.fit(surface);
                if (kind == Kind.INTERDUNE_SWALES)
                    admission *=
                            smooth((surface.climate.celsius - 25) / 3)
                                    * smooth((.35 - surface.climate.moisture) / .08);
                double value = shape(kind, u, v, id, out);
                double support = 1 - smooth((radius - .78) / .22);
                // Longitudinal variation hides the mathematical skeleton without moving junctions.
                double grade = .85 + .15 * Math.sin(v * 3.5 + random(id, 9) * 6.28);
                double cut = Math.max(0, value) * support * depth * admission * grade;
                out.cuts[kind.ordinal()] = cut;
                if (cut > 1e-6) out.overlaps++;
                if (cut > out.dominantCut) {
                    out.dominantCut = cut;
                    out.dominant = kind;
                    out.featureId = id;
                    out.anchorX = ax;
                    out.anchorZ = az;
                    out.anchorRegion = out.base.climate.region;
                }
            }
        out.rawCut = out.dominantCut;
        double room = Math.max(0, original - surface.minimumY);
        out.appliedCut = room <= 0 ? 0 : room * Math.tanh(out.rawCut / room);
        out.height = original - out.appliedCut;
    }

    /** Profile weights at an anchor; returns the plate sample when a structural profile read it. */
    private PlateMap.Sample weigh(TerrainMap.BaseSample base, double ax, double az, double[] out) {
        PlateMap.Sample plate = null;
        for (Kind kind : KINDS) {
            Profile p = profiles[kind.ordinal()];
            double fit = p.terrain.fit(base);
            if (kind.id >= 23) {
                if (plate == null)
                    plate = plates.sample((int) Math.floor(ax), (int) Math.floor(az));
                boolean boundary =
                        kind == Kind.GRABEN_TROUGH
                                ? plate.boundary == PlateMap.Boundary.DIVERGENT
                                : plate.boundary == PlateMap.Boundary.SHEAR;
                fit *= boundary ? (1 - smooth(plate.boundaryDistance / 900)) : 0;
            }
            out[kind.ordinal()] = p.admission[base.climate.region.ordinal()] * fit * p.occurrence;
        }
        return plate;
    }

    private final CellMemo<Boolean> eligibility = new CellMemo<>(4096);

    /**
     * A site's own cheap tests (a land anchor that selects a profile) before it competes, so an
     * anchor that could never carve cannot veto a neighbour that would. Pure per site.
     */
    private boolean eligible(int cx, int cz, long cell, IntentSampler intent) {
        return eligibility.get(
                cx,
                cz,
                () -> {
                    TerrainMap.BaseSample base = new TerrainMap.BaseSample();
                    double ax = sites.x(cx, cell), az = sites.z(cz, cell);
                    intent.sample(ax, az, base);
                    if (base.ocean.marine) return false;
                    double[] weights = new double[KINDS.length];
                    weigh(base, ax, az, weights);
                    return Rules.choose(weights, random(cell, 104)) >= 0;
                });
    }

    private static double shape(Kind kind, double u, double v, long id, Result out) {
        double phase = random(id, 10) * Math.PI * 2, bend = .13 + .19 * random(id, 11);
        double center = bend * Math.sin(v * 3 + phase) + .07 * Math.sin(v * 7 + phase * .5);
        double along = 1 - smooth((Math.abs(v) - .70) / .30);
        switch (kind) {
            case V_RAVINE:
                return Math.pow(Math.max(0, 1 - Math.abs(u)), 1.2) * along;
            case U_TROUGH:
                return (1 - smooth((Math.abs(u) - .48) / .52)) * along;
            case SINUOUS_GORGE:
                return gaussian((u - center) / .65, 6) * along;
            case BRANCHING_GULLY:
                return tree(
                        u, v, id, 2 + (int) (random(id, 77) * 2), .045 + .03 * random(id, 12), out);
            case SADDLE_PASS:
                return gaussian(u, 4) * gaussian(v / .50, 4) * (.8 + .2 * Math.cos(v * 2));
            case HEADWALL_BOWL:
                double r = Math.hypot(u, v);
                return Math.max(
                        (1 - smooth((r - .35) / .65)) * smooth((v + .75) / .25),
                        .55 * gaussian(u / .6, 2) * smooth((-.1 - v) / .6));
            case FALL_LINE_CHUTE:
                return gaussian((u - .15 * Math.sin(v * 3)) / (.55 + .25 * (1 - v)), 4)
                        * along
                        * (.7 + .3 * smooth((v + 1) / 2));
            case BREACHED_HOLLOW:
                return Math.max(
                        gaussian(Math.hypot(u + .2, v - .15) / .7, 2),
                        .65 * capsule(u, v, -.12, .1, .45, -1., .18, 2));
            case CIRQUE:
                double ellipse = Math.hypot(u / 1.05, (v - .05) / .8);
                return (1 - smooth((ellipse - .45) / .45))
                        * smooth((v + .80) / .22)
                        * (.8 + .2 * smooth((v + .2) / .6));
            case HANGING_TRIBUTARY:
                // A compound event contains its main trough and elevated side cut.
                return Math.max(
                        gaussian((u + .25) / .32, 6),
                        .45 * gaussian((v - .23) / .24, 4) * smooth((u + .25) / .20));
            case BASIN_STAIRCASE:
                double chain = .38 * gaussian(u / .75, 4) * along;
                for (int j = 0; j < 3; j++) {
                    double zv = -.52 + j * .49 + .08 * (random(id, 20 + j) - .5);
                    chain =
                            Math.max(
                                    chain,
                                    (.7 + .25 * random(id, 30 + j))
                                            * gaussian(Math.hypot(u / .75, (v - zv) / .27), 2));
                }
                return chain;
            case SNOW_PATCH_HOLLOWS:
                double pockets = 0;
                for (int j = 0; j < 4; j++) {
                    double px = -.58 + j * .35, pz = .25 + .2 * Math.sin(j * 1.8 + phase);
                    pockets =
                            Math.max(
                                    pockets,
                                    (.5 + .45 * random(id, 20 + j))
                                            * gaussian(
                                                    Math.hypot((u - px) / .20, (v - pz) / .14), 4));
                }
                return pockets;
            case SLOT_CANYON:
                return gaussian(
                                (u - .33 * Math.sin(v * 4 + phase) - .14 * Math.sin(v * 11)) / .65,
                                8)
                        * along;
            case BADLAND_DISSECTION:
                return tree(u, v, id, 3 + (int) (random(id, 77) * 2), .055, out)
                        * (.65 + .35 * smooth((v + 1) / 2));
            case BRAIDED_WASH:
                double lanes = 0;
                for (int j = 0; j < 3; j++) {
                    double lane =
                            (j - 1) * (.5 + .3 * Math.cos(v * 3 + phase))
                                    + .18 * Math.sin(v * 5 + j);
                    lanes =
                            Math.max(
                                    lanes,
                                    gaussian((u - lane) / .22, 4)
                                            * along
                                            * (.7 + .25 * random(id, 20 + j)));
                }
                return lanes;
            case BOX_COULEE:
                return (1 - smooth((Math.abs(u) - .60) / .40))
                        * smooth((v + .90) / .10)
                        * (1 - smooth((v - .72) / .18));
            case DEFLATION_HOLLOW:
                return (1 - smooth((Math.hypot(u + .15 * v * v, v / .75) - .20) / .75))
                        * (.6 + .4 * smooth((v + 1) / 2));
            case INTERDUNE_SWALES:
                double swales = 0;
                for (int j = -1; j <= 1; j++)
                    swales =
                            Math.max(
                                    swales,
                                    .8
                                            * gaussian(
                                                    (u - j * 1.35 - .22 * Math.sin(v * 3 + phase))
                                                            / .28,
                                                    2)
                                            * along);
                return swales;
            case CONVERGENT_HOLLOWS:
                double head = .16 + .65 * smooth((v + 1) / 2);
                return Math.max(
                        gaussian(u / head, 2) * along,
                        .55 * gaussian((u + .7) / .4, 2) * gaussian((v - .4) / .35, 2));
            case SCALLOPED_SCARP:
                double scallops = 0;
                for (int j = 0; j < 4; j++) {
                    double px = -.65 + j * .43, pz = .15 + .10 * Math.sin(j + phase);
                    scallops =
                            Math.max(
                                    scallops,
                                    gaussian(Math.hypot((u - px) / .22, (v - pz) / .35), 4)
                                            * smooth((v + .3) / .3));
                }
                return scallops;
            case SLOPE_FAILURE_SCAR:
                double span = .18 + .8 * smooth((v + 1) / 2);
                return (1 - smooth((Math.abs(u) / span - .35) / .65))
                        * along
                        * (.6 + .4 * smooth((v + .2) / .5));
            case VALLEY_BENCHES:
                double benches = .45 * gaussian(u / .25, 4) * along;
                for (int j = 0; j < 3; j++) {
                    double lane = .35 + j * .50 + .1 * Math.sin(v * 3 + phase + j);
                    benches =
                            Math.max(
                                    benches,
                                    (.45 + .22 * j) * gaussian((u - lane) / .35, 6) * along);
                }
                return benches;
            case GRABEN_TROUGH:
                double trough =
                        (1 - smooth((Math.abs(u) - .45) / .5))
                                * along
                                * (.7 + .3 * smooth((u + .1) / .4));
                return Math.max(trough, .45 * gaussian((u + .85) / .18, 6) * along);
            case EN_ECHELON_CLEFTS:
                double clefts = 0;
                for (int j = 0; j < 4; j++) {
                    double pz = -.65 + j * .44, px = .16 * Math.sin(j + phase);
                    clefts =
                            Math.max(
                                    clefts,
                                    (.7 + .3 * random(id, 20 + j))
                                            * capsule(
                                                    u, v, px - .14, pz - .17, px + .14, pz + .17,
                                                    .09, 4));
                }
                return clefts;
            default:
                throw new AssertionError(kind);
        }
    }

    /** At most 15 segments, addressed by binary-tree position rather than a mutable RNG. */
    private static double tree(double u, double v, long id, int levels, double width, Result out) {
        out.ax[0] = 0;
        out.az[0] = -.84;
        out.bx[0] = 0;
        out.bz[0] = .75;
        out.width[0] = width;
        int count = (1 << levels) - 1;
        double cut = 0;
        for (int i = 0; i < count; i++) {
            cut =
                    Math.max(
                            cut,
                            capsule(
                                    u,
                                    v,
                                    out.ax[i],
                                    out.az[i],
                                    out.bx[i],
                                    out.bz[i],
                                    out.width[i],
                                    2));
            int level = 31 - Integer.numberOfLeadingZeros(i + 1);
            if (level + 1 >= levels) continue;
            for (int side = -1; side <= 1; side += 2) {
                int child = 2 * i + (side < 0 ? 1 : 2);
                out.ax[child] = out.ax[i] * .35 + out.bx[i] * .65;
                out.az[child] = out.az[i] * .35 + out.bz[i] * .65;
                out.bx[child] =
                        out.bx[i]
                                + side
                                        * (.20 + .13 * random(id, 50 + child))
                                        * Math.pow(2, -.45 * level);
                out.bz[child] = out.bz[i] - .40 * Math.pow(2, -.25 * level);
                out.width[child] = out.width[i] * .62;
            }
        }
        return cut;
    }

    private static double capsule(
            double u,
            double v,
            double ax,
            double az,
            double bx,
            double bz,
            double width,
            int power) {
        double dx = bx - ax,
                dz = bz - az,
                t = Math.max(0, Math.min(1, ((u - ax) * dx + (v - az) * dz) / (dx * dx + dz * dz)));
        return gaussian(Math.hypot(u - ax - t * dx, v - az - t * dz) / width, power);
    }

    private static double gaussian(double t, int power) {
        double q = t * t;
        if (power == 4) q *= q;
        else if (power == 6) q = q * q * q;
        else if (power == 8) {
            q *= q;
            q *= q;
        }
        return Math.exp(-q);
    }

    private static double random(long id, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(id + parameter * 0x9e3779b97f4a7c15L));
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return Math.max(0, Math.min(1, t * t * t * (t * (t * 6 - 15) + 10)));
    }

    private static final class Profile {
        final Kind kind;
        final long salt;
        final double radius, depth, minimum, occurrence;
        final Terrain.Admission terrain;
        final double[] admission = new double[ClimateRegion.values().length];

        Profile(Kind kind, JsonObject json, TerrainHeightReference heights) {
            this.kind = kind;
            salt = Long.parseUnsignedLong(json.get("typeSaltHex").getAsString(), 16);
            TerrainHeightReference.Feature reference =
                    heights.feature(json.get("reference").getAsString());
            radius =
                    reference.width.targetAverage
                            * TerrainScale.HORIZONTAL
                            * positive(json, "widthScale")
                            / 2;
            depth =
                    Math.min(
                            reference.magnitude.max,
                            reference.magnitude.targetAverage * positive(json, "depthScale"));
            minimum = reference.magnitude.min;
            occurrence = positive(json, "occurrenceWeight");
            terrain = Terrain.Admission.create(json);
            if (json.getAsJsonArray("climateAdmission").size() != admission.length)
                throw new IllegalStateException("Carver climate coverage");
            for (int i = 0; i < admission.length; i++) {
                double w = json.getAsJsonArray("climateAdmission").get(i).getAsDouble();
                if (!Double.isFinite(w) || w < 0 || w > 2)
                    throw new IllegalStateException("Carver climate admission");
                admission[i] = w == 2 ? 2.5 : w;
            }
        }
    }

    private static final class Definition {
        final String fingerprint;
        final long variationSeed;
        final com.google.gson.JsonArray profiles;

        Definition(JsonObject json, String fingerprint) {
            if (json.get("schema").getAsInt() != 1)
                throw new IllegalStateException("Surface carver schema");
            this.fingerprint = fingerprint;
            variationSeed =
                    Long.parseUnsignedLong(
                            json.get("variationSeed").getAsString().substring(2), 16);
            profiles = json.getAsJsonArray("profiles");
        }

        static Definition load() {
            Rules rules = Rules.load("riven-surface-carvers.json", 1);
            return new Definition(rules.json, rules.fingerprint);
        }
    }

    private static double positive(JsonObject json, String key) {
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value <= 0) throw new IllegalStateException(key);
        return value;
    }
}
