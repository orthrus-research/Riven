package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.IntStream;

/**
 * The crust's depth biomes (riven-realms.json crust) as native cubes report them. Over a grid of
 * chunk columns: every cell column's stored biome equals its centre's classification (the depth
 * biome, or the column's own biome within the surface band), the stored lowest ground equals a
 * per-block scan of all 256 columns, cave decoration and frozen pools follow each floor's and
 * ceiling's depth biome, nothing decorates lava, unstable floors are thin rock between open air
 * below and headroom above, bare of floor and ceiling blocks and stored as sampled, and a fresh
 * cache filled in reverse order stores identical cells. Prints each depth biome's share of
 * underground cells by depth below the ground, the decoration each holds and its unstable floors.
 * args: [seed] [half-width in chunks] [step in chunks]
 */
public final class DescentCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Depth buckets below the ground, in blocks (the last is open). */
    private static final int[] BUCKETS = {8, 40, 100, 200, 300, 400, 500};

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 8675309L;
        int half = args.length > 1 ? Integer.parseInt(args[1]) : 128;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        GenerationMaps maps = new GenerationMaps(seed);
        List<RealmMap.Depth> depths = RealmMap.depths();
        int kinds = depths.size();
        List<int[]> chunks = new ArrayList<>();
        for (int cz = -half; cz <= half; cz += step)
            for (int cx = -half; cx <= half; cx += step) chunks.add(new int[] {cx, cz});
        AtomicLongArray cells = new AtomicLongArray(kinds * BUCKETS.length);
        AtomicLongArray decor = new AtomicLongArray(kinds * TerrainBlock.values().length);
        // Unstable floors per depth biome, then columns, then falls: survivable, lethal, severe.
        AtomicLongArray unstable = new AtomicLongArray(kinds + 4);
        AtomicLong eligible = new AtomicLong(), cubes = new AtomicLong(), frozen = new AtomicLong();
        long started = System.nanoTime();
        IntStream.range(0, chunks.size())
                .parallel()
                .forEach(
                        i -> {
                            int[] c = chunks.get(i);
                            check(maps, h, c[0], c[1], cells, decor, unstable, frozen);
                            NativeColumns.Column column = maps.columns.column(c[0], c[1]);
                            for (int y = h.minimumY; y < column.minimumGround() + 16; y += 16) {
                                cubes.incrementAndGet();
                                if (y + 15 < column.minimumGround()) eligible.incrementAndGet();
                            }
                        });
        // The same cells from a fresh cache filled in the opposite order.
        NativeColumns reverse = new GenerationMaps(seed).columns;
        for (int i = chunks.size() - 1; i >= 0; i -= 7) {
            int cx = chunks.get(i)[0], cz = chunks.get(i)[1];
            NativeColumns.Column a = maps.columns.column(cx, cz), b = reverse.column(cx, cz);
            require(a.minimumGround() == b.minimumGround(), "Ground differs at chunk " + cx);
            for (int cell = 0; cell < 16; cell++)
                for (int y = h.minimumY; y < h.maximumYExclusive; y += 4)
                    require(
                            a.depth(cell, y + 2) == b.depth(cell, y + 2),
                            "Reverse-order cell differs at chunk " + cx + "," + cz);
        }
        report(depths, cells, decor, unstable);
        System.out.printf(
                Locale.ROOT,
                "seed %d: %d chunk columns, %d of %d crust cubes below the ground report depth"
                        + " biomes, %d frozen pools; checked in %.1f s%n",
                seed,
                chunks.size(),
                eligible.get(),
                cubes.get(),
                frozen.get(),
                (System.nanoTime() - started) / 1e9);
    }

    private static void check(
            GenerationMaps maps,
            TerrainHeightReference h,
            int cx,
            int cz,
            AtomicLongArray cells,
            AtomicLongArray decor,
            AtomicLongArray unstable,
            AtomicLong frozen) {
        NativeColumns.Column column = maps.columns.column(cx, cz);
        TerrainMap.Sample s = new TerrainMap.Sample();
        int band = RealmMap.surfaceBand(), lowest = Integer.MAX_VALUE;
        for (int index = 0; index < 256; index++) {
            int lx = index & 15, lz = index >> 4, x = cx * 16 + lx, z = cz * 16 + lz;
            maps.terrain.sample(x, z, s);
            maps.terrain.caves.carve(x, z, s);
            boolean bare = ColumnBlocks.bareStone(s, maps.surfaces);
            // The ground, scanned block by block from the top.
            int ground = h.maximumYExclusive - 1;
            while (ground > h.minimumY) {
                TerrainBlock b = ColumnBlocks.at(ground, s, bare, maps.surfaces, h);
                if (b != TerrainBlock.AIR && b != TerrainBlock.WATER && b != TerrainBlock.LAVA)
                    break;
                ground--;
            }
            lowest = Math.min(lowest, ground);
            if ((lx & 3) == 2 && (lz & 3) == 2) {
                int cell = (lz >> 2) * 4 + (lx >> 2);
                for (int y = h.minimumY; y < h.maximumYExclusive; y += 4) {
                    int centre = y + 2;
                    int expected =
                            centre < ground && ground - centre >= band
                                    ? RealmMap.depth(centre, s).index + 1
                                    : 0;
                    require(
                            column.depth(cell, centre) == expected,
                            "Stored cell differs at " + x + "," + centre + "," + z);
                    if (expected > 0) {
                        int d = ground - centre, k = 0;
                        while (k + 1 < BUCKETS.length && d >= BUCKETS[k + 1]) k++;
                        cells.incrementAndGet((expected - 1) * BUCKETS.length + k);
                    }
                }
            }
            decoration(s, x, z, decor, frozen);
            unstable(s, x, z, column, index, unstable);
            unstable.incrementAndGet(unstable.length() - 4);
        }
        require(
                column.minimumGround() == lowest,
                "Lowest ground differs at chunk " + cx + "," + cz);
    }

    /**
     * A column's unstable floors: rock at most the most thickness between open air below for the
     * least drop and open air above for the headroom, holding no decoration (so its host rock
     * shows), and stored in the native column exactly where sampled.
     */
    private static void unstable(
            TerrainMap.Sample s,
            int x,
            int z,
            NativeColumns.Column column,
            int index,
            AtomicLongArray counts) {
        CaveMap.Voids v = s.caves;
        int[] shape = CaveDecor.unstableShape();
        for (int i = 0; i < v.unstables(); i++) {
            int lo = v.unstableLo(i), hi = v.unstableHi(i);
            String at = " at " + x + "," + lo + ".." + hi + "," + z;
            require(hi - lo <= shape[0], "Unstable floor too thick" + at);
            for (int y = lo - shape[1]; y < lo; y++)
                require(
                        v.voids(y) && v.fill(y, s) == TerrainBlock.AIR,
                        "Unstable floor not over air" + at);
            for (int y = hi; y < hi + shape[2]; y++)
                require(
                        v.voids(y) && v.fill(y, s) == TerrainBlock.AIR,
                        "Unstable floor without headroom" + at);
            for (int y = lo; y < hi; y++) {
                require(!v.voids(y), "Unstable floor in a void" + at);
                require(v.decor(y) == null, v.decor(y) + " in an unstable floor" + at);
            }
            for (int y = lo - 1; y <= hi; y++)
                require(
                        column.unstable(index, y) == v.unstable(y),
                        "Stored unstable floor differs" + at);
            counts.incrementAndGet(RealmMap.cell(hi, s).index);
            // The fall from the floor's top through the open air below; water breaks it.
            int air = 0;
            while (v.voids(lo - 1 - air) && v.fill(lo - 1 - air, s) == TerrainBlock.AIR) air++;
            boolean water = v.voids(lo - 1 - air) && v.fill(lo - 1 - air, s) == TerrainBlock.WATER;
            int fall = air + hi - lo, falls = counts.length() - 3;
            counts.incrementAndGet(
                    water || fall < shape[3] ? falls : fall < shape[4] ? falls + 1 : falls + 2);
        }
    }

    /** Whether a column lies within reach of a fault for fault dripstone. */
    private static boolean faulted(TerrainMap.Sample s) {
        return s.geology.faultThrow != 0 && s.geology.faultDistance < CaveDecor.faultReach();
    }

    /** The formation material a pointed block belongs to. */
    private static RealmMap.Formation formation(TerrainBlock b) {
        return b == TerrainBlock.ICE_UP || b == TerrainBlock.ICE_DOWN
                ? RealmMap.Formation.ICE
                : b == TerrainBlock.SALT_UP || b == TerrainBlock.SALT_DOWN
                        ? RealmMap.Formation.SALT
                        : RealmMap.Formation.BASALT;
    }

    /** Each decoration run against the depth biome of the floor or ceiling it hangs from. */
    private static void decoration(
            TerrainMap.Sample s, int x, int z, AtomicLongArray decor, AtomicLong frozen) {
        CaveMap.Voids v = s.caves;
        GeologyMap.Unit magma = GeologyMap.named("magma");
        int n = TerrainBlock.values().length;
        for (int i = 0; i < v.decorations; i++) {
            TerrainBlock b = v.decorBlock[i];
            int lo = v.decorLo[i], hi = v.decorHi[i];
            String at = " at " + x + "," + lo + "," + z;
            // Decoration follows the biome reported where the cave space lies: a run on a floor
            // starts at the void's lowest block, one under a ceiling ends at its highest, and a
            // floor block lies just under the void's lowest block.
            RealmMap.Depth floor = RealmMap.cell(lo, s), under = RealmMap.cell(hi, s);
            RealmMap.Depth ceiling = RealmMap.cell(hi - 1L, s);
            switch (b) {
                case TOADSTOOL:
                case PORTOBELLO:
                case BROWN_MUSHROOM:
                case RED_MUSHROOM:
                case MINERS_DELIGHT:
                case GLOWSHROOM:
                case ALGAE:
                    require(floor.life, b + " in lifeless " + floor.biome + at);
                    decor.incrementAndGet(floor.index * n + b.ordinal());
                    break;
                case DRIPSTONE_UP:
                    require(
                            floor.dripstone || (floor.faultDripstone && faulted(s)),
                            b + " in " + floor.biome + at);
                    decor.incrementAndGet(floor.index * n + b.ordinal());
                    break;
                case DRIPSTONE_DOWN:
                    require(
                            ceiling.dripstone || (ceiling.faultDripstone && faulted(s)),
                            b + " in " + ceiling.biome + at);
                    decor.incrementAndGet(ceiling.index * n + b.ordinal());
                    break;
                case ICE_UP:
                case SALT_UP:
                case BASALT_UP:
                    require(
                            floor.formation == formation(b)
                                    || (b == TerrainBlock.BASALT_UP
                                            && s.geology.unit(lo - 1).name.equals("basalt")),
                            b + " in " + floor.biome + at);
                    decor.incrementAndGet(floor.index * n + b.ordinal());
                    break;
                case ICE_DOWN:
                case SALT_DOWN:
                case BASALT_DOWN:
                    require(
                            ceiling.formation == formation(b)
                                    || (b == TerrainBlock.BASALT_DOWN
                                            && s.geology.unit(hi).name.equals("basalt")),
                            b + " in " + ceiling.biome + at);
                    decor.incrementAndGet(ceiling.index * n + b.ordinal());
                    break;
                case ROOT:
                    require(
                            ceiling.ceiling == RealmMap.Ceiling.ROOTS,
                            "Roots in " + ceiling.biome + at);
                    decor.incrementAndGet(ceiling.index * n + b.ordinal());
                    break;
                case COBWEB:
                    require(
                            ceiling.ceiling == RealmMap.Ceiling.COBWEBS,
                            "Cobweb in " + ceiling.biome + at);
                    decor.incrementAndGet(ceiling.index * n + b.ordinal());
                    break;
                case MAGMA:
                case ASH:
                case PACKED_ICE:
                case SAND:
                case MUD:
                case WHITE_SAND:
                    // A floor block replaces the rock under a void (the run ends at the void).
                    require(s.geology.unit(lo) != magma, b + " on lava" + at);
                    RealmMap.Floor kind = under.floor;
                    require(
                            b == TerrainBlock.MUD
                                    ? under.mud
                                    : (b == TerrainBlock.WHITE_SAND
                                                    && (s.geology.unit(lo).name.equals("evaporite")
                                                            || kind == RealmMap.Floor.SALT))
                                            || (b == TerrainBlock.MAGMA
                                                    && kind == RealmMap.Floor.MAGMA)
                                            || (b == TerrainBlock.ASH && kind == RealmMap.Floor.ASH)
                                            || (b == TerrainBlock.PACKED_ICE
                                                    && kind == RealmMap.Floor.ICE)
                                            || (b == TerrainBlock.SAND
                                                    && kind == RealmMap.Floor.SAND),
                            b + " floor in " + under.biome + at);
                    decor.incrementAndGet(under.index * n + b.ordinal());
                    break;
                default:
                    break;
            }
        }
        // Flooded voids are ice exactly where their floor lies in a frozen depth biome.
        for (int i = 0; i < v.count; i++) {
            TerrainBlock fill = v.fill(v.lo[i], s);
            if (fill != TerrainBlock.WATER && fill != TerrainBlock.ICE) continue;
            boolean cold = RealmMap.cell(v.lo[i], s).frozen;
            require((fill == TerrainBlock.ICE) == cold, "Pool freezing differs at " + x + "," + z);
            if (cold) frozen.incrementAndGet();
        }
    }

    private static void report(
            List<RealmMap.Depth> depths,
            AtomicLongArray cells,
            AtomicLongArray decor,
            AtomicLongArray unstable) {
        long total = 0;
        long[] byBucket = new long[BUCKETS.length];
        for (int d = 0; d < depths.size(); d++)
            for (int k = 0; k < BUCKETS.length; k++) {
                total += cells.get(d * BUCKETS.length + k);
                byBucket[k] += cells.get(d * BUCKETS.length + k);
            }
        StringBuilder head =
                new StringBuilder(String.format(Locale.ROOT, "%-18s %6s", "depth biome", "all"));
        for (int k = 0; k < BUCKETS.length; k++)
            head.append(
                    String.format(
                            Locale.ROOT,
                            " %8s",
                            BUCKETS[k]
                                    + (k + 1 < BUCKETS.length ? "-" + (BUCKETS[k + 1] - 1) : "+")));
        System.out.println("Share of underground cells (column %), by blocks below the ground");
        System.out.println(head);
        for (RealmMap.Depth depth : depths) {
            long all = 0;
            for (int k = 0; k < BUCKETS.length; k++)
                all += cells.get(depth.index * BUCKETS.length + k);
            StringBuilder row =
                    new StringBuilder(
                            String.format(
                                    Locale.ROOT,
                                    "%-18s %5.1f%%",
                                    depth.biome,
                                    100.0 * all / total));
            for (int k = 0; k < BUCKETS.length; k++)
                row.append(
                        String.format(
                                Locale.ROOT,
                                " %7.1f%%",
                                byBucket[k] == 0
                                        ? 0
                                        : 100.0
                                                * cells.get(depth.index * BUCKETS.length + k)
                                                / byBucket[k]));
            // Climate- and rock-bound biomes can be absent from a window; that is coverage, not
            // error.
            System.out.println(row + (all == 0 ? "  (absent from this window)" : ""));
        }
        System.out.println("Decoration runs by depth biome");
        int n = TerrainBlock.values().length;
        for (RealmMap.Depth depth : depths) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-18s", depth.biome));
            for (TerrainBlock b : TerrainBlock.values()) {
                long count = decor.get(depth.index * n + b.ordinal());
                if (count > 0)
                    row.append(' ')
                            .append(b.name().toLowerCase(Locale.ROOT))
                            .append('=')
                            .append(count);
            }
            System.out.println(row);
        }
        int falls = unstable.length() - 3;
        long columns = unstable.get(falls - 1), floors = 0;
        StringBuilder row = new StringBuilder();
        for (RealmMap.Depth depth : depths) {
            long count = unstable.get(depth.index);
            floors += count;
            if (count > 0) row.append(' ').append(depth.biome).append('=').append(count);
        }
        System.out.printf(
                Locale.ROOT,
                "Unstable floors: %d in %d columns (%.2f%%), by depth biome:%s%n",
                floors,
                columns,
                100.0 * floors / Math.max(1, columns),
                row);
        System.out.printf(
                Locale.ROOT,
                "Unstable falls: %.1f%% survivable (under %d blocks or into water), %.1f%%"
                        + " lethal, %.1f%% severe (%d or more)%n",
                100.0 * unstable.get(falls) / Math.max(1, floors),
                CaveDecor.unstableShape()[3],
                100.0 * unstable.get(falls + 1) / Math.max(1, floors),
                100.0 * unstable.get(falls + 2) / Math.max(1, floors),
                CaveDecor.unstableShape()[4]);
    }
}
