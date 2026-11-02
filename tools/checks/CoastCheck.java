package cc.orthrus.riven.worldgen;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
        for (long seed = 1; seed <= seeds; seed++) {
            GenerationMaps maps = new GenerationMaps(seed);
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
        System.out.println(
                "CoastCheck passed: land past its band keeps its height (" + inland + " columns)");
    }
}
