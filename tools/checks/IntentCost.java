package cc.orthrus.riven.worldgen;

/**
 * Times the samplers every cold plan and certificate reads: the continental map, climate and
 * full intent, one thread, over a grid of fresh points (each pass starts on ground no earlier pass
 * read, so per-point memos stay cold). Prints nanoseconds per sample and a checksum of the
 * results, so a speed change that alters values shows. args: [seed] [scale] [points per side]
 */
public final class IntentCost {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 2485331L;
        double scale = args.length > 1 ? Double.parseDouble(args[1]) : 4;
        int side = args.length > 2 ? Integer.parseInt(args[2]) : 160;
        GenerationMaps maps = new GenerationMaps(seed, new WorldScale(scale));
        TerrainMap.BaseSample s = new TerrainMap.BaseSample();
        ContinentalMap.Sample c = new ContinentalMap.Sample();
        ClimateMap.Sample k = new ClimateMap.Sample();
        // Warm the code on its own ground first.
        run(maps, s, c, k, side, 20000, 0);
        for (int pass = 1; pass <= 3; pass++) run(maps, s, c, k, side, 20000, pass * 9973);
    }

    private static void run(
            GenerationMaps maps,
            TerrainMap.BaseSample s,
            ContinentalMap.Sample c,
            ClimateMap.Sample k,
            int side,
            int origin,
            int offset) {
        int spacing = 37, n = side * side;
        double sum = 0;
        long t0 = System.nanoTime();
        for (int j = 0; j < side; j++)
            for (int i = 0; i < side; i++) {
                maps.continents.sample(origin + offset + i * spacing, -origin + j * spacing, c);
                sum += finite(c.distance);
            }
        long t1 = System.nanoTime();
        for (int j = 0; j < side; j++)
            for (int i = 0; i < side; i++) {
                maps.climate.sample(origin + offset + i * spacing, -origin + j * spacing + 5, k);
                sum += finite(k.moisture);
            }
        long t2 = System.nanoTime();
        for (int j = 0; j < side; j++)
            for (int i = 0; i < side; i++) {
                maps.terrain.intent(origin + offset + i * spacing + 11, -origin + j * spacing, s);
                sum += finite(s.baseHeight) + finite(s.shore.landWeight);
            }
        long t3 = System.nanoTime();
        System.out.printf(
                "pass %d: continents %.1f us, climate %.1f us, intent %.1f us per sample; checksum %.6f%n",
                offset, (t1 - t0) / 1e3 / n, (t2 - t1) / 1e3 / n, (t3 - t2) / 1e3 / n, sum);
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0;
    }
}
