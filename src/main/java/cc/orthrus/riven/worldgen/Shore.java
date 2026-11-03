package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The coast plan (riven-coasts.json): one earlier coastal contract for selectors, hydrology,
 * shaping and cover. The coast comes in segments, each with one type chosen once at its site from
 * pre-shore facts; a type shapes only its band, by its own cross-profile from the waterline to the
 * inland ground, and past the band the ground keeps its own height. No world or final-height reads.
 */
public final class Shore {
    public enum Kind {
        OCEAN,
        INLAND,
        PLAIN,
        BEACH,
        ROCK,
        CLIFF,
        MARSH
    }

    /** A pre-shore intent sample at a segment's site: everything intent knows but the shore. */
    @FunctionalInterface
    interface SiteSampler {
        void sample(double x, double z, TerrainMap.BaseSample out);
    }

    private static final Rules RULES = Rules.load("riven-coasts.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    /** The facts a coast type's suitability weighs, each 0 to 1 at its segment's site. */
    static final String[] FACTS = {
        "low",
        "middle",
        "high",
        "rugged",
        "cape",
        "headland",
        "shelter",
        "shoaling",
        "grade",
        "rocky",
        "wet",
        "warm",
        "bias"
    };

    private static final int SHAPE_CLIFF = 0,
            SHAPE_BLUFF = 1,
            SHAPE_PLATFORM = 2,
            SHAPE_BEACH = 3,
            SHAPE_MARSH = 4;
    private static final String[] SHAPES = {"cliff", "bluff", "platform", "beach", "marsh"};

    /** A coast type: its kind, profile shape, band and foot ranges and suitability. */
    public static final class Type {
        public final String id;

        /** The type's place in TYPES, which stages index their resolved lists by. */
        final int index;

        final Kind kind;
        final int shape;
        final double bandMin, bandMax, footMin, footMax;
        final double[] weights = new double[FACTS.length];

        /** The type's forms (CoastForms): each form's name and the blocks of shore per form. */
        final String[] forms;

        final double[] formSpacing;

        /** The type's sediment forms (CoastSediment), by name. */
        final String[] sediment;

        Type(JsonObject j, int index) {
            id = j.get("id").getAsString();
            this.index = index;
            kind = Kind.valueOf(j.get("kind").getAsString().toUpperCase(java.util.Locale.ROOT));
            if (kind != Kind.CLIFF && kind != Kind.ROCK && kind != Kind.BEACH && kind != Kind.MARSH)
                throw new IllegalStateException("riven-coasts.json " + id + ": kind " + kind);
            shape = java.util.Arrays.asList(SHAPES).indexOf(j.get("shape").getAsString());
            if (shape < 0) throw new IllegalStateException("riven-coasts.json " + id + ": shape");
            JsonArray band = j.getAsJsonArray("band"), foot = j.getAsJsonArray("foot");
            bandMin = band.get(0).getAsDouble();
            bandMax = band.get(1).getAsDouble();
            footMin = foot.get(0).getAsDouble();
            footMax = foot.get(1).getAsDouble();
            if (!(bandMin >= 8
                    && bandMin <= bandMax
                    && bandMax <= 1024
                    && footMin >= 0
                    && footMin <= footMax
                    && footMax < bandMin / 2))
                throw new IllegalStateException("riven-coasts.json " + id + ": band or foot");
            JsonArray list = j.has("forms") ? j.getAsJsonArray("forms") : new JsonArray();
            forms = new String[list.size()];
            formSpacing = new double[list.size()];
            for (int k = 0; k < forms.length; k++) {
                JsonObject form = list.get(k).getAsJsonObject();
                forms[k] = form.get("form").getAsString();
                formSpacing[k] = Rules.number(form, "perBlocks", 8, 4096);
            }
            JsonArray deposits = j.has("sediment") ? j.getAsJsonArray("sediment") : new JsonArray();
            sediment = new String[deposits.size()];
            for (int k = 0; k < sediment.length; k++) sediment[k] = deposits.get(k).getAsString();
            for (Map.Entry<String, JsonElement> w : j.getAsJsonObject("suitability").entrySet()) {
                int f = java.util.Arrays.asList(FACTS).indexOf(w.getKey());
                if (f < 0)
                    throw new IllegalStateException(
                            "riven-coasts.json " + id + ": unknown fact " + w.getKey());
                weights[f] = w.getValue().getAsDouble();
            }
        }
    }

    static final Type[] TYPES;
    private static final double CELL,
            KEEP,
            JITTER,
            BLEND,
            CHOICE,
            PROBE,
            ROCK_START,
            ROCK_END,
            WET,
            WARM,
            KNEE;
    private static final long VARIATION;

    /**
     * The shore snap's precision, not a terrain value: how near the coast a snapped point stops,
     * the bracket's reaches in multiples of the point's distance, and the most halvings.
     */
    private static final double SNAP = .25;

    private static final double[] BRACKET = {1, 1.5, 2, 3, 4, 6, 8};
    private static final int BISECTIONS = 24;

    static {
        JsonObject j = RULES.json;
        VARIATION = Rules.seed(j, "variationSeed");
        JsonObject s = j.getAsJsonObject("segments");
        CELL = Rules.number(s, "cellBlocks", 64, 8192);
        KEEP = Rules.number(s, "keepWithinCells", .1, 2);
        JITTER = Rules.number(s, "jitter", 0, 1);
        BLEND = Rules.number(s, "blendBlocks", 1, 1024);
        CHOICE = Rules.number(s, "choiceJitter", 0, 2);
        JsonObject f = j.getAsJsonObject("facts");
        PROBE = Rules.number(f, "probeBlocks", 8, 512);
        JsonArray rock = f.getAsJsonArray("rockSlope");
        ROCK_START = rock.get(0).getAsDouble();
        ROCK_END = rock.get(1).getAsDouble();
        if (!(ROCK_END > ROCK_START))
            throw new IllegalStateException("riven-coasts.json rockSlope");
        WET = Rules.number(f, "wetMoisture", 0, 1);
        WARM = Rules.number(f, "warmCelsius", -40, 60);
        KNEE = Rules.number(j.getAsJsonObject("envelope"), "kneeFraction", .1, .9);
        List<Type> types = new ArrayList<>();
        for (JsonElement e : j.getAsJsonArray("types"))
            types.add(new Type(e.getAsJsonObject(), types.size()));
        if (types.isEmpty()) throw new IllegalStateException("riven-coasts.json: no types");
        TYPES = types.toArray(new Type[0]);
    }

    /** The widest band any type may have: past it, no column is coastal. */
    private static final double WIDEST;

    static {
        double w = 0;
        for (Type t : TYPES) w = Math.max(w, t.bandMax);
        WIDEST = w;
    }

    /**
     * A coast cell's segment: its site on the shore, the outline's inland normal there, type, band
     * width and foot, and its cell and key. A cell whose site lies farther from the coast than keep
     * has no segment (type null).
     */
    static final class Segment {
        final int i, j;
        final long key;
        final double x, z, nx, nz;
        final Type type;
        final double width, foot;

        Segment(
                int i,
                int j,
                long key,
                double x,
                double z,
                double nx,
                double nz,
                Type type,
                double width,
                double foot) {
            this.i = i;
            this.j = j;
            this.key = key;
            this.x = x;
            this.z = z;
            this.nx = nx;
            this.nz = nz;
            this.type = type;
            this.width = width;
            this.foot = foot;
        }

        /**
         * Whether o is this cell's segment. Segments live in a bounded memo, so one evicted and
         * rebuilt is a new object equal to the old: compare cells, never references.
         */
        boolean is(Segment o) {
            return o != null && o.i == i && o.j == j;
        }
    }

    /** A column's two nearest segments and how far it blends toward the second (0 to .5). */
    static final class Nearest {
        Segment a, b;
        double mix;

        /** The share of the column that segment s owns. */
        double share(Segment s) {
            if (a == null) return 0;
            if (a.is(b)) return a.is(s) ? 1 : 0;
            return (a.is(s) ? 1 - mix : 0) + (b.is(s) ? mix : 0);
        }
    }

    private final long seed;
    private final double cell, keep, sea, highest;
    private final int water;
    private final double littoralWidth;
    private final SiteSampler sites;
    private final CellMemo<Segment> segments = new CellMemo<>(4096);
    private final ThreadLocal<TerrainMap.BaseSample> scratch =
            ThreadLocal.withInitial(TerrainMap.BaseSample::new);
    private final ThreadLocal<Nearest> nearestScratch = ThreadLocal.withInitial(Nearest::new);

    Shore(long worldSeed, TerrainHeightReference heights, WorldScale world, SiteSampler sites) {
        seed = SeededNoise.mix(worldSeed ^ VARIATION);
        cell = CELL * world.layout;
        keep = KEEP * cell;
        sea = heights.seaLevel;
        water = heights.waterTopY;
        highest = heights.highestSurface;
        littoralWidth = heights.feature("beach").width.max;
        this.sites = sites;
    }

    /**
     * The segment of coast cell (i, j). Its jittered site is kept only within keep of the coast,
     * where it is moved toward the shore (towardShore), so segment edges cross the coast instead
     * of slicing along it. Its type and widths are chosen there from facts read probe blocks
     * inland.
     */
    private Segment segment(int i, int j) {
        return segments.get(
                i,
                j,
                () -> {
                    double[] p = site(i, j);
                    long h = SeededNoise.cell(seed ^ 0x5345474dL, i, j);
                    double[] q = towardShore(p[0], p[1], keep);
                    if (q == null) return new Segment(i, j, h, p[0], p[1], 0, 0, null, 0, 0);
                    double nx = q[2], nz = q[3];
                    TerrainMap.BaseSample b = scratch.get();
                    sites.sample(q[0] + nx * PROBE, q[1] + nz * PROBE, b);
                    double[] f = facts(b);
                    Type best = TYPES[0];
                    double bestScore = Double.NEGATIVE_INFINITY;
                    for (int t = 0; t < TYPES.length; t++) {
                        double score =
                                CHOICE * (SeededNoise.unit(SeededNoise.mix(h + t * 0x9e37L)) - .5);
                        for (int k = 0; k < FACTS.length; k++) score += TYPES[t].weights[k] * f[k];
                        if (score > bestScore) {
                            bestScore = score;
                            best = TYPES[t];
                        }
                    }
                    double u = SeededNoise.unit(SeededNoise.mix(h ^ 0x57494454L)),
                            v = SeededNoise.unit(SeededNoise.mix(h ^ 0x464f4f54L));
                    return new Segment(
                            i,
                            j,
                            h,
                            q[0],
                            q[1],
                            nx,
                            nz,
                            best,
                            best.bandMin + (best.bandMax - best.bandMin) * u,
                            best.footMin + (best.footMax - best.footMin) * v);
                });
    }

    /** The segment of coast cell (i, j), for the coast's forms. */
    Segment segmentAt(int i, int j) {
        return segment(i, j);
    }

    /** The side of a coast cell in blocks. */
    double cellBlocks() {
        return cell;
    }

    /**
     * A point moved toward the shore: {x, z, nx, nz} with the outline's inland normal, or null
     * when the point lies farther than within from the coast. Two steps along the normal by the
     * coast distance, which close most of the way where the distance changes about a block per
     * block and stop short where it changes more slowly; a segment's site needs no closer, and
     * moving it would reclassify the coast's segments (onShore is exact).
     */
    private double[] towardShore(double x, double z, double within) {
        TerrainMap.BaseSample b = scratch.get();
        sites.sample(x, z, b);
        if (!(Math.abs(b.ocean.distance) <= within)) return null;
        double nx = 0, nz = 0;
        for (int step = 0; step < 2; step++) {
            ContinentalMap.Sample o = b.ocean;
            if (!Double.isFinite(o.distance) || o.normalX == 0 && o.normalZ == 0) break;
            nx = o.normalX;
            nz = o.normalZ;
            x -= nx * o.distance;
            z -= nz * o.distance;
            sites.sample(x, z, b);
        }
        if (b.ocean.normalX != 0 || b.ocean.normalZ != 0) {
            nx = b.ocean.normalX;
            nz = b.ocean.normalZ;
        }
        if (nx == 0 && nz == 0) return null;
        return new double[] {x, z, nx, nz};
    }

    /**
     * A point moved onto the shore, within SNAP of its line: {x, z, nx, nz} with the outline's
     * inland normal there, or null when the point lies farther than within from the coast or the
     * coast does not cross its normal within eight times its distance. The distance is the
     * outline's score, continuous but often changing only about half a block per block along the
     * normal (the crossing lies at a median 2.2 times the distance from points beside a segment's
     * shore), so stepping by it stops short; the crossing is bracketed along the normal and found
     * by bisection.
     */
    double[] onShore(double x, double z, double within) {
        TerrainMap.BaseSample b = scratch.get();
        sites.sample(x, z, b);
        double d0 = b.ocean.distance, rx = b.ocean.normalX, rz = b.ocean.normalZ;
        if (!(Math.abs(d0) <= within) || rx == 0 && rz == 0) return null;
        // t is blocks seaward along the normal; the crossing lies where the distance changes sign.
        double near = 0, far = Double.NaN, t = 0, d = d0;
        if (Math.abs(d0) > SNAP) {
            for (double k : BRACKET) {
                sites.sample(x - rx * d0 * k, z - rz * d0 * k, b);
                if (Math.signum(b.ocean.distance) != Math.signum(d0)) {
                    far = d0 * k;
                    break;
                }
                near = d0 * k;
            }
            if (Double.isNaN(far)) return null;
            for (int step = 0; step < BISECTIONS && Math.abs(d) > SNAP; step++) {
                t = .5 * (near + far);
                sites.sample(x - rx * t, z - rz * t, b);
                d = b.ocean.distance;
                if (Math.signum(d) == Math.signum(d0)) near = t;
                else far = t;
            }
            if (!(Math.abs(d) <= SNAP)) return null;
        }
        double nx = b.ocean.normalX, nz = b.ocean.normalZ;
        if (nx == 0 && nz == 0) {
            nx = rx;
            nz = rz;
        }
        return new double[] {x - rx * t, z - rz * t, nx, nz};
    }

    /** The column's two nearest kept segments and their blend; a null when there is none. */
    void nearest(double x, double z, Nearest out) {
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        double d1 = Double.POSITIVE_INFINITY, d2 = Double.POSITIVE_INFINITY;
        Segment a = null, b = null;
        for (int di = -2; di <= 2; di++)
            for (int dj = -2; dj <= 2; dj++) {
                Segment s = segment(ci + di, cj + dj);
                if (s.type == null) continue;
                double d = Math.hypot(x - s.x, z - s.z);
                if (d < d1) {
                    d2 = d1;
                    b = a;
                    d1 = d;
                    a = s;
                } else if (d < d2) {
                    d2 = d;
                    b = s;
                }
            }
        if (a != null && b == null) b = a;
        out.a = a;
        out.b = b;
        out.mix = a == null ? 0 : .5 * (1 - Rules.smooth((d2 - d1) / BLEND));
    }

    /** The facts at a segment's site, each 0 to 1. */
    private static double[] facts(TerrainMap.BaseSample b) {
        double[] f = new double[FACTS.length];
        Terrain.Sample land = b.land;
        ContinentalMap.Sample o = b.ocean;
        f[0] = land.low;
        f[1] = land.middle;
        f[2] = land.high;
        f[3] = land.rugged;
        f[4] = o.cape;
        f[5] = o.headland;
        f[6] = Rules.smooth(o.shelter * 3);
        f[7] = o.shoaling;
        double grade = Math.abs(b.broadDx * o.normalX + b.broadDz * o.normalZ);
        f[8] = Rules.smooth((grade - ROCK_START) / (ROCK_END - ROCK_START));
        f[9] = Math.max(f[8], o.cape);
        f[10] = Rules.smooth((b.climate.moisture - WET) / .18);
        f[11] = Rules.smooth((b.climate.celsius - WARM) / 8);
        f[12] = 1;
        return f;
    }

    private double[] site(int i, int j) {
        long h = SeededNoise.cell(seed, i, j);
        double u = SeededNoise.unit(h), v = SeededNoise.unit(SeededNoise.mix(h));
        return new double[] {
            (i + 0.5 + JITTER * (u - 0.5)) * cell, (j + 0.5 + JITTER * (v - 0.5)) * cell
        };
    }

    /**
     * Softly caps a feature's raise above its parent inside the coast's band, where it may rise no
     * higher than the sea plus the band's rise times the highest surface; past the band it is free.
     * It never lowers the parent.
     */
    public double envelope(double parent, double height, Sample shore) {
        return soften(parent, height, envelopeMaximum(shore));
    }

    /** As {@link #envelope}: major features (volcanic, cold, plateau, mountain) too. */
    public double majorEnvelope(double parent, double height, Sample shore) {
        return soften(parent, height, envelopeMaximum(shore));
    }

    /** The highest a feature may stand at a column: unbounded past the band. */
    public double envelopeMaximum(Sample shore) {
        if (shore.kind == Kind.OCEAN || shore.landWeight >= 1) return Double.POSITIVE_INFINITY;
        return sea + (highest - sea) * shore.landWeight;
    }

    private static double soften(double parent, double height, double maximum) {
        double raise = height - parent;
        if (raise <= 0) return height;
        double cap = maximum - parent;
        if (cap == Double.POSITIVE_INFINITY) return height;
        if (cap <= 0) return parent;
        double knee = cap * KNEE;
        if (raise <= knee) return height;
        return parent + cap - (cap - knee) * (cap - knee) / (raise - 2 * knee + cap);
    }

    /** Filled after broad terrain classification; later stages treat these facts as read-only. */
    public static final class Sample {
        public Kind kind = Kind.OCEAN;
        public double distance,
                normalX,
                normalZ,
                shelter,
                grade,
                proximity,
                beach,
                plain,
                cliff,
                rock,
                marsh;

        /** The band's rise at the column: 0 at the waterline, 1 at and past the band's edge. */
        public double landWeight;

        public double height;
        public boolean sandy, littoral;

        /** The column's segment type (the nearer of the two it blends), or null off the coast. */
        public Type type;

        // The column's two nearest segments, their mix and its shore distance, for shape().
        private Segment a, b;
        private double mix, sea;

        /** The same coastal surface used by planners and by final shaping: the band's profile. */
        public double shape(double inlandHeight) {
            if (a == null) return inlandHeight;
            double ha = profile(a, distance, inlandHeight, sea, null);
            if (mix <= 0) return ha;
            return ha + (profile(b, distance, inlandHeight, sea, null) - ha) * mix;
        }

        public boolean rocky() {
            return kind == Kind.ROCK || kind == Kind.CLIFF;
        }

        /** Local admission cannot erase a shoreline reservation. Inland behavior is unchanged. */
        public double detail() {
            return 1 - Math.max(beach, plain * .85);
        }
    }

    /**
     * A segment's profile height at shore distance d for inland height h; its rise (0 at the shore,
     * 1 at and past the band's edge) into rise[0] if given.
     */
    private static double profile(Segment s, double d, double h, double sea, double[] rise) {
        double w = s.width, low, r;
        switch (s.type.shape) {
            case SHAPE_CLIFF:
                {
                    double end = Math.max(s.foot + 3, w / 4);
                    low = sea;
                    r = Rules.smooth((d - s.foot) / (end - s.foot));
                    break;
                }
            case SHAPE_BLUFF:
                low = sea;
                r = Rules.smooth((d - s.foot) / (w - s.foot));
                break;
            case SHAPE_PLATFORM:
                low = sea + Rules.smooth(d / 4);
                r = Rules.smooth((d - .4 * w) / (.2 * w));
                break;
            case SHAPE_BEACH:
                low = sea + 2 * Rules.smooth(d / (.1 * w));
                r = Rules.smooth((d - .25 * w) / (.75 * w));
                break;
            case SHAPE_MARSH:
            default:
                low = sea + Rules.smooth(d / 8);
                r = Rules.smooth((d - .7 * w) / (.3 * w));
                break;
        }
        if (rise != null) rise[0] = r;
        low = Math.min(low, h);
        return low + (h - low) * r;
    }

    public void classify(double x, double z, TerrainMap.BaseSample base, Sample out) {
        ContinentalMap.Sample ocean = base.ocean;
        out.distance = ocean.distance;
        out.normalX = ocean.normalX;
        out.normalZ = ocean.normalZ;
        out.shelter = ocean.shelter;
        out.beach = out.plain = out.cliff = out.rock = out.marsh = out.grade = out.proximity = 0;
        out.sandy = false;
        out.type = null;
        out.a = out.b = null;
        out.mix = 0;
        out.sea = sea;
        out.littoral = !ocean.marine && ocean.distance <= littoralWidth;
        if (ocean.marine) {
            out.kind = Kind.OCEAN;
            out.landWeight = 0;
            out.height = ocean.bedHeight;
            return;
        }
        out.kind = Kind.INLAND;
        out.landWeight = 1;
        double distance = Math.max(0, ocean.distance);
        out.grade = Math.abs(base.broadDx * ocean.normalX + base.broadDz * ocean.normalZ);
        if (!(distance < WIDEST)) {
            out.height = base.baseHeight;
            return;
        }
        // The two nearest segments; the column blends them near the edge between.
        Nearest n = nearestScratch.get();
        nearest(x, z, n);
        Segment a = n.a, b = n.b;
        if (a == null) {
            out.height = base.baseHeight;
            return;
        }
        out.a = a;
        out.b = b;
        out.mix = n.mix;
        out.type = a.type;
        out.distance = distance;
        double[] rise = new double[1];
        profile(a, distance, base.baseHeight, sea, rise);
        double riseA = rise[0];
        profile(b, distance, base.baseHeight, sea, rise);
        out.landWeight = riseA + (rise[0] - riseA) * out.mix;
        out.proximity =
                (1 - out.mix) * (1 - Rules.smooth(distance / a.width))
                        + out.mix * (1 - Rules.smooth(distance / b.width));
        weights(a, distance, 1 - out.mix, out);
        weights(b, distance, out.mix, out);
        // The kind follows the nearer segment while the column is near its shore.
        if (out.proximity > .35) {
            Kind k = a.type.kind;
            if (k == Kind.BEACH && distance >= .25 * a.width) k = Kind.PLAIN;
            out.kind = k;
        }
        out.sandy = out.beach > .35 && !out.rocky();
        out.height = out.shape(base.baseHeight);
    }

    /** Adds a segment's coastal weights, by its share of the column, to the sample. */
    private static void weights(Segment s, double d, double share, Sample out) {
        if (share <= 0) return;
        double near = 1 - Rules.smooth(d / s.width);
        // A rocky class names its stretch of coast, whole across the band and fading over one more:
        // feature admission reads it as rugged ground (Terrain.Admission).
        double reach = 1 - Rules.smooth((d - s.width) / s.width);
        switch (s.type.kind) {
            case CLIFF:
                out.cliff += share * reach;
                if (d < s.foot) out.beach += share;
                break;
            case ROCK:
                out.rock += share * reach;
                if (d < s.foot) out.beach += share;
                break;
            case BEACH:
                {
                    double berm = 1 - Rules.smooth((d - .25 * s.width) / (.1 * s.width));
                    out.beach += share * berm;
                    out.plain += share * near * (1 - berm);
                    break;
                }
            default:
                out.marsh += share * (1 - Rules.smooth((d - .7 * s.width) / (.3 * s.width)));
                break;
        }
    }
}
