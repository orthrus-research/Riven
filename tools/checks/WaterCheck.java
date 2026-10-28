package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Underground water as native cubes hold it (riven-caves.json fill, riven-hydrology.json lakes).
 * Builds whole 32 x 32 column windows below the ground on a grid over 4 km and every underground
 * lake within 4 km, then requires: no water beside air (it would spill), no water over air, no air
 * below a lake's waterline, and the same blocks when the windows are built in reverse order. Prints
 * water volumes and the air pockets sealed in rock by cave family. args: [seed...]
 */
public final class WaterCheck {
    private static final byte SOLID = 0, AIR = 1, CAVE_WATER = 2, LAKE_WATER = 3, OTHER = 4;

    /** Totals over windows. */
    private static final class Stats {
        long columns, caveWater, lakeWater, caveAir, besideAir, overAir, airInLake, poolColumns;
        final long[] sealed = new long[3];
        final long[][] sealedFamily = new long[3][CaveMap.FAMILIES.length];
        final List<String> failures = new ArrayList<>();
        long digest;

        synchronized void add(Stats o) {
            columns += o.columns;
            caveWater += o.caveWater;
            lakeWater += o.lakeWater;
            caveAir += o.caveAir;
            besideAir += o.besideAir;
            overAir += o.overAir;
            airInLake += o.airInLake;
            poolColumns += o.poolColumns;
            for (int b = 0; b < 3; b++) {
                sealed[b] += o.sealed[b];
                for (int f = 0; f < CaveMap.FAMILIES.length; f++)
                    sealedFamily[b][f] += o.sealedFamily[b][f];
            }
            if (failures.size() < 10) failures.addAll(o.failures);
            digest += o.digest;
        }
    }

    private static boolean passable(TerrainBlock t) {
        return t == TerrainBlock.AIR
                || t == TerrainBlock.COBWEB
                || t == TerrainBlock.ROOT
                || t == TerrainBlock.TOADSTOOL
                || t == TerrainBlock.PORTOBELLO
                || t == TerrainBlock.BROWN_MUSHROOM
                || t == TerrainBlock.RED_MUSHROOM
                || t == TerrainBlock.MINERS_DELIGHT
                || t == TerrainBlock.GLOWSHROOM;
    }

    static Stats window(GenerationMaps maps, int x0, int z0, int size) {
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        int[] bottoms = new int[ColumnBlocks.maximumRuns() + 1];
        TerrainBlock[] blocks = new TerrainBlock[ColumnBlocks.maximumRuns()];
        TerrainMap.Sample s = new TerrainMap.Sample();
        int n = size * size, y0 = h.minimumY, top = h.minimumY;
        int[] surface = new int[n], lakeLo = new int[n], lakeLevel = new int[n];
        int[][] runBottoms = new int[n][];
        TerrainBlock[][] runBlocks = new TerrainBlock[n][];
        byte[][] family = new byte[n][];
        int pools = 0;
        for (int i = 0; i < n; i++) {
            int x = x0 + i % size, z = z0 + i / size;
            maps.terrain.sample(x, z, s);
            family[i] = new byte[h.maximumYExclusive - h.minimumY];
            maps.terrain.caves.carve(x, z, s, family[i]);
            if (s.caves.pools > 0) pools++;
            int count =
                    ColumnBlocks.runs(
                            s,
                            ColumnBlocks.bareStone(s, maps.surfaces),
                            maps.surfaces,
                            h,
                            bottoms,
                            blocks);
            runBottoms[i] = java.util.Arrays.copyOf(bottoms, count + 1);
            runBlocks[i] = java.util.Arrays.copyOf(blocks, count);
            surface[i] = s.blockY();
            boolean lake = s.water.aquiferMin <= s.water.aquiferMax;
            lakeLo[i] = lake ? s.water.aquiferMin : Integer.MAX_VALUE;
            lakeLevel[i] = lake ? s.water.aquiferLevel : Integer.MIN_VALUE;
            top = Math.max(top, surface[i]);
        }
        int span = top - y0 + 1;
        byte[] cat = new byte[n * span];
        Stats st = new Stats();
        st.columns = n;
        st.poolColumns = pools;
        for (int i = 0; i < n; i++)
            for (int r = 0; r < runBlocks[i].length; r++)
                for (int y = Math.max(y0, runBottoms[i][r]);
                        y < Math.min(top + 1, runBottoms[i][r + 1]);
                        y++) {
                    TerrainBlock t = runBlocks[i][r];
                    boolean lake = y >= lakeLo[i] && y <= lakeLevel[i];
                    byte c;
                    if (y > surface[i]) c = OTHER;
                    else if (passable(t)) c = AIR;
                    else if (t == TerrainBlock.WATER || t == TerrainBlock.ALGAE)
                        c = lake ? LAKE_WATER : CAVE_WATER;
                    else c = SOLID;
                    cat[i * span + y - y0] = c;
                    st.digest = st.digest * 31 + t.ordinal();
                    if (c == CAVE_WATER) st.caveWater++;
                    else if (c == LAKE_WATER) st.lakeWater++;
                    else if (c == AIR) {
                        st.caveAir++;
                        if (lake) fail(st, "air below a lake's waterline", x0, z0, size, i, y);
                    }
                }
        for (int i = 0; i < n; i++) {
            int x = i % size, z = i / size;
            for (int y = 1; y < span; y++) {
                int q = i * span + y;
                byte c = cat[q];
                if (c != CAVE_WATER && c != LAKE_WATER) continue;
                if (cat[q - 1] == AIR) {
                    st.overAir++;
                    fail(st, "water over air", x0, z0, size, i, y + y0);
                }
                int[] side = {
                    x > 0 ? q - span : -1,
                    x < size - 1 ? q + span : -1,
                    z > 0 ? q - size * span : -1,
                    z < size - 1 ? q + size * span : -1
                };
                for (int m : side)
                    if (m >= 0 && cat[m] == AIR) {
                        st.besideAir++;
                        fail(st, "water beside air", x0, z0, size, i, y + y0);
                    }
            }
        }
        // Air sealed in rock: components that touch neither the window's sides nor the sky.
        int[] queue = new int[n * span];
        boolean[] seen = new boolean[n * span];
        for (int start = 0; start < n * span; start++) {
            if (cat[start] != AIR || seen[start]) continue;
            int head = 0, tail = 0;
            boolean open = false;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int q = queue[head++];
                int i = q / span, y = q % span, x = i % size, z = i / size;
                if (x == 0 || z == 0 || x == size - 1 || z == size - 1) open = true;
                int[] next = {
                    x > 0 ? q - span : -1,
                    x < size - 1 ? q + span : -1,
                    z > 0 ? q - size * span : -1,
                    z < size - 1 ? q + size * span : -1,
                    y > 0 ? q - 1 : -1,
                    y < span - 1 ? q + 1 : -1
                };
                for (int m : next) {
                    if (m < 0) continue;
                    if (cat[m] == OTHER) open = true;
                    else if (cat[m] == AIR && !seen[m]) {
                        seen[m] = true;
                        queue[tail++] = m;
                    }
                }
            }
            if (open) continue;
            int bucket = tail < 8 ? 0 : tail < 64 ? 1 : 2;
            st.sealed[bucket]++;
            st.sealedFamily[bucket][family[start / span][start % span + y0 - h.minimumY]]++;
        }
        return st;
    }

    private static void fail(Stats st, String what, int x0, int z0, int size, int i, int y) {
        if (st.failures.size() < 10)
            st.failures.add(what + " at " + (x0 + i % size) + " " + y + " " + (z0 + i / size));
    }

    private static Stats grid(long seed, boolean reverse) throws InterruptedException {
        List<int[]> windows = new ArrayList<>();
        for (int z = -2048; z < 2048; z += 256)
            for (int x = -2048; x < 2048; x += 256) windows.add(new int[] {x, z, 32});
        GenerationMaps lakes = new GenerationMaps(seed);
        Hydro hydro = lakes.terrain.hydro;
        int cells = 2048 / hydro.cellBlocks + 1;
        for (int cz = -cells; cz < cells; cz++)
            for (int cx = -cells; cx < cells; cx++) {
                Lens lens = hydro.plan(cx, cz).lens;
                if (lens == null) continue;
                int r = (int) Math.ceil(lens.radius + lens.shell) + 2;
                windows.add(new int[] {(int) lens.x - r, (int) lens.z - r, 2 * r + 1});
            }
        if (reverse) java.util.Collections.reverse(windows);
        Stats total = new Stats();
        AtomicInteger next = new AtomicInteger();
        Thread[] pool = new Thread[Math.max(1, Runtime.getRuntime().availableProcessors() - 2)];
        for (int t = 0; t < pool.length; t++) {
            pool[t] =
                    new Thread(
                            () -> {
                                GenerationMaps own = new GenerationMaps(seed);
                                for (int i; (i = next.getAndIncrement()) < windows.size(); ) {
                                    int[] w = windows.get(i);
                                    total.add(window(own, w[0], w[1], w[2]));
                                }
                            });
            pool[t].start();
        }
        for (Thread t : pool) t.join();
        return total;
    }

    public static void main(String[] args) throws Exception {
        long[] seeds = args.length == 0 ? new long[] {8675309L, 101L} : new long[args.length];
        for (int i = 0; i < args.length; i++) seeds[i] = Long.parseLong(args[i]);
        for (long seed : seeds) {
            Stats st = grid(seed, false), again = grid(seed, true);
            System.out.printf(
                    "seed %d: %d columns; cave water %d, lake water %d, pool columns %d, cave air %d; water beside air %d, over air %d, air below a lake's waterline %d%n",
                    seed,
                    st.columns,
                    st.caveWater,
                    st.lakeWater,
                    st.poolColumns,
                    st.caveAir,
                    st.besideAir,
                    st.overAir,
                    st.airInLake);
            System.out.printf(
                    "  air sealed in rock <8/<64/more: %d %d %d%n",
                    st.sealed[0], st.sealed[1], st.sealed[2]);
            for (int b = 0; b < 3; b++) {
                StringBuilder line =
                        new StringBuilder("  sealed " + new String[] {"<8", "<64", "64+"}[b] + ":");
                for (int f = 0; f < CaveMap.FAMILIES.length; f++)
                    if (st.sealedFamily[b][f] > 0)
                        line.append(' ')
                                .append(CaveMap.FAMILIES[f])
                                .append('=')
                                .append(st.sealedFamily[b][f]);
                System.out.println(line);
            }
            for (String f : st.failures) System.out.println("  " + f);
            if (!st.failures.isEmpty()) throw new AssertionError("Water spills on seed " + seed);
            if (again.digest != st.digest || again.caveWater != st.caveWater)
                throw new AssertionError("Water depends on build order on seed " + seed);
        }
        System.out.println("WaterCheck passed");
    }
}
