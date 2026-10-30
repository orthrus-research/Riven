package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Bounded authored moisture potential from earlier structure; never a supplied-water observation. */
public final class ClimateForcingMap {
    public static final int MAX_UPWIND_SAMPLES = 8;
    private static final Rules RULES = Rules.load("riven-climate-forcing.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    public enum Policy {
        LEGACY,
        DERIVED
    }

    public enum Status {
        UNSAMPLED,
        LEGACY,
        DISABLED,
        OUTSIDE_DOMAIN,
        MARINE,
        COMPLETE
    }

    private final ClimateStructureMap structure;
    public final Policy policy;
    public final int upwindSamples;
    public final double transportX, transportZ, stepBlocks, maximumReach, responseHeight;
    private final double maximumWindwardFactor, maximumLeeFactor;
    private final double[] offsetX, offsetZ;

    /**
     * Upwind obstruction lattice spacing; 0 reads every upwind point exactly. Node values are pure
     * functions of their coordinates, so the bounded memo never changes a result.
     */
    public final double latticeBlocks;

    /**
     * Lattice nodes in square tiles, each built whole on its first read: a column reads 32 nodes,
     * mostly four at a time from one tile. The lattice follows the layout, so a world of any size
     * reads about as many nodes (12 players spread over 8 km read 545,139 in 2,527 tiles, over 32 km
     * 311,515 in 1,425). A tile holds 2 KB; 16,384 slots keep every tile those read, and bound the
     * memo to about 34 MB wherever players roam (timing only).
     */
    private static final int TILE_SHIFT = 4, TILE = 1 << TILE_SHIFT, TILE_MASK = TILE - 1;

    private final CellMemo<double[]> tiles = new CellMemo<>(16384);

    public ClimateForcingMap(ClimateStructureMap structure, TerrainHeightReference heights) {
        this(structure, heights, RULES.json, WorldScale.DEFAULT);
    }

    public ClimateForcingMap(
            ClimateStructureMap structure, TerrainHeightReference heights, WorldScale world) {
        this(structure, heights, RULES.json, world);
    }

    ClimateForcingMap(
            ClimateStructureMap structure, TerrainHeightReference heights, JsonObject json) {
        this(structure, heights, json, WorldScale.DEFAULT);
    }

    /**
     * The upwind step follows the layout scale: rain shadow comes mostly from crest highlands,
     * which are as wide as their continents, so its reach grows with them.
     */
    ClimateForcingMap(
            ClimateStructureMap structure,
            TerrainHeightReference heights,
            JsonObject json,
            WorldScale world) {
        if (structure == null || heights == null || json == null || world == null)
            throw new NullPointerException();
        this.structure = structure;
        policy = Policy.valueOf(json.get("policy").getAsString());
        double tx = Rules.number(json, "transportX", -1, 1);
        double tz = Rules.number(json, "transportZ", -1, 1);
        double length = StrictMath.hypot(tx, tz);
        if (length <= 1e-12) throw new IllegalStateException("Nonzero climate transport required");
        transportX = tx / length;
        transportZ = tz / length;
        double count = Rules.number(json, "upwindSamples", 1, MAX_UPWIND_SAMPLES);
        upwindSamples = (int) count;
        if (count != upwindSamples)
            throw new IllegalStateException("Integral upwind count required");
        TerrainHeightReference.Feature mountains = heights.feature("mountains");
        stepBlocks =
                world.layout
                        * mountains.width.targetAverage
                        * TerrainScale.HORIZONTAL
                        * Rules.number(json, "stepInMountainWidths", .125, 8);
        maximumReach = stepBlocks * upwindSamples;
        responseHeight =
                mountains.relief.targetAverage
                        * Rules.number(json, "responseInMountainRelief", .125, 8);
        maximumWindwardFactor = Rules.number(json, "maximumWindwardFactor", 0, 1);
        maximumLeeFactor = Rules.number(json, "maximumLeeFactor", 0, 1);
        // The obstruction field is smooth at continental scale, so its lattice follows the layout.
        latticeBlocks =
                json.has("upwindLatticeBlocks")
                        ? world.layout * Rules.number(json, "upwindLatticeBlocks", 0, stepBlocks)
                        : 0;
        offsetX = new double[upwindSamples];
        offsetZ = new double[upwindSamples];
        for (int i = 0; i < upwindSamples; i++) {
            double distance = stepBlocks * (i + 1);
            offsetX[i] = transportX * distance;
            offsetZ[i] = transportZ * distance;
        }
    }

    /** One reused structural scratch and a fixed stencil buffer, owned by the caller. */
    public static final class Sample {
        private final ClimateStructureMap.Sample structure = new ClimateStructureMap.Sample();
        private final double[] upwindObstruction = new double[MAX_UPWIND_SAMPLES];
        private Status status = Status.UNSAMPLED;
        private double landWeight = Double.NaN, obstructionAboveDatum = Double.NaN;
        private double meanContrastBlocks = Double.NaN;
        private double windwardPotential = Double.NaN, leePotential = Double.NaN;
        private double moistureFactor;
        private int structureQueries, elevationQueries;

        public Status status() {
            return status;
        }

        public double landWeight() {
            return landWeight;
        }

        public double obstructionAboveDatum() {
            return obstructionAboveDatum;
        }

        public double meanContrastBlocks() {
            return meanContrastBlocks;
        }

        public double windwardPotential() {
            return windwardPotential;
        }

        public double leePotential() {
            return leePotential;
        }

        public double moistureFactor() {
            return moistureFactor;
        }

        public int structureQueries() {
            return structureQueries;
        }

        public int elevationQueries() {
            return elevationQueries;
        }

        private void clear(Status next) {
            status = next;
            landWeight = obstructionAboveDatum = meanContrastBlocks = Double.NaN;
            windwardPotential = leePotential = Double.NaN;
            moistureFactor = 0;
            structureQueries = elevationQueries = 0;
        }
    }

    /** At most nine structure reads. Missing required coverage preserves background moisture. */
    public void sample(double x, double z, Sample out) {
        if (out == null) throw new NullPointerException("out");
        if (!Double.isFinite(x) || !Double.isFinite(z))
            throw new IllegalArgumentException("Finite climate forcing coordinates required");
        out.clear(Status.UNSAMPLED);
        if (policy == Policy.LEGACY) {
            out.status = Status.LEGACY;
            return;
        }
        if (!Rules.inCoordinateDomain(x, z)) {
            out.status = Status.OUTSIDE_DOMAIN;
            return;
        }
        if (maximumWindwardFactor == 0 && maximumLeeFactor == 0) {
            out.status = Status.DISABLED;
            return;
        }
        read(x, z, out);
        out.landWeight = out.structure.landWeight();
        out.obstructionAboveDatum = out.structure.obstructionAboveDatum();
        if (out.structure.status() == ClimateStructureMap.Status.MARINE) {
            out.status = Status.MARINE;
            out.meanContrastBlocks = out.windwardPotential = out.leePotential = 0;
            return;
        }
        // Validate the entire support before reading any upwind point. Never shorten at world
        // edges.
        double margin = latticeBlocks;
        for (int i = 0; i < upwindSamples; i++)
            if (!Rules.inCoordinateDomain(
                            x - offsetX[i] - Math.copySign(margin, offsetX[i]),
                            z - offsetZ[i] - Math.copySign(margin, offsetZ[i]))
                    || !Rules.inCoordinateDomain(
                            x - offsetX[i] + Math.copySign(margin, offsetX[i]),
                            z - offsetZ[i] + Math.copySign(margin, offsetZ[i]))) {
                out.status = Status.OUTSIDE_DOMAIN;
                return;
            }
        for (int i = 0; i < upwindSamples; i++) {
            if (latticeBlocks > 0) {
                out.upwindObstruction[i] = interpolated(x - offsetX[i], z - offsetZ[i]);
                continue;
            }
            read(x - offsetX[i], z - offsetZ[i], out);
            out.upwindObstruction[i] = out.structure.obstructionAboveDatum();
        }
        resolve(out);
        out.status = Status.COMPLETE;
    }

    /** Bilinear obstruction from the four surrounding lattice nodes. */
    private double interpolated(double x, double z) {
        double u = x / latticeBlocks, v = z / latticeBlocks;
        int ix = (int) Math.floor(u), iz = (int) Math.floor(v);
        double fx = u - ix, fz = v - iz;
        double a, b, c, d;
        // The four nodes share a tile unless the cell lies on its last row or column.
        if ((ix & TILE_MASK) != TILE_MASK && (iz & TILE_MASK) != TILE_MASK) {
            double[] t = tile(ix >> TILE_SHIFT, iz >> TILE_SHIFT);
            int i = (iz & TILE_MASK) << TILE_SHIFT | (ix & TILE_MASK);
            a = t[i];
            b = t[i + 1];
            c = t[i + TILE];
            d = t[i + TILE + 1];
        } else {
            a = node(ix, iz);
            b = node(ix + 1, iz);
            c = node(ix, iz + 1);
            d = node(ix + 1, iz + 1);
        }
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private double node(int ix, int iz) {
        int i = (iz & TILE_MASK) << TILE_SHIFT | (ix & TILE_MASK);
        return tile(ix >> TILE_SHIFT, iz >> TILE_SHIFT)[i];
    }

    private double[] tile(int tx, int tz) {
        return tiles.get(
                tx,
                tz,
                () -> {
                    double[] t = new double[TILE * TILE];
                    for (int j = 0; j < TILE; j++)
                        for (int i = 0; i < TILE; i++) {
                            int ix = (tx << TILE_SHIFT) + i, iz = (tz << TILE_SHIFT) + j;
                            ClimateStructureMap.Sample s = new ClimateStructureMap.Sample();
                            structure.sample(ix * latticeBlocks, iz * latticeBlocks, s);
                            t[j << TILE_SHIFT | i] = s.obstructionAboveDatum();
                        }
                    return t;
                });
    }

    private void read(double x, double z, Sample out) {
        structure.sample(x, z, out.structure);
        out.structureQueries++;
        if (out.structure.elevationSampled()) out.elevationQueries++;
    }

    private void resolve(Sample out) {
        double contrast = 0;
        for (int i = 0; i < upwindSamples; i++)
            contrast +=
                    (upwindSamples - i) * (out.obstructionAboveDatum - out.upwindObstruction[i]);
        out.meanContrastBlocks = contrast / (upwindSamples * (upwindSamples + 1) * .5);
        double normalized = Math.max(-1, Math.min(1, out.meanContrastBlocks / responseHeight));
        out.windwardPotential = Math.max(0, Math.min(1, Rules.smooth(Math.max(0, normalized))));
        out.leePotential = Math.max(0, Math.min(1, Rules.smooth(Math.max(0, -normalized))));
        // The second land weight controls applicability, distinct from obstruction's geometric
        // mask.
        out.moistureFactor =
                out.landWeight
                        * (maximumWindwardFactor * out.windwardPotential
                                - maximumLeeFactor * out.leePotential);
    }
}
