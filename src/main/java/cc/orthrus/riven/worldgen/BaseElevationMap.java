package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Earlier inland mean elevation and allocation, independent of relief and marine ownership. */
public final class BaseElevationMap {
    private static final Rules RULES = Rules.load("riven-base-elevation.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    private final PlateMap plates;
    private final TerrainHeightReference heights;
    private final TerrainHeightReference.Feature[] forms;
    private final SeededNoise regional;
    private final double regionalScale, stateOffset, upliftWeight, regionalWeight, crestWeight;
    private final double crestCap;
    public final int minimumSurfaceY;

    /** Continent crests raise the state; null leaves plates and regional noise alone. */
    private final ContinentalOutlineMap outline;

    public BaseElevationMap(long seed, PlateMap plates, TerrainHeightReference heights) {
        this(seed, plates, heights, RULES.json, null);
    }

    public BaseElevationMap(
            long seed,
            PlateMap plates,
            TerrainHeightReference heights,
            ContinentalOutlineMap outline) {
        this(seed, plates, heights, RULES.json, outline);
    }

    BaseElevationMap(long seed, PlateMap plates, TerrainHeightReference heights, JsonObject json) {
        this(seed, plates, heights, json, null);
    }

    BaseElevationMap(
            long seed,
            PlateMap plates,
            TerrainHeightReference heights,
            JsonObject json,
            ContinentalOutlineMap outline) {
        if (plates == null || heights == null || json == null) throw new NullPointerException();
        this.plates = plates;
        this.outline = outline;
        this.heights = heights;
        forms =
                new TerrainHeightReference.Feature[] {
                    heights.feature("inland_plain"), heights.feature("rolling_hills"),
                    heights.feature("foothills"), heights.feature("mountains")
                };
        for (TerrainHeightReference.Feature form : forms)
            if (heights.seaLevel + form.elevationOffset.min < heights.lowestSurface
                    || heights.seaLevel + form.elevationOffset.max > heights.highestSurface)
                throw new IllegalArgumentException(
                        "Terrain feature does not fit allocated height: " + form.id);
        minimumSurfaceY = heights.seaLevel + (int) forms[0].elevationOffset.min;
        regionalScale =
                forms[3].width.targetAverage
                        * Rules.number(json, "regionalInMountainWidths", .5, 16)
                        * TerrainScale.HORIZONTAL;
        stateOffset = Rules.number(json, "stateOffset", -2, 2);
        upliftWeight = Rules.number(json, "upliftWeight", 0, 2);
        regionalWeight = Rules.number(json, "regionalWeight", 0, 2);
        crestWeight = json.has("crestWeight") ? Rules.number(json, "crestWeight", 0, 2) : 0;
        crestCap =
                json.has("crestCap")
                        ? Rules.number(json, "crestCap", .05, 4)
                        : Double.POSITIVE_INFINITY;
        regional = new SeededNoise(SeededNoise.mix(seed ^ Rules.seed(json, "regionalSeed")));
    }

    /** Read-only observations in caller-owned scratch; allocation is not a final-terrain bound. */
    public static final class Sample {
        private final HeightSample regional = new HeightSample(), upliftSample = new HeightSample();
        private final ContinentalOutlineMap.Sample outline = new ContinentalOutlineMap.Sample();
        private double meanHeight, dx, dz, minimumY, maximumY, envelope, envelopeDx, envelopeDz;
        private double uplift, blend, intentDx, intentDz, crest, crestDx, crestDz;
        private double legacyPosition = -1;
        private String firstLandform, secondLandform;
        // Retain the interpolation terms so the LEGACY consumer keeps its exact arithmetic order.
        private double meanSpan, marginA, marginB, blendDerivative, stateDx, stateDz;

        public double meanHeight() {
            return meanHeight;
        }

        public double dx() {
            return dx;
        }

        public double dz() {
            return dz;
        }

        public double minimumY() {
            return minimumY;
        }

        public double maximumY() {
            return maximumY;
        }

        public double envelope() {
            return envelope;
        }

        public double envelopeDx() {
            return envelopeDx;
        }

        public double envelopeDz() {
            return envelopeDz;
        }

        public double uplift() {
            return uplift;
        }

        /** The column's continent crest weight (0 off any range) and its world gradient. */
        public double crest() {
            return crest;
        }

        public double crestDx() {
            return crestDx;
        }

        public double crestDz() {
            return crestDz;
        }

        public double blend() {
            return blend;
        }

        public double intentDx() {
            return intentDx;
        }

        public double intentDz() {
            return intentDz;
        }

        public double legacyPosition() {
            return legacyPosition;
        }

        public String firstLandform() {
            return firstLandform;
        }

        public String secondLandform() {
            return secondLandform;
        }

        double meanSpan() {
            return meanSpan;
        }

        double marginA() {
            return marginA;
        }

        double marginB() {
            return marginB;
        }

        double blendDerivative() {
            return blendDerivative;
        }

        double stateDx() {
            return stateDx;
        }

        double stateDz() {
            return stateDz;
        }
    }

    /** One noise evaluation and the bounded smooth plate stencil; no later maps or allocations. */
    public void sample(double x, double z, Sample out) {
        Rules.coordinates(x, z);
        if (out == null) throw new NullPointerException("out");
        regional.sample(x / regionalScale + .37, z / regionalScale + .61, out.regional);
        plates.uplift(x, z, out.upliftSample);
        double state =
                stateOffset
                        + upliftWeight * out.upliftSample.height
                        + regionalWeight * out.regional.height;
        double sx =
                upliftWeight * out.upliftSample.dx
                        + regionalWeight * out.regional.dx / regionalScale;
        double sz =
                upliftWeight * out.upliftSample.dz
                        + regionalWeight * out.regional.dz / regionalScale;
        // The largest continent crest here: one outline read with crests (cached cells).
        double crest = 0, crestDx = 0, crestDz = 0;
        if (outline != null && crestWeight > 0) {
            outline.sampleCrest(x, z, out.outline);
            crest = out.outline.crest();
            crestDx = out.outline.crestDx();
            crestDz = out.outline.crestDz();
            // Soft saturation: crestCap * (1 - exp(-weight * crest / crestCap)), so crest cores
            // form a dome below the top band instead of a table clamped at position 3.
            double raw = crestWeight * crest,
                    lift =
                            crestCap == Double.POSITIVE_INFINITY
                                    ? raw
                                    : crestCap * (1 - Math.exp(-raw / crestCap)),
                    slope = crestCap == Double.POSITIVE_INFINITY ? 1 : Math.exp(-raw / crestCap);
            state += lift;
            sx += slope * crestWeight * out.outline.crestDx();
            sz += slope * crestWeight * out.outline.crestDz();
        }
        double position = Math.max(0, Math.min(3, state * 3));
        out.legacyPosition = position;
        out.intentDx = state <= 0 || state >= 1 ? 0 : 3 * sx;
        out.intentDz = state <= 0 || state >= 1 ? 0 : 3 * sz;
        int index = Math.min(2, (int) position);
        double t = position - index, alpha = t * t * t * (t * (t * 6 - 15) + 10);
        double derivative = state <= 0 || state >= 1 ? 0 : 90 * t * t * (t - 1) * (t - 1);
        TerrainHeightReference.Feature a = forms[index], b = forms[index + 1];
        double meanA = heights.seaLevel + a.elevationOffset.targetAverage;
        double meanB = heights.seaLevel + b.elevationOffset.targetAverage;
        double marginA =
                Math.min(
                        a.elevationOffset.targetAverage - a.elevationOffset.min,
                        a.elevationOffset.max - a.elevationOffset.targetAverage);
        double marginB =
                Math.min(
                        b.elevationOffset.targetAverage - b.elevationOffset.min,
                        b.elevationOffset.max - b.elevationOffset.targetAverage);
        out.meanHeight = lerp(meanA, meanB, alpha);
        out.dx = (meanB - meanA) * derivative * sx;
        out.dz = (meanB - meanA) * derivative * sz;
        out.envelope = lerp(marginA, marginB, alpha);
        out.envelopeDx = (marginB - marginA) * derivative * sx;
        out.envelopeDz = (marginB - marginA) * derivative * sz;
        out.minimumY = heights.seaLevel + lerp(a.elevationOffset.min, b.elevationOffset.min, alpha);
        out.maximumY = heights.seaLevel + lerp(a.elevationOffset.max, b.elevationOffset.max, alpha);
        out.firstLandform = a.id;
        out.secondLandform = b.id;
        out.blend = alpha;
        out.uplift = out.upliftSample.height + crest;
        out.crest = crest;
        out.crestDx = crestDx;
        out.crestDz = crestDz;
        out.meanSpan = meanB - meanA;
        out.marginA = marginA;
        out.marginB = marginB;
        out.blendDerivative = derivative;
        out.stateDx = sx;
        out.stateDz = sz;
    }

    void clear(Sample out) {
        out.meanHeight = out.dx = out.dz = out.minimumY = out.maximumY = 0;
        out.envelope = out.envelopeDx = out.envelopeDz = 0;
        out.uplift = out.blend = out.intentDx = out.intentDz = 0;
        out.crest = out.crestDx = out.crestDz = 0;
        out.meanSpan =
                out.marginA = out.marginB = out.blendDerivative = out.stateDx = out.stateDz = 0;
        out.legacyPosition = -1;
        out.firstLandform = out.secondLandform = null;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
