package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.rocky.RockyCoasts;
import cc.orthrus.riven.contrib.rocky.RockyCoasts.Edit;
import cc.orthrus.riven.contrib.rocky.RockyCoasts.Family;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The coast plan's forms (riven-coasts.json forms): each rocky segment's coves, stepped scarps
 * and benches from the MIT rocky kernels, and its own stacks, arches and sea caves. A segment
 * places its forms once, at points on its own shore, against the ground its profile already
 * made, so no form needs a certificate: a stack or arch stands on the floor beneath each of its
 * columns, a sea cave starts above the waterline under its roof band, and the engine's pass rules
 * keep the rest (Lay never covers built rock).
 */
public final class CoastForms {
    /** A form, with a permanent salt: reordering forms never moves another's placements. */
    public enum Form {
        CLIFF_PLATFORM(0x434c494646504cL),
        STEPPED_HEADLAND(0x5354455050454448L),
        JOINT_COVES(0x4a4f494e54434f56L),
        STACK_ARCH(0x535441434b415243L),
        SEA_CAVE(0x5345414341564531L);

        final long salt;

        Form(long salt) {
            this.salt = salt;
        }
    }

    /** Complete terrain before the coast's forms; it must never ask for a form or later layer. */
    @FunctionalInterface
    interface Parent {
        void sample(double x, double z, TerrainMap.Sample out);
    }

    private static final Rules RULES = Rules.load("riven-coasts.json", 1);
    private static final int MAXIMUM_RUNS = 8;
    private static final Placed[] NONE = new Placed[0];

    /** The kernel families placed as height forms; stacks and arches are bodies of their own. */
    private static final Family[] HEIGHT_FAMILIES = {
        Family.CLIFF_PLATFORM, Family.STEPPED_HEADLAND, Family.JOINT_COVES
    };

    /** Each height form's kernel family, by form ordinal (null for bodies). */
    private static final Family[] FAMILY_OF = new Family[Form.values().length];

    static {
        for (Family f : HEIGHT_FAMILIES) FAMILY_OF[Form.valueOf(f.name()).ordinal()] = f;
    }

    /** Where a stack or arch reads its cliff's top, in blocks inland of its shore point. */
    private static final double[] CREST_PROBES = {12, 18};

    /** The open sea a stack keeps around its outline, in blocks. */
    private static final double MOAT = 2;

    /** The ground left between two bodies of a segment, in blocks. */
    private static final double CLEARANCE = 4;

    /** The wavelengths of a body's surface roughness and of an arch's wandering line. */
    private static final double ROUGH = 5, WANDER = 9;

    /** A stack's, arch's or sea cave's volume at a column. */
    private interface Body {
        /**
         * Adds the column's runs, built from its own ground (or, for a sea cave, cut under its
         * surface); returns the column's new height, or NaN where the body leaves it alone.
         */
        double column(double x, double z, double ground, Hydro.Sample water, Result out);
    }

    /**
     * One placed form: its segment, shore point and inland normal, square reach about the point,
     * and either a kernel plan (a height form) or a body. A body's centre and radius measure its
     * clearance from the segment's other bodies.
     */
    private static final class Placed {
        final Form form;
        final Shore.Segment segment;
        final long id;
        final double x, z, nx, nz, reach;
        final RockyCoasts.Plan plan;
        final Body body;
        final double cx, cz, radius;

        /** A height form from its kernel plan. */
        Placed(
                Form form,
                Shore.Segment segment,
                long id,
                double[] at,
                double reach,
                RockyCoasts.Plan plan) {
            this(form, segment, id, at, reach, plan, null, at[0], at[1], 0);
        }

        /** A stack, arch or sea cave. */
        Placed(
                Form form,
                Shore.Segment segment,
                long id,
                double[] at,
                double reach,
                Body body,
                double cx,
                double cz,
                double radius) {
            this(form, segment, id, at, reach, null, body, cx, cz, radius);
        }

        private Placed(
                Form form,
                Shore.Segment segment,
                long id,
                double[] at,
                double reach,
                RockyCoasts.Plan plan,
                Body body,
                double cx,
                double cz,
                double radius) {
            this.form = form;
            this.segment = segment;
            this.id = id;
            x = at[0];
            z = at[1];
            nx = at[2];
            nz = at[3];
            this.reach = reach;
            this.plan = plan;
            this.body = body;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
        }
    }

    /**
     * A sea stack: a lobed, slightly elongated outline whose columns rise with their distance
     * inside it, from the water at the outline to the stack's height a taper's width in, under a
     * rough top. The sides stand steep, the foot spreads into a ledge at the water, and every
     * column is one solid run from its own ground.
     */
    private final class Stack implements Body {
        final double cx, cz, cos, sin, ra, rb, taper, rise;
        final double[] lobes = new double[6];

        Stack(double cx, double cz, double radius, double rise, long h) {
            this.cx = cx;
            this.cz = cz;
            this.rise = rise;
            double angle = 2 * Math.PI * unit(h, 20), e = lerp(elongation, unit(h, 21));
            cos = Math.cos(angle);
            sin = Math.sin(angle);
            ra = radius * (1 + e);
            rb = radius * (1 - e);
            taper = lerp(stackTaper, unit(h, 22));
            for (int k = 0; k < 3; k++) {
                lobes[2 * k] = lobe * unit(h, 23 + k) / 3;
                lobes[2 * k + 1] = 2 * Math.PI * unit(h, 26 + k);
            }
        }

        /** The column's distance from the centre in outline radii, 1 on the outline. */
        double inside(double x, double z) {
            double dx = x - cx, dz = z - cz;
            double p = (dx * cos + dz * sin) / ra, q = (dz * cos - dx * sin) / rb;
            double theta = Math.atan2(q, p), outline = 1;
            for (int k = 0; k < 3; k++)
                outline += lobes[2 * k] * Math.cos((k + 2) * theta + lobes[2 * k + 1]);
            return StrictMath.hypot(p, q) / outline;
        }

        /** The farthest the outline reaches from the centre, in blocks. */
        double extent() {
            return Math.max(ra, rb) * (1 + lobe);
        }

        @Override
        public double column(double x, double z, double ground, Hydro.Sample water, Result out) {
            double n = inside(x, z);
            if (n >= 1) return Double.NaN;
            double f = Math.min(1, (1 - n) / taper);
            double top =
                    sea
                            + rise
                                    * f
                                    * (1
                                            - roughness
                                                    * (.5
                                                            + .5
                                                                    * rough.sample(
                                                                            x / ROUGH, z / ROUGH)));
            int floor = (int) Math.floor(ground), y = (int) Math.floor(top);
            if (y <= floor) return Double.NaN;
            out.add(floor + 1, y + 1, Edit.SOLID);
            return y;
        }
    }

    /**
     * A sea arch: a fin from the cliff out to sea along the shore's normal, of uneven thickness
     * and wandering line, lowering toward its seaward end, pierced across by an irregular
     * half-ellipse rising from the water, so the sea runs under it. The fin builds from each
     * column's own ground; the opening is a void over the water and through any ground it meets,
     * and leaves the water beneath it.
     */
    private final class Arch implements Body {
        final double x, z, nx, nz, length, thickness, rise, open, openHalf, openRise, a, b;

        Arch(double[] at, double length, double thickness, double rise, long h) {
            x = at[0];
            z = at[1];
            nx = at[2];
            nz = at[3];
            this.length = length;
            this.thickness = thickness;
            this.rise = rise;
            open = -length * lerp(openingCentre, unit(h, 31));
            openHalf = length * lerp(openingWidth, unit(h, 32));
            openRise = rise * lerp(openingHeight, unit(h, 33));
            a = 997 * unit(h, 34);
            b = 997 * unit(h, 35);
        }

        @Override
        public double column(double x, double z, double ground, Hydro.Sample water, Result out) {
            double dx = x - this.x, dz = z - this.z;
            double d = dx * nx + dz * nz;
            if (d < -length || d > archLand) return Double.NaN;
            double s = Math.max(0, -d) / length;
            double u = dz * nx - dx * nz - wander * rough.sample(d / WANDER + a, b);
            double half =
                    thickness
                            * (1 + thicknessVariation * rough.sample(d / ROUGH + b, a))
                            * (1 - seawardFall * s);
            double t = u / half;
            if (Math.abs(t) >= 1) return Double.NaN;
            double top =
                    sea
                            + rise * (1 - seawardFall * s) * (1 - seawardFall * t * t)
                            - roughness * rise * (.5 + .5 * rough.sample(x / ROUGH, z / ROUGH));
            int floor = (int) Math.floor(ground), y = (int) Math.floor(top);
            // The opening's rim at this distance, roughened, under a roof of roofBlocks at least.
            double k = 1 + irregularity * rough.sample(d / ROUGH + a, b + 7);
            double e = (d - open) / (openHalf * k);
            int cut =
                    Math.abs(e) < 1
                            ? Math.min(
                                    (int) Math.floor(sea + openRise * k * Math.sqrt(1 - e * e)),
                                    y - archRoof)
                            : sea - 1;
            int from = floor + 1;
            if (cut >= sea) {
                out.add(sea, cut + 1, Edit.VOID);
                from = Math.max(from, cut + 1);
            }
            if (y >= from) out.add(from, y + 1, Edit.SOLID);
            return out.hasVolume() ? Math.max(ground, y) : Double.NaN;
        }
    }

    /**
     * A sea cave: a tapering vault from one block above the water into the cliff. Where the column
     * keeps the roof band's rock over the vault, the vault is carved; nearer the face, where that
     * roof would be thinner, the column is cut down to the vault's floor, so the cave opens through
     * a notch at the waterline instead of sealing behind the cliff's foot. No aquifer is opened.
     */
    private final class Cave implements Body {
        final double x, z, nx, nz, length, half, rise;

        Cave(double[] at, double length, double half, double rise) {
            x = at[0];
            z = at[1];
            nx = at[2];
            nz = at[3];
            this.length = length;
            this.half = half;
            this.rise = rise;
        }

        @Override
        public double column(double x, double z, double surface, Hydro.Sample water, Result out) {
            double d = (x - this.x) * nx + (z - this.z) * nz;
            double u = (x - this.x) * -nz + (z - this.z) * nx;
            if (d < 0 || d > length) return Double.NaN;
            double t = d / length;
            double across = Math.abs(u) / (half * (1 - .5 * t));
            if (across >= 1) return Double.NaN;
            int ground = (int) Math.floor(surface);
            int top = (int) Math.floor(sea + rise * (1 - .6 * t) * Math.sqrt(1 - across * across));
            if (top <= sea || ground < sea) return Double.NaN;
            if (water.aquiferMin <= water.aquiferMax
                    && water.aquiferMin <= Math.max(top, ground)
                    && water.aquiferMax >= sea) return Double.NaN;
            if (ground - caveRoof >= top) {
                out.add(sea, top + 1, Edit.VOID);
                return surface;
            }
            // The notch's floor is the vault's: the block at the water's top, held by any levee.
            double notch = Hydro.holding(waterTop + .5, water);
            return notch < surface ? notch : Double.NaN;
        }
    }

    /** A column's forms: height, its edits as runs of solid or void, and what made them. */
    public static final class Result implements StageResult {
        public double height, parentHeight, weight;
        public long id;
        public Form family;
        public boolean arch, emergent;
        public String reason;
        private int runs;
        private final int[] bottom = new int[MAXIMUM_RUNS], top = new int[MAXIMUM_RUNS];
        private final Edit[] edit = new Edit[MAXIMUM_RUNS];

        @Override
        public void clear(double surface) {
            height = parentHeight = surface;
            weight = 0;
            id = 0;
            family = null;
            arch = emergent = false;
            reason = "NO_FORM";
            runs = 0;
        }

        /** Whether the column builds or carves anything beyond its height. */
        public boolean hasVolume() {
            return runs > 0;
        }

        public int runs() {
            return runs;
        }

        /** Run i covers block Ys [bottom(i), top(i)). */
        public int bottom(int i) {
            return bottom[i];
        }

        public int top(int i) {
            return top[i];
        }

        public Edit edit(int i) {
            return edit[i];
        }

        private void add(int from, int to, Edit e) {
            if (to <= from || runs == MAXIMUM_RUNS) return;
            if (runs > 0 && edit[runs - 1] == e && top[runs - 1] == from) {
                top[runs - 1] = to;
                return;
            }
            bottom[runs] = from;
            top[runs] = to;
            edit[runs] = e;
            runs++;
        }
    }

    private final long seed;
    private final Shore shore;
    private final Parent parent;
    private final RockyCoasts.Reference reference;
    private final Properties referenceValues;
    private final RockyCoasts.Tuning[] tuning = new RockyCoasts.Tuning[Family.values().length];
    private final SeededNoise rough;
    private final int waterTop, sea, groupMax, archRoof;
    private final Form[][] formsOf;
    private final double jitter, minimumExposure, caveRoof, roughness, lobe, archFraction;
    private final double archLand, thicknessVariation, seawardFall, irregularity, wander;
    private final double[] caveLength, caveHalfWidth, caveHeight;
    private final double[] stackCentre, stackRadius, elongation, stackTaper, stackHeight;
    private final double[] companionScale, companionHeight, companionGap;
    private final double[] archLength, archThickness, archHeight;
    private final double[] openingCentre, openingWidth, openingHeight;
    private final CellMemo<Placed[]> plans = new CellMemo<>(4096);
    private final ThreadLocal<TerrainMap.Sample> scratch =
            ThreadLocal.withInitial(TerrainMap.Sample::new);
    private final ThreadLocal<Shore.Nearest> nearest = ThreadLocal.withInitial(Shore.Nearest::new);
    private final ThreadLocal<RockyCoasts.Column> column =
            ThreadLocal.withInitial(RockyCoasts.Column::new);

    CoastForms(long worldSeed, TerrainHeightReference heights, Shore shore, Parent parent) {
        this.shore = shore;
        this.parent = parent;
        JsonObject j = RULES.json.getAsJsonObject("forms");
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        rough = new SeededNoise(SeededNoise.mix(seed ^ 0x524f554748L));
        jitter = Rules.number(j, "slotJitter", 0, 1);
        minimumExposure = Rules.number(j, "minimumExposure", 0, .9);
        roughness = Rules.number(j, "roughness", 0, .3);
        waterTop = heights.waterTopY;
        sea = waterTop + 1;
        TerrainHeightReference.Feature scarp = heights.feature("scarp");
        double scale = TerrainScale.HORIZONTAL;
        Properties r = new Properties();
        put(r, "sea", heights.seaLevel);
        put(r, "waterTop", heights.waterTopY);
        put(r, "low", heights.lowestSurface);
        put(r, "high", heights.highestSurface);
        put(r, "scarp.magnitude.min", scarp.magnitude.min);
        put(r, "scarp.magnitude.mean", scarp.magnitude.targetAverage);
        put(r, "scarp.magnitude.max", scarp.magnitude.max);
        put(r, "scarp.width.min", scarp.width.min * scale);
        put(r, "scarp.width.mean", scarp.width.targetAverage * scale);
        put(r, "scarp.width.max", scarp.width.max * scale);
        put(r, "beach.width.max", heights.feature("beach").width.max * scale);
        reference = new RockyCoasts.Reference(r);
        referenceValues = r;
        // Placement decides where a form stands, so each kernel plan always occurs; rock intent is
        // the segment's own type, and exposure still fades edits into sheltered water.
        Properties t = new Properties();
        put(t, "coveDepth", Rules.number(j, "coveDepth", .2, 1));
        put(t, "minimumRock", .1);
        put(t, "minimumExposure", minimumExposure);
        JsonObject families = j.getAsJsonObject("families");
        for (Family f : HEIGHT_FAMILIES) {
            JsonObject p = families.getAsJsonObject(f.name());
            put(t, f.name() + ".occurrence", 1);
            for (String name : new String[] {"widthScale", "reliefScale", "detail"})
                put(t, f.name() + "." + name, p.get(name).getAsDouble());
            tuning[f.ordinal()] = new RockyCoasts.Tuning(t, f);
        }
        JsonObject stack = j.getAsJsonObject("stack");
        stackCentre = range(stack, "centreBlocks", 4, 64);
        stackRadius = range(stack, "radiusBlocks", 2, 24);
        elongation = range(stack, "elongation", 0, .5);
        lobe = Rules.number(stack, "lobes", 0, .4);
        stackTaper = range(stack, "taper", .05, 1);
        stackHeight = range(stack, "heightBlocks", 4, 128);
        groupMax = (int) Rules.number(stack, "groupMax", 1, 5);
        companionScale = range(stack, "companionScale", .2, 1);
        companionHeight = range(stack, "companionHeight", .2, 1);
        companionGap = range(stack, "companionGapBlocks", 0, 32);
        JsonObject arch = j.getAsJsonObject("arch");
        archFraction = Rules.number(arch, "fraction", 0, 1);
        archLength = range(arch, "lengthBlocks", 6, 96);
        archLand = Rules.number(arch, "landBlocks", 0, 16);
        archThickness = range(arch, "halfThicknessBlocks", 1, 16);
        thicknessVariation = Rules.number(arch, "thicknessVariation", 0, .5);
        wander = Rules.number(arch, "wanderBlocks", 0, 8);
        seawardFall = Rules.number(arch, "seawardFall", 0, .6);
        archHeight = range(arch, "heightBlocks", 8, 128);
        openingCentre = range(arch, "openingCentre", .2, .8);
        openingWidth = range(arch, "openingWidth", .05, .4);
        openingHeight = range(arch, "openingHeight", .1, .8);
        irregularity = Rules.number(arch, "irregularity", 0, .5);
        archRoof = (int) Rules.number(arch, "roofBlocks", 2, 32);
        // The opening lies wholly at sea with a leg of fin beyond it, however its rim wanders.
        double widest = openingWidth[1] * (1 + irregularity);
        if (!(openingCentre[0] - widest > 0 && openingCentre[1] + widest < 1))
            throw new IllegalStateException("riven-coasts.json forms arch opening");
        JsonObject cave = j.getAsJsonObject("seaCave");
        caveLength = range(cave, "lengthBlocks", 4, 256);
        caveHalfWidth = range(cave, "halfWidthBlocks", 1, 16);
        caveHeight = range(cave, "heightBlocks", 2, 32);
        caveRoof = Rules.number(cave, "roofBlocks", 4, 64);
        // Names resolve once here; generation reads only the forms and families they name.
        formsOf = new Form[Shore.TYPES.length][];
        for (Shore.Type type : Shore.TYPES) {
            formsOf[type.index] = new Form[type.forms.length];
            for (int f = 0; f < type.forms.length; f++)
                formsOf[type.index][f] = Form.valueOf(type.forms[f]);
        }
    }

    private static double[] range(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(min <= lo && lo <= hi && hi <= max))
            throw new IllegalStateException("riven-coasts.json forms " + key);
        return new double[] {lo, hi};
    }

    private static double lerp(double[] range, double t) {
        return range[0] + (range[1] - range[0]) * t;
    }

    private static void put(Properties p, String key, double value) {
        p.setProperty(key, Double.toString(value));
    }

    private static double unit(long h, int k) {
        return SeededNoise.unit(SeededNoise.mix(h + k * 0x9e3779b97f4a7c15L));
    }

    /** A segment's forms, placed once: slots along its shore, each kept on its own stretch. */
    private Placed[] forms(int i, int j) {
        return plans.get(i, j, () -> place(shore.segmentAt(i, j)));
    }

    private Placed[] place(Shore.Segment s) {
        if (s.type == null || formsOf[s.type.index].length == 0) return NONE;
        List<Placed> out = new ArrayList<>();
        Shore.Nearest owner = new Shore.Nearest();
        double span = shore.cellBlocks(), tx = -s.nz, tz = s.nx;
        Form[] forms = formsOf[s.type.index];
        for (int f = 0; f < forms.length; f++) {
            Form form = forms[f];
            double per = s.type.formSpacing[f];
            int slots = Math.max(1, (int) Math.round(span / per));
            for (int k = 0; k < slots; k++) {
                long h = SeededNoise.mix(s.key ^ seed ^ form.salt ^ k * 0xd1b54a32d192ed03L);
                double u = (k + .5 + jitter * (unit(h, 0) - .5)) * per - slots * per / 2;
                double[] at = shore.onShore(s.x + tx * u, s.z + tz * u, per);
                // A form is anchored on the shore line itself, or not at all.
                if (at == null) continue;
                // A slot stands only on its own segment's stretch of shore.
                shore.nearest(at[0], at[1], owner);
                if (!s.is(owner.a)) continue;
                double length = StrictMath.hypot(at[2], at[3]);
                at[2] /= length;
                at[3] /= length;
                if (form == Form.SEA_CAVE) keep(cave(s, at, h), out);
                else if (form != Form.STACK_ARCH) keep(kernel(form, s, at, h), out);
                else if (unit(h, 7) < archFraction) keep(arch(s, at, h), out);
                else stacks(s, at, h, out);
            }
        }
        return out.toArray(NONE);
    }

    /**
     * Keeps a form that stays clear of the bodies placed before it in its segment: a stack, arch
     * or sea cave never overlaps another (the form listed first wins), so none walls another in.
     */
    private static void keep(Placed p, List<Placed> placed) {
        if (p == null) return;
        if (p.body != null)
            for (Placed q : placed)
                if (q.body != null
                        && StrictMath.hypot(p.cx - q.cx, p.cz - q.cz)
                                < p.radius + q.radius + CLEARANCE) return;
        placed.add(p);
    }

    private Placed cave(Shore.Segment s, double[] at, long h) {
        double length = lerp(caveLength, unit(h, 1));
        double half = lerp(caveHalfWidth, unit(h, 2));
        double rise = lerp(caveHeight, unit(h, 3));
        // A cave reaches no farther than its segment's band.
        length = Math.min(length, s.width);
        // Its column refuses reserved water and aquifers, so its whole axis must be clear, sampled
        // every block from the mouth: else the passage is cut and the vault inland is sealed.
        TerrainMap.Sample probe = scratch.get();
        for (int d = 0; d <= Math.ceil(length); d++) {
            parent.sample(at[0] + at[2] * d, at[1] + at[3] * d, probe);
            Hydro.Sample w = probe.water;
            if (reserved(probe) || w.aquiferMin <= w.aquiferMax && w.aquiferMax >= sea) return null;
        }
        return new Placed(
                Form.SEA_CAVE,
                s,
                h,
                at,
                length + half,
                new Cave(at, length, half, rise),
                at[0] + at[2] * length / 2,
                at[1] + at[3] * length / 2,
                length / 2 + half);
    }

    /**
     * A group of stacks off a shore point, one to groupMax: the first stands out at sea, its
     * companions, smaller and lower, beside it along the shore. Each is no taller than the cliff
     * behind the point, needs exposed water there, and open, supplied sea under its whole outline
     * and moat.
     */
    private void stacks(Shore.Segment s, double[] at, long h, List<Placed> out) {
        TerrainMap.Sample probe = scratch.get();
        double relief = relief(at, probe);
        if (!(relief >= stackHeight[0])) return;
        parent.sample(at[0], at[1], probe);
        if (exposure(probe) < minimumExposure) return;
        int size = Math.min(groupMax, 1 + (int) Math.floor(Math.pow(unit(h, 10), 2) * groupMax));
        double radius = lerp(stackRadius, unit(h, 11));
        double rise = Math.min(relief, lerp(stackHeight, unit(h, 12)));
        double offshore = lerp(stackCentre, unit(h, 13)), side = unit(h, 14) < .5 ? -1 : 1;
        double widest = radius * (1 + elongation[1]) * (1 + lobe);
        for (int i = 0; i < size; i++) {
            long g = SeededNoise.mix(h + (i + 1) * 0x632be59bd9b4e019L);
            double scale = i == 0 ? 1 : lerp(companionScale, unit(g, 1));
            double r = radius * scale, w = r * (1 + elongation[1]) * (1 + lobe);
            double offset = 0;
            if (i > 0) {
                // Companions alternate sides, each clear of the first.
                offset =
                        (i % 2 == 1 ? side : -side)
                                * (widest + w + CLEARANCE + lerp(companionGap, unit(g, 2)));
            }
            double seaward = offshore + (i == 0 ? 0 : (unit(g, 3) - .3) * 2 * w);
            double cx = at[0] - at[2] * seaward - at[3] * offset;
            double cz = at[1] - at[3] * seaward + at[2] * offset;
            Stack stack =
                    new Stack(
                            cx, cz, r, i == 0 ? rise : rise * lerp(companionHeight, unit(g, 4)), g);
            if (!moated(stack, probe)) continue;
            double extent = stack.extent();
            keep(
                    new Placed(
                            Form.STACK_ARCH,
                            s,
                            g,
                            at,
                            Math.max(Math.abs(cx - at[0]), Math.abs(cz - at[1])) + extent + 1,
                            stack,
                            cx,
                            cz,
                            extent),
                    out);
        }
    }

    /**
     * An arch at a shore point: no taller than the cliff behind it, in exposed water, its opening
     * over open, supplied sea and its fin's seaward end in the sea, under a roof of roofBlocks.
     */
    private Placed arch(Shore.Segment s, double[] at, long h) {
        TerrainMap.Sample probe = scratch.get();
        double relief = relief(at, probe);
        if (!(relief >= archHeight[0])) return null;
        parent.sample(at[0], at[1], probe);
        if (exposure(probe) < minimumExposure) return null;
        double length = lerp(archLength, unit(h, 4)), thickness = lerp(archThickness, unit(h, 5));
        Arch arch =
                new Arch(at, length, thickness, Math.min(relief, lerp(archHeight, unit(h, 6))), h);
        if (arch.rise - arch.openRise < archRoof + 2) return null;
        parent.sample(at[0] + at[2] * arch.open, at[1] + at[3] * arch.open, probe);
        if (!openSea(probe)) return null;
        parent.sample(at[0] - at[2] * (length - 2), at[1] - at[3] * (length - 2), probe);
        if (!probe.ocean.marine) return null;
        double mid = (archLand - length) / 2, reach = thickness * (1 + thicknessVariation) + wander;
        return new Placed(
                Form.STACK_ARCH,
                s,
                h,
                at,
                Math.max(length, archLand) + reach + 1,
                arch,
                at[0] + at[2] * mid,
                at[1] + at[3] * mid,
                (length + archLand) / 2 + reach);
    }

    /** The cliff's top behind a shore point above the sea, or NaN where sea lies behind it. */
    private double relief(double[] at, TerrainMap.Sample probe) {
        double crest = reference.high;
        for (double d : CREST_PROBES) {
            parent.sample(at[0] + at[2] * d, at[1] + at[3] * d, probe);
            if (probe.ocean.marine) return Double.NaN;
            crest = Math.min(crest, probe.height);
        }
        return crest - sea;
    }

    /**
     * Whether open, supplied sea at the water's top holds every column of a stack's outline and
     * of a moat MOAT blocks wide around it, so its base stands clear of land, banks and held
     * water on every side.
     */
    private boolean moated(Stack k, TerrainMap.Sample probe) {
        double reach = k.extent() + MOAT;
        for (int z = (int) Math.floor(k.cz - reach); z <= (int) Math.ceil(k.cz + reach); z++)
            for (int x = (int) Math.floor(k.cx - reach); x <= (int) Math.ceil(k.cx + reach); x++) {
                double n = k.inside(x, z), rho = StrictMath.hypot(x - k.cx, z - k.cz);
                // Blocks beyond the outline along the column's own ray.
                if (n > 1 && rho * (1 - 1 / n) > MOAT) continue;
                parent.sample(x, z, probe);
                if (!openSea(probe)) return false;
            }
        return true;
    }

    /**
     * A height form at a shore point from its MIT kernel. Its plan's geometry is fixed by its
     * seed, so a first plan says where to read the cliff top: one and one and a half kernel widths
     * inland. That reading and the exposure at the point are the anchor's only facts.
     */
    private Placed kernel(Form form, Shore.Segment s, double[] at, long h) {
        Family family = FAMILY_OF[form.ordinal()];
        RockyCoasts.Tuning t = tuning[family.ordinal()];
        RockyCoasts.Plan geometry =
                RockyCoasts.prepare(
                        family, anchor(h, at, reference.high, reference.high, 1), reference, t);
        TerrainMap.Sample probe = scratch.get();
        double crest = reference.high;
        for (double d : new double[] {geometry.width, 1.5 * geometry.width}) {
            parent.sample(at[0] + at[2] * d, at[1] + at[3] * d, probe);
            if (probe.ocean.marine) return null;
            crest = Math.min(crest, probe.height);
        }
        parent.sample(at[0], at[1], probe);
        double exposure = exposure(probe);
        crest = Math.min(Math.max(crest, reference.low), reference.high);
        double relief = crest - reference.sea;
        if (relief < reference.dropMin) return null;
        RockyCoasts.Plan plan =
                RockyCoasts.prepare(
                        family, anchor(h, at, crest, reference.high, exposure), local(relief), t);
        if (plan.admission != RockyCoasts.Reason.ACCEPTED) return null;
        double reach = StrictMath.hypot(plan.halfAlong, Math.max(plan.inland, plan.offshore));
        return new Placed(form, s, h, at, reach, plan);
    }

    /**
     * The scarp reference for the cliff a height form stands against. The kernel caps its crest
     * at the sea plus its drop, so that drop is the cliff's own relief: one from the shared 8 to
     * 80 block range would plane a taller cliff down to it. The least drop stays shared.
     */
    private RockyCoasts.Reference local(double relief) {
        Properties r = new Properties();
        r.putAll(referenceValues);
        put(r, "scarp.magnitude.mean", relief);
        put(r, "scarp.magnitude.max", relief);
        return new RockyCoasts.Reference(r);
    }

    /** A height form's anchor: its foundation is the lowest surface, its rock intent whole. */
    private RockyCoasts.Anchor anchor(
            long h, double[] at, double minimumParent, double maximumParent, double exposure) {
        return new RockyCoasts.Anchor(
                seed,
                h,
                at[0],
                at[1],
                at[2],
                at[3],
                minimumParent,
                maximumParent,
                reference.low,
                1,
                Math.max(0, Math.min(1, exposure)));
    }

    /**
     * How open a column's water is, 0 in full shelter to 1, clamped: smoothstep's polynomial
     * rounds a hair past 1 near full shelter, and the kernel refuses an exposure below 0.
     */
    private static double exposure(TerrainMap.BaseSample s) {
        return Math.max(0, Math.min(1, 1 - Rules.smooth(Math.min(1, s.ocean.shelter * 3))));
    }

    /** Water the forms leave alone: protected or reserved shore, wet or banked columns. */
    private static boolean reserved(TerrainMap.BaseSample s) {
        return s.ocean.protectedWater || s.water.wet || s.water.weight > 0;
    }

    /** Open, supplied sea at the water's top that no shore or water reserves. */
    private boolean openSea(TerrainMap.Sample s) {
        return s.ocean.marine && s.ocean.supplied && !reserved(s) && s.waterY() == waterTop;
    }

    /** A placed form as checks see it: kind, shore point, inland normal and segment band. */
    static final class View {
        final Form form;
        final long id;
        final double x, z, nx, nz, band, reach;

        /** A body's centre and radius; a sea cave's length and half width. */
        final double cx, cz, radius, length, halfWidth;

        final boolean arch;

        View(Placed p) {
            form = p.form;
            id = p.id;
            x = p.x;
            z = p.z;
            nx = p.nx;
            nz = p.nz;
            band = p.segment.width;
            reach = p.reach;
            cx = p.cx;
            cz = p.cz;
            radius = p.radius;
            arch = p.body instanceof Arch;
            length = p.body instanceof Cave c ? c.length : 0;
            halfWidth = p.body instanceof Cave c ? c.half : 0;
        }
    }

    /** Every form placed by the segments of the coast cells overlapping a box, for checks. */
    List<View> placed(double minX, double minZ, double maxX, double maxZ) {
        double cell = shore.cellBlocks();
        List<View> out = new ArrayList<>();
        for (int i = (int) Math.floor(minX / cell); i <= (int) Math.floor(maxX / cell); i++)
            for (int j = (int) Math.floor(minZ / cell); j <= (int) Math.floor(maxZ / cell); j++)
                for (Placed p : forms(i, j)) out.add(new View(p));
        return out;
    }

    /**
     * Places the forms of every segment near a region ahead of demand, on Cubature's pool, or
     * declines once a quarter of the memo is held. The plans are pure and their memo bounded, so
     * preparing them changes timing only.
     */
    boolean prepareRegion(double minX, double minZ, double maxX, double maxZ, BuildPool pool) {
        int[] held = {0};
        plans.forEachHeld(p -> held[0]++);
        if (held[0] > plans.capacity() / 4) return false;
        double cell = shore.cellBlocks();
        int x0 = (int) Math.floor(minX / cell) - 1, x1 = (int) Math.floor(maxX / cell) + 1;
        int z0 = (int) Math.floor(minZ / cell) - 1, z1 = (int) Math.floor(maxZ / cell) + 1;
        int nx = x1 - x0 + 1;
        pool.forEach(nx * (z1 - z0 + 1), k -> forms(x0 + k % nx, z0 + k / nx));
        return true;
    }

    /** Whether any form may edit the column: the cheap test before sampling it. */
    public boolean hasGeometry(double x, double z) {
        double cell = shore.cellBlocks();
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        for (int di = -1; di <= 1; di++)
            for (int dj = -1; dj <= 1; dj++)
                for (Placed p : forms(ci + di, cj + dj))
                    if (Math.abs(x - p.x) <= p.reach && Math.abs(z - p.z) <= p.reach) return true;
        return false;
    }

    /**
     * Applies the forms to a column after its shore and water: kernel height edits on land (the
     * deepest wins, shared out by the segment's own blend), a stack or arch built from the
     * column's own ground, or else a sea cave cut under the surface those edits left.
     */
    public void apply(double x, double z, TerrainMap.Sample sample) {
        Result out = sample.rocky;
        out.clear(sample.height);
        double distance = sample.ocean.distance;
        if (!Double.isFinite(distance) || !Double.isFinite(sample.height)) return;
        double cell = shore.cellBlocks();
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        double ground = sample.height, height = ground;
        boolean marine = sample.ocean.marine, reserved = reserved(sample);
        double protection =
                Math.max(
                        0,
                        Math.min(
                                1,
                                Math.max(
                                        sample.shore.marsh,
                                        Math.max(sample.shore.beach, sample.shore.plain))));
        double exposure = exposure(sample);
        Shore.Nearest owner = null;
        RockyCoasts.Column c = column.get();
        Placed built = null, cave = null;
        double builtHeight = Double.NaN;
        for (int di = -1; di <= 1; di++)
            for (int dj = -1; dj <= 1; dj++)
                for (Placed p : forms(ci + di, cj + dj)) {
                    if (Math.abs(x - p.x) > p.reach || Math.abs(z - p.z) > p.reach) continue;
                    if (p.form == Form.SEA_CAVE) {
                        if (cave == null && !marine && !reserved) cave = p;
                        continue;
                    }
                    if (p.body != null) {
                        // Bodies keep clear of each other, so at most one builds here.
                        if (built == null && !reserved) {
                            builtHeight = p.body.column(x, z, ground, sample.water, out);
                            if (out.hasVolume()) built = p;
                        }
                        continue;
                    }
                    if (marine) continue;
                    RockyCoasts.sample(
                            p.plan,
                            new RockyCoasts.Input(
                                    x,
                                    z,
                                    distance,
                                    ground,
                                    1,
                                    exposure,
                                    protection,
                                    marine,
                                    reserved),
                            c);
                    if (c.reason != RockyCoasts.Reason.ACCEPTED || c.weight <= 0) continue;
                    if (owner == null) {
                        owner = nearest.get();
                        shore.nearest(x, z, owner);
                    }
                    // The band owns its ground: a height form fades out over its last fifth.
                    double band = p.segment.width;
                    double share =
                            owner.share(p.segment)
                                    * (1 - Rules.smooth((distance - .8 * band) / (.2 * band)));
                    if (share <= 0) continue;
                    // A form lowers no levee below the channel water it holds.
                    double edited =
                            Hydro.holding(ground + (c.height - ground) * share, sample.water);
                    if (edited < height) {
                        height = edited;
                        out.weight = Math.max(out.weight, c.weight * share);
                        out.family = p.form;
                        out.id = p.id;
                    }
                }
        if (built != null) {
            out.family = Form.STACK_ARCH;
            out.id = built.id;
            out.arch = built.body instanceof Arch;
            out.weight = 1;
            height = builtHeight;
            out.emergent = marine && height >= sea;
        } else if (cave != null) {
            // A sea cave cuts through the cliff its coast's height forms built: on its own
            // footprint it wins, from the ground they left, so its passage is never walled off
            // between the mouth notch and the vault.
            double cut = cave.body.column(x, z, height, sample.water, out);
            if (!Double.isNaN(cut)) {
                out.family = Form.SEA_CAVE;
                out.id = cave.id;
                out.weight = 1;
                height = cut;
            }
        }
        if (out.family != null) out.reason = "ACCEPTED";
        out.height = height;
        sample.height = height;
        sample.emergentRock = out.emergent;
    }
}
