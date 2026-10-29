package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contribution.continents.ContinentalKernels;
import cc.orthrus.riven.contribution.continents.ContinentalKernels.Family;

import com.google.gson.JsonObject;

/** Earlier geographic ownership and coastal blend. No water admission or surface reconstruction. */
public final class ContinentalOutlineMap {
    static final Rules RULES = Rules.load("riven-continents.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    private final ContinentalKernels.Kernel kernel;

    /** The world's single outline family; null when every continent draws its own. */
    public final Family family;

    public final double spacing, landBlend;

    public ContinentalOutlineMap(long worldSeed, TerrainHeightReference heights) {
        this(worldSeed, heights, WorldScale.DEFAULT);
    }

    public ContinentalOutlineMap(long worldSeed, TerrainHeightReference heights, WorldScale world) {
        this(worldSeed, heights, RULES.json, world);
    }

    ContinentalOutlineMap(long worldSeed, TerrainHeightReference heights, JsonObject j) {
        this(worldSeed, heights, j, WorldScale.DEFAULT);
    }

    /**
     * The layout scale multiplies the lattice, radius, warp, bridges and bays (corridors follow in
     * spacings); coast detail, corridor width and the coastal transition stay physical.
     */
    ContinentalOutlineMap(
            long worldSeed, TerrainHeightReference heights, JsonObject j, WorldScale world) {
        if (heights == null || j == null || world == null) throw new NullPointerException();
        double layout = world.layout;
        long seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        ContinentalKernels.Reference reference =
                new ContinentalKernels.Reference(
                        heights.seaLevel,
                        heights.waterTopY,
                        heights.lowestSurface,
                        heights.highestSurface,
                        heights.feature("coastal_plain").width.targetAverage,
                        TerrainScale.HORIZONTAL,
                        heights.fingerprint);
        JsonObject p = j.getAsJsonObject("tuning");
        ContinentalKernels.Tuning tuning =
                new ContinentalKernels.Tuning(
                        layout * number(p, "spacing"),
                        layout * number(p, "radius"),
                        // Below the 8 km tuning the base coast octaves shrink with the layout.
                        Math.min(1, layout) * number(p, "detailAmplitude"),
                        Math.min(1, layout) * number(p, "detailWavelength"),
                        layout * number(p, "warpAmplitude"),
                        layout * number(p, "warpWavelength"),
                        number(p, "occurrence"),
                        number(p, "eligibilityFloor"),
                        layout * number(p, "bridgeHalfWidth"),
                        layout * number(p, "bayDepth"),
                        layout * number(p, "bayHalfWidth"),
                        number(p, "corridorHalfWidth"),
                        // The coastal blend shrinks with the layout below the 8 km tuning too.
                        Math.min(1, layout)
                                * reference.coastalPlainWidth
                                * reference.horizontalScale
                                * Rules.number(p, "transitionScale", .25, 2),
                        p.has("corridorOffsetInSpacings")
                                ? layout
                                        * number(p, "spacing")
                                        * Rules.number(p, "corridorOffsetInSpacings", 0, 100)
                                : 0,
                        layout
                                * number(p, "spacing")
                                * (p.has("corridorPeriodInSpacings")
                                        ? Rules.number(p, "corridorPeriodInSpacings", 1, 100)
                                        : 3),
                        p.has("stratifiedOccupancy")
                                ? (int) Rules.number(p, "stratifiedOccupancy", 0, 4)
                                : 0);
        String selected = j.get("family").getAsString();
        ContinentalKernels.Earlier earlier = new Prior(seed, tuning.spacing);
        JsonObject styles = j.getAsJsonObject("styles");
        ContinentalKernels.HarmonicStyle harmonic = harmonic(styles);
        ContinentalKernels.SpineStyle spine = spine(styles);
        ContinentalKernels.CellularStyle cellular = cellular(styles);
        ContinentalKernels.PotentialStyle potential = potential(styles);
        if ("PER_CONTINENT".equals(selected)) {
            family = null;
            kernel =
                    new ContinentalKernels.Varied(
                            seed,
                            tuning,
                            reference,
                            earlier,
                            variation(j, layout),
                            harmonic,
                            spine,
                            cellular,
                            potential);
        } else {
            if ("WORLD_SEEDED".equals(selected)) {
                int index =
                        Rules.choose(
                                weights(j),
                                SeededNoise.unit(SeededNoise.mix(seed ^ 0x434f4e5446414d33L)));
                if (index < 0) throw new IllegalStateException("No continental family enabled");
                family = Family.values()[index];
            } else family = Family.valueOf(selected);
            switch (family) {
                case HARMONIC:
                    kernel =
                            new ContinentalKernels.Harmonic(
                                    seed, tuning, reference, earlier, harmonic);
                    break;
                case SPINE:
                    kernel = new ContinentalKernels.Spine(seed, tuning, reference, earlier, spine);
                    break;
                case CELLULAR:
                    kernel =
                            new ContinentalKernels.Cellular(
                                    seed, tuning, reference, earlier, cellular);
                    break;
                case POTENTIAL:
                    kernel =
                            new ContinentalKernels.Potential(
                                    seed, tuning, reference, earlier, potential);
                    break;
                default:
                    throw new IllegalStateException("Continental family");
            }
        }
        spacing = tuning.spacing;
        landBlend = tuning.transitionWidth;
    }

    private static double[] weights(JsonObject j) {
        double[] weights = new double[Family.values().length];
        for (int i = 0; i < weights.length; i++)
            weights[i] =
                    Rules.number(
                            j.getAsJsonObject("familyWeights"), Family.values()[i].name(), 0, 10);
        return weights;
    }

    /** Per-continent draws; the shelter depth is a layout length and follows the world scale. */
    private static ContinentalKernels.Variation variation(JsonObject j, double layout) {
        JsonObject v = j.getAsJsonObject("variation");
        return new ContinentalKernels.Variation(
                Rules.number(v, "anchorJitter", 0, .4),
                Rules.number(v, "sizeMin", .4, 2),
                Rules.number(v, "sizeMax", .4, 2),
                Rules.number(v, "sizeSplit", .4, 2),
                Rules.number(v, "largeShare", 0, 1),
                Rules.number(v, "elongationMax", 1, 4),
                Rules.number(v, "roughnessMin", 0, 4),
                Rules.number(v, "roughnessMax", 0, 4),
                Rules.number(v, "bayProbability", 0, 1),
                layout * Rules.number(v, "shelterReach", 64, 4000),
                Rules.number(v, "shelterFloor", 0, 4),
                Rules.number(v, "shelterConcavity", .05, 4),
                layout * Rules.number(v, "straitBlend", 8, 2000),
                weights(j),
                skeleton(j),
                layout);
    }

    /** Crest lines per continent; absent means none. Lengths are in continent radii. */
    private static ContinentalKernels.Skeleton skeleton(JsonObject j) {
        if (!j.has("skeleton")) return null;
        JsonObject k = j.getAsJsonObject("skeleton");
        return new ContinentalKernels.Skeleton(
                Rules.number(k, "rangeShare", 0, 1),
                Rules.number(k, "strengthMin", 0, 2),
                Rules.number(k, "strengthMax", 0, 2),
                Rules.number(k, "crestHalfWidthInRadii", .05, 1),
                Rules.number(k, "crestLengthInRadii", 0, 1.5),
                Rules.number(k, "crestBendInRadii", 0, .6),
                Rules.number(k, "coastClearInRadii", .05, 1));
    }

    private static ContinentalKernels.HarmonicStyle harmonic(JsonObject styles) {
        JsonObject h = styles.getAsJsonObject("HARMONIC");
        return new ContinentalKernels.HarmonicStyle(
                number(h, "harmonicStrength"), number(h, "satelliteScale"));
    }

    private static ContinentalKernels.SpineStyle spine(JsonObject styles) {
        JsonObject s = styles.getAsJsonObject("SPINE");
        return new ContinentalKernels.SpineStyle(number(s, "widthScale"), number(s, "spurScale"));
    }

    private static ContinentalKernels.CellularStyle cellular(JsonObject styles) {
        JsonObject c = styles.getAsJsonObject("CELLULAR");
        return new ContinentalKernels.CellularStyle(
                number(c, "insetScale"), number(c, "connectionProbability"));
    }

    private static ContinentalKernels.PotentialStyle potential(JsonObject styles) {
        JsonObject q = styles.getAsJsonObject("POTENTIAL");
        return new ContinentalKernels.PotentialStyle(
                number(q, "mainlandThreshold"), number(q, "islandThreshold"));
    }

    private static double number(JsonObject j, String key) {
        double n = j.get(key).getAsDouble();
        if (!Double.isFinite(n)) throw new IllegalStateException(key);
        return n;
    }

    /** Explicit geographic opportunity fields, not terrain, rainfall or a tectonic reconstruction. */
    private static final class Prior implements ContinentalKernels.Earlier {
        final SeededNoise admission, islands;
        final double scale;

        Prior(long seed, double scale) {
            admission = new SeededNoise(SeededNoise.mix(seed ^ 0x434f4e5450524931L));
            islands = new SeededNoise(SeededNoise.mix(seed ^ 0x434f4e5449534c31L));
            this.scale = scale;
        }

        public double suitability(double x, double z) {
            return Math.max(
                    0,
                    Math.min(1, .5 + .5 * admission.sample(x / (scale * 1.5), z / (scale * 1.5))));
        }

        public double islandBias(double x, double z) {
            return Math.max(
                    0,
                    Math.min(
                            1,
                            .15
                                    + .7
                                            * (.5
                                                    + .5
                                                            * islands.sample(
                                                                    x / (scale * .8) + .19,
                                                                    z / (scale * .8) + .73))));
        }

        public double marineClearance(double x, double z) {
            return scale;
        }
    }

    /** Reusable caller-owned observations. The score is not a signed distance or a water proof. */
    public static final class Sample {
        private final ContinentalKernels.Sample value = new ContinentalKernels.Sample();
        private final ContinentalKernels.Scratch work = new ContinentalKernels.Scratch();

        public double coastCoordinate() {
            return value.score;
        }

        public double landWeight() {
            return value.landWeight;
        }

        public boolean marine() {
            return value.marine;
        }

        public long owner() {
            return value.owner;
        }

        public boolean archipelago() {
            return value.archipelago;
        }

        public ContinentalKernels.Reason reason() {
            return value.reason;
        }

        /** The largest crest weight at the column and its world gradient (sampleCrest only). */
        public double crest() {
            return value.crest;
        }

        public double crestDx() {
            return value.crestDx;
        }

        public double crestDz() {
            return value.crestDz;
        }
    }

    public void sample(double x, double z, Sample out) {
        if (out == null) throw new NullPointerException("out");
        // This sample exposes no shelter, so it skips the owner's concavity probes.
        sampleScore(x, z, out.value, out.work);
    }

    /** Full continental observations reuse their existing raw scratch without another point query. */
    void sample(
            double x, double z, ContinentalKernels.Sample out, ContinentalKernels.Scratch work) {
        kernel.sample(x, z, out, work);
    }

    /** The farthest any continent's shape reaches from its anchor; it grows with the layout. */
    public double largestSupport() {
        return kernel.largestSupport();
    }

    /** Caller-owned scratch for trunk-path and crest-cell queries. */
    public static final class TrunkScratch {
        final ContinentalKernels.Scratch work = new ContinentalKernels.Scratch();
        public final long[] cells = new long[2 * 49];
        public final double[] paths;

        public TrunkScratch(int capacity) {
            paths = new double[capacity];
        }
    }

    /** Crest-bearing continents whose support plus margin reaches a column, as cell pairs. */
    public int crestCells(double x, double z, double margin, TrunkScratch s) {
        Rules.coordinates(x, z);
        return kernel.crestCells(x, z, margin, s.work, s.cells);
    }

    /** Built continents whose support plus margin reaches a column, as cell pairs. */
    public int continentCells(double x, double z, double margin, TrunkScratch s) {
        Rules.coordinates(x, z);
        return kernel.continentCells(x, z, margin, s.work, s.cells);
    }

    /** A continent's owner id, with a conservative world-space box around its support. */
    public long continentBounds(long cellX, long cellZ, TrunkScratch s, double[] box) {
        return kernel.continentBounds(cellX, cellZ, s.work, box);
    }

    /** One continent's trunk river paths from its crest outward (see the kernel). */
    public int trunkPaths(
            long cellX, long cellZ, double spacing, double step, int maxSteps, TrunkScratch s) {
        return kernel.trunkPaths(cellX, cellZ, spacing, step, maxSteps, s.work, s.paths);
    }

    /** Caller-owned scratch for crest-node queries. */
    public static final class CrestScratch {
        final ContinentalKernels.Scratch work = new ContinentalKernels.Scratch();
        public final double[] nodes = new double[6 * 48];
    }

    /**
     * Crest-chain nodes within {@code radius} of a column, spaced about {@code spacing} along each
     * crest: (cell x, cell z, node index, world x, world z, tangent angle) in {@code s.nodes}.
     */
    public int crestNodes(double x, double z, double spacing, double radius, CrestScratch s) {
        Rules.coordinates(x, z);
        return kernel.crestNodes(x, z, spacing, radius, s.work, s.nodes);
    }

    /** Score and the crest weight with its gradient; shelter is left at zero. */
    public void sampleCrest(double x, double z, Sample out) {
        if (out == null) throw new NullPointerException("out");
        kernel.sampleCrest(x, z, out.value, out.work);
    }

    /** Score-only probe for finite differences; shelter is left at zero. */
    void sampleScore(
            double x, double z, ContinentalKernels.Sample out, ContinentalKernels.Scratch work) {
        kernel.sampleScore(x, z, out, work);
    }

    /** Restricted to the downstream continental adapter and native marine qualification. */
    ContinentalKernels.Kernel kernel() {
        return kernel;
    }

    /** Conservative clearance of the reserved scaffold; no connectivity plan is queried. */
    public double scaffoldClearance(double x, double z) {
        return kernel.scaffoldClearance(x, z) / kernel.warpStretch();
    }
}
