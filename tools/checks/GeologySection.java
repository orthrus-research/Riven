package cc.orthrus.riven.worldgen;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * Geology images from the real generator, never Minecraft.
 *
 * <pre>
 * GeologySection section SEED X1 Z1 X2 Z2 SPACING YMIN YMAX OUT.png
 *     vertical slice along a line: one column per SPACING blocks, one row per block
 * GeologySection outcrop SEED CX CZ SIZE STEP OUT.png
 *     plan map of the first rock under the soil, hillshaded, water blue
 * </pre>
 */
public final class GeologySection {
    private static final int SKY = 0xd9e6ee, WATER = 0x3f6f8c, SOIL = 0x7a5a3a;

    public static void main(String[] args) throws Exception {
        GenerationMaps maps = new GenerationMaps(Long.parseLong(args[1]));
        if (args[0].equals("section")) section(maps, args);
        else if (args[0].equals("outcrop")) outcrop(maps, args);
        else if (args[0].equals("stats")) stats(maps, args);
        else if (args[0].equals("cost")) cost(maps, Long.parseLong(args[1]));
        else if (args[0].equals("probe")) probe(maps, args);
        else if (args[0].equals("events")) events(maps);
        else if (args[0].equals("find")) find(maps);
        else if (args[0].equals("depth")) depth(maps, args);
        else if (args[0].equals("caves")) caveStats(maps);
        else if (args[0].equals("entrances")) entrances(maps);
        else if (args[0].equals("water")) waterOverAir(maps);
        else if (args[0].equals("families")) families(maps);
        else if (args[0].equals("spots")) spots(maps);
        else if (args[0].equals("ores")) ores(maps);
        else if (args[0].equals("orespots")) oreSpots(maps);
        else if (args[0].equals("orebands")) oreBands(maps);
        else if (args[0].equals("decor")) decor(maps);
        else throw new IllegalArgumentException(args[0]);
    }

    private static void section(GenerationMaps maps, String[] a) throws Exception {
        double x1 = Double.parseDouble(a[2]), z1 = Double.parseDouble(a[3]);
        double x2 = Double.parseDouble(a[4]), z2 = Double.parseDouble(a[5]);
        int spacing = Integer.parseInt(a[6]),
                y0 = Integer.parseInt(a[7]),
                y1 = Integer.parseInt(a[8]);
        int width = (int) Math.max(1, Math.hypot(x2 - x1, z2 - z1) / spacing), height = y1 - y0 + 1;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        AtomicInteger next = new AtomicInteger();
        Thread[] threads = new Thread[Math.max(1, Runtime.getRuntime().availableProcessors() / 2)];
        for (int t = 0; t < threads.length; t++) {
            threads[t] =
                    new Thread(
                            () -> {
                                TerrainMap.Sample s = new TerrainMap.Sample();
                                OreMap.Bodies ob = new OreMap.Bodies();
                                for (int i; (i = next.getAndIncrement()) < width; ) {
                                    double f = width == 1 ? 0 : i / (double) (width - 1);
                                    double x = Math.floor(x1 + (x2 - x1) * f),
                                            z = Math.floor(z1 + (z2 - z1) * f);
                                    maps.terrain.sample(x, z, s);
                                    maps.terrain.caves.carve((int) x, (int) z, s);
                                    maps.terrain.ores.bodies((int) x, (int) z, s, ob);
                                    int surface = s.blockY(), water = s.waterY();
                                    for (int y = y0; y <= y1; y++) {
                                        int rgb;
                                        if (s.caves.voids(y)) rgb = fill(s.caves.fill(y, s));
                                        else if (y > surface) rgb = y <= water ? WATER : SKY;
                                        else if (y > surface - 3 && !s.ocean.marine) rgb = SOIL;
                                        else
                                            rgb =
                                                    rock(
                                                            maps,
                                                            ob,
                                                            (int) x,
                                                            y,
                                                            (int) z,
                                                            s.geology.unit(y).color);
                                        image.setRGB(i, y1 - y, rgb);
                                    }
                                }
                            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        ImageIO.write(image, "png", new File(a[9]));
        System.out.println("section " + width + "x" + height + " -> " + a[9]);
    }

    /** GeologySection stats SEED: land uplift and base elevation percentiles over 8 km. */
    private static void stats(GenerationMaps maps, String[] a) {
        java.util.List<double[]> v = new java.util.ArrayList<>();
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (int z = -4096; z < 4096; z += 64)
            for (int x = -4096; x < 4096; x += 64) {
                maps.terrain.sample(x, z, s);
                if (!s.ocean.marine) v.add(new double[] {s.uplift, s.baseHeight, s.height});
            }
        for (int k = 0; k < 3; k++) {
            final int i = k;
            v.sort((p, q) -> Double.compare(p[i], q[i]));
            StringBuilder b = new StringBuilder(new String[] {"uplift", "base", "height"}[k] + ":");
            for (double q : new double[] {0, .05, .25, .5, .75, .95, 1})
                b.append(
                        String.format(
                                " p%.0f=%.2f", q * 100, v.get((int) (q * (v.size() - 1)))[i]));
            System.out.println(b);
        }
    }

    /** GeologySection cost SEED: chunk-column build time and the geology share of it. */
    private static void cost(GenerationMaps maps, long seed) {
        GeologyMap geology = new GeologyMap(seed, maps.plates);
        TerrainMap.Sample s = new TerrainMap.Sample();
        GeologyMap.Column column = new GeologyMap.Column();
        int[] bounds = new int[GeologyMap.MAXIMUM_BOUNDARIES];
        for (int pass = 0; pass < 2; pass++) {
            long build = 0, rock = 0;
            int chunks = 0, sink = 0;
            for (int cz = -40; cz < 40; cz += 5)
                for (int cx = -40; cx < 40; cx += 5) {
                    long t0 = System.nanoTime();
                    maps.columns.build(cx + pass * 200, cz);
                    build += System.nanoTime() - t0;
                    chunks++;
                    for (int i = 0; i < 256; i++) {
                        double x = (cx + pass * 200) * 16 + (i & 15), z = cz * 16 + (i >> 4);
                        maps.terrain.sample(x, z, s);
                        long t1 = System.nanoTime();
                        geology.column(x, z, s, column);
                        sink += column.boundaries(-512, s.blockY(), bounds, 0);
                        rock += System.nanoTime() - t1;
                    }
                }
            System.out.printf(
                    "pass %d: %d chunk columns, build %.2f ms each, geology %.2f ms each (%.0f%%) [%d]%n",
                    pass,
                    chunks,
                    build / 1e6 / chunks,
                    rock / 1e6 / chunks,
                    100.0 * rock / build,
                    sink);
        }
    }

    /** GeologySection probe SEED X Z [X Z ...]: climate, cover and the rock at the surface. */
    private static void probe(GenerationMaps maps, String[] a) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (int i = 2; i + 1 < a.length; i += 2) {
            double x = Double.parseDouble(a[i]), z = Double.parseDouble(a[i + 1]);
            maps.terrain.sample(x, z, s);
            int y = s.blockY();
            boolean bare = ColumnBlocks.bareStone(s, maps.surfaces);
            StringBuilder b = new StringBuilder();
            for (int d = 0; d < 8; d++)
                b.append(' ')
                        .append(
                                ColumnBlocks.at(
                                        y - d,
                                        s,
                                        bare,
                                        maps.surfaces,
                                        TerrainHeightReference.DEFAULT));
            System.out.printf(
                    "%.0f,%.0f surface=%d region=%s reason=%s bare=%b unit=%s blocks:%s%n",
                    x,
                    z,
                    y,
                    s.climate.region,
                    maps.surfaces.reason(s),
                    bare,
                    s.geology.unit(y).name,
                    b);
        }
    }

    /** GeologySection events SEED: how often each event reaches land columns over 8 km. */
    private static void events(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        int chamber = 0,
                feeder = 0,
                kimberlite = 0,
                kimberliteAtSurface = 0,
                basaltAtSurface = 0,
                rift = 0;
        int land = 0,
                pluton = 0,
                plutonAtSurface = 0,
                dike = 0,
                thrust = 0,
                thrustAtSurface = 0,
                nearFault = 0,
                upperAtSurface = 0,
                basementAtSurface = 0;
        for (int z = -4096; z < 4096; z += 64)
            for (int x = -4096; x < 4096; x += 32) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                land++;
                GeologyMap.Column c = s.geology;
                int y = s.blockY() - 3;
                if (c.pluton) pluton++;
                if (c.pluton && y <= c.plutonRoof) plutonAtSurface++;
                if (c.dike) dike++;
                if (c.thrustY < Double.POSITIVE_INFINITY) thrust++;
                if (y > c.thrustY && y <= c.unconformity) thrustAtSurface++;
                if (c.faultDistance < 6) nearFault++;
                if (c.chamberTop > Double.NEGATIVE_INFINITY) chamber++;
                if (c.feeder) feeder++;
                if (c.kimberliteY < Double.POSITIVE_INFINITY) kimberlite++;
                if (y >= c.kimberliteY) kimberliteAtSurface++;
                if (c.unit(y).name.equals("basalt")) basaltAtSurface++;
                if (c.rift > 0.5) rift++;
                if (y > c.unconformity) upperAtSurface++;
                String unit = c.unit(y).name;
                if (unit.equals("gneiss") || unit.equals("schist")) basementAtSurface++;
            }
        System.out.printf(
                "land %d: pluton %.1f%% (exposed %.1f%%), dike %.2f%%, thrust sheet %.1f%% (at surface %.1f%%), within 6 of a fault %.1f%%, upper sequence at surface %.1f%%, basement at surface %.1f%%%n",
                land,
                100.0 * pluton / land,
                100.0 * plutonAtSurface / land,
                100.0 * dike / land,
                100.0 * thrust / land,
                100.0 * thrustAtSurface / land,
                100.0 * nearFault / land,
                100.0 * upperAtSurface / land,
                100.0 * basementAtSurface / land);
        System.out.printf(
                "  magma chamber under %.2f%%, feeder pipe %.3f%%, kimberlite %.3f%% (at surface %.3f%%), basalt at surface %.2f%%, rift over 0.5 %.1f%%%n",
                100.0 * chamber / land,
                100.0 * feeder / land,
                100.0 * kimberlite / land,
                100.0 * kimberliteAtSurface / land,
                100.0 * basaltAtSurface / land,
                100.0 * rift / land);
    }

    /** GeologySection find SEED: a few land columns over each magma chamber and kimberlite pipe. */
    private static void find(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        int chambers = 0, pipes = 0;
        for (int z = -4096; z < 4096 && (chambers < 3 || pipes < 3); z += 24)
            for (int x = -4096; x < 4096; x += 24) {
                maps.terrain.sample(x, z, s);
                GeologyMap.Column c = s.geology;
                if (chambers < 3 && c.chamberTop > Double.NEGATIVE_INFINITY && c.feeder) {
                    chambers++;
                    System.out.printf(
                            "chamber %d,%d top %.0f bottom %.0f surface %d%n",
                            x, z, c.chamberTop, c.chamberBottom, s.blockY());
                }
                if (pipes < 3 && c.kimberliteY < s.blockY() - 20) {
                    pipes++;
                    System.out.printf(
                            "kimberlite %d,%d from %.0f surface %d%n",
                            x, z, c.kimberliteY, s.blockY());
                }
            }
    }

    private static int fill(TerrainBlock b) {
        return b == TerrainBlock.LAVA ? 0xe0581c : b == TerrainBlock.WATER ? 0x2d5f86 : 0x111111;
    }

    /** GeologySection depth SEED CX CZ SIZE STEP Y OUT.png: rock and caves at one level. */
    private static void depth(GenerationMaps maps, String[] a) throws Exception {
        int cx = Integer.parseInt(a[2]), cz = Integer.parseInt(a[3]);
        int size = Integer.parseInt(a[4]),
                step = Integer.parseInt(a[5]),
                level = Integer.parseInt(a[6]);
        int n = size / step;
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        AtomicInteger next = new AtomicInteger();
        Thread[] threads = new Thread[Math.max(1, Runtime.getRuntime().availableProcessors() / 2)];
        for (int i = 0; i < threads.length; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                TerrainMap.Sample s = new TerrainMap.Sample();
                                OreMap.Bodies ob = new OreMap.Bodies();
                                for (int r; (r = next.getAndIncrement()) < n; )
                                    for (int c = 0; c < n; c++) {
                                        int x = cx - size / 2 + c * step,
                                                z = cz - size / 2 + r * step;
                                        maps.terrain.sample(x, z, s);
                                        maps.terrain.caves.carve(x, z, s);
                                        maps.terrain.ores.bodies(x, z, s, ob);
                                        int rgb;
                                        if (level > s.blockY()) rgb = s.ocean.marine ? WATER : SKY;
                                        else if (s.caves.voids(level))
                                            rgb = fill(s.caves.fill(level, s));
                                        else
                                            rgb =
                                                    rock(
                                                            maps,
                                                            ob,
                                                            x,
                                                            level,
                                                            z,
                                                            s.geology.unit(level).color);
                                        image.setRGB(c, r, rgb);
                                    }
                            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        ImageIO.write(image, "png", new File(a[7]));
        System.out.println("depth " + n + "x" + n + " at Y" + level + " -> " + a[7]);
    }

    /** GeologySection caves SEED: share of rock carved by depth band, and fills. */
    private static void caveStats(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        int[] bands = {-512, -320, -150, 0, 100, 400};
        long[] rock = new long[bands.length - 1], cave = new long[bands.length - 1];
        long water = 0, lava = 0, entrances = 0, columns = 0;
        for (int z = -2048; z < 2048; z += 64)
            for (int x = -2048; x < 2048; x += 64) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                columns++;
                if (s.caves.count() > 0) entrances++;
                maps.terrain.caves.carve(x, z, s);
                for (int y = -510; y <= s.blockY(); y++) {
                    int b = 0;
                    while (y >= bands[b + 1]) b++;
                    rock[b]++;
                    if (s.caves.voids(y)) {
                        cave[b]++;
                        TerrainBlock f = s.caves.fill(y, s);
                        if (f == TerrainBlock.WATER) water++;
                        if (f == TerrainBlock.LAVA) lava++;
                    }
                }
            }
        StringBuilder out = new StringBuilder("caves by band:");
        long total = 0;
        for (int b = 0; b < rock.length; b++) {
            out.append(
                    String.format(
                            " [%d,%d) %.1f%%",
                            bands[b], bands[b + 1], 100.0 * cave[b] / Math.max(1, rock[b])));
            total += cave[b];
        }
        System.out.println(out);
        System.out.printf(
                "  of cave volume: water %.1f%%, lava %.1f%%; columns with an entrance %.2f%% (on a 64-block grid)%n",
                100.0 * water / Math.max(1, total),
                100.0 * lava / Math.max(1, total),
                100.0 * entrances / columns);
    }

    /** GeologySection entrances SEED: entrance columns near the origin, deepest first per kind. */
    private static void entrances(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        int found = 0;
        for (int r = 0; r < 3000 && found < 12; r += 8)
            for (int z = -r; z <= r && found < 12; z += 8)
                for (int x = -r; x <= r && found < 12; x += 8) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
                    maps.terrain.sample(x, z, s);
                    if (s.caves.count() == 0) continue;
                    found++;
                    System.out.printf(
                            "entrance %d,%d surface %d depth %d %s%n",
                            x,
                            z,
                            s.blockY(),
                            s.blockY() + 1 - s.caves.lo[0],
                            Math.abs(s.geology.faultThrow) >= 32 ? "chasm" : "sinkhole");
                }
    }

    /** GeologySection water SEED: columns with air directly below water, preview and native. */
    private static void waterOverAir(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        long previewColumns = 0, previewBad = 0, nativeColumns = 0, nativeBad = 0, voids = 0;
        for (int z = -3000; z < 3000; z += 4)
            for (int x = -3000; x < 3000; x += 4) {
                maps.terrain.sample(x, z, s);
                boolean entrance = s.caves.count() > 0;
                boolean carve = (x % 64 == 0) && (z % 64 == 0);
                if (!entrance && !carve) continue;
                for (int pass = 0; pass < 2; pass++) {
                    if (pass == 0 && !entrance) continue;
                    if (pass == 1) {
                        if (!carve) continue;
                        maps.terrain.caves.carve(x, z, s);
                    }
                    boolean bare = ColumnBlocks.bareStone(s, maps.surfaces);
                    boolean bad = false;
                    TerrainBlock below = ColumnBlocks.at(h.minimumY, s, bare, maps.surfaces, h);
                    for (int y = h.minimumY + 1; y <= s.blockY() + 1; y++) {
                        TerrainBlock here = ColumnBlocks.at(y, s, bare, maps.surfaces, h);
                        if (here == TerrainBlock.WATER && below == TerrainBlock.AIR) bad = true;
                        below = here;
                    }
                    if (pass == 0) {
                        previewColumns++;
                        if (bad) previewBad++;
                    } else {
                        nativeColumns++;
                        voids += s.caves.count();
                        if (bad) nativeBad++;
                    }
                }
            }
        System.out.printf(
                "entrance columns %d with air below water %d; carved columns %d (voids %d) with air below water %d%n",
                previewColumns, previewBad, nativeColumns, voids, nativeBad);
    }

    /** GeologySection families SEED: cave share by realm, and each family's share of the caves. */
    private static void families(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        byte[] family = new byte[h.maximumYExclusive - h.minimumY];
        String[] realms = {"skin", "layered", "basement", "root"};
        long[] rock = new long[4], cave = new long[4];
        long[] byFamily = new long[CaveMap.FAMILIES.length];
        java.util.Set<String> basement =
                new java.util.HashSet<>(
                        java.util.Arrays.asList("gneiss", "schist", "granite", "dolerite"));
        for (int z = -2048; z < 2048; z += 64)
            for (int x = -2048; x < 2048; x += 64) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                java.util.Arrays.fill(family, (byte) 0);
                maps.terrain.caves.carve(x, z, s, family);
                for (int y = h.minimumY + 2; y <= s.blockY() - 8; y++) {
                    int realm =
                            y <= s.geology.deepY
                                    ? 3
                                    : s.blockY() - y < 40
                                            ? 0
                                            : basement.contains(s.geology.unit(y).name) ? 2 : 1;
                    rock[realm]++;
                    int f = family[y - h.minimumY];
                    if (f != 0) {
                        cave[realm]++;
                        byFamily[f]++;
                    }
                }
            }
        StringBuilder out = new StringBuilder("cave share by realm:");
        long total = 0;
        for (int r = 0; r < 4; r++) {
            out.append(
                    String.format(" %s %.1f%%", realms[r], 100.0 * cave[r] / Math.max(1, rock[r])));
            total += cave[r];
        }
        System.out.println(out);
        StringBuilder fam = new StringBuilder("families:");
        for (int f = 1; f < byFamily.length; f++)
            fam.append(
                    String.format(
                            " %s %.1f%%",
                            CaveMap.FAMILIES[f], 100.0 * byFamily[f] / Math.max(1, total)));
        System.out.println(fam);
    }

    /** Rock colour, or a bright colour per mineral where the block is ore. */
    private static int rock(GenerationMaps maps, OreMap.Bodies b, int x, int y, int z, int color) {
        int m = maps.terrain.ores.mineral(x, y, z, b);
        return m < 0 ? color : java.awt.Color.HSBtoRGB((m * 0.618034f) % 1f, 0.95f, 1f);
    }

    /** GeologySection ores SEED: ore share of rock by realm, ore per km3, and each deposit's share. */
    private static void ores(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        OreMap.Bodies b = new OreMap.Bodies();
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        String[] realms = {"skin", "layered", "basement", "root"};
        long[] rock = new long[4],
                ore = new long[4],
                bodiesRock = new long[4],
                byDeposit = new long[OreMap.deposits()];
        long[] byMineral = new long[OreMap.MINERALS.length];
        long columns = 0, bodies = 0, maxBodies = 0;
        java.util.Set<String> basement =
                new java.util.HashSet<>(
                        java.util.Arrays.asList("gneiss", "schist", "granite", "dolerite"));
        long started = System.nanoTime();
        for (int z = -2048; z < 2048; z += 32)
            for (int x = -2048; x < 2048; x += 32) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                columns++;
                maps.terrain.caves.carve(x, z, s);
                maps.terrain.ores.bodies(x, z, s, b);
                bodies += b.count();
                maxBodies = Math.max(maxBodies, b.count());
                for (int y = h.minimumY + 1; y <= s.blockY(); y++) {
                    if (s.caves.voids(y) || s.geology.unit(y).name.equals("magma")) continue;
                    int realm =
                            y <= s.geology.deepY
                                    ? 3
                                    : s.blockY() - y < 40
                                            ? 0
                                            : basement.contains(s.geology.unit(y).name) ? 2 : 1;
                    rock[realm]++;
                    int m = maps.terrain.ores.mineral(x, y, z, b);
                    if (m < 0) continue;
                    // Seams and rock bodies are Riven blocks, counted apart from ore.
                    if (OreMap.BLOCK[m] != null) {
                        bodiesRock[realm]++;
                        byMineral[m]++;
                        continue;
                    }
                    ore[realm]++;
                    byMineral[m]++;
                    for (int i = 0; i < b.count(); i++)
                        if (y >= b.lo(i) - 6 && y < b.hi(i) + 6) {
                            byDeposit[b.deposit(i)]++;
                            break;
                        }
                }
            }
        StringBuilder out = new StringBuilder("ore share of rock by realm:");
        long total = 0, rocks = 0;
        for (int r = 0; r < 4; r++) {
            out.append(
                    String.format(" %s %.2f%%", realms[r], 100.0 * ore[r] / Math.max(1, rock[r])));
            total += ore[r];
            rocks += rock[r];
        }
        System.out.println(out);
        System.out.printf(
                "  seams and rock bodies: skin %.2f%% layered %.2f%% basement %.2f%% root %.2f%%%n",
                100.0 * bodiesRock[0] / Math.max(1, rock[0]),
                100.0 * bodiesRock[1] / Math.max(1, rock[1]),
                100.0 * bodiesRock[2] / Math.max(1, rock[2]),
                100.0 * bodiesRock[3] / Math.max(1, rock[3]));
        System.out.printf(
                "  all rock %.2f%% ore = %.1f million ore blocks per km3 of rock; bodies per column %.2f (max %d); %.2f ms per column%n",
                100.0 * total / Math.max(1, rocks),
                1e9 * total / Math.max(1, rocks) / 1e6,
                bodies / (double) columns,
                maxBodies,
                (System.nanoTime() - started) / 1e6 / columns);
        StringBuilder dep = new StringBuilder("deposits:");
        for (int d = 0; d < byDeposit.length; d++)
            dep.append(
                    String.format(
                            " %s %.1f%%;",
                            OreMap.deposit(d), 100.0 * byDeposit[d] / Math.max(1, total)));
        System.out.println(dep);
        StringBuilder min = new StringBuilder("minerals:");
        for (int m = 0; m < byMineral.length; m++)
            if (byMineral[m] > 0)
                min.append(
                        String.format(
                                " %s %.1f%%",
                                OreMap.MINERALS[m], 100.0 * byMineral[m] / Math.max(1, total)));
        System.out.println(min);
    }

    /**
     * GeologySection orebands SEED: over an 8 km world, ore share and the leading rock units and
     * deposits per 32-block band, then each deposit's share and the Y range holding 90% of its ore.
     */
    private static void oreBands(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        OreMap.Bodies b = new OreMap.Bodies();
        int lo = -512, bands = (384 - lo) / 32, nd = OreMap.deposits();
        java.util.List<String> unitNames = new java.util.ArrayList<>();
        for (GeologyMap.Unit u : GeologyMap.units()) unitNames.add(u.name);
        long[] rock = new long[bands], ore = new long[bands];
        long[][] byUnit = new long[bands][unitNames.size()], byDep = new long[bands][nd];
        long[][] depY = new long[nd][384 - lo];
        for (int z = -4096; z < 4096; z += 32)
            for (int x = -4096; x < 4096; x += 32) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                maps.terrain.caves.carve(x, z, s);
                maps.terrain.ores.bodies(x, z, s, b);
                for (int y = lo + 1; y <= s.blockY(); y++) {
                    if (s.caves.voids(y)) continue;
                    GeologyMap.Unit u = s.geology.unit(y);
                    if (u.name.equals("magma")) continue;
                    int band = (y - lo) / 32;
                    rock[band]++;
                    byUnit[band][unitNames.indexOf(u.name)]++;
                    if (maps.terrain.ores.mineral(x, y, z, b) < 0) continue;
                    ore[band]++;
                    for (int i = 0; i < b.count(); i++)
                        if (y >= b.lo(i) - 6 && y < b.hi(i) + 6) {
                            byDep[band][b.deposit(i)]++;
                            depY[b.deposit(i)][y - lo]++;
                            break;
                        }
                }
            }
        System.out.println("band        ore%   rock: leading units          ore: leading deposits");
        for (int band = bands - 1; band >= 0; band--) {
            if (rock[band] == 0) continue;
            StringBuilder units = new StringBuilder(), deps = new StringBuilder();
            for (int k : top(byUnit[band], 3))
                units.append(
                        String.format(
                                "%s %.0f%% ",
                                unitNames.get(k), 100.0 * byUnit[band][k] / rock[band]));
            for (int k : top(byDep[band], 3))
                if (byDep[band][k] > 0)
                    deps.append(
                            String.format(
                                    "%s %.0f%%, ",
                                    OreMap.deposit(k), 100.0 * byDep[band][k] / ore[band]));
            System.out.printf(
                    "[%4d,%4d) %5.2f   %-34s %s%n",
                    lo + band * 32,
                    lo + band * 32 + 32,
                    100.0 * ore[band] / rock[band],
                    units,
                    deps);
        }
        long total = 0;
        for (long o : ore) total += o;
        System.out.println("deposit                     share   90% of its ore between");
        for (int d = 0; d < nd; d++) {
            long sum = 0;
            for (long c : depY[d]) sum += c;
            if (sum == 0) {
                System.out.printf("%-26s  none in this world%n", OreMap.deposit(d));
                continue;
            }
            long acc = 0;
            int p5 = 0, p95 = 0;
            for (int y = 0; y < depY[d].length; y++) {
                long before = acc;
                acc += depY[d][y];
                if (before < sum * 0.05 && acc >= sum * 0.05) p5 = y + lo;
                if (before < sum * 0.95 && acc >= sum * 0.95) p95 = y + lo;
            }
            System.out.printf(
                    "%-26s %5.1f%%   Y%d to Y%d%n",
                    OreMap.deposit(d), 100.0 * sum / total, p5, p95);
        }
    }

    /**
     * GeologySection decor SEED: cave decoration blocks per realm over 4 km (32-block grid), and a
     * spot for each kind: the column with the longest run of it beside air.
     */
    private static void decor(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        TerrainBlock[] kinds = {
            TerrainBlock.DRIPSTONE_DOWN,
            TerrainBlock.DRIPSTONE_UP,
            TerrainBlock.DRIPSTONE_BLOCK,
            TerrainBlock.GRAVEL,
            TerrainBlock.MUD,
            TerrainBlock.WHITE_SAND,
            TerrainBlock.ASH,
            TerrainBlock.MAGMA,
            TerrainBlock.DIRT,
            TerrainBlock.TOADSTOOL,
            TerrainBlock.PORTOBELLO,
            TerrainBlock.ROOT,
            TerrainBlock.MINERS_DELIGHT,
            TerrainBlock.BROWN_MUSHROOM,
            TerrainBlock.RED_MUSHROOM,
            TerrainBlock.GLOWSHROOM,
            TerrainBlock.ALGAE,
            TerrainBlock.COBWEB
        };
        RealmMap.Crust[] realms = RealmMap.Crust.values();
        long[][] count = new long[kinds.length][realms.length];
        int[] best = new int[kinds.length];
        String[] where = new String[kinds.length];
        long columns = 0, decorated = 0;
        for (int z = -2048; z < 2048; z += 32)
            for (int x = -2048; x < 2048; x += 32) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                maps.terrain.caves.carve(x, z, s);
                columns++;
                boolean any = false;
                int[] run = new int[kinds.length];
                for (int y = h.minimumY + 1; y <= s.blockY(); y++) {
                    TerrainBlock d = s.caves.decor(y);
                    for (int k = 0; k < kinds.length; k++) {
                        if (d != kinds[k]) {
                            run[k] = 0;
                            continue;
                        }
                        any = true;
                        count[k][RealmMap.crust(y, s.geology.unit(y), s).ordinal()]++;
                        run[k]++;
                        boolean open =
                                s.caves.voids(y + 1) || s.caves.voids(y - 1) || s.caves.voids(y);
                        if (open && run[k] > best[k]) {
                            best[k] = run[k];
                            where[k] = "x " + x + " y " + y + " z " + z;
                        }
                    }
                }
                if (any) decorated++;
            }
        System.out.printf(
                "land columns %d, with decoration %.1f%%%n", columns, 100.0 * decorated / columns);
        for (int k = 0; k < kinds.length; k++) {
            StringBuilder line =
                    new StringBuilder(String.format("%-16s", kinds[k].name().toLowerCase()));
            for (RealmMap.Crust r : realms)
                line.append(String.format(" %s %d", r.name().toLowerCase(), count[k][r.ordinal()]));
            line.append("   spot: ")
                    .append(where[k] == null ? "none" : where[k] + " (run " + best[k] + ")");
            System.out.println(line);
        }
    }

    private static int[] top(long[] v, int n) {
        Integer[] idx = new Integer[v.length];
        for (int i = 0; i < v.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (p, q) -> Long.compare(v[q], v[p]));
        int[] out = new int[Math.min(n, v.length)];
        for (int i = 0; i < out.length; i++) out[i] = idx[i];
        return out;
    }

    /** GeologySection orespots SEED: per deposit, a carved air block beside ore in its body. */
    private static void oreSpots(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        OreMap.Bodies b = new OreMap.Bodies();
        int n = OreMap.deposits();
        int[] best = new int[n];
        String[] where = new String[n];
        for (int z = -1536; z < 1536; z += 8)
            for (int x = -1536; x < 1536; x += 8) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                maps.terrain.caves.carve(x, z, s);
                maps.terrain.ores.bodies(x, z, s, b);
                for (int i = 0; i < b.count(); i++) {
                    int d = b.deposit(i), ore = 0, air = Integer.MIN_VALUE;
                    for (int y = b.lo(i); y < b.hi(i); y++) {
                        if (s.caves.voids(y)) continue;
                        if (maps.terrain.ores.mineral(x, y, z, b) >= 0) ore++;
                    }
                    // A cave void just above or below the body lets a player see it.
                    for (int y = b.hi(i); y < b.hi(i) + 3; y++)
                        if (s.caves.voids(y) && s.caves.fill(y, s) == TerrainBlock.AIR) air = y;
                    for (int y = b.lo(i) - 3; y < b.lo(i); y++)
                        if (s.caves.voids(y) && s.caves.fill(y, s) == TerrainBlock.AIR) air = y;
                    if (air == Integer.MIN_VALUE || ore <= best[d]) continue;
                    best[d] = ore;
                    where[d] =
                            String.format(
                                    "x %d z %d body %d..%d air at %d",
                                    x, z, b.lo(i), b.hi(i) - 1, air);
                }
            }
        for (int d = 0; d < n; d++)
            System.out.printf(
                    "%-26s %s%n",
                    OreMap.deposit(d),
                    where[d] == null ? "none beside a cave" : where[d] + " (" + best[d] + " ore)");
    }

    /** GeologySection spots SEED: per family, the column with the tallest air run, to stand in. */
    private static void spots(GenerationMaps maps) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        byte[] family = new byte[h.maximumYExclusive - h.minimumY];
        int n = CaveMap.FAMILIES.length;
        int[] best = new int[n], bx = new int[n], by = new int[n], bz = new int[n];
        for (int z = -1536; z < 1536; z += 16)
            for (int x = -1536; x < 1536; x += 16) {
                maps.terrain.sample(x, z, s);
                if (s.ocean.marine) continue;
                java.util.Arrays.fill(family, (byte) 0);
                maps.terrain.caves.carve(x, z, s, family);
                int run = 0, runFamily = 0;
                for (int y = h.minimumY; y <= s.blockY(); y++) {
                    int f = family[y - h.minimumY];
                    boolean air =
                            f != 0 && s.caves.voids(y) && s.caves.fill(y, s) == TerrainBlock.AIR;
                    if (air && f == runFamily) run++;
                    else {
                        if (runFamily != 0 && run > best[runFamily]) {
                            best[runFamily] = run;
                            bx[runFamily] = x;
                            by[runFamily] = y - run;
                            bz[runFamily] = z;
                        }
                        run = air ? 1 : 0;
                        runFamily = air ? f : 0;
                    }
                }
            }
        for (int f = 1; f < n; f++)
            System.out.printf(
                    "%-14s %s%n",
                    CaveMap.FAMILIES[f],
                    best[f] == 0
                            ? "none"
                            : String.format(
                                    "x %d floor %d z %d, %d tall", bx[f], by[f], bz[f], best[f]));
    }

    private static void outcrop(GenerationMaps maps, String[] a) throws Exception {
        int cx = Integer.parseInt(a[2]), cz = Integer.parseInt(a[3]);
        int size = Integer.parseInt(a[4]), step = Integer.parseInt(a[5]), n = size / step;
        int[] rock = new int[n * n];
        double[] height = new double[n * n];
        boolean[] wet = new boolean[n * n];
        AtomicInteger next = new AtomicInteger();
        Thread[] threads = new Thread[Math.max(1, Runtime.getRuntime().availableProcessors() / 2)];
        for (int t = 0; t < threads.length; t++) {
            threads[t] =
                    new Thread(
                            () -> {
                                TerrainMap.Sample s = new TerrainMap.Sample();
                                OreMap.Bodies ob = new OreMap.Bodies();
                                for (int r; (r = next.getAndIncrement()) < n; )
                                    for (int c = 0; c < n; c++) {
                                        double x = cx - size / 2 + c * step,
                                                z = cz - size / 2 + r * step;
                                        maps.terrain.sample(x, z, s);
                                        int i = r * n + c, surface = s.blockY();
                                        height[i] = s.height;
                                        wet[i] = s.waterY() > surface;
                                        rock[i] = s.geology.unit(surface - 3).color;
                                    }
                            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++) {
                int i = r * n + c;
                if (wet[i]) {
                    image.setRGB(c, r, WATER);
                    continue;
                }
                double
                        dx =
                                (height[r * n + Math.min(n - 1, c + 1)]
                                        - height[r * n + Math.max(0, c - 1)]),
                        dz =
                                (height[Math.min(n - 1, r + 1) * n + c]
                                        - height[Math.max(0, r - 1) * n + c]);
                double shade = Math.max(0.45, Math.min(1.25, 1 - (dx + dz) / (4.0 * step)));
                int rgb = rock[i];
                int red = (int) Math.min(255, ((rgb >> 16) & 255) * shade),
                        green = (int) Math.min(255, ((rgb >> 8) & 255) * shade),
                        blue = (int) Math.min(255, (rgb & 255) * shade);
                image.setRGB(c, r, (red << 16) | (green << 8) | blue);
            }
        ImageIO.write(image, "png", new File(a[6]));
        System.out.println("outcrop " + n + "x" + n + " -> " + a[6]);
    }
}
