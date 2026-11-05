package cc.orthrus.riven.worldgen;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Every climate shows up several times in a world (terrain-v129). Usage: ClimateCoverageCheck
 * [seeds [layout]] (default 16 seeds at layout 4, a 32 km world). Samples the intent (land,
 * effective climate after province offsets and the lapse rate, landform class) on a 128-block grid
 * over the world square and counts, per temperature x moisture combination, the separate land
 * patches of at least 1 km^2. Requires every one of the 15 combinations to form at least
 * MIN_PATCHES patches in every world, frozen land to average 15-20% of land and stay under 30% in
 * every world. Reports each combination's patches and land share, and the landform classes' shares.
 */
public final class ClimateCoverageCheck {
    private static final int MIN_PATCHES = 3;
    private static final double STEP = 128, MIN_AREA = 1_000_000;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        double layout = args.length > 1 ? Double.parseDouble(args[1]) : 4;
        WorldScale world = new WorldScale(layout);
        int n = (int) Math.round(world.side() / STEP),
                minCells = (int) Math.ceil(MIN_AREA / (STEP * STEP));
        int regions = TemperatureBand.values().length * MoistureBand.values().length;
        Terrain.Landform[] forms = Terrain.Landform.values();
        double frozenSum = 0, frozenWorst = 0;
        int[] fewest = new int[regions];
        java.util.Arrays.fill(fewest, Integer.MAX_VALUE);
        double[] formShare = new double[forms.length];
        StringBuilder failures = new StringBuilder();
        System.out.printf(
                Locale.ROOT,
                "%d seeds at layout %.1f (%.0f km), %dx%d grid of %.0f blocks, patches of >= %d cells%n",
                seeds,
                layout,
                world.side() / 1024,
                n,
                n,
                STEP,
                minCells);
        for (int s = 1; s <= seeds; s++) {
            long seed = s * 7919L + 1;
            GenerationMaps maps = new GenerationMaps(seed, world);
            byte[] region = new byte[n * n];
            byte[] form = new byte[n * n];
            double half = world.side() / 2;
            IntStream.range(0, n)
                    .parallel()
                    .forEach(
                            r -> {
                                TerrainMap.BaseSample b = new TerrainMap.BaseSample();
                                for (int c = 0; c < n; c++) {
                                    double x = -half + (c + .5) * STEP, z = -half + (r + .5) * STEP;
                                    maps.terrain.intent(x, z, b);
                                    int k = r * n + c;
                                    if (b.ocean.marine) {
                                        region[k] = -1;
                                        continue;
                                    }
                                    TemperatureBand t = TemperatureBand.at(b.climate.celsius);
                                    MoistureBand m = MoistureBand.at(b.climate.moisture);
                                    region[k] =
                                            (byte)
                                                    (t.ordinal() * MoistureBand.values().length
                                                            + m.ordinal());
                                    form[k] = (byte) b.land.landform.ordinal();
                                }
                            });
            int land = 0;
            int[] cells = new int[regions],
                    patches = new int[regions],
                    forms_ = new int[forms.length];
            boolean[] seen = new boolean[n * n];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int k = 0; k < n * n; k++) {
                if (region[k] < 0) continue;
                land++;
                cells[region[k]]++;
                forms_[form[k]]++;
                if (seen[k]) continue;
                int size = 0, id = region[k];
                seen[k] = true;
                queue.add(k);
                while (!queue.isEmpty()) {
                    int q = queue.poll(), qr = q / n, qc = q % n;
                    size++;
                    int[] next = {q - n, q + n, qc > 0 ? q - 1 : -1, qc < n - 1 ? q + 1 : -1};
                    for (int m : next)
                        if (m >= 0 && m < n * n && !seen[m] && region[m] == id) {
                            seen[m] = true;
                            queue.add(m);
                        }
                }
                if (size >= minCells) patches[id]++;
            }
            int frozen = 0;
            for (int m = 0; m < MoistureBand.values().length; m++) frozen += cells[m];
            double frozenShare = 100.0 * frozen / Math.max(1, land);
            frozenSum += frozenShare;
            frozenWorst = Math.max(frozenWorst, frozenShare);
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < regions; i++) {
                fewest[i] = Math.min(fewest[i], patches[i]);
                row.append(String.format(Locale.ROOT, " %2d", patches[i]));
                if (patches[i] < MIN_PATCHES)
                    failures.append(
                            String.format(
                                    Locale.ROOT, " seed %d %s: %d;", seed, name(i), patches[i]));
            }
            for (int f = 0; f < forms.length; f++)
                formShare[f] += 100.0 * forms_[f] / Math.max(1, land) / seeds;
            System.out.printf(
                    Locale.ROOT,
                    "seed %6d draw %d/%d frozen %4.1f%% patches:%s%n",
                    seed,
                    maps.climateAttempt + 1,
                    maps.climateDraws,
                    frozenShare,
                    row);
        }
        StringBuilder head = new StringBuilder("fewest patches per combination:");
        for (int i = 0; i < regions; i++)
            head.append(String.format(Locale.ROOT, " %s %d,", name(i), fewest[i]));
        System.out.println(head);
        StringBuilder classes = new StringBuilder("landform classes (mean share of land):");
        for (int f = 0; f < forms.length; f++)
            classes.append(String.format(Locale.ROOT, " %s %.1f%%,", forms[f], formShare[f]));
        System.out.println(classes);
        double frozenMean = frozenSum / seeds;
        System.out.printf(
                Locale.ROOT, "frozen land mean %.1f%%, worst %.1f%%%n", frozenMean, frozenWorst);
        require(
                failures.length() == 0,
                "Combinations under " + MIN_PATCHES + " patches:" + failures);
        require(
                frozenMean >= 15 && frozenMean <= 20,
                "Frozen land mean outside 15-20%: " + frozenMean);
        require(frozenWorst < 30, "A world with frozen land at " + frozenWorst + "%");
        System.out.println("ClimateCoverageCheck passed");
    }

    private static String name(int i) {
        int m = MoistureBand.values().length;
        return TemperatureBand.values()[i / m] + "_" + MoistureBand.values()[i % m];
    }
}
