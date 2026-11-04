package cc.orthrus.riven.worldgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Landmass shape over a seed set: what the world's coastlines and continents look like from
 * above, measured on the kept outline (the land weight above zero) in the world square.
 *
 * <ul>
 *   <li><b>masses</b>: 4-connected landmasses of at least {@link #MIN_KM2} km², and the largest's
 *       share of land.
 *   <li><b>rank-size slope</b>: the slope of log area against log rank over those masses; about
 *       -1 is a power law (a few large, many small), near 0 is masses of one size.
 *   <li><b>solidity</b>: each mass's area over its convex hull's, area-weighted; 1 is a convex
 *       blob, lower has gulfs, peninsulas and bays.
 *   <li><b>coast dimension</b>: box-counting dimension of the coast pixels over boxes of 1 to 32
 *       pixels; a circle is 1.0, Britain about 1.25, Norway about 1.5.
 *   <li><b>Clark-Evans R</b>: mean nearest-neighbour distance between mass centroids over that of
 *       random points at the same density; 1 is random, above 1 regular (2.15 a hexagonal grid),
 *       below 1 clustered.
 *   <li><b>slivers</b>: landmasses of 6 pixels or more that are at least 85% edge (a one-pixel
 *       erosion leaves 15% or less): crescents and streaks rather than islands.
 *   <li><b>coast gradient</b>: the median and 90th percentile of the outline score's gradient at
 *       coast pixels (16-block central differences); the score is read as coast distance, so it
 *       should stay near 1.
 *   <li><b>plate margin ratio</b>: the share of coast within {@code 256 x layout} blocks of a plate
 *       boundary over the share of the whole square there; 1 means coasts ignore plates.
 * </ul>
 *
 * <p>It measures; it does not fail. Arguments: {@code [seeds=16] [first=1] [scale=4]
 * [side=512] [masks]}; seeds are first, first + 1, ... With a masks directory, each seed's land
 * also goes to {@code shape-<seed>.pgm} there (sea 0, land 170; the plate margin band 60 at sea
 * and 230 on land), north up.
 */
public final class ShapeCheck {
    private ShapeCheck() {}

    static final double MIN_KM2 = 4;
    private static final int[] BOXES = {1, 2, 4, 8, 16, 32};

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        long first = args.length > 1 ? Long.parseLong(args[1]) : 1;
        double layout = args.length > 2 ? Double.parseDouble(args[2]) : 4;
        int side = args.length > 3 ? Integer.parseInt(args[3]) : 512;
        Path masks = args.length > 4 ? Path.of(args[4]) : null;
        if (masks != null) Files.createDirectories(masks);
        if (side % BOXES[BOXES.length - 1] != 0) throw new IllegalArgumentException("side");
        WorldScale world = layout == 1 ? WorldScale.DEFAULT : new WorldScale(layout);
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        System.out.printf(
                Locale.ROOT,
                "%.0f km (%s), %d x %d samples (%.0f blocks); %d seeds from %d%n",
                world.side() / 1024,
                world,
                side,
                side,
                world.side() / side,
                count,
                first);
        ExecutorService pool =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Future<double[]>> rows = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                long seed = first + i;
                rows.add(pool.submit(() -> measure(seed, heights, world, side, masks)));
            }
            report(rows, count <= 8);
        } finally {
            pool.shutdown();
        }
        System.out.println("OK");
    }

    /** seed, land, masses, largest, slope, solidity, dimension, R, margin ratio, gradient p50, p90, slivers. */
    static double[] measure(
            long seed, TerrainHeightReference heights, WorldScale world, int side, Path masks)
            throws IOException {
        ContinentalOutlineMap outline = LandShare.choose(seed, heights, world).outline;
        PlateMap plates = new PlateMap(seed, world);
        double step = world.side() / side, origin = -world.side() / 2 + step / 2;
        double near = 256 * world.layout;
        boolean[] land = new boolean[side * side];
        boolean[] margin = new boolean[side * side];
        ContinentalOutlineMap.Sample sample = new ContinentalOutlineMap.Sample();
        int landCount = 0;
        for (int i = 0; i < side; i++)
            for (int k = 0; k < side; k++) {
                double x = origin + i * step, z = origin + k * step;
                outline.sample(x, z, sample);
                if (land[i * side + k] = sample.landWeight() > 0) landCount++;
                margin[i * side + k] =
                        plates.sample((int) Math.floor(x), (int) Math.floor(z)).boundaryDistance
                                < near;
            }

        if (masks != null) {
            byte[] image = new byte[side * side];
            for (int i = 0; i < side; i++)
                for (int k = 0; k < side; k++) {
                    int c = i * side + k;
                    image[k * side + i] =
                            (byte) (land[c] ? (margin[c] ? 230 : 170) : margin[c] ? 60 : 0);
                }
            byte[] header =
                    ("P5\n" + side + " " + side + "\n255\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] file = Arrays.copyOf(header, header.length + image.length);
            System.arraycopy(image, 0, file, header.length, image.length);
            Files.write(masks.resolve("shape-" + seed + ".pgm"), file);
        }

        // Coast pixels: land with a sea 4-neighbour inside the square.
        boolean[] coast = new boolean[side * side];
        int coastCount = 0, coastNear = 0, allNear = 0;
        for (int i = 0; i < side; i++)
            for (int k = 0; k < side; k++) {
                int c = i * side + k;
                if (margin[c]) allNear++;
                if (!land[c]) continue;
                boolean edge =
                        (i > 0 && !land[c - side])
                                || (i < side - 1 && !land[c + side])
                                || (k > 0 && !land[c - 1])
                                || (k < side - 1 && !land[c + 1]);
                if (edge) {
                    coast[c] = true;
                    coastCount++;
                    if (margin[c]) coastNear++;
                }
            }

        // Masses: area, centroid and convex-hull solidity.
        int minPixels = (int) Math.ceil(MIN_KM2 * 1e6 / (step * step));
        boolean[] seen = new boolean[side * side];
        int[] stack = new int[side * side];
        List<double[]> masses = new ArrayList<>(); // area, ci, ck, solidity
        int largest = 0, slivers = 0;
        for (int start = 0; start < land.length; start++) {
            if (!land[start] || seen[start]) continue;
            int top = 0, size = 0;
            double si = 0, sk = 0;
            List<int[]> rim = new ArrayList<>();
            seen[start] = true;
            stack[top++] = start;
            while (top > 0) {
                int c = stack[--top], i = c / side, k = c % side;
                size++;
                si += i;
                sk += k;
                if (coast[c] || i == 0 || k == 0 || i == side - 1 || k == side - 1)
                    rim.add(new int[] {i, k});
                if (i > 0 && land[c - side] && !seen[c - side])
                    seen[stack[top++] = c - side] = true;
                if (i < side - 1 && land[c + side] && !seen[c + side])
                    seen[stack[top++] = c + side] = true;
                if (k > 0 && land[c - 1] && !seen[c - 1]) seen[stack[top++] = c - 1] = true;
                if (k < side - 1 && land[c + 1] && !seen[c + 1]) seen[stack[top++] = c + 1] = true;
            }
            largest = Math.max(largest, size);
            // A sliver is all edge: at least 6 pixels, of which at most 15% are interior.
            if (size >= 6 && size - rim.size() <= .15 * size) slivers++;
            if (size >= minPixels)
                masses.add(new double[] {size, si / size, sk / size, size / hullArea(rim)});
        }

        // Rank-size slope.
        double slope = 0;
        if (masses.size() >= 3) {
            double[] areas = masses.stream().mapToDouble(m -> m[0]).sorted().toArray();
            int n = areas.length;
            double[] lx = new double[n], ly = new double[n];
            for (int r = 0; r < n; r++) {
                lx[r] = Math.log(r + 1);
                ly[r] = Math.log(areas[n - 1 - r]);
            }
            slope = fit(lx, ly);
        }

        // Area-weighted solidity.
        double weighted = 0, total = 0;
        for (double[] m : masses) {
            weighted += m[0] * m[3];
            total += m[0];
        }
        double solidity = total == 0 ? 0 : weighted / total;

        // Box-counting dimension of the coast.
        double[] lx = new double[BOXES.length], ly = new double[BOXES.length];
        for (int b = 0; b < BOXES.length; b++) {
            int box = BOXES[b], cells = side / box, filled = 0;
            boolean[] hit = new boolean[cells * cells];
            for (int c = 0; c < coast.length; c++)
                if (coast[c]) {
                    int cell = (c / side) / box * cells + (c % side) / box;
                    if (!hit[cell]) {
                        hit[cell] = true;
                        filled++;
                    }
                }
            lx[b] = Math.log(box);
            ly[b] = Math.log(Math.max(1, filled));
        }
        double dimension = -fit(lx, ly);

        // Clark-Evans R over mass centroids.
        double r = Double.NaN;
        if (masses.size() >= 4) {
            double sum = 0;
            for (double[] a : masses) {
                double best = Double.MAX_VALUE;
                for (double[] b : masses)
                    if (a != b) best = Math.min(best, Math.hypot(a[1] - b[1], a[2] - b[2]));
                sum += best;
            }
            double density = masses.size() / ((double) side * side);
            r = (sum / masses.size()) / (.5 / Math.sqrt(density));
        }

        // The score is read as coast distance (v65): its gradient at the coast should stay near 1.
        double[] gradients = new double[coastCount];
        int g = 0;
        double h = 16;
        ContinentalOutlineMap.Sample probe = new ContinentalOutlineMap.Sample();
        for (int c = 0; c < coast.length; c++) {
            if (!coast[c]) continue;
            double x = origin + (c / side) * step, z = origin + (c % side) * step;
            outline.sample(x + h, z, probe);
            double east = probe.coastCoordinate();
            outline.sample(x - h, z, probe);
            double dx = (east - probe.coastCoordinate()) / (2 * h);
            outline.sample(x, z + h, probe);
            double south = probe.coastCoordinate();
            outline.sample(x, z - h, probe);
            double dz = (south - probe.coastCoordinate()) / (2 * h);
            gradients[g++] = Math.hypot(dx, dz);
        }
        Arrays.sort(gradients);
        double p50 = g == 0 ? Double.NaN : gradients[g / 2];
        double p90 = g == 0 ? Double.NaN : gradients[Math.min(g - 1, (int) (.9 * g))];

        double ratio =
                coastCount == 0 || allNear == 0
                        ? Double.NaN
                        : (coastNear / (double) coastCount) / (allNear / (double) land.length);
        return new double[] {
            seed,
            landCount / (double) land.length,
            masses.size(),
            landCount == 0 ? 0 : largest / (double) landCount,
            slope,
            solidity,
            dimension,
            r,
            ratio,
            p50,
            p90,
            slivers
        };
    }

    /** Area of the convex hull of the pixel squares whose (row, column) cells are given. */
    private static double hullArea(List<int[]> cells) {
        long[] points = new long[cells.size() * 4];
        int n = 0;
        for (int[] c : cells)
            for (int d = 0; d < 4; d++)
                points[n++] = ((long) (c[0] + (d >> 1)) << 32) | (c[1] + (d & 1));
        Arrays.sort(points, 0, n);
        // Andrew's monotone chain: lower hull, then upper; the last point repeats the first.
        long[] hull = new long[2 * n];
        int h = 0;
        for (int j = 0; j < n; j++) {
            while (h >= 2 && cross(hull[h - 2], hull[h - 1], points[j]) <= 0) h--;
            hull[h++] = points[j];
        }
        for (int j = n - 2, lower = h + 1; j >= 0; j--) {
            while (h >= lower && cross(hull[h - 2], hull[h - 1], points[j]) <= 0) h--;
            hull[h++] = points[j];
        }
        h--;
        double area = 0;
        for (int j = 0; j < h; j++) {
            long a = hull[j], b = hull[(j + 1) % h];
            area += (double) (a >> 32) * (b & 0xffffffffL) - (double) (b >> 32) * (a & 0xffffffffL);
        }
        return Math.abs(area) / 2;
    }

    private static long cross(long o, long a, long b) {
        long ox = o >> 32, oz = o & 0xffffffffL;
        return ((a >> 32) - ox) * ((b & 0xffffffffL) - oz)
                - ((a & 0xffffffffL) - oz) * ((b >> 32) - ox);
    }

    /** Least-squares slope of y on x. */
    private static double fit(double[] x, double[] y) {
        double mx = 0, my = 0;
        for (int i = 0; i < x.length; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= x.length;
        my /= y.length;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < x.length; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
        }
        return sxx == 0 ? 0 : sxy / sxx;
    }

    private static final String[] NAMES = {
        "land %",
        "masses",
        "largest % of land",
        "rank-size slope",
        "solidity",
        "coast dimension",
        "Clark-Evans R",
        "plate margin ratio",
        "coast gradient p50",
        "coast gradient p90",
        "slivers"
    };

    private static void report(List<Future<double[]>> rows, boolean each) throws Exception {
        int metrics = NAMES.length;
        double[] sum = new double[metrics], min = new double[metrics], max = new double[metrics];
        int[] valid = new int[metrics];
        Arrays.fill(min, Double.MAX_VALUE);
        Arrays.fill(max, -Double.MAX_VALUE);
        for (Future<double[]> row : rows) {
            double[] r = row.get();
            double[] v = r.clone();
            v[1] *= 100;
            v[3] *= 100;
            if (each)
                System.out.printf(
                        Locale.ROOT,
                        "  seed %d: land %.1f%%, %d masses, largest %.0f%%, slope %.2f,"
                                + " solidity %.2f, dimension %.3f, R %.2f, margin %.2f,"
                                + " gradient %.2f / %.2f, slivers %d%n",
                        (long) v[0],
                        v[1],
                        (int) v[2],
                        v[3],
                        v[4],
                        v[5],
                        v[6],
                        v[7],
                        v[8],
                        v[9],
                        v[10],
                        (int) v[11]);
            for (int m = 0; m < metrics; m++) {
                double x = v[m + 1];
                if (Double.isNaN(x)) continue;
                valid[m]++;
                sum[m] += x;
                min[m] = Math.min(min[m], x);
                max[m] = Math.max(max[m], x);
            }
        }
        for (int m = 0; m < metrics; m++)
            System.out.printf(
                    Locale.ROOT,
                    "  %-19s mean %7.3f  range %7.3f - %7.3f%n",
                    NAMES[m],
                    valid[m] == 0 ? Double.NaN : sum[m] / valid[m],
                    min[m],
                    max[m]);
    }
}
