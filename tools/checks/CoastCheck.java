package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.rocky.RockyCoasts.Edit;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The coast plan holds (S3a). Usage: CoastCheck [seeds]. Over a 512 x 512 grid of each 8 km world
 * square, at intent: requires every land column past its band to keep its inland height exactly (no
 * proportional scaling) and the coast's height to be continuous (no jump of a block within 1/256 of
 * a block, so segment edges and band edges have no seams); reports the coast types' shares of coastal columns and of segments, band
 * widths per type, the land weight's spread, and the steepest step in shore height between
 * neighbouring blocks (cliffs are steep, so steps are reported, not required).
 *
 * <p>The coast's forms (S3b), sampled in full at every block of up to 12 stacks, 4 arches, 12 sea
 * caves and 12 height forms per world: a stack column is one solid run from its own ground, and
 * every column beside a stack's base is open, supplied sea; no arch solid floats over air, and an
 * arch has a roofed opening over the sea and feet on land and on the sea floor; a sea cave's vault
 * is one void from above the water to no higher than its roof band under the surface, opening
 * through a notch cut to its floor; a height form only lowers land and edits nothing past its
 * segment's band.
 */
public final class CoastCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        Map<String, long[]> byType = new HashMap<>();
        Map<String, Set<Long>> segments = new HashMap<>();
        long land = 0, coastal = 0, inland = 0;
        double[] steps = new double[seeds * 512 * 512];
        int stepCount = 0;
        Map<String, int[]> forms = new HashMap<>();
        for (long seed = 1; seed <= seeds; seed++) {
            GenerationMaps maps = new GenerationMaps(seed);
            forms(maps, seed, forms);
            TerrainMap.BaseSample s = new TerrainMap.BaseSample(), e = new TerrainMap.BaseSample();
            for (int i = 0; i < 512; i++)
                for (int j = 0; j < 512; j++) {
                    double x = -4096 + i * 16 + 3, z = -4096 + j * 16 + 7;
                    maps.terrain.intent(x, z, s);
                    if (s.ocean.marine) continue;
                    land++;
                    Shore.Sample sh = s.shore;
                    if (sh.landWeight >= 1) {
                        inland++;
                        require(
                                sh.height == s.baseHeight,
                                "Land past its band moved at " + x + "," + z + " seed " + seed);
                        continue;
                    }
                    coastal++;
                    String id = sh.type == null ? "none" : sh.type.id;
                    byType.computeIfAbsent(id, k -> new long[1])[0]++;
                    segments.computeIfAbsent(id, k -> new HashSet<>())
                            .add(
                                    seed * 1_000_003L
                                            + (long) Math.floor(x / 500) * 7919
                                            + (long) Math.floor(z / 500));
                    maps.terrain.intent(x + 1, z, e);
                    if (!e.ocean.marine) steps[stepCount++] = Math.abs(e.shore.height - sh.height);
                    // Continuity: a step that does not shrink with the spacing is a seam.
                    maps.terrain.intent(x + 1.0 / 256, z, e);
                    if (!e.ocean.marine)
                        require(
                                Math.abs(e.shore.height - sh.height) < 1,
                                "Coast height jumps "
                                        + (e.shore.height - sh.height)
                                        + " at "
                                        + x
                                        + ","
                                        + z
                                        + " seed "
                                        + seed
                                        + " ("
                                        + id
                                        + ")");
                }
        }
        System.out.printf(
                Locale.ROOT,
                "%d seeds: %d land columns, %.1f%% inside a coast band%n",
                seeds,
                land,
                100.0 * coastal / land);
        for (Shore.Type t : Shore.TYPES) {
            long n = byType.getOrDefault(t.id, new long[1])[0];
            System.out.printf(
                    Locale.ROOT,
                    "  %-9s %5.1f%% of band columns, in about %d segment cells, band %.0f-%.0f%n",
                    t.id,
                    100.0 * n / coastal,
                    segments.getOrDefault(t.id, new HashSet<>()).size(),
                    t.bandMin,
                    t.bandMax);
        }
        double[] st = Arrays.copyOf(steps, stepCount);
        Arrays.sort(st);
        System.out.printf(
                Locale.ROOT,
                "shore height step between neighbouring blocks in bands: p50 %.2f p90 %.2f p99 %.2f max %.1f%n",
                st[stepCount / 2],
                st[9 * stepCount / 10],
                st[99 * stepCount / 100],
                st[stepCount - 1]);
        for (Map.Entry<String, int[]> f : new java.util.TreeMap<>(forms).entrySet())
            System.out.printf(
                    Locale.ROOT,
                    "  form %-16s placed %5d, checked %3d (%d columns)%n",
                    f.getKey(),
                    f.getValue()[0],
                    f.getValue()[1],
                    f.getValue()[2]);
        System.out.println(
                "CoastCheck passed: land past its band keeps its height (" + inland + " columns)");
    }

    private static final int STACKS = 12, ARCHES = 4, CAVES = 12, HEIGHTS = 12;

    /** The form checks for one world; tallies are placed, checked and columns per form. */
    private static void forms(GenerationMaps maps, long seed, Map<String, int[]> tallies) {
        TerrainMap terrain = maps.terrain;
        int waterTop = TerrainHeightReference.DEFAULT.waterTopY;
        List<CoastForms.View> views = terrain.rocky.placed(-4096, -4096, 4096, 4096);
        Map<String, Integer> checked = new HashMap<>();
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (CoastForms.View v : views) {
            String name = v.form == CoastForms.Form.STACK_ARCH && v.arch ? "ARCH" : v.form.name();
            int[] tally = tallies.computeIfAbsent(name, k -> new int[3]);
            tally[0]++;
            int done = checked.getOrDefault(name, 0);
            int limit =
                    name.equals("ARCH")
                            ? ARCHES
                            : name.equals("STACK_ARCH")
                                    ? STACKS
                                    : name.equals("SEA_CAVE") ? CAVES : HEIGHTS;
            if (done >= limit) continue;
            int columns =
                    name.equals("ARCH")
                            ? arch(terrain, v, s, seed)
                            : name.equals("STACK_ARCH")
                                    ? stack(terrain, v, s, waterTop, seed)
                                    : name.equals("SEA_CAVE")
                                            ? cave(terrain, v, s, waterTop, seed)
                                            : band(terrain, v, s, seed);
            if (columns == 0) continue;
            checked.put(name, done + 1);
            tally[1]++;
            tally[2] += columns;
        }
    }

    private static String at(double x, double z, long seed) {
        return " at " + x + "," + z + " seed " + seed;
    }

    /** A stack: one solid run per column from its own ground; open sea all round its base. */
    private static int stack(
            TerrainMap terrain, CoastForms.View v, TerrainMap.Sample s, int waterTop, long seed) {
        double cx = v.cx, cz = v.cz;
        int r = (int) Math.ceil(v.radius) + 2;
        int x0 = (int) Math.floor(cx) - r, z0 = (int) Math.floor(cz) - r, side = 2 * r + 1;
        boolean[] body = new boolean[side * side], sea = new boolean[side * side];
        int columns = 0;
        for (int dz = 0; dz < side; dz++)
            for (int dx = 0; dx < side; dx++) {
                int x = x0 + dx, z = z0 + dz;
                terrain.sample(x, z, s);
                CoastForms.Result f = s.rocky;
                sea[dz * side + dx] = s.ocean.marine && s.waterY() == waterTop;
                if (f.id != v.id || !f.hasVolume()) continue;
                body[dz * side + dx] = true;
                columns++;
                require(
                        f.runs() == 1
                                && f.edit(0) == Edit.SOLID
                                && f.bottom(0) == (int) Math.floor(f.parentHeight) + 1,
                        "Stack column not one solid run from its ground" + at(x, z, seed));
            }
        for (int dz = 1; dz + 1 < side; dz++)
            for (int dx = 1; dx + 1 < side; dx++) {
                if (!body[dz * side + dx]) continue;
                for (int k = 0; k < 4; k++) {
                    int q =
                            (dz + (k == 2 ? 1 : k == 3 ? -1 : 0)) * side
                                    + dx
                                    + (k == 0 ? 1 : k == 1 ? -1 : 0);
                    require(
                            body[q] || sea[q],
                            "Stack base not in open sea" + at(x0 + dx, z0 + dz, seed));
                }
            }
        return columns;
    }

    /**
     * An arch: every solid run stands on its column's ground or on the run below it (a tunnel cut
     * into the ground leaves that ground between), a roofed opening over the sea, a foot on land
     * and a foot on the sea floor.
     */
    private static int arch(TerrainMap terrain, CoastForms.View v, TerrainMap.Sample s, long seed) {
        int r = (int) Math.ceil(v.radius) + 2;
        int columns = 0;
        boolean roofed = false, land = false, sea = false;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                int x = (int) Math.floor(v.cx) + dx, z = (int) Math.floor(v.cz) + dz;
                terrain.sample(x, z, s);
                CoastForms.Result f = s.rocky;
                if (f.id != v.id || !f.hasVolume()) continue;
                columns++;
                if (!s.ocean.marine) land = true;
                int ground = (int) Math.floor(f.parentHeight) + 1;
                if (s.ocean.marine && f.edit(0) == Edit.SOLID && f.bottom(0) <= ground) sea = true;
                for (int i = 0; i < f.runs(); i++)
                    require(
                            f.edit(i) != Edit.SOLID
                                    || f.bottom(i) <= ground
                                    || i > 0 && f.bottom(i) == f.top(i - 1),
                            "Arch solid floats over air" + at(x, z, seed));
                for (int i = 0; i + 1 < f.runs(); i++)
                    if (s.ocean.marine && f.edit(i) == Edit.VOID && f.edit(i + 1) == Edit.SOLID)
                        roofed = true;
            }
        if (columns > 0) {
            require(roofed, "Arch without a roofed opening" + at(v.x, v.z, seed));
            require(land, "Arch without a foot on land" + at(v.x, v.z, seed));
            require(sea, "Arch without a foot on the sea floor" + at(v.x, v.z, seed));
        }
        return columns;
    }

    /**
     * A sea cave: each vault column one void from above the water to its roof band under the
     * surface, each notch column cut to the vault's floor at the water's top, and a mouth: some
     * vault column beside lower ground (a notch, or the sea) that opens at its floor.
     */
    private static int cave(
            TerrainMap terrain, CoastForms.View v, TerrainMap.Sample s, int waterTop, long seed) {
        int r = (int) Math.ceil(v.length + v.halfWidth) + 2, side = 2 * r + 1;
        int x0 = (int) Math.floor(v.x) - r, z0 = (int) Math.floor(v.z) - r;
        int[] ground = new int[side * side], floor = new int[side * side];
        int columns = 0, vaults = 0;
        for (int dz = 0; dz < side; dz++)
            for (int dx = 0; dx < side; dx++) {
                int x = x0 + dx, z = z0 + dz, k = dz * side + dx;
                terrain.sample(x, z, s);
                ground[k] = s.blockY();
                floor[k] = Integer.MAX_VALUE;
                CoastForms.Result f = s.rocky;
                if (f.id != v.id) continue;
                if (f.hasVolume()) {
                    columns++;
                    vaults++;
                    floor[k] = f.bottom(0);
                    require(
                            f.runs() == 1
                                    && f.edit(0) == Edit.VOID
                                    && f.bottom(0) >= waterTop + 1
                                    && f.top(0) <= s.blockY() - 8 + 1,
                            "Sea cave breaks its water line or roof band" + at(x, z, seed));
                } else if (f.height < f.parentHeight) {
                    columns++;
                    require(
                            s.blockY() == waterTop,
                            "Sea cave notch not at the vault's floor" + at(x, z, seed));
                }
            }
        boolean mouth = false;
        for (int dz = 1; dz + 1 < side && !mouth; dz++)
            for (int dx = 1; dx + 1 < side && !mouth; dx++) {
                int k = dz * side + dx;
                if (floor[k] == Integer.MAX_VALUE) continue;
                for (int q : new int[] {k - 1, k + 1, k - side, k + side})
                    if (ground[q] < floor[k]) mouth = true;
            }
        require(vaults == 0 || mouth, "Sea cave without a mouth" + at(v.x, v.z, seed));
        return columns;
    }

    /** A height form: lowers land only, and edits nothing past its segment's band. */
    private static int band(TerrainMap terrain, CoastForms.View v, TerrainMap.Sample s, long seed) {
        int r = (int) Math.ceil(v.reach), columns = 0;
        Shore.Nearest owner = new Shore.Nearest();
        for (int dz = -r; dz <= r; dz += 8)
            for (int dx = -r; dx <= r; dx += 8) {
                int x = (int) Math.floor(v.x) + dx, z = (int) Math.floor(v.z) + dz;
                terrain.sample(x, z, s);
                CoastForms.Result f = s.rocky;
                if (f.id != v.id || f.weight <= 0) continue;
                columns++;
                require(f.height <= f.parentHeight, "Height form raised land" + at(x, z, seed));
                terrain.shores.nearest(x, z, owner);
                double band = Math.max(owner.a.width, owner.b.width);
                require(
                        s.ocean.distance <= band + 1,
                        "Height form past its band ("
                                + s.ocean.distance
                                + " > "
                                + band
                                + ")"
                                + at(x, z, seed));
            }
        return columns;
    }
}
