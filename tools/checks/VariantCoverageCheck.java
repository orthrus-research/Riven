package cc.orthrus.riven.worldgen;

import java.util.Locale;

/**
 * Every biome variant shows up several times in a world (terrain-v134). Usage:
 * VariantCoverageCheck [seeds [layout]] (default 8 seeds at layout 4, a 32 km world). Takes every
 * variant patch over the world square (Variants.patch, the production choice) and counts, per
 * variant, the patches that choose it and those whose point lies in a parent biome's climate (its
 * effective climate region, lowland and calm: a patch whose country can show). Requires each
 * variant to have at least MIN_PATCHES such patches in every world, and reports the share of land
 * patches that take no variant.
 */
public final class VariantCoverageCheck {
    private static final int MIN_PATCHES = 3;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        double layout = args.length > 1 ? Double.parseDouble(args[1]) : 4;
        WorldScale world = new WorldScale(layout);
        double patch = Variants.patchBlocks(), half = world.side() / 2;
        int lo = (int) Math.floor(-half / patch), hi = (int) Math.ceil(half / patch);
        int count = Variants.count();
        int[] fewest = new int[count];
        java.util.Arrays.fill(fewest, Integer.MAX_VALUE);
        StringBuilder failures = new StringBuilder();
        System.out.printf(
                Locale.ROOT,
                "%d seeds at layout %.1f (%.0f km), %d variants, patches of %.0f blocks, %dx%d%n",
                seeds,
                layout,
                world.side() / 1024,
                count,
                patch,
                hi - lo,
                hi - lo);
        for (int s = 1; s <= seeds; s++) {
            long seed = s * 7919L + 1;
            GenerationMaps maps = new GenerationMaps(seed, world);
            int[] chosen = new int[count], shown = new int[count];
            int land = 0, plain = 0;
            TerrainMap.BaseSample b = new TerrainMap.BaseSample();
            for (int gz = lo; gz < hi; gz++)
                for (int gx = lo; gx < hi; gx++) {
                    double x = (gx + .5) * patch, z = (gz + .5) * patch;
                    maps.terrain.intent(x, z, b);
                    int v = maps.variants.patch(gx, gz);
                    if (b.ocean.marine) continue;
                    land++;
                    if (v == Variants.NONE) {
                        plain++;
                        continue;
                    }
                    chosen[v]++;
                    ClimateRegion region =
                            ClimateRegion.at(
                                    TemperatureBand.at(b.climate.celsius),
                                    MoistureBand.at(b.climate.moisture));
                    int biome = RivenBiomeTable.select(region.name() + "/LOW/calm").index;
                    if (Variants.parent(v, biome)) shown[v]++;
                }
            StringBuilder row = new StringBuilder();
            for (int v = 0; v < count; v++) {
                fewest[v] = Math.min(fewest[v], shown[v]);
                row.append(
                        String.format(
                                Locale.ROOT, " %s %d/%d", Variants.id(v), shown[v], chosen[v]));
                if (shown[v] < MIN_PATCHES)
                    failures.append(
                            String.format(
                                    Locale.ROOT,
                                    " seed %d %s: %d;",
                                    seed,
                                    Variants.id(v),
                                    shown[v]));
            }
            System.out.printf(
                    Locale.ROOT,
                    "seed %6d land patches %d, none %.0f%%, in parent climate/chosen:%s%n",
                    seed,
                    land,
                    100.0 * plain / Math.max(1, land),
                    row);
        }
        StringBuilder head = new StringBuilder("fewest patches in a parent climate:");
        for (int v = 0; v < count; v++)
            head.append(String.format(Locale.ROOT, " %s %d,", Variants.id(v), fewest[v]));
        System.out.println(head);
        require(failures.length() == 0, "Variants under " + MIN_PATCHES + " patches:" + failures);
        System.out.println("VariantCoverageCheck passed");
    }
}
