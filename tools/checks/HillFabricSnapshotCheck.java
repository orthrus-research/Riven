package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.hills.Hills;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Frozen-artifact hill plans on a controlled flat parent; not natural occurrence evidence. */
public final class HillFabricSnapshotCheck {
    private HillFabricSnapshotCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static final class Counts {
        int intents, parents, waters;
        long hash = 7919;

        void visit(double x, double z, int kind) {
            hash =
                    SeededNoise.mix(
                            hash
                                    ^ Double.doubleToRawLongBits(x)
                                    ^ Long.rotateLeft(Double.doubleToRawLongBits(z), 23)
                                    ^ kind);
        }
    }

    private static final class Flat
            implements IntentSampler, MajorFeatureParent, OwnerEdgeWaterClearance {
        final TerrainHeightReference heights;
        final ThreadLocal<Counts> counts = ThreadLocal.withInitial(Counts::new);

        Flat(TerrainHeightReference heights) {
            this.heights = heights;
        }

        void reset() {
            counts.set(new Counts());
        }

        public void sample(double x, double z, TerrainMap.BaseSample out) {
            Counts c = counts.get();
            out.baseHeight = 128;
            out.broadDx = .1;
            out.broadDz = .2;
            out.ocean.marine = false;
            out.land.low = 0;
            out.land.middle = c.intents == 0 ? 1 : 0;
            out.land.high = c.intents == 0 ? 0 : 1;
            c.intents++;
            c.visit(x, z, 1);
        }

        public void sample(double x, double z, TerrainMap.Sample out) {
            Counts c = counts.get();
            out.height = 128;
            out.minimumY = heights.lowestSurface;
            out.maximumY = heights.highestSurface;
            out.ocean.marine = false;
            out.shore.kind = Shore.Kind.INLAND;
            out.shore.landWeight = 1;
            out.shore.distance = Double.POSITIVE_INFINITY;
            c.parents++;
            c.visit(x, z, 2);
        }

        public double surfaceClearance(double x, double z) {
            Counts c = counts.get();
            c.waters++;
            c.visit(x, z, 3);
            return 1000000;
        }
    }

    static String geometry(HillTerrain.Decision decision) throws Exception {
        if (decision.site == null) return "NONE";
        Hills.Site s = decision.site;
        StringBuilder out = new StringBuilder();
        for (String field : new String[] {"c", "s"}) {
            Field f = Hills.Site.class.getDeclaredField(field);
            f.setAccessible(true);
            out.append(bits(f.getDouble(s))).append('|');
        }
        for (int i = 0; i < 12; i++) {
            double x = Math.floor(s.x + (i % 4 - 1.5) * s.majorRadius / 2);
            double z = Math.floor(s.z + (i / 4 - 1) * s.majorRadius / 2);
            out.append(bits(s.weight(x, z))).append(':').append(bits(s.delta(x, z))).append('|');
        }
        return out.toString();
    }

    static String identity(HillTerrain.Decision d) {
        String prefix = d.family + "," + d.reason + "," + d.certificateColumns;
        if (d.site == null) return prefix + ",NONE,NONE,NONE,NONE,NONE,NONE";
        Hills.Site s = d.site;
        return prefix
                + ","
                + Long.toHexString(s.key)
                + ","
                + bits(s.x)
                + ","
                + bits(s.z)
                + ","
                + bits(s.majorRadius)
                + ","
                + bits(s.minorRadius)
                + ","
                + bits(s.amplitude);
    }

    private static void surface(Hills.Site s, Path output) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("x,z,delta,weight");
        int radius = (int) Math.ceil(s.majorRadius + 2);
        for (int z = (int) Math.floor(s.z) - radius; z <= (int) Math.ceil(s.z) + radius; z++)
            for (int x = (int) Math.floor(s.x) - radius; x <= (int) Math.ceil(s.x) + radius; x++) {
                double delta = s.delta(x, z), weight = s.weight(x, z);
                require(
                        Double.isFinite(delta)
                                && Double.isFinite(weight)
                                && weight >= 0
                                && weight <= 1 + 1e-12,
                        "Nonfinite or invalid rotated surface");
                require(delta >= -1e-9 && delta <= s.amplitude + 1e-9, "Skew amplitude bound");
                if (Math.hypot(x - s.x, z - s.z) >= s.majorRadius + 1e-9)
                    require(delta == 0 && weight == 0, "Rotated geometry escaped certified disk");
                rows.add(x + "," + z + "," + delta + "," + weight);
            }
        Files.write(output, rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("<new-plans.csv> <existing-surface-dir>");
        List<String> rows = new ArrayList<>();
        rows.add(
                "seed,cx,cz,owner,family,reason,columns,key,xBits,zBits,aBits,bBits,amplitudeBits,intents,parents,waters,queryHash,geometry");
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        int acceptedSkew = 0;
        for (long seed : new long[] {8675309, 7, -918273645}) {
            Sites sites = new Terrain(seed, heights).sites;
            Flat probe = new Flat(heights);
            HillTerrain hills = new HillTerrain(seed, heights, sites, probe, probe, probe);
            List<int[]> points = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            boolean saved = false;
            search:
            for (int z = -32; z < 128; z++)
                for (int x = -32; x < 128; x++) {
                    long owner = sites.key(x, z);
                    if (!sites.admits(x, z, owner, Sites.Lane.HILLS)) continue;
                    probe.reset();
                    HillTerrain.Decision d = hills.plan(x, z);
                    Counts c = probe.counts.get();
                    require(c.intents == 5 && c.parents == c.waters, "Earlier-query protocol");
                    require(c.parents == d.certificateColumns, "Certificate count mismatch");
                    String snapshot = identity(d) + "," + geometry(d);
                    expected.add(snapshot);
                    points.add(new int[] {x, z});
                    rows.add(
                            seed
                                    + ","
                                    + x
                                    + ","
                                    + z
                                    + ","
                                    + Long.toHexString(owner)
                                    + ","
                                    + identity(d)
                                    + ","
                                    + c.intents
                                    + ","
                                    + c.parents
                                    + ","
                                    + c.waters
                                    + ","
                                    + Long.toHexString(c.hash)
                                    + ","
                                    + geometry(d));
                    if (d.site != null && d.family == Hills.Family.SKEW_HILLS) {
                        acceptedSkew++;
                        if (!saved) {
                            surface(d.site, Paths.get(args[1]).resolve("surface-" + seed + ".csv"));
                            saved = true;
                        }
                    }
                    if (points.size() == 24) break search;
                }
            require(points.size() == 24 && saved, "Bounded cohort lacks positive skew control");
            // Populate enough rejection entries to exercise eviction independently of geometry.
            for (int n = 0; n < 512; n++) {
                int x = 10000 + n, z = -10000;
                if (!sites.admits(x, z, sites.key(x, z), Sites.Lane.HILLS)) hills.plan(x, z);
            }
            for (int i = points.size() - 1; i >= 0; i--) {
                int[] p = points.get(i);
                probe.reset();
                HillTerrain.Decision d = hills.plan(p[0], p[1]);
                require(
                        expected.get(i).equals(identity(d) + "," + geometry(d)),
                        "Evicted/reverse replay");
            }
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int lane = 0; lane < 4; lane++) {
                    final int id = lane;
                    tasks.add(
                            () -> {
                                for (int n = 0; n < 24; n++) {
                                    int i = (n * 7 + id) % 24;
                                    probe.reset();
                                    int[] p = points.get(i);
                                    HillTerrain.Decision d = hills.plan(p[0], p[1]);
                                    require(
                                            expected.get(i).equals(identity(d) + "," + geometry(d)),
                                            "Concurrent hill replay");
                                }
                                return null;
                            });
                }
                for (Future<Void> task : pool.invokeAll(tasks)) task.get();
            } finally {
                pool.shutdownNow();
            }
        }
        Files.write(
                Paths.get(args[0]), rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(
                "Flat-parent plans="
                        + (rows.size() - 1)
                        + "; accepted skew="
                        + acceptedSkew
                        + "; rotated integer disk/amplitude, memo eviction/reverse/concurrency=PASS; no natural/native qualification.");
    }
}
