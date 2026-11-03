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
 * The coast plan's forms (riven-coasts.json forms): each rocky segment's coves, stepped scarps,
 * benches, stacks and arches from the MIT rocky kernels, and its sea caves. A segment places its
 * forms once, at points on its own shore, against the ground its profile already made, so no form
 * needs a certificate: a stack stands on the floor beneath each of its columns, an arch springs
 * from the ground its body crosses, a sea cave starts above the waterline under its roof band, and
 * the engine's pass rules keep the rest (Lay never covers built rock, Carve keeps the roof band).
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

    /** The kernel's tunnel centre seaward of an arch's anchor, in widths. */
    private static final double TUNNEL_D = .45;

    /** The open sea a stack keeps around its body's ellipse, in blocks. */
    private static final double MOAT = 2;

    /** The ground left between two bodies of a segment, in blocks. */
    private static final double CLEARANCE = 4;

    /**
     * One placed form: its segment, kernel plan or cave, centre and square reach, and how far its
     * body (a stack, arch or sea cave's volume) reaches from the centre, 0 for a height form.
     */
    private static final class Placed {
        final Form form;
        final Shore.Segment segment;
        final RockyCoasts.Plan plan;
        final double x, z, nx, nz, reach, body;
        final long id;
        final double length, halfWidth, height;

        Placed(
                Form form,
                Shore.Segment segment,
                RockyCoasts.Plan plan,
                double[] at,
                double reach,
                long id,
                double length,
                double halfWidth,
                double height) {
            this.form = form;
            this.segment = segment;
            this.plan = plan;
            x = at[0];
            z = at[1];
            nx = at[2];
            nz = at[3];
            this.reach = reach;
            this.id = id;
            this.length = length;
            this.halfWidth = halfWidth;
            this.height = height;
            body =
                    form == Form.SEA_CAVE
                            ? length + halfWidth
                            : form != Form.STACK_ARCH
                                    ? 0
                                    : plan.arch
                                            ? Math.max(
                                                    Math.min(plan.width * 2, plan.offshore * .6),
                                                    plan.radiusU)
                                            : Math.abs(plan.centerD)
                                                    + Math.max(plan.radiusU, plan.radiusD);
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
    private final double jitter, minimumExposure, caveRoof;
    private final double[] caveLength, caveHalfWidth, caveHeight;
    private final int waterTop;
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
        jitter = Rules.number(j, "slotJitter", 0, 1);
        minimumExposure = Rules.number(j, "minimumExposure", 0, .9);
        waterTop = heights.waterTopY;
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
        put(t, "archFraction", Rules.number(j, "archFraction", 0, 1));
        JsonObject families = j.getAsJsonObject("families");
        for (Family f : Family.values()) {
            JsonObject p = families.getAsJsonObject(f.name());
            put(t, f.name() + ".occurrence", 1);
            for (String name : new String[] {"widthScale", "reliefScale", "detail"})
                put(t, f.name() + "." + name, p.get(name).getAsDouble());
            tuning[f.ordinal()] = new RockyCoasts.Tuning(t, f);
        }
        JsonObject cave = j.getAsJsonObject("seaCave");
        caveLength = range(cave, "lengthBlocks", 4, 256);
        caveHalfWidth = range(cave, "halfWidthBlocks", 1, 16);
        caveHeight = range(cave, "heightBlocks", 2, 32);
        caveRoof = Rules.number(cave, "roofBlocks", 4, 64);
        for (Shore.Type type : Shore.TYPES) for (String form : type.forms) Form.valueOf(form);
    }

    private static double[] range(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(min <= lo && lo <= hi && hi <= max))
            throw new IllegalStateException("riven-coasts.json forms " + key);
        return new double[] {lo, hi};
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
        if (s.type == null || s.type.forms.length == 0) return NONE;
        List<Placed> out = new ArrayList<>();
        Shore.Nearest owner = new Shore.Nearest();
        double span = shore.cellBlocks(), tx = -s.nz, tz = s.nx;
        for (int f = 0; f < s.type.forms.length; f++) {
            Form form = Form.valueOf(s.type.forms[f]);
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
                Placed p = form == Form.SEA_CAVE ? cave(s, at, h) : kernel(form, s, at, h);
                if (p != null && clear(p, out)) out.add(p);
            }
        }
        return out.toArray(NONE);
    }

    /**
     * Whether a form keeps clear of the bodies placed before it in its segment: a stack, arch or
     * sea cave never overlaps another (the form listed first wins), so none walls another in.
     */
    private static boolean clear(Placed p, List<Placed> placed) {
        if (p.body == 0) return true;
        for (Placed q : placed)
            if (q.body > 0 && Math.hypot(p.x - q.x, p.z - q.z) < p.body + q.body + CLEARANCE)
                return false;
        return true;
    }

    private Placed cave(Shore.Segment s, double[] at, long h) {
        double length = caveLength[0] + (caveLength[1] - caveLength[0]) * unit(h, 1);
        double half = caveHalfWidth[0] + (caveHalfWidth[1] - caveHalfWidth[0]) * unit(h, 2);
        double height = caveHeight[0] + (caveHeight[1] - caveHeight[0]) * unit(h, 3);
        // A cave reaches no farther than its segment's band.
        length = Math.min(length, s.width);
        return new Placed(Form.SEA_CAVE, s, null, at, length + half, h, length, half, height);
    }

    /**
     * A kernel form at a shore point. Its plan's geometry is fixed by its seed, so a first plan
     * says where to read the ground: the cliff top one and one and a half widths inland, and for
     * a stack or arch the sea floor under its centre or tunnel, which must be open, supplied sea
     * (for a stack, every column of its footprint and moat). Those readings are the anchor's
     * only facts.
     */
    private Placed kernel(Form form, Shore.Segment s, double[] at, long h) {
        Family family = Family.valueOf(form.name());
        RockyCoasts.Tuning t = tuning[family.ordinal()];
        double length = Math.hypot(at[2], at[3]);
        at[2] /= length;
        at[3] /= length;
        RockyCoasts.Plan geometry =
                RockyCoasts.prepare(
                        family,
                        anchor(h, at, reference.high, reference.high, reference.low, 1, true, true),
                        reference,
                        t);
        TerrainMap.Sample probe = scratch.get();
        double crest = reference.high;
        for (double d : new double[] {geometry.width, 1.5 * geometry.width}) {
            parent.sample(at[0] + at[2] * d, at[1] + at[3] * d, probe);
            if (probe.ocean.marine) return null;
            crest = Math.min(crest, probe.height);
        }
        parent.sample(at[0], at[1], probe);
        double exposure = exposure(probe);
        // A body is built from each column's own ground, so no column is too deep for it: the
        // kernel's foundation, the deepest floor its columns may stand on, is the lowest surface.
        double foundation = reference.low;
        boolean permit = false;
        if (family == Family.STACK_ARCH) {
            // A stack stands in open sea and an arch's tunnel opens over it, however shallow.
            double d = geometry.arch ? -geometry.width * TUNNEL_D : geometry.centerD;
            parent.sample(at[0] + at[2] * d, at[1] + at[3] * d, probe);
            permit =
                    probe.ocean.marine
                            && probe.ocean.supplied
                            && !reserved(probe)
                            && probe.waterY() == waterTop;
        }
        crest = Math.min(Math.max(crest, reference.low), reference.high);
        double relief = crest - reference.sea;
        if (relief < reference.dropMin) return null;
        RockyCoasts.Plan plan =
                RockyCoasts.prepare(
                        family,
                        anchor(h, at, crest, reference.high, foundation, exposure, permit, true),
                        local(relief, family == Family.STACK_ARCH),
                        t);
        if (plan.admission != RockyCoasts.Reason.ACCEPTED) return null;
        if (family == Family.STACK_ARCH && !plan.arch && !moated(plan, probe)) return null;
        double reach = Math.hypot(plan.halfAlong, Math.max(plan.inland, plan.offshore));
        return new Placed(form, s, plan, at, reach, h, 0, 0, 0);
    }

    /**
     * The scarp reference for the cliff a form stands against. The kernel caps a height form's
     * crest at the sea plus its drop, so that drop is the cliff's own relief: one from the shared
     * 8 to 80 block range would plane a taller cliff down to it. A stack or arch keeps the shared
     * drop, no taller than its cliff, since a free-standing body as high as an 80-block cliff
     * would be a needle ten times its width. The least drop stays shared (an arch's least roof).
     */
    private RockyCoasts.Reference local(double relief, boolean stack) {
        Properties r = new Properties();
        r.putAll(referenceValues);
        double cap = stack ? Math.min(relief, reference.dropMax) : relief;
        put(r, "scarp.magnitude.mean", stack ? Math.min(reference.dropMean, cap) : relief);
        put(r, "scarp.magnitude.max", cap);
        return new RockyCoasts.Reference(r);
    }

    private RockyCoasts.Anchor anchor(
            long h,
            double[] at,
            double minimumParent,
            double maximumParent,
            double foundation,
            double exposure,
            boolean permit,
            boolean grounded) {
        return new RockyCoasts.Anchor(
                seed,
                h,
                at[0],
                at[1],
                at[2],
                at[3],
                minimumParent,
                maximumParent,
                foundation,
                1,
                Math.max(0, Math.min(1, exposure)),
                permit,
                grounded);
    }

    /**
     * Whether open, supplied sea at the water's top holds every column within MOAT of a stack's
     * body ellipse, so its base stands clear of land, banks and held water on every side.
     */
    private boolean moated(RockyCoasts.Plan p, TerrainMap.Sample probe) {
        RockyCoasts.Anchor a = p.anchor;
        double ru = p.radiusU + MOAT, rd = p.radiusD + MOAT, reach = Math.max(ru, rd);
        double cx = a.x + a.nx * p.centerD, cz = a.z + a.nz * p.centerD;
        for (int z = (int) Math.floor(cz - reach); z <= (int) Math.ceil(cz + reach); z++)
            for (int x = (int) Math.floor(cx - reach); x <= (int) Math.ceil(cx + reach); x++) {
                double u = ((x - a.x) * -a.nz + (z - a.z) * a.nx) / ru,
                        d = ((x - a.x) * a.nx + (z - a.z) * a.nz - p.centerD) / rd;
                if (u * u + d * d >= 1) continue;
                parent.sample(x, z, probe);
                if (!probe.ocean.marine
                        || !probe.ocean.supplied
                        || reserved(probe)
                        || probe.waterY() != waterTop) return false;
            }
        return true;
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

    /** A placed form as checks see it: kind, shore point, inland normal and segment band. */
    static final class View {
        final Form form;
        final long id;
        final double x, z, nx, nz, band, reach;

        /** A stack's centre across the shore and radius, an arch's half extent; a cave's size. */
        final double centerD, radius, extent, length, halfWidth;

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
            RockyCoasts.Plan plan = p.plan;
            arch = plan != null && plan.arch;
            centerD = plan == null ? 0 : plan.centerD;
            radius = plan == null ? 0 : Math.max(plan.radiusU, plan.radiusD);
            extent = plan == null ? 0 : Math.min(plan.width * 2, plan.offshore * .6);
            length = p.length;
            halfWidth = p.halfWidth;
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
     * deepest wins, shared out by the segment's own blend), a stack or arch body built from the
     * column's own ground, and a sea cave carved under the final surface's roof band.
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
        Placed body = null, cave = null;
        double bodyTop = 0, localD = 0;
        for (int di = -1; di <= 1; di++)
            for (int dj = -1; dj <= 1; dj++)
                for (Placed p : forms(ci + di, cj + dj)) {
                    if (Math.abs(x - p.x) > p.reach || Math.abs(z - p.z) > p.reach) continue;
                    if (p.form == Form.SEA_CAVE) {
                        if (cave == null && !marine && !reserved) cave = p;
                        continue;
                    }
                    boolean stack = p.form == Form.STACK_ARCH;
                    if (!stack && marine) continue;
                    RockyCoasts.sample(
                            p.plan,
                            new RockyCoasts.Input(
                                    x,
                                    z,
                                    distance,
                                    // An arch's foot crosses the shore onto lower land: its body
                                    // is read against the cliff top and built from the column's
                                    // own ground (body), and no beach foot breaks its span.
                                    stack && !marine
                                            ? Math.max(ground, p.plan.anchor.minimumParent)
                                            : ground,
                                    1,
                                    exposure,
                                    stack ? 0 : protection,
                                    marine,
                                    reserved,
                                    stack && !reserved),
                            c);
                    if (c.reason != RockyCoasts.Reason.ACCEPTED || c.weight <= 0) continue;
                    if (stack) {
                        if (body == null) {
                            body = p;
                            bodyTop = c.bodyTop;
                            localD = c.localD;
                        }
                        continue;
                    }
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
        // A body claims only the columns it builds or carves; elsewhere its support is free.
        if (body != null) body(body, ground, bodyTop, localD, out);
        if (out.hasVolume()) {
            out.family = Form.STACK_ARCH;
            out.id = body.id;
            out.arch = body.plan.arch;
            out.weight = 1;
            height = Math.max(ground, Math.floor(bodyTop));
            out.emergent = marine && height >= waterTop + 1;
        } else if (cave != null && out.family == null) {
            double cut = caveColumn(cave, x, z, height, sample.water, out);
            if (out.hasVolume() || cut < height) {
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

    /**
     * A stack or arch body's runs: solid from the column's own ground to the body's top, so every
     * column stands on what lies beneath it, and the arch's tunnel void through body and ground.
     */
    private void body(Placed p, double ground, double bodyTop, double localD, Result out) {
        RockyCoasts.Column c = new RockyCoasts.Column();
        c.reason = RockyCoasts.Reason.ACCEPTED;
        c.localD = localD;
        c.bodyTop = bodyTop;
        int floor = (int) Math.floor(ground), top = (int) Math.floor(bodyTop);
        // Below its ground a column takes only the arch's tunnel.
        int from =
                p.plan.arch
                        ? Math.min(
                                floor + 1,
                                (int) Math.floor(p.plan.tunnelCenterY - p.plan.tunnelRadiusY))
                        : floor + 1;
        for (int y = from; y <= top; y++) {
            Edit e = RockyCoasts.occupancy(p.plan, null, c, y);
            if (e == Edit.VOID) out.add(y, y + 1, Edit.VOID);
            else if (y > floor) out.add(y, y + 1, Edit.SOLID);
        }
    }

    /**
     * A sea cave at a column: a tapering vault from one block above the water into the cliff.
     * Where the column keeps the roof band's rock over the vault, the vault is carved; nearer the
     * face, where that roof would be thinner, the column is cut down to the vault's floor, so the
     * cave opens through a notch at the waterline instead of sealing behind the cliff's foot. No
     * aquifer is opened either way. Returns the column's height.
     */
    private double caveColumn(
            Placed p, double x, double z, double surface, Hydro.Sample water, Result out) {
        double d = (x - p.x) * p.nx + (z - p.z) * p.nz;
        double u = (x - p.x) * -p.nz + (z - p.z) * p.nx;
        if (d < 0 || d > p.length) return surface;
        double t = d / p.length;
        double half = p.halfWidth * (1 - .5 * t);
        double across = Math.abs(u) / half;
        if (across >= 1) return surface;
        int bottom = waterTop + 1, ground = (int) Math.floor(surface);
        int top =
                (int) Math.floor(bottom + p.height * (1 - .6 * t) * Math.sqrt(1 - across * across));
        if (top <= bottom || ground < bottom) return surface;
        if (water.aquiferMin <= water.aquiferMax
                && water.aquiferMin <= Math.max(top, ground)
                && water.aquiferMax >= bottom) return surface;
        if (ground - caveRoof >= top) {
            out.add(bottom, top + 1, Edit.VOID);
            return surface;
        }
        // The notch's floor is the vault's: the block at the water's top, held by any levee.
        return Math.min(surface, Hydro.holding(waterTop + .5, water));
    }
}
