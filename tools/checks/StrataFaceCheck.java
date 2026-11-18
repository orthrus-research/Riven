package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.AssembledColumn;
import cc.orthrus.riven.worldgen.engine.Assembler;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * What rock faces show. Usage: StrataFaceCheck SEED LAYOUT SIDE STRIDE [OUT_DIR].
 *
 * <p>Over a SIDE-block square centred on the origin, every STRIDE blocks, assembles the column and
 * reads its four neighbours' ground. A column's face is the blocks above its lowest neighbour's
 * ground, up to its own. Reports the face height distribution; for faces whose rock has a climate
 * palette (arid sedimentary units), the share of face blocks that show a bed colour by depth below
 * the column's top (band continuity); and the thickness of complete same-block runs seen on faces
 * (the beds as they read). With OUT_DIR, writes the tallest faces per class (arid, humid, mountain)
 * as CSV for tours.
 */
public final class StrataFaceCheck {
    private static final int[] HEIGHT_EDGES = {2, 4, 8, 16, 25, 49, 97};
    private static final int[] DEPTH_EDGES = {0, 24, 48, 96};
    private static final int[] RUN_EDGES = {1, 2, 3, 4, 5, 6, 9, 13, 21};

    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        double layout = Double.parseDouble(args[1]);
        int side = Integer.parseInt(args[2]), stride = Integer.parseInt(args[3]);
        Path out = args.length > 4 ? Paths.get(args[4]) : null;
        GenerationMaps maps =
                new GenerationMaps(seed, layout == 1 ? WorldScale.DEFAULT : new WorldScale(layout));
        int cells = side / stride;
        // The palettes as the definitions name them, so the check reads any tree.
        com.google.gson.JsonObject tint =
                new com.google.gson.Gson()
                        .fromJson(
                                new java.io.InputStreamReader(
                                        GeologyMap.class.getResourceAsStream("/riven-geology.json"),
                                        java.nio.charset.StandardCharsets.UTF_8),
                                com.google.gson.JsonObject.class)
                        .getAsJsonObject("tint");
        java.util.Map<String, java.util.Set<String>> palettes = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e :
                tint.getAsJsonObject("regions").entrySet())
            palettes.put(
                    e.getKey(),
                    tint.getAsJsonObject("palettes")
                            .getAsJsonObject(e.getValue().getAsString())
                            .keySet());
        // Rock face blocks of palette units: bed colour, stained or weathered, fresh.
        AtomicLongArray states = new AtomicLongArray(3);
        // Face heights (all land, arid), eligible and coloured face blocks by depth, run lengths.
        AtomicLongArray heights = new AtomicLongArray(2 * (HEIGHT_EDGES.length + 1));
        AtomicLongArray eligible = new AtomicLongArray(DEPTH_EDGES.length),
                coloured = new AtomicLongArray(DEPTH_EDGES.length),
                tallEligible = new AtomicLongArray(DEPTH_EDGES.length),
                tallColoured = new AtomicLongArray(DEPTH_EDGES.length);
        AtomicLongArray runs = new AtomicLongArray(RUN_EDGES.length + 1);
        AtomicLongArray land = new AtomicLongArray(4);
        List<List<long[]>> tallest = new ArrayList<>();
        for (int i = 0; i < 3; i++) tallest.add(new ArrayList<>());
        AtomicInteger next = new AtomicInteger();
        Thread[] threads = new Thread[Math.max(1, Runtime.getRuntime().availableProcessors() - 2)];
        for (int t = 0; t < threads.length; t++) {
            threads[t] =
                    new Thread(
                            () -> {
                                Assembler<TerrainMap.Sample> crust = maps.columns.assembler();
                                AssembledColumn c = new AssembledColumn();
                                TerrainMap.Sample s = new TerrainMap.Sample(),
                                        n = new TerrainMap.Sample();
                                for (int row; (row = next.getAndIncrement()) < cells; ) {
                                    int z = -side / 2 + row * stride;
                                    for (int col = 0; col < cells; col++) {
                                        int x = -side / 2 + col * stride;
                                        maps.terrain.sample(x, z, s);
                                        if (s.ocean.marine) continue;
                                        java.util.Set<String> palette =
                                                s.climate.region == null
                                                        ? null
                                                        : palettes.get(s.climate.region.name());
                                        boolean arid = palette != null;
                                        land.incrementAndGet(0);
                                        if (arid) land.incrementAndGet(1);
                                        int top = s.blockY(), low = top;
                                        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                                        for (int[] o : d) {
                                            maps.terrain.sample(x + o[0], z + o[1], n);
                                            low = Math.min(low, n.blockY());
                                        }
                                        int face = top - low;
                                        if (face < 2) continue;
                                        int b = bucket(HEIGHT_EDGES, face);
                                        heights.incrementAndGet(b);
                                        if (arid)
                                            heights.incrementAndGet(HEIGHT_EDGES.length + 1 + b);
                                        int kind =
                                                arid
                                                        ? 0
                                                        : s.climate.region != null && top > 200
                                                                ? 2
                                                                : 1;
                                        synchronized (tallest.get(kind)) {
                                            tallest.get(kind).add(new long[] {face, x, z, top});
                                            if (tallest.get(kind).size() > 400)
                                                trim(tallest.get(kind));
                                        }
                                        if (!arid) continue;
                                        c = crust.assemble(s, c);
                                        TerrainBlock previous = null;
                                        int run = 0;
                                        boolean open = false;
                                        for (int y = top; y > low; y--) {
                                            TerrainBlock at = c.at(y);
                                            boolean can =
                                                    at.kind.equals("rock")
                                                            && palette.contains(
                                                                    s.geology.unit(y).name);
                                            int depth = bucket(DEPTH_EDGES, top - y) - 1;
                                            // Bed colours: terracotta (shared before v149) or a
                                            // unit's _bed_ entry; stains and rinds: _stained or
                                            // _weathered.
                                            boolean bed =
                                                    at.tags.contains("terracotta")
                                                            || at.id.contains("_bed_");
                                            boolean weathered =
                                                    at.id.endsWith("_stained")
                                                            || at.id.endsWith("_weathered");
                                            boolean shows = can && (bed || weathered);
                                            if (can)
                                                states.incrementAndGet(bed ? 0 : weathered ? 1 : 2);
                                            if (can) {
                                                eligible.incrementAndGet(depth);
                                                if (shows) coloured.incrementAndGet(depth);
                                                if (face > 24) {
                                                    tallEligible.incrementAndGet(depth);
                                                    if (shows) tallColoured.incrementAndGet(depth);
                                                }
                                            }
                                            // Complete runs of one rock block inside the face.
                                            boolean rock = at.kind.equals("rock");
                                            if (rock && at == previous) run++;
                                            else {
                                                if (open && previous != null && rock)
                                                    runs.incrementAndGet(
                                                            bucket(RUN_EDGES, run) - 1 + 1);
                                                open = previous != null && rock;
                                                run = 1;
                                            }
                                            if (!rock) open = false;
                                            previous = rock ? at : null;
                                        }
                                    }
                                }
                            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        System.out.printf(
                "seed %d layout %s side %d stride %d: land %d, arid %d%n",
                seed, args[1], side, stride, land.get(0), land.get(1));
        long all = states.get(0) + states.get(1) + states.get(2);
        System.out.printf(
                "arid palette rock face blocks %d: bed colour %.1f%%, stained or weathered %.1f%%,"
                        + " other (fresh, or a palette entry that is the unit's own) %.1f%%%n",
                all,
                100.0 * states.get(0) / Math.max(1, all),
                100.0 * states.get(1) / Math.max(1, all),
                100.0 * states.get(2) / Math.max(1, all));
        System.out.println("face height  all  arid");
        for (int i = 1; i <= HEIGHT_EDGES.length; i++)
            System.out.printf(
                    "  %s  %d  %d%n",
                    label(HEIGHT_EDGES, i),
                    heights.get(i),
                    heights.get(HEIGHT_EDGES.length + 1 + i));
        System.out.println(
                "arid palette rock face blocks, share terracotta or stained by depth below the top");
        for (int i = 0; i < DEPTH_EDGES.length; i++)
            System.out.printf(
                    "  depth %s: all faces %d blocks %.1f%%; faces over 24: %d blocks %.1f%%%n",
                    label(DEPTH_EDGES, i + 1),
                    eligible.get(i),
                    100.0 * coloured.get(i) / Math.max(1, eligible.get(i)),
                    tallEligible.get(i),
                    100.0 * tallColoured.get(i) / Math.max(1, tallEligible.get(i)));
        long total = 0;
        for (int i = 0; i < runs.length(); i++) total += runs.get(i);
        System.out.println("complete same-block runs on arid faces (bed thickness seen): " + total);
        for (int i = 1; i <= RUN_EDGES.length; i++)
            System.out.printf(
                    "  %s: %.1f%%%n",
                    label(RUN_EDGES, i), 100.0 * runs.get(i) / Math.max(1, total));
        if (out != null) {
            Files.createDirectories(out);
            String[] names = {"arid", "humid", "mountain"};
            for (int k = 0; k < 3; k++) {
                trim(tallest.get(k));
                try (PrintWriter w =
                        new PrintWriter(out.resolve("faces-" + names[k] + ".csv").toFile())) {
                    w.println("face,x,z,top");
                    for (long[] f : tallest.get(k))
                        w.printf("%d,%d,%d,%d%n", f[0], f[1], f[2], f[3]);
                }
            }
        }
    }

    private static void trim(List<long[]> list) {
        list.sort((a, b) -> Long.compare(b[0], a[0]));
        // Keep the tallest, at most one per 256-block cell.
        List<long[]> kept = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long[] f : list) {
            long cell = (Math.floorDiv(f[1], 256) << 32) ^ (Math.floorDiv(f[2], 256) & 0xffffffffL);
            if (seen.add(cell)) kept.add(f);
            if (kept.size() >= 100) break;
        }
        list.clear();
        list.addAll(kept);
    }

    /** The bucket index of v: 0 below edges[0], i for edges[i - 1] <= v < edges[i]. */
    private static int bucket(int[] edges, int v) {
        int i = 0;
        while (i < edges.length && v >= edges[i]) i++;
        return i;
    }

    private static String label(int[] edges, int i) {
        return i < edges.length ? edges[i - 1] + "-" + (edges[i] - 1) : edges[i - 1] + "+";
    }
}
