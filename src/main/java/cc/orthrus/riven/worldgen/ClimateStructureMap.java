package cc.orthrus.riven.worldgen;

/** Earlier land obstruction for climate. Neither a reconstructed surface nor a water observation. */
public final class ClimateStructureMap {
    public static final int VERSION = 1;

    public enum Status {
        UNSAMPLED,
        LAND,
        MARINE,
        OUTSIDE_DOMAIN
    }

    private final ContinentalOutlineMap outline;
    private final BaseElevationMap elevation;
    public final int datumY;

    public ClimateStructureMap(
            ContinentalOutlineMap outline,
            BaseElevationMap elevation,
            TerrainHeightReference heights) {
        if (outline == null || elevation == null || heights == null)
            throw new NullPointerException();
        this.outline = outline;
        this.elevation = elevation;
        datumY = heights.seaLevel;
    }

    /** Caller-owned scratch. Unavailable observations are NaN, not a fabricated flat or wet point. */
    public static final class Sample {
        private final ContinentalOutlineMap.Sample outline = new ContinentalOutlineMap.Sample();
        private final BaseElevationMap.Sample elevation = new BaseElevationMap.Sample();
        private Status status = Status.UNSAMPLED;
        private double landWeight = Double.NaN,
                meanSolidY = Double.NaN,
                obstructionAboveDatum = Double.NaN;
        private boolean elevationSampled;

        public Status status() {
            return status;
        }

        public double landWeight() {
            return landWeight;
        }

        public double meanSolidY() {
            return meanSolidY;
        }

        public double obstructionAboveDatum() {
            return obstructionAboveDatum;
        }

        public boolean elevationSampled() {
            return elevationSampled;
        }

        private void clear(Status next) {
            status = next;
            landWeight = meanSolidY = obstructionAboveDatum = Double.NaN;
            elevationSampled = false;
        }
    }

    /** One outline read and at most one elevation read; no neighbors, water admission or later maps. */
    public void sample(double x, double z, Sample out) {
        if (out == null) throw new NullPointerException("out");
        if (!Rules.inCoordinateDomain(x, z)) {
            if (!Double.isFinite(x) || !Double.isFinite(z))
                throw new IllegalArgumentException("Finite climate structure coordinates required");
            out.clear(Status.OUTSIDE_DOMAIN);
            return;
        }
        outline.sample(x, z, out.outline);
        out.clear(out.outline.marine() ? Status.MARINE : Status.LAND);
        if (out.status == Status.MARINE) {
            out.landWeight = out.obstructionAboveDatum = 0;
            return;
        }
        out.landWeight = Math.max(0, Math.min(1, out.outline.landWeight()));
        if (out.landWeight == 0) {
            out.obstructionAboveDatum = 0;
            return;
        }
        elevation.sample(x, z, out.elevation);
        out.meanSolidY = out.elevation.meanHeight();
        out.elevationSampled = true;
        out.obstructionAboveDatum = out.landWeight * Math.max(0, out.meanSolidY + 1 - datumY);
    }
}
