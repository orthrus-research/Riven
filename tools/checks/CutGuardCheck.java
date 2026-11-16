package cc.orthrus.riven.worldgen;

/**
 * A surface cut plan's shore guard reads the inside of its protection disk on a lattice and its
 * rim at every column. This check builds every cut site within a window of each seed and layout
 * scale twice, once with the lattice and once reading every column, and requires the same
 * decision for each (reason, plan or none, and a plan's parent bounds and relief), reporting the time each way.
 * args: [seed:scale ...] (default 8675309:1 101:1 42:4 -991:4); window: 24 x 24 site cells.
 */
public final class CutGuardCheck {
    private static final int CELLS = 24;

    public static void main(String[] args) {
        String[] runs =
                args.length > 0 ? args : new String[] {"8675309:1", "101:1", "42:4", "-991:4"};
        long mismatches = 0, decided = 0;
        for (String run : runs) {
            long seed = Long.parseLong(run.split(":")[0]);
            double scale = Double.parseDouble(run.split(":")[1]);
            GenerationMaps lattice = new GenerationMaps(seed, new WorldScale(scale));
            GenerationMaps full = new GenerationMaps(seed, new WorldScale(scale));
            full.terrain.cuts.exhaustive(true);
            int origin = 0;
            GeneralSurfaceCuts.Decision[] a = new GeneralSurfaceCuts.Decision[CELLS * CELLS];
            long t0 = System.nanoTime();
            for (int k = 0; k < a.length; k++)
                a[k] =
                        lattice.terrain.cuts.plan(
                                origin + k % CELLS - CELLS / 2, origin + k / CELLS - CELLS / 2);
            long t1 = System.nanoTime();
            int plans = 0, same = 0;
            for (int k = 0; k < a.length; k++) {
                GeneralSurfaceCuts.Decision b =
                        full.terrain.cuts.plan(
                                origin + k % CELLS - CELLS / 2, origin + k / CELLS - CELLS / 2);
                boolean equal =
                        a[k].reason.equals(b.reason)
                                && (a[k].plan == null) == (b.plan == null)
                                // A rejection's partial facts depend on where it stopped.
                                && (a[k].plan == null
                                        || a[k].minParent == b.minParent
                                                && a[k].maxParent == b.maxParent
                                                && a[k].relief == b.relief);
                if (a[k].plan != null) plans++;
                if (equal) same++;
                else
                    System.out.printf(
                            "  differs at cell %d %d: %s vs %s%n",
                            k % CELLS - CELLS / 2, k / CELLS - CELLS / 2, a[k].reason, b.reason);
            }
            long t2 = System.nanoTime();
            System.out.printf(
                    "seed %d scale %s: %d decisions, %d plans, %d equal; lattice %.1f s, every column %.1f s%n",
                    seed, scale, a.length, plans, same, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
            mismatches += a.length - same;
            decided += a.length;
        }
        if (mismatches > 0)
            throw new AssertionError(mismatches + " of " + decided + " decisions differ");
        System.out.println("CutGuardCheck passed");
    }
}
