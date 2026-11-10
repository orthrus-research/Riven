package cc.orthrus.riven.worldgen;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;

/**
 * Network pruning changes no drainage answer. For each column of a fixed set (a grid around one
 * centre plus scattered points), it asks {@code nearest}, {@code clearance} and {@code lake} with
 * pruning on, then off, on the same maps, and requires bit-identical results. It also reports how
 * many networks each query needed with and without pruning. Usage: {@code DrainagePruneCheck <seed>
 * <scale> <centreX> <centreZ> <half width> <grid step>}.
 */
public final class DrainagePruneCheck {
    private DrainagePruneCheck() {}

    private record Answer(
            String nearest, double clearance, long lake, double level, double ground) {}

    private static String hit(Drainage.Hit h) {
        if (h == null) return "none";
        return h.id
                + "/"
                + Double.doubleToRawLongBits(h.distance)
                + "/"
                + Double.doubleToRawLongBits(h.radius)
                + "/"
                + Double.doubleToRawLongBits(h.level)
                + "/"
                + Double.doubleToRawLongBits(h.depth)
                + "/"
                + h.river
                + h.wet
                + h.steep
                + "/"
                + h.bar
                + "/"
                + Double.doubleToRawLongBits(h.levee);
    }

    private static Answer ask(Drainage d, double x, double z, double mouthRadius) {
        Drainage.Hit wet = new Drainage.Hit(), near = new Drainage.Hit();
        double[] mouth = new double[1], lake = new double[2];
        Drainage.Hit found = d.nearest(x, z, wet, near, mouthRadius, mouth);
        String nearest = hit(found) + "|" + Double.doubleToRawLongBits(mouth[0]);
        double clearance = d.clearance(x, z, 512);
        long id = d.lake(x, z, lake);
        return new Answer(nearest, clearance, id, lake[0], lake[1]);
    }

    public static void main(String[] args) {
        if (args.length != 6)
            throw new IllegalArgumentException(
                    "Usage: DrainagePruneCheck <seed> <scale> <centreX> <centreZ> <half> <step>");
        long seed = Long.parseLong(args[0]);
        WorldScale world = new WorldScale(Double.parseDouble(args[1]));
        double cx = Double.parseDouble(args[2]), cz = Double.parseDouble(args[3]);
        double half = Double.parseDouble(args[4]), step = Double.parseDouble(args[5]);
        GenerationMaps maps =
                new GenerationMaps(
                        seed, TerrainHeightReference.DEFAULT, world, new ForkJoinPool(4), null);
        Drainage d = maps.terrain.hydro.drainage;
        double mouthRadius = 192;
        int n = (int) Math.floor(2 * half / step) + 1;
        double[][] points = new double[n * n + 64][];
        int count = 0;
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++)
                points[count++] = new double[] {cx - half + i * step, cz - half + j * step};
        java.util.SplittableRandom random = new java.util.SplittableRandom(seed);
        for (int k = 0; k < 64; k++)
            points[count++] =
                    new double[] {
                        cx + random.nextDouble(-2 * half, 2 * half),
                        cz + random.nextDouble(-2 * half, 2 * half)
                    };
        Answer[] pruned = new Answer[count];
        long started = System.nanoTime();
        for (int k = 0; k < count; k++) pruned[k] = ask(d, points[k][0], points[k][1], mouthRadius);
        double prunedSeconds = (System.nanoTime() - started) / 1e9;
        d.pruning(false);
        started = System.nanoTime();
        int differences = 0;
        for (int k = 0; k < count; k++) {
            Answer full = ask(d, points[k][0], points[k][1], mouthRadius);
            if (!full.nearest.equals(pruned[k].nearest)
                    || Double.doubleToRawLongBits(full.clearance)
                            != Double.doubleToRawLongBits(pruned[k].clearance)
                    || full.lake != pruned[k].lake
                    || Double.doubleToRawLongBits(full.level)
                            != Double.doubleToRawLongBits(pruned[k].level)
                    || Double.doubleToRawLongBits(full.ground)
                            != Double.doubleToRawLongBits(pruned[k].ground)) {
                if (differences++ < 8)
                    System.out.println(
                            "DIFFERENT at "
                                    + Arrays.toString(points[k])
                                    + ": pruned "
                                    + pruned[k]
                                    + " full "
                                    + full);
            }
        }
        double fullSeconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(
                "Drainage pruning: seed %d scale %s, %d columns, %d differences; pruned pass %.1f s"
                        + " (first), full pass %.1f s (after)%n",
                seed, args[1], count, differences, prunedSeconds, fullSeconds);
        if (differences != 0) throw new AssertionError(differences + " drainage answers changed");
        System.exit(0);
    }
}
