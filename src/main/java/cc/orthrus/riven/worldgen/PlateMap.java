package cc.orthrus.riven.worldgen;

/** Seeded present-day plate domains and motion; no historical simulation or terrain work. */
public final class PlateMap {
    public static final int VERSION = 2;
    public static final double CELL_BLOCKS = 6144 * TerrainScale.HORIZONTAL;
    private final long seed;

    /** Plate cell size: CELL_BLOCKS times the layout scale, since plates span continents. */
    private final double cellBlocks;

    public PlateMap(long worldSeed) {
        this(worldSeed, WorldScale.DEFAULT);
    }

    public PlateMap(long worldSeed, WorldScale world) {
        seed = SeededNoise.mix(worldSeed ^ 0x504c4154454d4150L);
        cellBlocks = CELL_BLOCKS * world.layout;
    }

    public enum Boundary {
        CONVERGENT,
        DIVERGENT,
        SHEAR
    }

    public static final class Sample {
        public final long plateId, neighborId;
        public final double boundaryDistance, velocityX, velocityZ, normalMotion;
        public final Boundary boundary;

        private Sample(
                long plateId,
                long neighborId,
                double distance,
                double vx,
                double vz,
                double normalMotion) {
            this.plateId = plateId;
            this.neighborId = neighborId;
            this.boundaryDistance = distance;
            this.velocityX = vx;
            this.velocityZ = vz;
            this.normalMotion = normalMotion;
            this.boundary =
                    normalMotion < -0.20
                            ? Boundary.CONVERGENT
                            : normalMotion > 0.20 ? Boundary.DIVERGENT : Boundary.SHEAR;
        }
    }

    /** This world's plate cell size in blocks. */
    public double cellBlocks() {
        return cellBlocks;
    }

    public Sample sample(int blockX, int blockZ) {
        int cx = (int) Math.floor(blockX / cellBlocks), cz = (int) Math.floor(blockZ / cellBlocks);
        long owner = 0;
        double nearest = Double.POSITIVE_INFINITY, px = 0, pz = 0;
        // Site jitter stays inside the middle half of each cell. A 3x3 search owns the point.
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                long id = SeededNoise.cell(seed, cx + dx, cz + dz);
                double sx = siteX(cx + dx, id), sz = siteZ(cz + dz, id);
                double distance = square(sx - blockX) + square(sz - blockZ);
                if (distance < nearest) {
                    nearest = distance;
                    owner = id;
                    px = sx;
                    pz = sz;
                }
            }
        double angle = SeededNoise.unit(SeededNoise.mix(owner ^ 0x76656c6f63697479L)) * Math.PI * 2;
        double vx = Math.cos(angle), vz = Math.sin(angle);
        double edge = Double.POSITIVE_INFINITY, motion = 0;
        long neighbor = owner;
        // The closest bisector need not belong to the second closest site.
        // Include the surrounding two cells to avoid truncating boundary distances at cell seams.
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                long id = SeededNoise.cell(seed, cx + dx, cz + dz);
                if (id == owner) continue;
                double sx = siteX(cx + dx, id), sz = siteZ(cz + dz, id);
                double nx = sx - px, nz = sz - pz, length = Math.hypot(nx, nz);
                double distance =
                        (square(sx - blockX) + square(sz - blockZ) - nearest) / (2 * length);
                if (distance < edge) {
                    edge = distance;
                    neighbor = id;
                    double other =
                            SeededNoise.unit(SeededNoise.mix(id ^ 0x76656c6f63697479L))
                                    * Math.PI
                                    * 2;
                    motion = ((Math.cos(other) - vx) * nx + (Math.sin(other) - vz) * nz) / length;
                }
            }
        return new Sample(owner, neighbor, Math.max(0, edge), vx, vz, motion);
    }

    private double siteX(int cell, long id) {
        return (cell + 0.25 + 0.5 * SeededNoise.unit(id)) * cellBlocks;
    }

    private double siteZ(int cell, long id) {
        return (cell + 0.25 + 0.5 * SeededNoise.unit(SeededNoise.mix(id))) * cellBlocks;
    }

    private static double square(double v) {
        return v * v;
    }

    /**
     * Smooth present-day extension from the same compact blend of plate velocities as {@link
     * #uplift}: 0 to 1 where the blended velocity field diverges, with no nearest-plate switch.
     */
    public double extension(double blockX, double blockZ) {
        double x = blockX / cellBlocks, z = blockZ / cellBlocks;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        double w = 0, wx = 0, wz = 0, a = 0, ax = 0, b = 0, bz = 0;
        double radius2 = 1.25 * 1.25;
        for (int iz = -1; iz <= 1; iz++)
            for (int ix = -1; ix <= 1; ix++) {
                long id = SeededNoise.cell(seed, cx + ix, cz + iz);
                double rx = x - siteX(cx + ix, id) / cellBlocks,
                        rz = z - siteZ(cz + iz, id) / cellBlocks;
                double q = 1 - (rx * rx + rz * rz) / radius2;
                if (q <= 0) continue;
                double q3 = q * q * q, weight = q3 * q;
                double dx = -8 * rx * q3 / radius2, dz = -8 * rz * q3 / radius2;
                double angle =
                        SeededNoise.unit(SeededNoise.mix(id ^ 0x76656c6f63697479L)) * Math.PI * 2;
                double vx = Math.cos(angle), vz = Math.sin(angle);
                w += weight;
                wx += dx;
                wz += dz;
                a += weight * vx;
                ax += dx * vx;
                b += weight * vz;
                bz += dz * vz;
            }
        double vx = a / w, vz = b / w;
        double divergence = (ax - vx * wx) / w + (bz - vz * wz) / w;
        double t = (divergence - 0.05) / 1.95;
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * Smooth present-day compression intent from a compact blend of plate velocities.
     * The kernel and its first three derivatives vanish outside support, including at
     * grid-window changes. No nearest-plate or nearest-edge switch enters this field.
     */
    public void uplift(double blockX, double blockZ, HeightSample out) {
        double x = blockX / cellBlocks, z = blockZ / cellBlocks;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        double w = 0, wx = 0, wz = 0, wxx = 0, wxz = 0, wzz = 0;
        double a = 0, ax = 0, az = 0, axx = 0, axz = 0;
        double b = 0, bx = 0, bz = 0, bxz = 0, bzz = 0;
        double radius2 = 1.25 * 1.25;
        for (int iz = -1; iz <= 1; iz++)
            for (int ix = -1; ix <= 1; ix++) {
                long id = SeededNoise.cell(seed, cx + ix, cz + iz);
                double rx = x - siteX(cx + ix, id) / cellBlocks,
                        rz = z - siteZ(cz + iz, id) / cellBlocks;
                double q = 1 - (rx * rx + rz * rz) / radius2;
                if (q <= 0) continue;
                double q2 = q * q, q3 = q2 * q, weight = q2 * q2;
                double dx = -8 * rx * q3 / radius2, dz = -8 * rz * q3 / radius2;
                double dxx = -8 * q3 / radius2 + 48 * rx * rx * q2 / (radius2 * radius2);
                double dxz = 48 * rx * rz * q2 / (radius2 * radius2);
                double dzz = -8 * q3 / radius2 + 48 * rz * rz * q2 / (radius2 * radius2);
                double angle =
                        SeededNoise.unit(SeededNoise.mix(id ^ 0x76656c6f63697479L)) * Math.PI * 2;
                double vx = Math.cos(angle), vz = Math.sin(angle);
                w += weight;
                wx += dx;
                wz += dz;
                wxx += dxx;
                wxz += dxz;
                wzz += dzz;
                a += weight * vx;
                ax += dx * vx;
                az += dz * vx;
                axx += dxx * vx;
                axz += dxz * vx;
                b += weight * vz;
                bx += dx * vz;
                bz += dz * vz;
                bxz += dxz * vz;
                bzz += dzz * vz;
            }
        double vx = a / w, vz = b / w;
        double vxx = (ax - vx * wx) / w, vxz = (az - vx * wz) / w;
        double vzx = (bx - vz * wx) / w, vzz = (bz - vz * wz) / w;
        double compression = -(vxx + vzz);
        double dx =
                -((axx - vx * wxx - 2 * vxx * wx) / w + (bxz - vz * wxz - vzx * wz - vzz * wx) / w);
        double dz =
                -((axz - vx * wxz - vxx * wz - vxz * wx) / w + (bzz - vz * wzz - 2 * vzz * wz) / w);
        double t = (compression - 0.05) / 1.95;
        if (t <= 0) out.set(0, 0, 0);
        else if (t >= 1) out.set(1, 0, 0);
        else {
            double f = t * t * t * (t * (t * 6 - 15) + 10),
                    df = 30 * t * t * (t - 1) * (t - 1) / 1.95 / cellBlocks;
            out.set(f, dx * df, dz * df);
        }
    }
}
