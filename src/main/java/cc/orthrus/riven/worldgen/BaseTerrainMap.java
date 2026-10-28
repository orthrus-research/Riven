package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Earlier inland height potential. Marine ownership, water and later shaping remain downstream. */
public final class BaseTerrainMap {
    private static final Rules RULES = Rules.load("riven-base-terrain.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    public enum Policy {
        LEGACY,
        REGIONAL
    }

    private final BaseElevationMap elevation;
    private final RegionalProfileMap profiles;
    private final SeededNoise body, detail, wide;
    private final double bodyScale, detailScale, wideScale;
    private final double legacyAmplitude, legacyBody, legacyFine;
    private final Policy policy;
    public final int minimumSurfaceY;

    public static Policy configuredPolicy() {
        return Policy.valueOf(RULES.json.get("policy").getAsString());
    }

    BaseTerrainMap(
            long seed,
            PlateMap plates,
            TerrainHeightReference heights,
            RegionalProfileMap profiles,
            Policy policy) {
        this(seed, new BaseElevationMap(seed, plates, heights), heights, profiles, policy);
    }

    BaseTerrainMap(
            long seed,
            BaseElevationMap elevation,
            TerrainHeightReference heights,
            RegionalProfileMap profiles,
            Policy policy) {
        if (elevation == null || heights == null || profiles == null || policy == null)
            throw new NullPointerException();
        this.elevation = elevation;
        this.profiles = profiles;
        this.policy = policy;
        JsonObject j = RULES.json;
        minimumSurfaceY = elevation.minimumSurfaceY;
        bodyScale =
                heights.feature("foothills").width.targetAverage
                        * Rules.number(j, "bodyInFoothillWidths", .5, 4)
                        * TerrainScale.HORIZONTAL;
        detailScale =
                heights.feature("rolling_hills").width.targetAverage
                        * Rules.number(j, "detailInHillWidths", .125, 2)
                        * TerrainScale.HORIZONTAL;
        wideScale = bodyScale * Rules.number(j, "wideInBodyScales", 2, 8);
        if (detailScale >= bodyScale || bodyScale >= wideScale)
            throw new IllegalStateException("Base spectral scales must descend");
        legacyAmplitude = Rules.number(j, "legacyAmplitudeFraction", 0, 1);
        legacyBody = Rules.number(j, "legacyBodyShare", 0, 1);
        legacyFine = Rules.number(j, "legacyFineShare", 0, 1);
        if (legacyBody + legacyFine != 1) throw new IllegalStateException("Legacy spectral shares");
        body = new SeededNoise(SeededNoise.mix(seed ^ Rules.seed(j, "bodySeed")));
        detail = new SeededNoise(SeededNoise.mix(seed ^ Rules.seed(j, "detailSeed")));
        wide = new SeededNoise(SeededNoise.mix(seed ^ Rules.seed(j, "wideSeed")));
    }

    /** Caller-owned scratch. Its local derivatives are not footprint or later-terrain bounds. */
    public static final class Sample {
        private final BaseElevationMap.Sample elevation = new BaseElevationMap.Sample();
        private final HeightSample body = new HeightSample(),
                detail = new HeightSample(),
                wide = new HeightSample();
        private final RegionalProfileMap.Sample region = new RegionalProfileMap.Sample();
        private double height, dx, dz, broadDx, broadDz;
        private double amplitude, noise, coarseHeight, fineContribution;

        public double height() {
            return height;
        }

        public double dx() {
            return dx;
        }

        public double dz() {
            return dz;
        }

        public double broadDx() {
            return broadDx;
        }

        public double broadDz() {
            return broadDz;
        }

        public double minimumY() {
            return elevation.minimumY();
        }

        public double maximumY() {
            return elevation.maximumY();
        }

        public double uplift() {
            return elevation.uplift();
        }

        public double blend() {
            return elevation.blend();
        }

        public double intentDx() {
            return elevation.intentDx();
        }

        public double intentDz() {
            return elevation.intentDz();
        }

        public double amplitude() {
            return amplitude;
        }

        public double noise() {
            return noise;
        }

        public double legacyPosition() {
            return elevation.legacyPosition();
        }

        public double meanHeight() {
            return elevation.meanHeight();
        }

        public double coarseHeight() {
            return coarseHeight;
        }

        public double fineContribution() {
            return fineContribution;
        }

        public String firstLandform() {
            return elevation.firstLandform();
        }

        public String secondLandform() {
            return elevation.secondLandform();
        }

        public RegionalProfileMap.Sample region() {
            return region;
        }

        public BaseElevationMap.Sample elevation() {
            return elevation;
        }
    }

    public Policy policy() {
        return policy;
    }

    public double wideScale() {
        return wideScale;
    }

    public double bodyScale() {
        return bodyScale;
    }

    public double detailScale() {
        return detailScale;
    }

    /** No marine admission, local plans, callbacks, caches or per-query objects. */
    public void sample(double x, double z, Sample out) {
        Rules.coordinates(x, z);
        if (out == null) throw new NullPointerException("out");
        elevation.sample(x, z, out.elevation);
        BaseElevationMap.Sample e = out.elevation;
        body.sample(x / bodyScale + 0.19, z / bodyScale + 0.73, out.body);
        detail.sample(x / detailScale + 0.43, z / detailScale + 0.29, out.detail);
        if (policy == Policy.LEGACY) {
            profiles.clear(out.region);
            // Preserve the former operation order, phases and derivatives for the comparator.
            double amplitudeA = legacyAmplitude * e.marginA(),
                    amplitudeB = legacyAmplitude * e.marginB();
            double amplitude = lerp(amplitudeA, amplitudeB, e.blend());
            double n = out.body.height * legacyBody + out.detail.height * legacyFine;
            out.amplitude = amplitude;
            out.noise = n;
            double nx =
                    out.body.dx / bodyScale * legacyBody + out.detail.dx / detailScale * legacyFine;
            double nz =
                    out.body.dz / bodyScale * legacyBody + out.detail.dz / detailScale * legacyFine;
            double blendSlope = e.meanSpan() + (amplitudeB - amplitudeA) * n;
            double broadBlendSlope =
                    e.meanSpan() + (amplitudeB - amplitudeA) * out.body.height * legacyBody;
            out.broadDx =
                    amplitude * out.body.dx / bodyScale * legacyBody
                            + broadBlendSlope * e.blendDerivative() * e.stateDx();
            out.broadDz =
                    amplitude * out.body.dz / bodyScale * legacyBody
                            + broadBlendSlope * e.blendDerivative() * e.stateDz();
            out.height = e.meanHeight() + amplitude * n;
            out.dx = amplitude * nx + blendSlope * e.blendDerivative() * e.stateDx();
            out.dz = amplitude * nz + blendSlope * e.blendDerivative() * e.stateDz();
            out.fineContribution = amplitude * out.detail.height * legacyFine;
            out.coarseHeight = e.meanHeight() + amplitude * out.body.height * legacyBody;
        } else {
            profiles.sample(x, z, out.region);
            wide.sample(x / wideScale + .53, z / wideScale + .17, out.wide);
            double envelope = e.envelope();
            double envelopeDx = e.envelopeDx();
            double envelopeDz = e.envelopeDz();
            double fraction = out.region.amplitudeFraction();
            double amplitude = envelope * fraction;
            double amplitudeDx = envelopeDx * fraction + envelope * out.region.amplitudeDx();
            double amplitudeDz = envelopeDz * fraction + envelope * out.region.amplitudeDz();
            double w = out.region.wideShare(), f = out.region.fineShare(), c = 1 - w - f;
            double coarse = w * out.wide.height + c * out.body.height;
            double n = coarse + f * out.detail.height;
            double coarseDx =
                    w * out.wide.dx / wideScale
                            + c * out.body.dx / bodyScale
                            + out.region.wideDx() * (out.wide.height - out.body.height)
                            - out.region.fineDx() * out.body.height;
            double coarseDz =
                    w * out.wide.dz / wideScale
                            + c * out.body.dz / bodyScale
                            + out.region.wideDz() * (out.wide.height - out.body.height)
                            - out.region.fineDz() * out.body.height;
            double nx =
                    coarseDx
                            + f * out.detail.dx / detailScale
                            + out.region.fineDx() * out.detail.height;
            double nz =
                    coarseDz
                            + f * out.detail.dz / detailScale
                            + out.region.fineDz() * out.detail.height;
            double meanDx = e.dx();
            double meanDz = e.dz();
            out.amplitude = amplitude;
            out.noise = n;
            out.coarseHeight = e.meanHeight() + amplitude * coarse;
            out.fineContribution = amplitude * f * out.detail.height;
            out.height = e.meanHeight() + amplitude * n;
            out.dx = meanDx + amplitudeDx * n + amplitude * nx;
            out.dz = meanDz + amplitudeDz * n + amplitude * nz;
            out.broadDx = meanDx + amplitudeDx * coarse + amplitude * coarseDx;
            out.broadDz = meanDz + amplitudeDz * coarse + amplitude * coarseDz;
        }
    }

    /** Clears private inland observations when the composing stage instead owns a marine column. */
    void clear(Sample out) {
        out.height = out.dx = out.dz = out.broadDx = out.broadDz = 0;
        out.amplitude = out.noise = out.coarseHeight = out.fineContribution = 0;
        elevation.clear(out.elevation);
        profiles.clear(out.region);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
