package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.coast.SedimentaryCoasts;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The coast plan's sediment (riven-coasts.json sediment): each beach segment's bench along its
 * shore, a spit off a beach's end beside rock, and a tombolo tying a beach to a stack or islet,
 * from the MIT sedimentary kernels. A segment places its sediment once, against the ground the
 * coast's earlier stages made, so none needs a certificate: a bench follows the segment's own
 * snapped shore, and a spit or tombolo stands only where every node of its line lies on shallow,
 * unreserved ground no higher than a beach.
 */
public final class CoastSediment {
    /** A sediment form, each the kernel family of its name. */
    public enum Form {
        BEACH_BENCH,
        SPIT,
        TOMBOLO;

        final SedimentaryCoasts.Family family = SedimentaryCoasts.Family.valueOf(name());
    }

    /** Complete terrain before the sediment, coast forms included; it never asks for sediment. */
    @FunctionalInterface
    interface Parent {
        void sample(double x, double z, TerrainMap.Sample out);
    }

    private static final Rules RULES = Rules.load("riven-coasts.json", 1);
    private static final Placed[] NONE = new Placed[0];
    private static final SedimentaryCoasts.Reservation[] NO_MASKS =
            new SedimentaryCoasts.Reservation[0];

    /**
     * The segment's type is the admission: a beach has supply and a gentle berm by its profile, so
     * the kernel's whole-body facts pass and placement alone decides.
     */
    private static final SedimentaryCoasts.Admission ADMITTED =
            new SedimentaryCoasts.Admission(1, 0, 0, true);

    /** How many coast cells from its segment's cell a sediment form may reach. */
    private static final int SEARCH = 2;

    /** The most guide points a stretch walks each way from its site (a plan holds 64). */
    private static final int MAXIMUM_STEPS = 31;

    /** How far inland of the shore line a spit or tombolo joins the land, in blocks. */
    private static final double ATTACHMENT = 2;

    /** The directions an islet's surrounding sea is probed in. */
    private static final int ISLET_PROBES = 12;

    /** One placed sediment form: its kernel plan and the box it edits. */
    private static final class Placed {
        final Form form;
        final long id;
        final SedimentaryCoasts.Plan plan;

        Placed(Form form, long id, SedimentaryCoasts.Plan plan) {
            this.form = form;
            this.id = id;
            this.plan = plan;
        }

        boolean contains(double x, double z) {
            return x >= plan.minX && x <= plan.maxX && z >= plan.minZ && z <= plan.maxZ;
        }
    }

    /**
     * A segment's own stretch of shore: snapped points with land on the left of their order, the
     * index range the segment owns, and whether the point past each end belongs to a rocky type.
     */
    private static final class Stretch {
        final double[] x, z, nx, nz;
        final int first, last;
        final boolean rockBefore, rockAfter;

        Stretch(List<double[]> points, int first, int last, boolean rockBefore, boolean rockAfter) {
            int n = points.size();
            x = new double[n];
            z = new double[n];
            nx = new double[n];
            nz = new double[n];
            for (int i = 0; i < n; i++) {
                double[] p = points.get(i);
                x[i] = p[0];
                z[i] = p[1];
                nx[i] = p[2];
                nz[i] = p[3];
            }
            this.first = first;
            this.last = last;
            this.rockBefore = rockBefore;
            this.rockAfter = rockAfter;
        }
    }

    /** A column's sediment: its height, the edit that made it and whether sand rose from the sea. */
    public static final class Result implements StageResult {
        public double height, parentHeight, weight;
        public long id;
        public Form family;
        public boolean emergent;
        public String reason;
        private final SedimentaryCoasts.Sample geometry = new SedimentaryCoasts.Sample();

        @Override
        public void clear(double surface) {
            height = parentHeight = surface;
            weight = 0;
            id = 0;
            family = null;
            emergent = false;
            reason = "NO_FORM";
        }
    }

    private final long seed;
    private final Shore shore;
    private final CoastForms rocks;
    private final Parent parent;
    private final SedimentaryCoasts.Reference reference;
    private final SedimentaryCoasts.Tuning tuning;
    private final double guide, spitChance, tomboloSpacing, isletRadius;
    private final int sea;
    private final CellMemo<Placed[]> plans = new CellMemo<>(4096);
    private final ThreadLocal<TerrainMap.Sample> scratch =
            ThreadLocal.withInitial(TerrainMap.Sample::new);

    CoastSediment(
            long worldSeed,
            TerrainHeightReference heights,
            Shore shore,
            CoastForms rocks,
            Parent parent) {
        this.shore = shore;
        this.rocks = rocks;
        this.parent = parent;
        JsonObject j = RULES.json.getAsJsonObject("sediment");
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        sea = heights.waterTopY + 1;
        TerrainHeightReference.Feature beach = heights.feature("beach"),
                plain = heights.feature("coastal_plain"),
                shoal = heights.feature("shoal");
        double scale = TerrainScale.HORIZONTAL;
        reference =
                new SedimentaryCoasts.Reference(
                        heights.seaLevel,
                        beach.elevationOffset.min,
                        beach.elevationOffset.targetAverage,
                        beach.elevationOffset.max,
                        beach.width.min * scale,
                        beach.width.targetAverage * scale,
                        beach.width.max * scale,
                        beach.relief.targetAverage,
                        plain.width.targetAverage * scale,
                        -shoal.elevationOffset.min,
                        heights.fingerprint);
        JsonObject t = j.getAsJsonObject("tuning");
        tuning =
                new SedimentaryCoasts.Tuning(
                        Rules.number(t, "widthScale", .35, 2),
                        Rules.number(t, "lengthScale", .2, 1.5),
                        Rules.number(t, "offsetScale", .08, .45),
                        Rules.number(t, "hook", .1, .8),
                        Rules.number(t, "roughness", 0, 1),
                        Rules.number(t, "bench", 0, 1));
        guide = Rules.number(j, "guideBlocks", 8, 64);
        spitChance = Rules.number(j, "spitChance", 0, 1);
        tomboloSpacing = Rules.number(j, "tomboloSlotBlocks", 32, 1024);
        isletRadius = Rules.number(j, "isletRadiusBlocks", 8, 256);
        for (Shore.Type type : Shore.TYPES) for (String form : type.sediment) Form.valueOf(form);
    }

    private static double unit(long h, int k) {
        return SeededNoise.unit(SeededNoise.mix(h + k * 0x9e3779b97f4a7c15L));
    }

    /** A segment's sediment, placed once. */
    private Placed[] forms(int i, int j) {
        return plans.get(i, j, () -> place(shore.segmentAt(i, j)));
    }

    private Placed[] place(Shore.Segment s) {
        if (s.type == null || s.type.sediment.length == 0) return NONE;
        Stretch stretch = stretch(s);
        if (stretch == null) return NONE;
        List<Placed> out = new ArrayList<>();
        for (String name : s.type.sediment) {
            Form form = Form.valueOf(name);
            long h = SeededNoise.mix(s.key ^ seed ^ form.family.salt);
            switch (form) {
                case BEACH_BENCH -> bench(stretch, h, out);
                case SPIT -> spits(stretch, h, out);
                case TOMBOLO -> tombolo(stretch, h, out);
            }
        }
        return out.toArray(NONE);
    }

    /**
     * Walks a segment's shore from its site both ways, a guide's step at a time along each point's
     * own shore, snapping every step onto the shore line, until the next point belongs to another
     * segment (that point is kept, so neighbouring benches overlap) or the shore is lost.
     */
    private Stretch stretch(Shore.Segment s) {
        // A site lies near its shore, not on it (S3a moves it toward the line).
        double[] start = shore.onShore(s.x, s.z, shore.cellBlocks() / 2);
        if (start == null) return null;
        Shore.Nearest owner = new Shore.Nearest();
        List<double[]> before = new ArrayList<>(), after = new ArrayList<>();
        boolean[] rock = new boolean[2];
        for (int side = 0; side < 2; side++) {
            List<double[]> walk = side == 0 ? before : after;
            double sign = side == 0 ? -1 : 1;
            double[] at = start;
            for (int step = 0; step < MAXIMUM_STEPS; step++) {
                // Land lies on the left of the tangent (nz, -nx).
                double[] next =
                        shore.onShore(
                                at[0] + sign * at[3] * guide, at[1] - sign * at[2] * guide, guide);
                if (next == null || Math.hypot(next[0] - at[0], next[1] - at[1]) > 2 * guide) break;
                walk.add(next);
                shore.nearest(next[0], next[1], owner);
                if (!s.is(owner.a)) {
                    rock[side] =
                            owner.a != null
                                    && owner.a.type != null
                                    && (owner.a.type.kind == Shore.Kind.CLIFF
                                            || owner.a.type.kind == Shore.Kind.ROCK);
                    break;
                }
                at = next;
            }
        }
        List<double[]> points = new ArrayList<>();
        for (int k = before.size() - 1; k >= 0; k--) points.add(before.get(k));
        points.add(start);
        points.addAll(after);
        // The owned range leaves out a walk's last point where it crossed into another segment.
        int first = ownedFrom(points, s, owner, 0), last = ownedFrom(points, s, owner, 1);
        if (last - first < 1) return null;
        return new Stretch(points, first, last, rock[0], rock[1]);
    }

    /** The first (end 0) or last (end 1) point of a walk that its segment owns. */
    private int ownedFrom(List<double[]> points, Shore.Segment s, Shore.Nearest owner, int end) {
        int n = points.size();
        for (int k = 0; k < n; k++) {
            int i = end == 0 ? k : n - 1 - k;
            double[] p = points.get(i);
            shore.nearest(p[0], p[1], owner);
            if (s.is(owner.a)) return i;
        }
        return end == 0 ? n : -1;
    }

    /** A bench along the segment's whole stretch, overlapping its neighbours by a point. */
    private void bench(Stretch s, long h, List<Placed> out) {
        out.add(
                new Placed(
                        Form.BEACH_BENCH,
                        h,
                        SedimentaryCoasts.beach(
                                seed, h, reference, tuning, ADMITTED, s.x, s.z, NO_MASKS)));
    }

    /**
     * A spit off each end of the stretch that meets a rocky segment, by spitChance, rooted at the
     * last point the beach owns and drifting away from the rock.
     */
    private void spits(Stretch s, long h, List<Placed> out) {
        TerrainMap.Sample probe = scratch.get();
        for (int end = 0; end < 2; end++) {
            if (!(end == 0 ? s.rockBefore : s.rockAfter) || unit(h, end) >= spitChance) continue;
            SedimentaryCoasts.Anchor root = attachment(s, end == 0 ? s.first : s.last, probe);
            if (root == null) continue;
            long id = SeededNoise.mix(h + end + 1);
            SedimentaryCoasts.Plan plan =
                    SedimentaryCoasts.spit(
                            seed,
                            id,
                            reference,
                            tuning,
                            ADMITTED,
                            root,
                            NO_MASKS,
                            end == 0 ? 1 : -1);
            if (plan.admission == SedimentaryCoasts.Reason.ACCEPTED && shallow(plan, 1, probe))
                out.add(new Placed(Form.SPIT, id, plan));
        }
    }

    /**
     * At most one tombolo, from the first slot along the stretch that finds a stack (from the
     * coast's forms) or an islet out to sea within the kernel's tie bounds, along a line of
     * shallow, unreserved ground.
     */
    private void tombolo(Stretch s, long h, List<Placed> out) {
        TerrainMap.Sample probe = scratch.get();
        int step = Math.max(1, (int) Math.round(tomboloSpacing / guide));
        for (int i = s.first + step / 2; i <= s.last; i += step) {
            SedimentaryCoasts.Anchor mainland = attachment(s, i, probe);
            if (mainland == null) continue;
            SedimentaryCoasts.Anchor island = island(s, i, probe);
            if (island == null) continue;
            // The kernel's tie bounds, between the two attachments themselves.
            double tie = Math.hypot(island.x - mainland.x, island.z - mainland.z);
            if (tie < reference.widthMean * 2 || tie > reference.plainWidth * 1.5) continue;
            long id = SeededNoise.mix(h + i);
            SedimentaryCoasts.Plan plan =
                    SedimentaryCoasts.tombolo(
                            seed, id, reference, tuning, ADMITTED, mainland, island, NO_MASKS);
            if (plan.admission != SedimentaryCoasts.Reason.ACCEPTED || !shallow(plan, 1, probe))
                continue;
            out.add(new Placed(Form.TOMBOLO, id, plan));
            return;
        }
    }

    /**
     * Where a spit or tombolo joins the land at stretch point i: ATTACHMENT blocks inland of the
     * shore line, so on land, or null where that is sea.
     */
    private SedimentaryCoasts.Anchor attachment(Stretch s, int i, TerrainMap.Sample probe) {
        double x = s.x[i] + s.nx[i] * ATTACHMENT, z = s.z[i] + s.nz[i] * ATTACHMENT;
        parent.sample(x, z, probe);
        if (probe.ocean.marine) return null;
        return new SedimentaryCoasts.Anchor(x, z, probe.height, s.nz[i], -s.nx[i], true);
    }

    /**
     * What a tombolo from stretch point i would tie to: the nearest stack seaward of it, else the
     * first land met seaward across open water if it is an islet, inside the kernel's tie bounds.
     */
    private SedimentaryCoasts.Anchor island(Stretch s, int i, TerrainMap.Sample probe) {
        double x = s.x[i], z = s.z[i], nx = s.nx[i], nz = s.nz[i];
        double nearest = reference.widthMean * 2, farthest = reference.plainWidth * 1.5;
        CoastForms.View stack = null;
        double best = Double.POSITIVE_INFINITY;
        for (CoastForms.View v :
                rocks.placed(x - farthest, z - farthest, x + farthest, z + farthest)) {
            if (v.form != CoastForms.Form.STACK_ARCH || v.arch) continue;
            double dx = v.cx - x, dz = v.cz - z, d = Math.hypot(dx, dz) - v.radius - 1;
            // Out to sea: within 40 degrees of the seaward normal.
            if (d < nearest
                    || d > farthest
                    || -(dx * nx + dz * nz) < Math.cos(Math.toRadians(40)) * Math.hypot(dx, dz))
                continue;
            if (d < best) {
                best = d;
                stack = v;
            }
        }
        if (stack != null) {
            double dx = x - stack.cx, dz = z - stack.cz, d = Math.hypot(dx, dz);
            double r = stack.radius + 1;
            return new SedimentaryCoasts.Anchor(
                    stack.cx + dx / d * r,
                    stack.cz + dz / d * r,
                    reference.sea + reference.beachMean,
                    nz,
                    -nx,
                    true);
        }
        for (double d = guide; d <= farthest; d += 4) {
            double ix = x - nx * d, iz = z - nz * d;
            parent.sample(ix, iz, probe);
            if (probe.ocean.marine) continue;
            if (d < nearest) return null;
            double top = probe.height;
            // An islet, not a far shore: open sea all round it within isletRadius.
            for (int k = 0; k < ISLET_PROBES; k++) {
                double a = 2 * Math.PI * k / ISLET_PROBES;
                parent.sample(
                        ix + Math.cos(a) * isletRadius, iz + Math.sin(a) * isletRadius, probe);
                if (!probe.ocean.marine) return null;
            }
            return new SedimentaryCoasts.Anchor(ix, iz, top, nz, -nx, true);
        }
        return null;
    }

    /**
     * Whether every node of a plan from index from on stands on ground a deposit may build on:
     * not reserved, no deeper than the shoal and no higher than a beach.
     */
    private boolean shallow(SedimentaryCoasts.Plan plan, int from, TerrainMap.Sample probe) {
        for (int k = from; k < plan.nodeCount() - 1; k++) {
            parent.sample(plan.nodeX(k), plan.nodeZ(k), probe);
            if (reserved(probe)
                    || probe.height < reference.sea - reference.shoalDepth
                    || probe.height > reference.sea + reference.beachMax) return false;
        }
        return true;
    }

    /** Water sediment leaves alone: protected or reserved shore, wet or banked columns. */
    private static boolean reserved(TerrainMap.BaseSample s) {
        return s.ocean.protectedWater || s.water.wet || s.water.weight > 0;
    }

    /**
     * Places the sediment of every segment near a region ahead of demand, on Cubature's pool, or
     * declines once a quarter of the memo is held. Plans are pure and their memo bounded, so
     * preparing them changes timing only.
     */
    boolean prepareRegion(double minX, double minZ, double maxX, double maxZ, BuildPool pool) {
        int[] held = {0};
        plans.forEachHeld(p -> held[0]++);
        if (held[0] > plans.capacity() / 4) return false;
        double cell = shore.cellBlocks();
        int x0 = (int) Math.floor(minX / cell) - SEARCH,
                x1 = (int) Math.floor(maxX / cell) + SEARCH;
        int z0 = (int) Math.floor(minZ / cell) - SEARCH,
                z1 = (int) Math.floor(maxZ / cell) + SEARCH;
        int nx = x1 - x0 + 1;
        pool.forEach(nx * (z1 - z0 + 1), k -> forms(x0 + k % nx, z0 + k / nx));
        return true;
    }

    /** Whether any sediment form may edit the column: the cheap test before sampling it. */
    public boolean hasGeometry(double x, double z) {
        double cell = shore.cellBlocks();
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        for (int di = -SEARCH; di <= SEARCH; di++)
            for (int dj = -SEARCH; dj <= SEARCH; dj++)
                for (Placed p : forms(ci + di, cj + dj)) if (p.contains(x, z)) return true;
        return false;
    }

    /** A placed sediment form as checks see it: its kind, id and plan nodes. */
    static final class View {
        final Form form;
        final long id;
        final double[] x, z;

        View(Placed p) {
            form = p.form;
            id = p.id;
            x = new double[p.plan.nodeCount()];
            z = new double[x.length];
            for (int k = 0; k < x.length; k++) {
                x[k] = p.plan.nodeX(k);
                z[k] = p.plan.nodeZ(k);
            }
        }
    }

    /** Every sediment form of the segments of the coast cells overlapping a box, for checks. */
    List<View> placed(double minX, double minZ, double maxX, double maxZ) {
        double cell = shore.cellBlocks();
        List<View> out = new ArrayList<>();
        for (int i = (int) Math.floor(minX / cell); i <= (int) Math.floor(maxX / cell); i++)
            for (int j = (int) Math.floor(minZ / cell); j <= (int) Math.floor(maxZ / cell); j++)
                for (Placed p : forms(i, j)) out.add(new View(p));
        return out;
    }

    /**
     * Applies the sediment to a column after the coast's forms, where no form claimed it: the
     * highest edit of every sediment form whose plan reaches it (deposition forms only build; a
     * bench may also cut the low shore, but no channel's levee below its water). Sand rising from
     * marine columns is emergent.
     */
    public void apply(double x, double z, TerrainMap.Sample sample) {
        Result out = sample.sediment;
        out.clear(sample.height);
        // Sand never covers a coast form's edit: its notch, cove, stack or arch stays as built.
        if (!Double.isFinite(sample.height) || sample.rocky.family != null) return;
        double cell = shore.cellBlocks(), ground = sample.height, height = Double.NaN;
        boolean marine = sample.ocean.marine, reserved = reserved(sample);
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        SedimentaryCoasts.Sample g = out.geometry;
        for (int di = -SEARCH; di <= SEARCH; di++)
            for (int dj = -SEARCH; dj <= SEARCH; dj++)
                for (Placed p : forms(ci + di, cj + dj)) {
                    if (!p.contains(x, z)) continue;
                    SedimentaryCoasts.sample(
                            p.plan, x, z, ground, marine, p.form != Form.BEACH_BENCH, reserved, g);
                    if (g.reason != SedimentaryCoasts.Reason.ACCEPTED || g.weight <= 0) continue;
                    double edited = Hydro.holding(g.heightY, sample.water);
                    if (Double.isNaN(height) || edited > height) {
                        height = edited;
                        out.weight = g.weight;
                        out.family = p.form;
                        out.id = p.id;
                    }
                }
        if (out.family == null) return;
        out.reason = "ACCEPTED";
        out.height = height;
        out.emergent = marine && height >= sea;
        sample.height = height;
        sample.emergentSediment = out.emergent;
    }
}
