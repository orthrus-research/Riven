package cc.orthrus.riven.worldgen;

import java.util.List;
import java.util.Locale;

/**
 * A shore walk that stalls ends its stretch. Usage: CoastStretchCheck. At seed 8675309, layout
 * scale 1, the segment whose shore runs into a cusp near (13057, 44235) walks 24 blocks a step
 * until the snap returns every step to the cusp, each closer than the last; the bench's guide
 * once reached two coinciding points and the sedimentary kernel refused the plan, which failed
 * every column that asked for the segment (first met at column 11840, 42816, through its flood
 * cell). Requires that column to sample, the bench to stand with its guide ending in the cusp,
 * and every placed plan there to keep the kernel's node spacing.
 */
public final class CoastStretchCheck {
    private static final double CUSP_X = 13056.77, CUSP_Z = 44234.98;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        GenerationMaps maps = new GenerationMaps(8675309L);
        TerrainMap.Sample sample = new TerrainMap.Sample();
        maps.terrain.sample(11840, 42816, sample);
        require(Double.isFinite(sample.height), "Column 11840, 42816 has no height");
        List<CoastSediment.View> placed =
                maps.terrain.sediment.placed(
                        CUSP_X - 600, CUSP_Z - 600, CUSP_X + 600, CUSP_Z + 600);
        int benches = 0, atCusp = 0;
        double least = Double.POSITIVE_INFINITY;
        for (CoastSediment.View v : placed) {
            if (v.form == CoastSediment.Form.TIDAL_CREEK) continue;
            if (v.form == CoastSediment.Form.BEACH_BENCH) benches++;
            for (int k = 1; k < v.x.length; k++)
                least = Math.min(least, StrictMath.hypot(v.x[k] - v.x[k - 1], v.z[k] - v.z[k - 1]));
            int end = v.x.length - 1;
            if (v.form == CoastSediment.Form.BEACH_BENCH
                    && (StrictMath.hypot(v.x[0] - CUSP_X, v.z[0] - CUSP_Z) < 1
                            || StrictMath.hypot(v.x[end] - CUSP_X, v.z[end] - CUSP_Z) < 1))
                atCusp++;
        }
        require(atCusp == 1, atCusp + " benches end in the cusp, expected 1");
        require(
                least >= cc.orthrus.riven.contrib.coast.SedimentaryCoasts.MINIMUM_NODE_SPACING,
                "Plan nodes " + least + " apart");
        System.out.printf(
                Locale.ROOT,
                "CoastStretchCheck passed: column 11840, 42816 at height %.2f; %d benches near the"
                        + " cusp, one ending in it; least node spacing %.4f%n",
                sample.height,
                benches,
                least);
    }
}
