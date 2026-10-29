package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Rare exaggerated landforms (riven-wonders.json): pillar forests, tepuis, hoodoo amphitheatres,
 * tower karst, giant sinkholes, arch fins, tsingy blade mazes, ring structures and basalt
 * column buttes. Each owns a WONDER site; its decision reads one earlier column at the anchor and is
 * memoized per cell, so it is a pure function of seed, definitions and coordinates. A column takes
 * a new surface and at most one void interval below it (an arch, a cap's underside, a rim's
 * undercut); trigonometry is StrictMath, so every thread and preview agrees to the bit.
 */
public final class WonderTerrain {
    private static final Rules RULES = Rules.load("riven-wonders.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    /** Largest rise of a wonder column above its strata floor; bounds a column's strata runs. */
    public static final int MAXIMUM_SPAN = 200;

    public enum Family {
        PILLAR_FOREST,
        TEPUI,
        HOODOO_FIELD,
        TOWER_KARST,
        TIANKENG,
        FIN_ARCHES,
        TSINGY,
        RING_STRUCTURE,
        BASALT_COLUMNS;

        /** Explicit identity salt from the stable name, never the ordinal. */
        final long salt;

        Family() {
            long h = 0xcbf29ce484222325L;
            for (char ch : name().toCharArray()) h = (h ^ ch) * 0x100000001b3L;
            salt = h;
        }
    }

    private static final Family[] FAMILIES = Family.values();

    private final long seed;
    private final Sites sites;
    private final MajorFeatureParent earlier;
    private final BoundedWaterClearance water;
    private final Profile[] profiles = new Profile[FAMILIES.length];
    private final double[][] regionWeights =
            new double[ClimateRegion.values().length][FAMILIES.length];
    private final double radius, shoreReservation, waterSkirt, ceiling, floorLimit;
    private final int searchRadius;
    private final SeededNoise texture;
    private final CellMemo<Decision> decisions = new CellMemo<>(1024), plans = new CellMemo<>(1024);

    WonderTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            MajorFeatureParent earlier,
            BoundedWaterClearance water) {
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        texture = new SeededNoise(SeededNoise.mix(seed ^ 0x5445585455524531L));
        this.sites = sites;
        this.earlier = earlier;
        this.water = water;
        radius = sites.maximumRadius(Sites.Lane.WONDER);
        searchRadius = sites.searchRadius(Sites.Lane.WONDER);
        shoreReservation =
                heights.feature("coastal_plain").width.targetAverage
                        * Rules.number(j, "shoreReservationInCoastalPlainWidths", 0, 4);
        waterSkirt = Rules.number(j, "waterSkirtBlocks", 1, 128);
        ceiling = heights.highestSurface - Rules.number(j, "headroomBlocks", 0, 128);
        floorLimit = heights.lowestSurface + Rules.number(j, "floorMarginBlocks", 0, 128);
        JsonObject families = j.getAsJsonObject("families");
        for (Family f : FAMILIES) {
            Profile p = new Profile(families.getAsJsonObject(f.name()));
            if (p.radius[1] > radius)
                throw new IllegalStateException("Wonder radius exceeds its lane: " + f);
            // Repeated elements stay inside the 3 x 3 grid neighbourhood each column searches.
            if (f != Family.BASALT_COLUMNS && p.spacing[1] > 0 && p.size[1] > p.spacing[0] * .75)
                throw new IllegalStateException("Wonder element size exceeds its spacing: " + f);
            if (p.height[1] > MAXIMUM_SPAN)
                throw new IllegalStateException("Wonder height exceeds its span: " + f);
            profiles[f.ordinal()] = p;
        }
        JsonObject weights = j.getAsJsonObject("regionWeights");
        for (ClimateRegion region : ClimateRegion.values()) {
            JsonArray row = weights.getAsJsonArray(region.name());
            if (row == null || row.size() != FAMILIES.length)
                throw new IllegalStateException("Wonder weights " + region);
            for (int i = 0; i < FAMILIES.length; i++) {
                double w = row.get(i).getAsDouble();
                if (!Double.isFinite(w) || w < 0) throw new IllegalStateException("Wonder weight");
                regionWeights[region.ordinal()][i] = w;
            }
        }
    }

    private static final class Profile {
        final double occurrence;
        final double[] radius, height, spacing, size, depth, overhang;
        final double density;
        final boolean bare;
        final TerrainBlock[] palette;

        Profile(JsonObject p) {
            occurrence = Rules.number(p, "occurrence", 0, 1);
            radius = range(p, "radius");
            height = range(p, "height");
            spacing = range(p, "spacing");
            size = range(p, "size");
            depth = range(p, "depth");
            overhang = range(p, "overhang");
            density = Rules.number(p, "density", 0, 1);
            bare = p.get("bare").getAsBoolean();
            palette =
                    p.has("palette") && !p.get("palette").isJsonNull()
                            ? Materials.palette(p.get("palette").getAsString())
                            : null;
        }

        private static double[] range(JsonObject p, String key) {
            JsonArray a = p.getAsJsonArray(key);
            double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo < 0 || hi < lo)
                throw new IllegalStateException("Wonder range " + key);
            return new double[] {lo, hi};
        }
    }

    /** One site's own decision: its family, footprint and anchor level, or a rejection reason. */
    static final class Decision {
        final Family family;
        final String reason;
        final long id;
        final double x, z, radius, angle, cos, sin, level, scale;
        final double spacing, size, depth, overhang, height, density;

        Decision(String reason) {
            this.reason = reason;
            family = null;
            id = 0;
            x = z = radius = angle = cos = sin = level = scale = 0;
            spacing = size = depth = overhang = height = density = 0;
        }

        Decision(
                Family family,
                long id,
                double x,
                double z,
                Profile p,
                double level,
                double ceil,
                double floor) {
            this.family = family;
            this.id = id;
            this.x = x;
            this.z = z;
            reason = "ACCEPTED";
            radius = lerp(p.radius, unit(id, 1));
            angle = unit(id, 2) * Math.PI * 2;
            cos = StrictMath.cos(angle);
            sin = StrictMath.sin(angle);
            this.level = level;
            double tall = lerp(p.height, unit(id, 3));
            // Headroom shrinks the whole form, never clips its tops flat; a sinkhole's is below.
            double room = family == Family.TIANKENG ? level - floor : ceil - level;
            scale = Math.max(0, Math.min(1, room / Math.max(1, tall)));
            height = tall * scale;
            spacing = lerp(p.spacing, unit(id, 4));
            size = lerp(p.size, unit(id, 5));
            depth = lerp(p.depth, unit(id, 6));
            overhang = lerp(p.overhang, unit(id, 7));
            density = p.density;
        }
    }

    /** The anchor's own tests; pure per cell, and never reads another site. */
    private Decision decide(int cx, int cz) {
        return decisions.get(
                cx,
                cz,
                () -> {
                    long key = sites.key(Sites.Lane.WONDER, cx, cz);
                    double ax = sites.x(cx, key), az = sites.z(cz, key);
                    if (Math.abs(ax) + radius > 29999984 || Math.abs(az) + radius > 29999984)
                        return new Decision("WORLD_BOUND");
                    TerrainMap.Sample s = new TerrainMap.Sample();
                    earlier.sample(Math.floor(ax), Math.floor(az), s);
                    if (s.ocean.marine) return new Decision("MARINE");
                    if (s.shore.kind != Shore.Kind.INLAND || s.shore.distance <= shoreReservation)
                        return new Decision("SHORE");
                    if (s.water.wet || s.water.weight > 0) return new Decision("WATER");
                    if (s.climate.region == null) return new Decision("CLIMATE");
                    double[] w = regionWeights[s.climate.region.ordinal()];
                    int choice = Rules.choose(w, Sites.random(key, 0x574f4e44));
                    if (choice < 0) return new Decision("NOT_SELECTED");
                    Family family = FAMILIES[choice];
                    Profile p = profiles[choice];
                    long id = SeededNoise.mix(key ^ seed ^ family.salt);
                    if (unit(id, 0) >= p.occurrence) return new Decision("OCCURRENCE");
                    Decision d = new Decision(family, id, ax, az, p, s.height, ceiling, floorLimit);
                    if (d.scale < .35) return new Decision("HEADROOM");
                    // A wonder needs most of its footprint: no river across its heart, and at most
                    // two of eight ring probes on sea or coast.
                    if (water.surfaceClearance(ax, az, d.radius * .3) < d.radius * .3)
                        return new Decision("WATER_SUPPORT");
                    int lost = 0;
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        earlier.sample(
                                Math.floor(ax + d.radius * .65 * StrictMath.cos(a)),
                                Math.floor(az + d.radius * .65 * StrictMath.sin(a)),
                                s);
                        if (s.ocean.marine || s.shore.kind != Shore.Kind.INLAND) lost++;
                    }
                    if (lost > 2) return new Decision("SHORE_SUPPORT");
                    return d;
                });
    }

    /** The decision after same-lane competition among sites that pass their own tests. */
    Decision plan(int cx, int cz) {
        return plans.get(
                cx,
                cz,
                () -> {
                    long key = sites.key(Sites.Lane.WONDER, cx, cz);
                    Decision own = decide(cx, cz);
                    if (own.family == null) return own;
                    if (!sites.admits(
                            cx,
                            cz,
                            key,
                            Sites.Lane.WONDER,
                            (x, z, k) -> decide(x, z).family != null))
                        return new Decision("SHARED_BUDGET");
                    return own;
                });
    }

    public static final class Result implements StageResult {
        public double height, delta, base;
        public int voidBottom, voidTop;
        public Family family;
        public long id;
        public boolean bare;
        public TerrainBlock[] palette;
        public String reason;

        public void clear(double parent) {
            height = base = parent;
            delta = 0;
            voidBottom = voidTop = 0;
            family = null;
            id = 0;
            bare = false;
            palette = null;
            reason = "NO_SITE";
        }

        /** Whether Y lies in this column's void interval. */
        public boolean voids(long y) {
            return y >= voidBottom && y < voidTop;
        }

        public boolean hasVoid() {
            return voidTop > voidBottom;
        }
    }

    /** Shape scratch: a surface and an optional void interval, before fades. */
    private static final class Shape {
        double height, base, voidBottom, voidTop;
        boolean bare;

        void set(double parent) {
            height = base = parent;
            voidBottom = voidTop = 0;
            bare = false;
        }

        void raise(double h) {
            if (h > height) {
                height = h;
                voidBottom = voidTop = 0;
            }
        }

        /** A solid top over a void from {@code bottom} up to {@code top - thickness}. */
        void cap(double top, double bottom, double thickness) {
            if (top <= height) return;
            height = top;
            if (top - thickness > bottom + 2) {
                voidBottom = bottom;
                voidTop = top - thickness;
            } else voidBottom = voidTop = 0;
        }
    }

    public void apply(double x, double z, TerrainMap.Sample s, double parent, Result out) {
        out.clear(parent);
        if (s.ocean.marine || !Double.isFinite(parent)) return;
        int ix = (int) Math.floor(x / sites.spacing), iz = (int) Math.floor(z / sites.spacing);
        Decision found = null;
        for (int dz = -searchRadius; dz <= searchRadius && found == null; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long key = sites.key(Sites.Lane.WONDER, cx, cz);
                if (!sites.inLane(key, Sites.Lane.WONDER)) continue;
                double px = x - sites.x(cx, key), pz = z - sites.z(cz, key);
                if (px * px + pz * pz >= radius * radius) continue;
                Decision d = plan(cx, cz);
                out.reason = d.reason;
                if (d.family == null || px * px + pz * pz >= d.radius * d.radius) continue;
                // Accepted sites of one lane lie two lane radii apart: one footprint per column.
                found = d;
                break;
            }
        if (found == null) return;
        Shape shape = new Shape();
        shape.set(parent);
        shape(found, x, z, parent, shape);
        double h = Math.min(shape.height, ceiling);
        if (Math.abs(h - parent) < 1e-9 && shape.voidTop <= shape.voidBottom) return;
        // Rivers and lakes cut through a wonder: its relief eases to nothing toward water.
        double fade = Rules.smooth(water.surfaceClearance(x, z, waterSkirt) / waterSkirt);
        out.family = found.family;
        out.id = found.id;
        Profile p = profiles[found.family.ordinal()];
        out.bare = p.bare || shape.bare;
        out.palette = p.palette;
        out.height = parent + (h - parent) * fade;
        out.delta = out.height - parent;
        out.base = Math.min(out.height, Math.max(floorLimit, shape.base));
        out.base = Math.max(out.base, out.height - MAXIMUM_SPAN);
        if (fade >= 1 && shape.voidTop > shape.voidBottom) {
            out.voidBottom = (int) Math.floor(Math.max(floorLimit, shape.voidBottom));
            out.voidTop = (int) Math.floor(shape.voidTop);
        }
        out.reason = "ACCEPTED";
    }

    private void shape(Decision d, double x, double z, double parent, Shape out) {
        double px = x - d.x, pz = z - d.z;
        double u = px * d.cos + pz * d.sin, v = -px * d.sin + pz * d.cos;
        double r = Math.sqrt(u * u + v * v), t = r / d.radius;
        switch (d.family) {
            case PILLAR_FOREST:
                pillars(d, u, v, t, parent, out);
                return;
            case TEPUI:
                tepui(d, u, v, r, parent, out);
                return;
            case HOODOO_FIELD:
                hoodoos(d, u, v, t, parent, out);
                return;
            case TOWER_KARST:
                towers(d, u, v, t, parent, out);
                return;
            case TIANKENG:
                sinkhole(d, u, v, r, parent, out);
                return;
            case FIN_ARCHES:
                fins(d, u, v, t, parent, out);
                return;
            case TSINGY:
                tsingy(d, x, z, u, v, t, parent, out);
                return;
            case RING_STRUCTURE:
                rings(d, u, v, r, parent, out);
                return;
            case BASALT_COLUMNS:
                basalt(d, u, v, parent, out);
                return;
            default:
                throw new AssertionError(d.family);
        }
    }

    /** Sheer sandstone pillars on a lowered floor, a few joined by natural stone bridges. */
    private void pillars(Decision d, double u, double v, double t, double parent, Shape out) {
        double floor = parent - d.depth * (1 - Rules.smooth((t - .7) / .3));
        out.set(floor);
        double sp = d.spacing;
        int gu = (int) Math.floor(u / sp), gv = (int) Math.floor(v / sp);
        for (int a = gu - 1; a <= gu + 1; a++)
            for (int b = gv - 1; b <= gv + 1; b++) {
                long k = point(d.id, a, b);
                double cu = (a + .15 + .7 * unit(k, 1)) * sp, cv = (b + .15 + .7 * unit(k, 2)) * sp;
                double tp = Math.hypot(cu, cv) / d.radius;
                if (unit(k, 3) >= d.density * (1 - Rules.smooth((tp - .45) / .45))) continue;
                double du = u - cu, dv = v - cv, dist = Math.sqrt(du * du + dv * dv);
                if (dist >= d.size * 1.2 + 1) continue;
                double rad = d.size * (.55 + .45 * unit(k, 4)) * lobes(k, du, dv);
                if (dist >= rad + 1) continue;
                double tall = d.height * (.5 + .5 * unit(k, 5)) * (.55 + .45 * (1 - tp));
                double top = floor + tall - 1.5 * square(dist / Math.max(1, rad));
                out.raise(floor + (top - floor) * (1 - Rules.smooth((dist - rad) / 1.2)));
            }
        if (out.height > floor) return;
        // Bridges span from a pillar to its +u neighbour, a slab over open air.
        for (int a = gu - 1; a <= gu; a++)
            for (int b = gv - 1; b <= gv + 1; b++) {
                long k = point(d.id, a, b), n = point(d.id, a + 1, b);
                if (unit(k, 6) >= d.overhang) continue;
                double au = (a + .15 + .7 * unit(k, 1)) * sp, av = (b + .15 + .7 * unit(k, 2)) * sp;
                double bu = (a + 1.15 + .7 * unit(n, 1)) * sp,
                        bv = (b + .15 + .7 * unit(n, 2)) * sp;
                double tpa = Math.hypot(au, av) / d.radius, tpb = Math.hypot(bu, bv) / d.radius;
                double density = d.density;
                if (unit(k, 3) >= density * (1 - Rules.smooth((tpa - .45) / .45))
                        || unit(n, 3) >= density * (1 - Rules.smooth((tpb - .45) / .45))) continue;
                double ha = d.height * (.5 + .5 * unit(k, 5)) * (.55 + .45 * (1 - tpa)),
                        hb = d.height * (.5 + .5 * unit(n, 5)) * (.55 + .45 * (1 - tpb));
                double su = bu - au, sv = bv - av, len2 = su * su + sv * sv;
                double q = Math.max(0, Math.min(1, ((u - au) * su + (v - av) * sv) / len2));
                double off = Math.hypot(u - au - q * su, v - av - q * sv);
                if (off > 1.5 + unit(k, 7) * 1.5) continue;
                double deck =
                        floor + Math.min(ha, hb) * (.62 + .2 * unit(k, 8)) - 2 * square(q - .5);
                out.cap(deck, floor + 1, 3 + unit(k, 9) * 2);
            }
    }

    /** A sheer-walled table mountain on a talus apron; some wall sectors overhang. */
    private void tepui(Decision d, double u, double v, double r, double parent, Shape out) {
        double theta = StrictMath.atan2(v, u), apron = d.size;
        double rim = (d.radius - apron - d.overhang) / 1.2;
        double outline =
                rim
                        * (1
                                + .09 * StrictMath.sin(3 * theta + unit(d.id, 11) * 6.283)
                                + .06 * StrictMath.sin(5 * theta + unit(d.id, 12) * 6.283)
                                + .04 * StrictMath.sin(11 * theta + unit(d.id, 13) * 6.283)
                                - .2
                                        * StrictMath.pow(
                                                Math.max(
                                                        0,
                                                        StrictMath.cos(
                                                                2 * theta
                                                                        + unit(d.id, 14) * 6.283)),
                                                14));
        double top =
                d.level
                        + d.height
                        + .15 * (parent - d.level)
                        - 3 * Math.abs(texture.sample(u / 23, v / 23));
        double foot =
                parent
                        + .28
                                * d.height
                                * StrictMath.pow(Math.max(0, 1 - (r - outline) / apron), 1.7);
        if (r < outline) {
            out.raise(Math.max(top, parent));
            out.base = parent;
            return;
        }
        double ledge = d.overhang * Math.max(0, StrictMath.sin(4 * theta + unit(d.id, 15) * 6.283));
        if (r < outline + ledge) {
            out.cap(Math.max(top, parent), foot + 1, 4);
            out.base = foot;
            return;
        }
        if (r < outline + apron) out.raise(foot);
    }

    /** Capped spires crowding an amphitheatre, tops near the rim, caps wider than their necks. */
    private void hoodoos(Decision d, double u, double v, double t, double parent, Shape out) {
        double bowl = 1 - Rules.smooth((t - .55) / .35);
        double floor = parent - d.depth * bowl;
        out.set(floor);
        double sp = d.spacing;
        int gu = (int) Math.floor(u / sp), gv = (int) Math.floor(v / sp);
        for (int a = gu - 1; a <= gu + 1; a++)
            for (int b = gv - 1; b <= gv + 1; b++) {
                long k = point(d.id, a, b);
                double cu = (a + .15 + .7 * unit(k, 1)) * sp, cv = (b + .15 + .7 * unit(k, 2)) * sp;
                double tp = Math.hypot(cu, cv) / d.radius;
                double bowlAt = 1 - Rules.smooth((tp - .55) / .35);
                if (unit(k, 3) >= d.density * Rules.smooth(bowlAt / .3)) continue;
                double dist = Math.hypot(u - cu, v - cv);
                double neck = d.size * (.6 + .4 * unit(k, 4)), cap = neck * 1.6 + .8;
                if (dist >= cap) continue;
                double top =
                        floor + d.depth * bowlAt * (.62 + .42 * unit(k, 5)) + 3 + 5 * unit(k, 6);
                if (dist < neck) out.raise(top);
                else out.cap(top, floor + 1, 2 + Math.floor(unit(k, 7) * 2));
            }
    }

    /** Steep rounded limestone towers on a flattened karst plain. */
    private void towers(Decision d, double u, double v, double t, double parent, Shape out) {
        double env = 1 - Rules.smooth((t - .75) / .25);
        // The plain eases toward the anchor's level, never carving a hillside into a scarp.
        double floor = parent + Math.max(-8, Math.min(8, (d.level - parent) * .5)) * env;
        out.set(floor);
        double sp = d.spacing;
        int gu = (int) Math.floor(u / sp), gv = (int) Math.floor(v / sp);
        for (int a = gu - 1; a <= gu + 1; a++)
            for (int b = gv - 1; b <= gv + 1; b++) {
                long k = point(d.id, a, b);
                double cu = (a + .15 + .7 * unit(k, 1)) * sp, cv = (b + .15 + .7 * unit(k, 2)) * sp;
                double tp = Math.hypot(cu, cv) / d.radius;
                if (unit(k, 3) >= d.density * (1 - Rules.smooth((tp - .6) / .35))) continue;
                double du = u - cu, dv = v - cv;
                if (du * du + dv * dv >= square(d.size * 1.2)) continue;
                double rad = d.size * (.6 + .4 * unit(k, 4)) * lobes(k, du, dv);
                double q = Math.sqrt(du * du + dv * dv) / rad;
                if (q >= 1) continue;
                double tall = d.height * (.55 + .45 * unit(k, 5)),
                        top = floor + tall * Math.sqrt(1 - q * q * q * q);
                if (top <= out.height) continue;
                out.raise(top);
                // Only the rounded crown keeps soil; the steep flanks are bare limestone.
                out.bare = q > .6;
            }
    }

    /** A giant sinkhole: a sheer shaft to a forest floor, widening under its rim. */
    private void sinkhole(Decision d, double u, double v, double r, double parent, Shape out) {
        double theta = StrictMath.atan2(v, u);
        double shaft =
                d.size
                        * (1
                                + .08 * StrictMath.sin(3 * theta + unit(d.id, 11) * 6.283)
                                + .05 * StrictMath.sin(7 * theta + unit(d.id, 12) * 6.283));
        double floor =
                Math.max(
                        floorLimit,
                        Math.min(parent, d.level) - d.height + 3 * texture.sample(u / 11, v / 11));
        double sag = parent - 4 * (1 - Rules.smooth((r - shaft) / 40));
        out.base = floor;
        if (r < shaft) {
            out.height = floor;
            return;
        }
        double under =
                d.overhang * (.45 + .55 * StrictMath.sin(3 * theta + unit(d.id, 13) * 6.283));
        out.height = sag;
        if (r < shaft + under && sag - 6 > floor + 2) {
            out.voidBottom = floor + 1;
            out.voidTop = sag - 6;
        }
    }

    /** Parallel sandstone fins, many pierced by arches. */
    private void fins(Decision d, double u, double v, double t, double parent, Shape out) {
        if (t >= 1) return;
        double sp = d.spacing;
        int k0 = (int) Math.floor(v / sp + .5);
        for (int k = k0 - 1; k <= k0 + 1; k++) {
            long f = point(d.id, 0x46494e, k);
            double base = (k + (unit(f, 1) - .5) * .4) * sp;
            double across = base / d.radius;
            if (Math.abs(across) >= .95) continue;
            double half = d.radius * .6 * Math.sqrt(1 - across * across) * (.35 + .65 * unit(f, 2));
            double shift = (unit(f, 3) - .5) * .5 * d.radius;
            double along = u - shift;
            if (Math.abs(along) >= half) continue;
            // Each fin meanders and leans on its own, so the set reads as eroded joints, not rails.
            double center =
                    base
                            + sp
                                    * .45
                                    * StrictMath.sin(
                                            along / (30 + 40 * unit(f, 10)) + unit(f, 11) * 6.283)
                            + along * (unit(f, 12) - .5) * .25;
            double w =
                    d.size
                            * (.7 + .3 * unit(f, 4))
                            * (1 + .25 * StrictMath.sin(u / 9 + unit(f, 5) * 6.283));
            double e = Math.abs(v - center);
            if (e >= w) continue;
            double tall =
                    d.height
                            * (.55 + .45 * unit(f, 6))
                            * Rules.smooth((half - Math.abs(along)) / 14);
            double top = parent + tall * (1 - .35 * square(e / w));
            if (unit(f, 7) < d.overhang) {
                double mid = (unit(f, 8) - .5) * half,
                        span = Math.min(half * .45, 8 + 14 * unit(f, 9)),
                        q = (along - mid) / span;
                double rise = Math.min(tall - 6, tall * .7);
                if (Math.abs(q) < 1 && rise > 4) {
                    double arch = parent + 1 + rise * Math.sqrt(1 - q * q);
                    if (top > out.height) {
                        out.height = top;
                        out.voidBottom = parent + 1;
                        out.voidTop = arch;
                    }
                    continue;
                }
            }
            out.raise(top);
        }
    }

    /** A maze of razor limestone blades along cell walls, with corridors sunk between them. */
    private void tsingy(
            Decision d,
            double x,
            double z,
            double u,
            double v,
            double t,
            double parent,
            Shape out) {
        double env = 1 - Rules.smooth((t - .6) / .4);
        if (env <= 0) return;
        double sp = d.spacing, f1 = Double.MAX_VALUE, f2 = Double.MAX_VALUE;
        int gu = (int) Math.floor(u / sp), gv = (int) Math.floor(v / sp);
        for (int a = gu - 1; a <= gu + 1; a++)
            for (int b = gv - 1; b <= gv + 1; b++) {
                long k = point(d.id, a, b);
                double cu = (a + unit(k, 1)) * sp, cv = (b + unit(k, 2)) * sp;
                double dist = Math.hypot(u - cu, v - cv);
                if (dist < f1) {
                    f2 = f1;
                    f1 = dist;
                } else if (dist < f2) f2 = dist;
            }
        double blade = 1 - Rules.smooth((f2 - f1 - d.size * .5) / 1.6);
        double serration =
                unit(SeededNoise.cell(d.id, (int) Math.floor(x), (int) Math.floor(z)), 1);
        double tall = d.height * env * (.6 + .4 * Math.abs(texture.sample(u / 37, v / 37)));
        double floor = parent - d.depth * env;
        out.set(floor);
        out.raise(floor + blade * (tall - 3 * serration));
    }

    /** Concentric eroded cuesta rings around a low dome, like a desert eye. */
    private void rings(Decision d, double u, double v, double r, double parent, Shape out) {
        double theta = StrictMath.atan2(v, u);
        double warped =
                r
                        * (1
                                + .05 * StrictMath.sin(3 * theta + unit(d.id, 11) * 6.283)
                                + .03 * StrictMath.sin(7 * theta + unit(d.id, 12) * 6.283));
        int count = 3 + (int) (unit(d.id, 13) * 3);
        double raise = d.height * .35 * Math.exp(-square(warped / (d.radius * .12)));
        for (int i = 0; i < count; i++) {
            double ring = d.radius * (.2 + .72 * (i + .5) / count);
            double width = d.size * (.7 + .5 * unit(d.id, 20 + i));
            double s = (warped - ring) / width;
            // Steep inner scarp, gentle outer dip slope.
            double profile = s < 0 ? Math.exp(-square(s / .35)) : Math.exp(-square(s / 1.3));
            // Each ring is mostly whole, breached in one or two places.
            double gap =
                    Rules.smooth(
                            (StrictMath.sin(theta * (1 + i % 2) + unit(d.id, 30 + i) * 6.283) + .9)
                                    / .35);
            double tall = d.height * (.55 + .45 * unit(d.id, 40 + i)) * (1 - .35 * i / count);
            raise = Math.max(raise, tall * profile * gap);
        }
        out.raise(parent + raise * (1 - Rules.smooth((r / d.radius - .9) / .1)));
    }

    /** A butte of hexagonal basalt columns, its skirt a stair of broken columns. */
    private void basalt(Decision d, double u, double v, double parent, Shape out) {
        double a = d.spacing;
        // Nearest hexagon centre (pointy-top axial rounding) gives the column this block is in.
        double qf = (Math.sqrt(3) / 3 * u - v / 3) / a, rf = (2. / 3 * v) / a;
        double sf = -qf - rf;
        long q = Math.round(qf), rr = Math.round(rf), s = Math.round(sf);
        double dq = Math.abs(q - qf), dr = Math.abs(rr - rf), ds = Math.abs(s - sf);
        if (dq > dr && dq > ds) q = -rr - s;
        else if (dr > ds) rr = -q - s;
        double cu = a * Math.sqrt(3) * (q + rr / 2.), cv = a * 1.5 * rr;
        long k = point(d.id, (int) q, (int) rr);
        double butte = d.size, skirt = d.radius - butte, rc = Math.hypot(cu, cv);
        double profile =
                rc < butte
                        ? 1 - .04 * rc / butte
                        : .4 * StrictMath.pow(Math.max(0, 1 - (rc - butte) / skirt), 1.5);
        if (profile <= 0) return;
        double tall = d.height * profile + (unit(k, 1) - .5) * (rc < butte ? 3 : 6);
        if (rc >= butte && unit(k, 2) > .55 + .45 * (1 - (rc - butte) / skirt)) return;
        out.raise(parent + Math.max(0, Math.floor(tall)));
        out.base = parent;
    }

    /** Irregular lobes for a round form: within 0.8 to 1.2 of its nominal radius. */
    private static double lobes(long k, double du, double dv) {
        double theta = StrictMath.atan2(dv, du);
        return 1
                + .12 * StrictMath.sin(3 * theta + unit(k, 20) * 6.283)
                + .07 * StrictMath.sin(5 * theta + unit(k, 21) * 6.283);
    }

    private static long point(long id, int a, int b) {
        return SeededNoise.cell(id, a, b);
    }

    private static double unit(long id, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(id + parameter * 0x9e3779b97f4a7c15L));
    }

    private static double lerp(double[] range, double t) {
        return range[0] + (range[1] - range[0]) * t;
    }

    private static double square(double x) {
        return x * x;
    }
}
