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
    private final double regionalScale, stateOffset, upliftWeight, regionalWeight, beltWeight;
    private final double beltCap;
    public final int minimumSurfaceY;

    public BaseElevationMap(long seed, PlateMap plates, TerrainHeightReference heights) {
        this(seed, plates, heights, RULES.json);
    }

    BaseElevationMap(long seed, PlateMap plates, TerrainHeightReference heights, JsonObject json) {
        if (plates == null || heights == null || json == null) throw new NullPointerException();
        this.plates = plates;
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
        beltWeight = json.has("beltWeight") ? Rules.number(json, "beltWeight", 0, 2) : 0;
        beltCap =
                json.has("beltCap")
                        ? Rules.number(json, "beltCap", .05, 4)
                        : Double.POSITIVE_INFINITY;
        regional = new SeededNoise(SeededNoise.mix(seed ^ Rules.seed(json, "regionalSeed")));
    }

    /**
     * The landform position (0 plain to 3 mountains) a ground height reads as: piecewise linear
     * through the four reference forms' mean heights, clamped to 0..3. Elevation classes (low,
     * middle, high) read the ground through it.
     */
    public double position(double height) {
        double previous = heights.seaLevel + forms[0].elevationOffset.targetAverage;
        if (height <= previous) return 0;
        for (int i = 1; i < forms.length; i++) {
            double next = heights.seaLevel + forms[i].elevationOffset.targetAverage;
            if (height <= next) return i - 1 + (height - previous) / (next - previous);
            previous = next;
        }
        return forms.length - 1;
    }

    /** Read-only observations in caller-owned scratch; allocation is not a final-terrain bound. */
    public static final class Sample {
        private final HeightSample regional = new HeightSample(), upliftSample = new HeightSample();
        private double meanHeight, uplift, blend, intentDx, intentDz;

        /** The intent's landform position (0..3 over the four reference forms); -1 at sea. */
        private double intentPosition = -1;

        public double intentPosition() {
            return intentPosition;
        }

        private String firstLandform, secondLandform;

        public double meanHeight() {
            return meanHeight;
        }

        public double uplift() {
            return uplift;
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

        public String firstLandform() {
            return firstLandform;
        }

        public String secondLandform() {
            return secondLandform;
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
        // The strongest mountain belt here (the plates' convergent boundaries).
        double belt = 0, beltDx = 0, beltDz = 0;
        if (beltWeight > 0) {
            PlateMap.Sample plate = plates.sample((int) Math.floor(x), (int) Math.floor(z));
            belt = plate.belt;
            beltDx = plate.beltDx;
            beltDz = plate.beltDz;
            // Soft saturation: beltCap * (1 - exp(-weight * belt / beltCap)), so belt cores form
            // a dome below the top band instead of a table clamped at position 3.
            double raw = beltWeight * belt,
                    lift =
                            beltCap == Double.POSITIVE_INFINITY
                                    ? raw
                                    : beltCap * (1 - StrictMath.exp(-raw / beltCap)),
                    slope =
                            beltCap == Double.POSITIVE_INFINITY
                                    ? 1
                                    : StrictMath.exp(-raw / beltCap);
            state += lift;
            sx += slope * beltWeight * beltDx;
            sz += slope * beltWeight * beltDz;
        }
        double position = Math.max(0, Math.min(3, state * 3));
        out.intentPosition = position;
        out.intentDx = state <= 0 || state >= 1 ? 0 : 3 * sx;
        out.intentDz = state <= 0 || state >= 1 ? 0 : 3 * sz;
        int index = Math.min(2, (int) position);
        double t = position - index, alpha = t * t * t * (t * (t * 6 - 15) + 10);
        double derivative = state <= 0 || state >= 1 ? 0 : 90 * t * t * (t - 1) * (t - 1);
        TerrainHeightReference.Feature a = forms[index], b = forms[index + 1];
        double meanA = heights.seaLevel + a.elevationOffset.targetAverage;
        double meanB = heights.seaLevel + b.elevationOffset.targetAverage;
        out.meanHeight = lerp(meanA, meanB, alpha);
        out.firstLandform = a.id;
        out.secondLandform = b.id;
        out.blend = alpha;
        // Readers of uplift want raising only: the signed uplift's positive part, and the belt.
        out.uplift = Math.max(0, out.upliftSample.height) + belt;
    }

    void clear(Sample out) {
        out.meanHeight = out.uplift = out.blend = out.intentDx = out.intentDz = 0;
        out.intentPosition = -1;
        out.firstLandform = out.secondLandform = null;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
