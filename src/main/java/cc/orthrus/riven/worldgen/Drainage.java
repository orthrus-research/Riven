package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.IntStream;

/**
 * Drainage networks (riven-hydrology.json drainage, terrain-v91). Each built continent routes its
 * own land to the sea on a grid of cellBlocks, over its intent height plus a gentle routing relief
 * (so flats still gather into valleys), with a priority flood: hollows fill and spill, and every
 * cell drains to the sea or to the network lake or route that receives it. Rain-weighted area
 * accumulates downstream; a channel begins where it passes channelAreaCells, and its width and
 * depth grow with the square root of the area it drains, so headwater streams join into creeks and
 * rivers that widen below every junction. A channel's level is the running minimum of the ground
 * less one block from its head, never below the water it joins. Pure functions of seed,
 * definitions and continent cell, memoized per continent.
 */
final class Drainage {
    /**
     * One channel near a column: its id, distance to the centreline and its section there (radius
     * is the open channel's, or a braided reach's whole bed). {@code wet} for water; else {@code
     * bar} names a bar's material (a braided reach's gravel bar or a point bar inside a bend), or
     * null for a bank. {@code steep} marks a gravel bed. {@code levee} is the highest water a dry
     * column within LEVEE blocks of a bed may touch.
     */
    static final class Hit {
        long id;
        double distance, radius, level, depth;
        boolean river, wet, steep;
        Hydro.Bed bar;

        /** The highest water level of any bed within the levee of a dry column. */
        double levee;

        void set(Channel c, double distance, double radius, double level, double depth) {
            id = c.id;
            this.distance = distance;
            this.radius = radius;
            this.level = level;
            this.depth = depth;
            river = c.river;
            wet = steep = false;
            bar = null;
        }
    }

    /** One channel from its head to the sea, a receiving lake or the channel it joins. */
    private static final class Channel {
        final long id;
        final double[] x, z, level, radius, depth, valley;

        /**
         * Braid phase (cycles of a braided bed's weave, accumulated along the course), fall per
         * block, braiding (0..1), point-bar side and riffle (1 where the water falls between pools).
         */
        final double[] weave, gradient, braid, bar, riffle;

        final boolean river;
        final double minX, minZ, maxX, maxZ, mouthX, mouthZ;

        Channel(
                long id,
                double[] x,
                double[] z,
                double[] level,
                double[] radius,
                double[] depth,
                double[] valley,
                double[] weave,
                double[] gradient,
                double[] braid,
                double[] bar,
                double[] riffle,
                boolean river,
                double reach,
                double span,
                double mouthX,
                double mouthZ) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.level = level;
            this.radius = radius;
            this.depth = depth;
            this.valley = valley;
            this.weave = weave;
            this.gradient = gradient;
            this.braid = braid;
            this.bar = bar;
            this.riffle = riffle;
            this.river = river;
            this.mouthX = mouthX;
            this.mouthZ = mouthZ;
            double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
            double c = Double.POSITIVE_INFINITY, d = Double.NEGATIVE_INFINITY, widest = 0;
            for (int i = 0; i < x.length; i++) {
                a = Math.min(a, x[i]);
                b = Math.max(b, x[i]);
                c = Math.min(c, z[i]);
                d = Math.max(d, z[i]);
                widest = Math.max(widest, radius[i] * (1 + braid[i] * span) + valley[i]);
            }
            double margin = reach + widest;
            minX = a - margin;
            maxX = b + margin;
            minZ = c - margin;
            maxZ = d + margin;
        }
    }

    /**
     * One continent's channels, with a bin index of every segment's reach (water, bank, valley), a
     * bin index of river segments only (for clearance), and its river mouths.
     */
    private static final class Network {
        static final Network EMPTY =
                new Network(
                        new Channel[0], 0, 0, 1, 1, 1, new int[1][0], new int[1][0], new double[0]);
        final Channel[] channels;
        final double originX, originZ, bin;
        final int bins, binsZ;
        final int[][] refs, rivers;

        /** River mouths as (x, z) pairs. */
        final double[] mouths;

        Network(
                Channel[] channels,
                double originX,
                double originZ,
                double bin,
                int bins,
                int binsZ,
                int[][] refs,
                int[][] rivers,
                double[] mouths) {
            this.channels = channels;
            this.originX = originX;
            this.originZ = originZ;
            this.bin = bin;
            this.bins = bins;
            this.binsZ = binsZ;
            this.refs = refs;
            this.rivers = rivers;
            this.mouths = mouths;
        }

        int[] at(double x, double z) {
            int bx = (int) Math.floor((x - originX) / bin),
                    bz = (int) Math.floor((z - originZ) / bin);
            if (bx < 0 || bz < 0 || bx >= bins || bz >= binsZ) return NONE;
            return refs[bz * bins + bx];
        }
    }

    private static final int[] NONE = new int[0];

    /** Dry columns this close to a bed stand at least as high as any water beside them. */
    static final double LEVEE = 1.5;

    /** The coarse scan's cells across a continent's support, at most. */
    private static final double COARSE_CELLS = 96;

    private final long seed;
    private final TerrainHeightReference heights;
    private final IntentSampler intent;
    private final ContinentalMap continents;
    private final ContinentalOutlineMap outline;
    private final DoubleBinaryOperator receiving;
    private final int planBlocks;
    private final Plans plans;
    private final double cellBase, relief, runoffDry, runoffSpan, runoffMin, runoffMax, channelArea;
    private final int maximumCells;
    private final double[] reliefWave, reliefWeight;
    private final double widthHead, widthPerRoot, widthMax, depthHead, depthPerRoot, depthMax;
    private final double riverWidth, bankScale, maxCut, estuaryLength, estuaryFlare;
    final double valleyRatio, valleyCap;
    private final double binBlocks, reach;
    private final int passes;
    private final SeededNoise[] octaves;
    private final SeededNoise wander;
    private final double meanderWaves, minimumWave, amplitude, flatGradient, steepGradient, skew;
    private final double step, wanderBlocks;
    private final double poolDrop, poolWidths, poolMinimum, riffleRun, sandGradient, pointBar;
    private final double braidStart, braidFull, braidCold, braidDry, braidLow, braidHigh;
    private final double braidWave;
    private final int braidChannels;

    /** A fully braided reach's bed, in open-channel radii beyond the channel's own. */
    private final double braidSpan;

    private final CellMemo<Network> memo;
    private final ThreadLocal<ContinentalOutlineMap.TrunkScratch> query =
            ThreadLocal.withInitial(() -> new ContinentalOutlineMap.TrunkScratch(0));

    /** Builds the hydrology plans of owner cells, given as (x, z) pairs, before a scan reads them. */
    interface Plans {
        void prepare(int[] cells, int count);
    }

    /**
     * {@code receiving} gives the water level of a network lake or route at a column, or NaN:
     * streams that reach it end there. It reads the hydrology plan of the column's owner cell,
     * {@code planBlocks} wide, which {@code plans} builds ahead of a scan.
     */
    Drainage(
            long seed,
            JsonObject j,
            TerrainHeightReference heights,
            IntentSampler intent,
            ContinentalMap continents,
            ContinentalOutlineMap outline,
            DoubleBinaryOperator receiving,
            int planBlocks,
            Plans plans,
            double bankScale,
            double maxCut,
            double estuaryLength,
            double estuaryFlare) {
        this.seed = SeededNoise.mix(seed ^ 0x445241494e414745L);
        this.heights = heights;
        this.intent = intent;
        this.continents = continents;
        this.outline = outline;
        this.receiving = receiving;
        this.planBlocks = planBlocks;
        this.plans = plans;
        this.bankScale = bankScale;
        this.maxCut = maxCut;
        this.estuaryLength = estuaryLength;
        this.estuaryFlare = estuaryFlare;
        cellBase = Rules.number(j, "cellBlocks", 8, 256);
        maximumCells = (int) Rules.number(j, "maximumCellsPerSide", 32, 4096);
        JsonObject r = j.getAsJsonObject("routingRelief");
        relief = Rules.number(r, "amplitudeBlocks", 0, 64);
        JsonArray waves = r.getAsJsonArray("wavelengthBlocks"),
                weights = r.getAsJsonArray("weights");
        if (waves.size() != weights.size() || waves.size() == 0)
            throw new IllegalStateException("Range: routingRelief");
        reliefWave = new double[waves.size()];
        reliefWeight = new double[waves.size()];
        octaves = new SeededNoise[waves.size()];
        for (int i = 0; i < waves.size(); i++) {
            reliefWave[i] = waves.get(i).getAsDouble();
            reliefWeight[i] = weights.get(i).getAsDouble();
            if (!(reliefWave[i] >= cellBase) || !(reliefWeight[i] >= 0))
                throw new IllegalStateException("Range: routingRelief");
            octaves[i] = new SeededNoise(SeededNoise.mix(this.seed + i * 0x9e3779b97f4a7c15L));
        }
        JsonObject f = j.getAsJsonObject("runoff");
        runoffDry = Rules.number(f, "dryMoisture", 0, 1);
        runoffSpan = Rules.number(f, "moistureSpan", .01, 2);
        runoffMin = Rules.number(f, "minimum", 0, 4);
        runoffMax = Rules.number(f, "maximum", runoffMin, 8);
        channelArea = Rules.number(j, "channelAreaCells", 1, 10000);
        JsonObject w = j.getAsJsonObject("widthBlocks");
        widthHead = Rules.number(w, "head", 1, 16);
        widthPerRoot = Rules.number(w, "perRootSquareKilometre", 0, 256);
        widthMax = Rules.number(w, "maximum", widthHead, 256);
        JsonObject d = j.getAsJsonObject("depthBlocks");
        depthHead = Rules.number(d, "head", 1, 16);
        depthPerRoot = Rules.number(d, "perRootSquareKilometre", 0, 64);
        depthMax = Rules.number(d, "maximum", depthHead, 64);
        riverWidth = Rules.number(j, "riverWidthBlocks", widthHead, widthMax);
        valleyRatio = Rules.number(j, "valleyInCutDepths", 0, 8);
        valleyCap = Rules.number(j, "streamValleyMaximumBlocks", 0, 512);
        passes = (int) Rules.number(j, "smoothingPasses", 0, 4);
        JsonObject m = j.getAsJsonObject("meanders");
        meanderWaves = Rules.number(m, "wavelengthInWidths", 2, 64);
        minimumWave = Rules.number(m, "minimumWavelengthBlocks", 4, 1024);
        amplitude = Rules.number(m, "amplitudeInWavelengths", 0, 1);
        flatGradient = Rules.number(m, "flatGradient", 0, 1);
        steepGradient = Rules.number(m, "steepGradient", flatGradient + 1e-6, 1);
        skew = Rules.number(m, "skew", 0, .5);
        step = Rules.number(m, "stepBlocks", 1, 16);
        wanderBlocks = Rules.number(m, "wanderBlocks", 16, 4096);
        wander = new SeededNoise(SeededNoise.mix(this.seed ^ 0x4d45414e44455253L));
        JsonObject pools = j.getAsJsonObject("pools");
        poolDrop = Rules.number(pools, "dropBlocks", 1, 16);
        poolWidths = Rules.number(pools, "minimumInWidths", 1, 64);
        poolMinimum = Rules.number(pools, "minimumBlocks", 2, 1024);
        riffleRun = Rules.number(pools, "riffleBlocksPerDrop", 1, 16);
        JsonObject bars = j.getAsJsonObject("bars");
        sandGradient = Rules.number(bars, "sandBelowGradient", 0, 1);
        pointBar = Rules.number(bars, "pointBarStrength", 0, 1);
        JsonObject braids = j.getAsJsonObject("braids");
        braidStart = Rules.number(braids, "fromWidthBlocks", 1, 256);
        braidFull = Rules.number(braids, "fullWidthBlocks", braidStart + .001, 512);
        braidSpan = Rules.number(braids, "bedInWidths", 0, 16);
        braidCold = Rules.number(braids, "coldBelowCelsius", -50, 50);
        braidDry = Rules.number(braids, "dryBelowMoisture", 0, 1);
        JsonArray grade = braids.getAsJsonArray("gradientRange");
        braidLow = grade.get(0).getAsDouble();
        braidHigh = grade.get(1).getAsDouble();
        if (!(braidHigh > braidLow && braidLow >= 0))
            throw new IllegalStateException("Range: braids");
        braidChannels = (int) Rules.number(braids, "channels", 2, 8);
        braidWave = Rules.number(braids, "wavelengthInBeds", 1, 64);
        binBlocks = Rules.number(j, "binBlocks", 16, 1024);
        memo = new CellMemo<>((int) Rules.number(j, "continentMemo", 4, 4096));
        // The farthest a channel's water, bank or valley reaches beyond its centreline.
        double widest = widthMax * .5 * Math.max(estuaryFlare, 1 + braidSpan);
        reach = widest * (1 + 2 * bankScale) + 2 + Math.max(valleyCap, valleyRatio * maxCut);
    }

    /**
     * The channel whose bed holds a column (its water, or a braided reach's gravel bar), else the
     * nearest one whose bank or valley reaches it (a point bar inside a bend names its material);
     * null for none. {@code mouth} receives the river-mouth weight (1 at a river's ocean mouth, 0
     * at {@code mouthRadius}).
     */
    Hit nearest(double x, double z, Hit wet, Hit near, double mouthRadius, double[] mouth) {
        ContinentalOutlineMap.TrunkScratch q = query.get();
        int cells = outline.continentCells(x, z, reach, q);
        prepare(q, cells);
        double wetScore = Double.POSITIVE_INFINITY, nearScore = Double.POSITIVE_INFINITY;
        double levee = Double.NEGATIVE_INFINITY;
        mouth[0] = 0;
        for (int k = 0; k < cells; k++) {
            Network n = network(q.cells[2 * k], q.cells[2 * k + 1]);
            if (mouthRadius > 0)
                for (int m = 0; m < n.mouths.length; m += 2) {
                    double d = Math.hypot(x - n.mouths[m], z - n.mouths[m + 1]);
                    if (d < mouthRadius)
                        mouth[0] = Math.max(mouth[0], 1 - Rules.smooth(d / mouthRadius));
                }
            for (int ref : n.at(x, z)) {
                Channel c = n.channels[ref >>> 16];
                int i = ref & 0xffff;
                double vx = c.x[i + 1] - c.x[i], vz = c.z[i + 1] - c.z[i];
                double length2 = vx * vx + vz * vz;
                if (length2 == 0) continue;
                double t =
                        Math.max(0, Math.min(1, ((x - c.x[i]) * vx + (z - c.z[i]) * vz) / length2));
                double dist = Math.hypot(x - c.x[i] - t * vx, z - c.z[i] - t * vz);
                double radius = lerp(c.radius, i, t), braid = lerp(c.braid, i, t);
                double bed = radius * (1 + braid * braidSpan);
                // Signed distance across the course, along the normal that meanders offset by.
                double length = Math.sqrt(length2);
                double lateral = ((z - c.z[i]) * vx - (x - c.x[i]) * vz) / length;
                if (dist - bed <= LEVEE) {
                    // Water beside a column may stand a riffle's step higher than at its nearest
                    // point: take the level two blocks upstream.
                    double up = t * length - 2;
                    levee =
                            Math.max(
                                    levee,
                                    up >= 0
                                            ? lerp(c.level, i, up / length)
                                            : c.level[Math.max(0, i - 1)]);
                }
                if (dist <= bed) {
                    double score = dist / bed;
                    if (score >= wetScore) continue;
                    wetScore = score;
                    // A riffle runs shallow over gravel.
                    double riffle = lerp(c.riffle, i, t), depth = lerp(c.depth, i, t);
                    wet.set(c, dist, bed, lerp(c.level, i, t), depth - (depth - 1) * riffle);
                    wet.steep = riffle > .5 || lerp(c.gradient, i, t) >= sandGradient;
                    if (braid < .05) {
                        wet.wet = true;
                        continue;
                    }
                    // A braided reach: a few shallow channels weave across a gravel bed whose
                    // bars stand at the water's level.
                    double sub = Math.max(1, radius * (1 - .5 * braid));
                    double weave = lerp(c.weave, i, t);
                    for (int b = 0; b < braidChannels && !wet.wet; b++) {
                        long h = SeededNoise.mix(c.id + (b + 1) * 0x9e3779b97f4a7c15L);
                        double centre =
                                (bed - sub)
                                        * Math.sin(
                                                2
                                                        * Math.PI
                                                        * (weave / (1 + .4 * b)
                                                                + SeededNoise.unit(h)));
                        wet.wet = Math.abs(lateral - centre) <= sub;
                    }
                    if (wet.wet) {
                        wet.distance = 0;
                        wet.depth = 1;
                    } else wet.bar = Hydro.Bed.GRAVEL;
                } else {
                    double beyond = dist - bed;
                    if (beyond >= nearScore) continue;
                    nearScore = beyond;
                    near.set(c, dist, bed, lerp(c.level, i, t), lerp(c.depth, i, t));
                    // A point bar on the inside of a bend: sand where the channel runs gently,
                    // gravel where it runs steeper.
                    double bar = lerp(c.bar, i, t);
                    if (Math.abs(bar) >= pointBar
                            && beyond < 2 * radius
                            && Math.signum(lateral) == Math.signum(bar))
                        near.bar =
                                lerp(c.gradient, i, t) < sandGradient
                                        ? Hydro.Bed.SAND
                                        : Hydro.Bed.GRAVEL;
                }
            }
        }
        Hit hit = wetScore <= 1 ? wet : nearScore < Double.POSITIVE_INFINITY ? near : null;
        if (hit != null) hit.levee = levee;
        return hit;
    }

    private static double lerp(double[] a, int i, double t) {
        return a[i] + t * (a[i + 1] - a[i]);
    }

    /** The values from {@code start} while below {@code end}, each the last plus {@code step}. */
    private static double[] steps(double start, double end, double step) {
        int n = 0;
        for (double v = start; v < end; v += step) n++;
        double[] out = new double[n];
        int i = 0;
        for (double v = start; v < end; v += step) out[i++] = v;
        return out;
    }

    /**
     * Distance outside every river's bed, bank and reserved valley (streams reserve nothing),
     * capped at {@code clearance}.
     */
    double clearance(double x, double z, double clearance) {
        ContinentalOutlineMap.TrunkScratch q = query.get();
        int cells = outline.continentCells(x, z, clearance + reach, q);
        prepare(q, cells);
        for (int k = 0; k < cells; k++) {
            Network n = network(q.cells[2 * k], q.cells[2 * k + 1]);
            if (n.channels.length == 0) continue;
            // River segments are binned by their support; scan only the bins within reach.
            int ax = (int) Math.floor((x - clearance - n.originX) / n.bin);
            int bx = (int) Math.floor((x + clearance - n.originX) / n.bin);
            int az = (int) Math.floor((z - clearance - n.originZ) / n.bin);
            int bz = (int) Math.floor((z + clearance - n.originZ) / n.bin);
            for (int zz = Math.max(0, az); zz <= Math.min(n.binsZ - 1, bz); zz++)
                for (int xx = Math.max(0, ax); xx <= Math.min(n.bins - 1, bx); xx++)
                    for (int ref : n.rivers[zz * n.bins + xx]) {
                        Channel c = n.channels[ref >>> 16];
                        int i = ref & 0xffff;
                        double support =
                                Math.max(
                                                        c.radius[i] * (1 + c.braid[i] * braidSpan),
                                                        c.radius[i + 1]
                                                                * (1 + c.braid[i + 1] * braidSpan))
                                                * (1 + bankScale)
                                        + 2
                                        + Math.max(c.valley[i], c.valley[i + 1]);
                        double vx = c.x[i + 1] - c.x[i], vz = c.z[i + 1] - c.z[i];
                        double length2 = vx * vx + vz * vz;
                        double t =
                                length2 == 0
                                        ? 0
                                        : Math.max(
                                                0,
                                                Math.min(
                                                        1,
                                                        ((x - c.x[i]) * vx + (z - c.z[i]) * vz)
                                                                / length2));
                        clearance =
                                Math.min(
                                        clearance,
                                        Math.hypot(x - c.x[i] - t * vx, z - c.z[i] - t * vz)
                                                - support);
                    }
        }
        return clearance;
    }

    /** Tools: the continents whose support reaches a column, as cell pairs (x, z). */
    long[] continents(double x, double z) {
        ContinentalOutlineMap.TrunkScratch q = query.get();
        int n = outline.continentCells(x, z, 0, q);
        return Arrays.copyOf(q.cells, 2 * n);
    }

    /**
     * Tools: one continent's channels, each as {x, z, level, radius, gradient, braid, riffle}
     * arrays (copies), with the river flag as the sign of the first radius (negative for streams).
     */
    List<double[][]> channels(long gx, long gz) {
        List<double[][]> out = new ArrayList<>();
        for (Channel c : network(gx, gz).channels) {
            double[] radius = c.radius.clone();
            if (!c.river) radius[0] = -radius[0];
            out.add(
                    new double[][] {
                        c.x.clone(),
                        c.z.clone(),
                        c.level.clone(),
                        radius,
                        c.gradient.clone(),
                        c.braid.clone(),
                        c.riffle.clone()
                    });
        }
        return out;
    }

    private Network network(long gx, long gz) {
        int ix = (int) gx, iz = (int) gz;
        return memo.get(ix, iz, () -> build(gx, gz));
    }

    /**
     * Builds the networks of a query's continent cells together when more than one is missing,
     * so a column waits for the slowest build instead of their sum (timing only).
     */
    private void prepare(ContinentalOutlineMap.TrunkScratch q, int cells) {
        int missing = 0;
        for (int k = 0; k < cells; k++)
            if (!memo.holds((int) q.cells[2 * k], (int) q.cells[2 * k + 1])) missing++;
        if (missing < 2) return;
        long[] pairs = Arrays.copyOf(q.cells, 2 * cells);
        IntStream.range(0, cells).parallel().forEach(k -> network(pairs[2 * k], pairs[2 * k + 1]));
    }

    /**
     * The hydrology owner cells under a continent's land, as (x, z) pairs: each coarse row's own
     * land, widened by the two coarse cells the fine grid adds, within the grid's box.
     */
    private int[] planCells(double[] zs, double[][] rows, double coarse, double[] box) {
        Set<Long> cells = new LinkedHashSet<>();
        for (int r = 0; r < zs.length; r++) {
            if (rows[r] == null) continue;
            int cz0 = planCell(Math.max(box[1], zs[r] - 2 * coarse));
            int cz1 = planCell(Math.min(box[3], zs[r] + 2 * coarse));
            int cx0 = planCell(Math.max(box[0], rows[r][0] - 2 * coarse));
            int cx1 = planCell(Math.min(box[2], rows[r][1] + 2 * coarse));
            for (int cz = cz0; cz <= cz1; cz++)
                for (int cx = cx0; cx <= cx1; cx++)
                    cells.add(((long) cx << 32) ^ (cz & 0xffffffffL));
        }
        int[] out = new int[2 * cells.size()];
        int k = 0;
        for (long c : cells) {
            out[k++] = (int) (c >> 32);
            out[k++] = (int) c;
        }
        return out;
    }

    private int planCell(double v) {
        return (int) Math.floor(v / planBlocks);
    }

    /** Routing relief at a column: gentle multi-scale relief that gathers flats into valleys. */
    private double relief(double x, double z) {
        double v = 0;
        for (int i = 0; i < octaves.length; i++)
            v += reliefWeight[i] * octaves[i].sample(x / reliefWave[i], z / reliefWave[i]);
        return relief * v;
    }

    /**
     * A grid cell's channel point, x ({@code axis} 0) or z: a seeded spot within the middle 70% of
     * the cell, so channels stop following the grid's eight directions.
     */
    private double point(int i, int j, int axis, double cell) {
        long h = SeededNoise.mix(SeededNoise.cell(seed, i, j) + axis * 0x9e3779b97f4a7c15L);
        return ((axis == 0 ? i : j) + .15 + .7 * SeededNoise.unit(h)) * cell;
    }

    /** Rain-weighted area a cell contributes, from its moisture. */
    private double runoff(double moisture) {
        return Math.max(runoffMin, Math.min(runoffMax, (moisture - runoffDry) / runoffSpan));
    }

    private Network build(long gx, long gz) {
        ContinentalOutlineMap.TrunkScratch q = new ContinentalOutlineMap.TrunkScratch(0);
        double[] box = new double[4];
        long owner = outline.continentBounds(gx, gz, q, box);
        // The support is a loose bound: a coarse scan finds the continent's own land, and the fine
        // grid covers that with two coarse cells to spare.
        // In larger worlds (a larger layout) the scan coarsens with the support.
        double coarse = Math.max(4 * cellBase, (box[2] - box[0]) / COARSE_CELLS);
        // The scan's points, accumulated as they always were; only the continental map decides
        // land and owner, so the scan reads nothing else. Rows run in parallel: each point is a
        // pure function, and the land box is their minimum and maximum, in any order.
        double[] xs = steps((Math.floor(box[0] / coarse) + .5) * coarse, box[2], coarse);
        double[] zs = steps((Math.floor(box[1] / coarse) + .5) * coarse, box[3], coarse);
        double[][] rows = new double[zs.length][];
        IntStream.range(0, zs.length)
                .parallel()
                .forEach(
                        r -> {
                            ContinentalMap.Sample c = new ContinentalMap.Sample();
                            double x0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY;
                            for (double px : xs) {
                                continents.sample(px, zs[r], c);
                                if (c.marine || c.owner != owner) continue;
                                x0 = Math.min(x0, px);
                                x1 = Math.max(x1, px);
                            }
                            rows[r] = x0 <= x1 ? new double[] {x0, x1} : null;
                        });
        double lx0 = Double.POSITIVE_INFINITY, lz0 = Double.POSITIVE_INFINITY;
        double lx1 = Double.NEGATIVE_INFINITY, lz1 = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < zs.length; r++) {
            if (rows[r] == null) continue;
            lx0 = Math.min(lx0, rows[r][0]);
            lx1 = Math.max(lx1, rows[r][1]);
            lz0 = Math.min(lz0, zs[r]);
            lz1 = Math.max(lz1, zs[r]);
        }
        if (lx0 > lx1) return Network.EMPTY;
        box[0] = Math.max(box[0], lx0 - 2 * coarse);
        box[1] = Math.max(box[1], lz0 - 2 * coarse);
        box[2] = Math.min(box[2], lx1 + 2 * coarse);
        box[3] = Math.min(box[3], lz1 + 2 * coarse);
        // The fine grid spans at most maximumCellsPerSide cells; a coarser grid keeps the head
        // area the same in blocks, so streams are as dense in every world size.
        double cell = Math.max(cellBase, Math.max(box[2] - box[0], box[3] - box[1]) / maximumCells);
        double head = Math.max(1, channelArea * (cellBase / cell) * (cellBase / cell));
        int x0 = (int) Math.floor(box[0] / cell), z0 = (int) Math.floor(box[1] / cell);
        int nx = (int) Math.floor(box[2] / cell) - x0 + 1,
                nz = (int) Math.floor(box[3] / cell) - z0 + 1;
        int total = nx * nz;
        // Receiving water reads the hydrology plan of every own-land cell. Those plans are built
        // first, together, so rows find them built instead of parking on the row building one.
        int[] owners = planCells(zs, rows, coarse, box);
        plans.prepare(owners, owners.length / 2);
        // Grid: each cell's own land, ground (intent height), routing height, rain and receivers.
        boolean[] own = new boolean[total], marine = new boolean[total], sink = new boolean[total];
        double[] ground = new double[total], route = new double[total], rain = new double[total];
        double[] warmth = new double[total], moisture = new double[total];
        double[] filled = new double[total];
        double water = heights.waterTopY;
        // Rows run in parallel: every cell is a pure function of its coordinates, written to its
        // own index (the hydrology plans it reads are built once, whichever row asks first).
        IntStream.range(0, nz)
                .parallel()
                .forEach(
                        j -> {
                            TerrainMap.BaseSample s = new TerrainMap.BaseSample();
                            for (int i = 0; i < nx; i++) {
                                int k = j * nx + i;
                                double px = (x0 + i + .5) * cell, pz = (z0 + j + .5) * cell;
                                intent.sample(px, pz, s);
                                marine[k] = s.ocean.marine;
                                if (marine[k] || s.ocean.owner != owner) continue;
                                own[k] = true;
                                ground[k] = s.shore.height;
                                route[k] = ground[k] + relief(px, pz);
                                rain[k] = runoff(s.climate.moisture);
                                warmth[k] = s.climate.celsius;
                                moisture[k] = s.climate.moisture;
                                double level = receiving.applyAsDouble(px, pz);
                                if (!Double.isNaN(level)) {
                                    sink[k] = true;
                                    filled[k] = level;
                                }
                            }
                        });
        boolean any = false;
        for (boolean o : own) any |= o;
        if (!any) return Network.EMPTY;
        // Priority flood from the sea and receiving water: every own cell drains to the cell it
        // was reached from. Ties break by index, so the routing never depends on order.
        int[] down = new int[total], order = new int[total];
        Arrays.fill(down, -1);
        boolean[] seen = new boolean[total];
        PriorityQueue<long[]> queue =
                new PriorityQueue<>(
                        (a, b) ->
                                a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        for (int k = 0; k < total; k++) {
            if (sink[k]) {
                seen[k] = true;
                queue.add(new long[] {Math.round(filled[k] * 1024), k});
            } else if (marine[k] && touches(own, k, nx, nz)) {
                seen[k] = true;
                filled[k] = water;
                queue.add(new long[] {Math.round(water * 1024), k});
            }
        }
        int routed = 0;
        while (!queue.isEmpty()) {
            int k = (int) queue.poll()[1];
            if (own[k] && !sink[k]) order[routed++] = k;
            int i = k % nx, j = k / nx;
            for (int dj = -1; dj <= 1; dj++)
                for (int di = -1; di <= 1; di++) {
                    int ii = i + di, jj = j + dj;
                    if ((di | dj) == 0 || ii < 0 || jj < 0 || ii >= nx || jj >= nz) continue;
                    int m = jj * nx + ii;
                    if (seen[m] || !own[m]) continue;
                    seen[m] = true;
                    filled[m] = Math.max(route[m], filled[k] + 1 / 1024.0);
                    down[m] = k;
                    queue.add(new long[] {Math.round(filled[m] * 1024), m});
                }
        }
        // Rain-weighted area accumulates downstream, upstream cells first.
        double[] area = new double[total];
        for (int c = routed - 1; c >= 0; c--) {
            int k = order[c];
            area[k] += rain[k];
            int d = down[k];
            if (d >= 0 && own[d] && !sink[d]) area[d] += area[k];
        }
        boolean[] channel = new boolean[total], fed = new boolean[total];
        int[] main = new int[total];
        Arrays.fill(main, -1);
        for (int c = 0; c < routed; c++) {
            int k = order[c];
            channel[k] = area[k] >= head;
        }
        // Each channel cell continues the upstream channel that brings it the most water.
        for (int c = 0; c < routed; c++) {
            int k = order[c], d = down[k];
            if (!channel[k] || d < 0 || !channel[d]) continue;
            fed[d] = true;
            int m = main[d];
            if (m < 0 || area[k] > area[m] || (area[k] == area[m] && k < m)) main[d] = k;
        }
        // Trace each head downstream along its main line; a line ends at the sea, at receiving
        // water, or where it joins a larger channel.
        List<int[]> lines = new ArrayList<>();
        for (int c = routed - 1; c >= 0; c--) {
            int k = order[c];
            if (!channel[k] || fed[k]) continue;
            int[] line = new int[16];
            int n = 0;
            int at = k;
            while (true) {
                if (n + 2 > line.length) line = Arrays.copyOf(line, 2 * line.length);
                line[n++] = at;
                int d = down[at];
                if (d < 0) break;
                if (!channel[d] || main[d] != at) {
                    line[n++] = d;
                    break;
                }
                at = d;
            }
            if (n >= 2) lines.add(Arrays.copyOf(line, n));
        }
        // Larger channels first, so a tributary's junction already has its level.
        lines.sort(
                (a, b) -> {
                    double ea = area[a[Math.max(0, a.length - 2)]],
                            eb = area[b[Math.max(0, b.length - 2)]];
                    return ea != eb ? Double.compare(eb, ea) : Integer.compare(a[0], b[0]);
                });
        double km2 = cell * cell / 1e6, root0 = Math.sqrt(head * km2);
        int[] lineOf = new int[total], indexOf = new int[total];
        Arrays.fill(lineOf, -1);
        List<Channel> channels = new ArrayList<>();
        double[][] smoothX = new double[lines.size()][], smoothZ = new double[lines.size()][];
        double[][] smoothLevel = new double[lines.size()][], rawLevel = new double[lines.size()][];
        for (int li = 0; li < lines.size(); li++) {
            int[] line = lines.get(li);
            int n = line.length, interior = n - 1, end = line[n - 1];
            double[] px = new double[n], pz = new double[n], lv = new double[n];
            double[] rad = new double[n], dep = new double[n], gr = new double[n];
            double[] cel = new double[n], mst = new double[n];
            for (int p = 0; p < n; p++) {
                px[p] = point(x0 + line[p] % nx, z0 + line[p] / nx, 0, cell);
                pz[p] = point(x0 + line[p] % nx, z0 + line[p] / nx, 1, cell);
            }
            // Level: the running minimum of the ground less one block, held at the water it
            // joins.
            double run = Double.POSITIVE_INFINITY;
            for (int p = 0; p < interior; p++) {
                run = Math.max(water, Math.min(run, ground[line[p]] - 1));
                lv[p] = run;
                gr[p] = ground[line[p]];
                cel[p] = warmth[line[p]];
                mst[p] = moisture[line[p]];
                double root = Math.sqrt(area[line[p]] * km2) - root0;
                rad[p] = .5 * Math.min(widthMax, widthHead + widthPerRoot * Math.max(0, root));
                dep[p] = Math.min(depthMax, depthHead + depthPerRoot * Math.max(0, root));
            }
            double endLevel = water;
            int joins = -1;
            if (sink[end]) endLevel = filled[end];
            else if (own[end] && lineOf[end] >= 0) {
                joins = lineOf[end];
                endLevel = rawLevel[joins][indexOf[end]];
            }
            for (int p = 0; p < interior; p++) lv[p] = Math.max(lv[p], endLevel);
            lv[interior] = endLevel;
            rawLevel[li] = lv.clone();
            gr[interior] = gr[interior - 1];
            cel[interior] = cel[interior - 1];
            mst[interior] = mst[interior - 1];
            rad[interior] = rad[interior - 1];
            dep[interior] = dep[interior - 1];
            // A main line claims its interior cells, so its tributaries can find their junction.
            for (int p = 0; p < interior; p++)
                if (lineOf[line[p]] < 0) {
                    lineOf[line[p]] = li;
                    indexOf[line[p]] = p;
                }
            double mouthX = marine[end] ? px[interior] : Double.NaN,
                    mouthZ = marine[end] ? pz[interior] : Double.NaN;
            // A shoaling coast drowns a river's mouth into a funnel estuary.
            double flare = 1;
            if (marine[end] && 2 * rad[interior - 1] >= riverWidth && estuaryLength > 0) {
                TerrainMap.BaseSample s = new TerrainMap.BaseSample();
                intent.sample(px[interior], pz[interior], s);
                flare = 1 + (estuaryFlare - 1) * Rules.smooth(s.ocean.shoaling);
                double toMouth = 0;
                for (int p = interior; p >= 0; p--) {
                    if (p < interior) toMouth += Math.hypot(px[p + 1] - px[p], pz[p + 1] - pz[p]);
                    if (toMouth >= estuaryLength || flare <= 1) break;
                    rad[p] *= 1 + (flare - 1) * Rules.smooth(1 - toMouth / estuaryLength);
                }
            }
            long id = SeededNoise.mix(seed ^ owner ^ (li * 0x9e3779b97f4a7c15L));
            double[][] smooth =
                    meander(
                            chaikin(new double[][] {px, pz, lv, rad, dep, gr, cel, mst}, passes),
                            id,
                            marine[end]);
            double[] cx = smooth[0], cz = smooth[1], cl = smooth[2];
            // A tributary ends on its main channel's centreline, at that channel's level.
            if (joins >= 0) snap(cx, cz, cl, smoothX[joins], smoothZ[joins], smoothLevel[joins]);
            double[] along = smooth[8], gradient = smooth[9], radius = smooth[3];
            double[] riffle = pools(cl, along, radius);
            smoothX[li] = cx;
            smoothZ[li] = cz;
            smoothLevel[li] = cl;
            double[] valley = new double[cx.length], braid = new double[cx.length];
            boolean river = false;
            for (int p = 0; p < cx.length; p++) {
                radius[p] = Math.max(.75, radius[p]);
                river |= 2 * radius[p] >= riverWidth;
            }
            for (int p = 0; p < cx.length; p++) {
                if (river)
                    valley[p] = valleyRatio * Math.min(maxCut, Math.max(0, smooth[5][p] - cl[p]));
                braid[p] = braid(2 * radius[p], gradient[p], smooth[6][p], smooth[7][p]);
            }
            // An estuary's funnel is never braided: braids fade out as it flares.
            if (flare > 1)
                for (int p = cx.length - 1; p >= 0; p--) {
                    double toMouth = along[cx.length - 1] - along[p];
                    if (toMouth >= estuaryLength) break;
                    braid[p] *=
                            1
                                    - (flare - 1)
                                            / (estuaryFlare - 1)
                                            * Rules.smooth(2 - 2 * toMouth / estuaryLength);
                }
            // The weave's phase accumulates, so its wavelength can follow the bed.
            double[] weave = new double[cx.length];
            for (int p = 1; p < cx.length; p++)
                weave[p] =
                        weave[p - 1]
                                + (along[p] - along[p - 1])
                                        / (braidWave * radius[p] * (1 + braid[p] * braidSpan));
            channels.add(
                    new Channel(
                            id,
                            cx,
                            cz,
                            cl,
                            radius,
                            smooth[4],
                            valley,
                            weave,
                            gradient,
                            braid,
                            smooth[10],
                            riffle,
                            river,
                            reach,
                            braidSpan,
                            mouthX,
                            mouthZ));
        }
        return index(channels.toArray(new Channel[0]), box);
    }

    /** Moves a line's last point onto the nearest point of a main centreline, at its level. */
    private static void snap(
            double[] x, double[] z, double[] level, double[] mx, double[] mz, double[] ml) {
        int last = x.length - 1;
        double best = Double.POSITIVE_INFINITY, bx = x[last], bz = z[last], bl = level[last];
        for (int i = 0; i < mx.length - 1; i++) {
            double vx = mx[i + 1] - mx[i], vz = mz[i + 1] - mz[i], length2 = vx * vx + vz * vz;
            double t =
                    length2 == 0
                            ? 0
                            : Math.max(
                                    0,
                                    Math.min(
                                            1,
                                            ((x[last] - mx[i]) * vx + (z[last] - mz[i]) * vz)
                                                    / length2));
            double px = mx[i] + t * vx,
                    pz = mz[i] + t * vz,
                    d = Math.hypot(x[last] - px, z[last] - pz);
            if (d < best) {
                best = d;
                bx = px;
                bz = pz;
                bl = ml[i] + t * (ml[i + 1] - ml[i]);
            }
        }
        x[last] = bx;
        z[last] = bz;
        for (int i = 0; i < last; i++) level[i] = Math.max(level[i], bl);
        level[last] = bl;
    }

    /**
     * Lays meanders along a smoothed channel ({x, z, level, radius, depth, ground, celsius,
     * moisture}): resamples it every stepBlocks and moves each point sideways by a wave whose
     * length is wavelengthInWidths channel widths (at least minimumWavelengthBlocks, drifting 30%
     * along the course) and whose amplitude is amplitudeInWavelengths of it on flat ground, easing
     * to none where the channel falls faster than steepGradient, with a third harmonic (skew) so
     * bends lean downstream. The wave fades in from the head and out to the end over one
     * wavelength (over the estuary at a sea mouth), so a channel still meets its main channel or
     * the sea where it did. Appends the distance along the course, the fall per block over 64
     * blocks, and each point's point-bar side and strength (positive on the side the meander's
     * normal points to, where the bend's inside lies).
     */
    private double[][] meander(double[][] a, long id, boolean sea) {
        double[] x = a[0], z = a[1];
        int n = x.length;
        double[] s = new double[n];
        for (int i = 1; i < n; i++) s[i] = s[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
        double total = s[n - 1];
        int m = total > 0 ? (int) Math.ceil(total / step) + 1 : 2;
        double[][] b = new double[a.length + 3][m];
        // Resample every attribute at even distances along the course.
        for (int k = 0, i = 0; k < m; k++) {
            double at = Math.min(total, k * total / (m - 1));
            while (i < n - 2 && s[i + 1] < at) i++;
            double t = s[i + 1] > s[i] ? (at - s[i]) / (s[i + 1] - s[i]) : 0;
            for (int c = 0; c < a.length; c++) b[c][k] = a[c][i] + t * (a[c][i + 1] - a[c][i]);
        }
        double[] along = b[a.length], gradient = b[a.length + 1], bar = b[a.length + 2];
        double seg = total / (m - 1);
        int span = seg > 0 ? Math.max(1, (int) Math.round(32 / seg)) : 1;
        for (int k = 0; k < m; k++) {
            along[k] = k * seg;
            int lo = Math.max(0, k - span), hi = Math.min(m - 1, k + span);
            gradient[k] = hi > lo && seg > 0 ? (b[2][lo] - b[2][hi]) / ((hi - lo) * seg) : 0;
        }
        if (total < 2 * minimumWave || amplitude <= 0) return b;
        double[] bx = b[0].clone(), bz = b[1].clone();
        double phase = 2 * Math.PI * SeededNoise.unit(SeededNoise.mix(id));
        double fadeEnd = sea ? Math.max(estuaryLength, 1) : 0;
        for (int k = 0; k < m; k++) {
            double width = 2 * b[3][k];
            double drift = wander.sample(along[k] / wanderBlocks, (id & 0xffff) * 1.37);
            double wave = Math.max(minimumWave, meanderWaves * width) * (1 + .3 * drift);
            if (k > 0) phase += 2 * Math.PI * seg / wave;
            // Flat reaches wind, steep ones run straight.
            double flat =
                    1 - Rules.smooth((gradient[k] - flatGradient) / (steepGradient - flatGradient));
            double strength =
                    .6 + .4 * wander.sample(along[k] / wanderBlocks + 17.3, (id & 0xffff) * .71);
            double fade =
                    Rules.smooth(along[k] / wave)
                            * Rules.smooth((total - along[k]) / Math.max(wave, fadeEnd));
            double wave1 = Math.sin(phase);
            double offset =
                    amplitude
                            * wave
                            * flat
                            * strength
                            * fade
                            * (wave1 + skew * Math.sin(3 * phase));
            // The bend bulges toward the offset; its point bar lies on the inside.
            bar[k] = -Math.signum(offset) * Math.abs(wave1) * flat * fade;
            // The normal from the unmeandered course around this point.
            int p = Math.max(0, k - 1), q = Math.min(m - 1, k + 1);
            double tx = bx[q] - bx[p], tz = bz[q] - bz[p], length = Math.hypot(tx, tz);
            if (length == 0) continue;
            b[0][k] = bx[k] - tz / length * offset;
            b[1][k] = bz[k] + tx / length * offset;
        }
        return b;
    }

    /**
     * Pools and riffles: a channel's water holds flat in pools and falls in short riffles, instead
     * of a block at a time all along its course. Each pool-riffle unit runs at least
     * minimumInWidths channel widths (and minimumBlocks), then until the course has fallen
     * dropBlocks; its riffle at the foot takes the fall over riffleBlocksPerDrop blocks per block
     * (at most half the unit). A pool holds the level of its riffle's top, so the water never
     * stands above the course's own level, and the last point keeps the junction's level. Returns
     * the riffle marks (1 on a riffle).
     */
    private double[] pools(double[] level, double[] along, double[] radius) {
        int n = level.length;
        double[] l = level.clone(), riffle = new double[n];
        int[] top = new int[n + 1], foot = new int[n + 1];
        int units = 0;
        for (int start = 0; start < n - 1; ) {
            double spacing = Math.max(poolMinimum, poolWidths * 2 * radius[start]);
            int end = start + 1;
            while (end < n - 1
                    && (along[end] - along[start] < spacing || l[start] - l[end] < poolDrop)) end++;
            double run =
                    Math.min((l[start] - l[end]) * riffleRun, .5 * (along[end] - along[start]));
            int r = end;
            while (r > start + 1 && along[end] - along[r - 1] <= run) r--;
            top[units] = r;
            foot[units++] = end;
            start = end;
        }
        for (int u = 0, start = 0; u < units; u++) {
            int r = top[u], end = foot[u];
            double pool = l[r], next = u + 1 < units ? l[top[u + 1]] : l[n - 1];
            for (int k = start; k < r; k++) level[k] = pool;
            for (int k = r; k < end; k++) {
                double t = (along[k] - along[r]) / Math.max(1e-9, along[end] - along[r]);
                level[k] = Math.min(l[k], pool + (next - pool) * t);
                riffle[k] = pool - next >= 1 ? 1 : 0;
            }
            start = end;
        }
        level[n - 1] = l[n - 1];
        return riffle;
    }

    /**
     * How braided a channel runs (0 to 1): wide (fromWidthBlocks to fullWidthBlocks), laden with
     * sediment (frozen or arid ground) and falling between the gradient range's bounds.
     */
    private double braid(double width, double gradient, double celsius, double moisture) {
        double wide = Rules.smooth((width - braidStart) / (braidFull - braidStart));
        if (wide <= 0) return 0;
        double laden =
                Math.max(
                        Rules.smooth((braidCold - celsius) / 4),
                        Rules.smooth((braidDry - moisture) / .08));
        double slope =
                Rules.smooth((gradient - braidLow) / braidLow)
                        * (1 - Rules.smooth((gradient - braidHigh) / braidHigh));
        return wide * laden * slope;
    }

    /** Chaikin corner cutting over parallel attribute arrays, keeping both ends. */
    private static double[][] chaikin(double[][] a, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            int n = a[0].length;
            if (n < 3) return a;
            double[][] b = new double[a.length][2 * (n - 1)];
            for (int k = 0; k < a.length; k++) {
                b[k][0] = a[k][0];
                int o = 1;
                for (int i = 0; i < n - 1; i++) {
                    if (i > 0) b[k][o++] = .75 * a[k][i] + .25 * a[k][i + 1];
                    if (i < n - 2) b[k][o++] = .25 * a[k][i] + .75 * a[k][i + 1];
                }
                b[k][o++] = a[k][n - 1];
                b[k] = Arrays.copyOf(b[k], o);
            }
            a = b;
        }
        return a;
    }

    private static boolean touches(boolean[] own, int k, int nx, int nz) {
        int i = k % nx, j = k / nx;
        for (int dj = -1; dj <= 1; dj++)
            for (int di = -1; di <= 1; di++) {
                int ii = i + di, jj = j + dj;
                if (ii >= 0 && jj >= 0 && ii < nx && jj < nz && own[jj * nx + ii]) return true;
            }
        return false;
    }

    /** Bins every segment by the reach of its water, bank and valley. */
    private Network index(Channel[] channels, double[] box) {
        double originX = box[0] - reach, originZ = box[1] - reach;
        int bins = (int) Math.ceil((box[2] - box[0] + 2 * reach) / binBlocks) + 1;
        int binsZ = (int) Math.ceil((box[3] - box[1] + 2 * reach) / binBlocks) + 1;
        List<List<Integer>> lists = new ArrayList<>(bins * binsZ);
        for (int i = 0; i < bins * binsZ; i++) lists.add(null);
        for (int c = 0; c < channels.length; c++) {
            Channel ch = channels[c];
            for (int i = 0; i < ch.x.length - 1; i++) {
                double r =
                        Math.max(
                                                ch.radius[i] * (1 + ch.braid[i] * braidSpan),
                                                ch.radius[i + 1]
                                                        * (1 + ch.braid[i + 1] * braidSpan))
                                        * (1 + 2 * bankScale)
                                + 2
                                + Math.max(valleyCap, Math.max(ch.valley[i], ch.valley[i + 1]));
                int ax =
                        (int)
                                Math.floor(
                                        (Math.min(ch.x[i], ch.x[i + 1]) - r - originX) / binBlocks);
                int bx =
                        (int)
                                Math.floor(
                                        (Math.max(ch.x[i], ch.x[i + 1]) + r - originX) / binBlocks);
                int az =
                        (int)
                                Math.floor(
                                        (Math.min(ch.z[i], ch.z[i + 1]) - r - originZ) / binBlocks);
                int bz =
                        (int)
                                Math.floor(
                                        (Math.max(ch.z[i], ch.z[i + 1]) + r - originZ) / binBlocks);
                for (int zz = Math.max(0, az); zz <= Math.min(binsZ - 1, bz); zz++)
                    for (int xx = Math.max(0, ax); xx <= Math.min(bins - 1, bx); xx++) {
                        List<Integer> l = lists.get(zz * bins + xx);
                        if (l == null) lists.set(zz * bins + xx, l = new ArrayList<>());
                        l.add(c << 16 | i);
                    }
            }
        }
        // Rivers alone, binned by their reserved support, for clearance; and their mouths.
        List<List<Integer>> riverLists = new ArrayList<>(bins * binsZ);
        for (int i = 0; i < bins * binsZ; i++) riverLists.add(null);
        List<Double> mouths = new ArrayList<>();
        for (int c = 0; c < channels.length; c++) {
            Channel ch = channels[c];
            if (!ch.river) continue;
            if (!Double.isNaN(ch.mouthX)) {
                mouths.add(ch.mouthX);
                mouths.add(ch.mouthZ);
            }
            for (int i = 0; i < ch.x.length - 1; i++) {
                double r =
                        Math.max(
                                                ch.radius[i] * (1 + ch.braid[i] * braidSpan),
                                                ch.radius[i + 1]
                                                        * (1 + ch.braid[i + 1] * braidSpan))
                                        * (1 + bankScale)
                                + 2
                                + Math.max(ch.valley[i], ch.valley[i + 1]);
                int ax =
                        (int)
                                Math.floor(
                                        (Math.min(ch.x[i], ch.x[i + 1]) - r - originX) / binBlocks);
                int bx =
                        (int)
                                Math.floor(
                                        (Math.max(ch.x[i], ch.x[i + 1]) + r - originX) / binBlocks);
                int az =
                        (int)
                                Math.floor(
                                        (Math.min(ch.z[i], ch.z[i + 1]) - r - originZ) / binBlocks);
                int bz =
                        (int)
                                Math.floor(
                                        (Math.max(ch.z[i], ch.z[i + 1]) + r - originZ) / binBlocks);
                for (int zz = Math.max(0, az); zz <= Math.min(binsZ - 1, bz); zz++)
                    for (int xx = Math.max(0, ax); xx <= Math.min(bins - 1, bx); xx++) {
                        List<Integer> l = riverLists.get(zz * bins + xx);
                        if (l == null) riverLists.set(zz * bins + xx, l = new ArrayList<>());
                        l.add(c << 16 | i);
                    }
            }
        }
        double[] mouthArray = new double[mouths.size()];
        for (int i = 0; i < mouthArray.length; i++) mouthArray[i] = mouths.get(i);
        return new Network(
                channels,
                originX,
                originZ,
                binBlocks,
                bins,
                binsZ,
                array(lists),
                array(riverLists),
                mouthArray);
    }

    private static int[][] array(List<List<Integer>> lists) {
        int[][] refs = new int[lists.size()][];
        for (int i = 0; i < refs.length; i++) {
            List<Integer> l = lists.get(i);
            if (l == null) refs[i] = NONE;
            else {
                refs[i] = new int[l.size()];
                for (int k = 0; k < refs[i].length; k++) refs[i][k] = l.get(k);
            }
        }
        return refs;
    }
}
