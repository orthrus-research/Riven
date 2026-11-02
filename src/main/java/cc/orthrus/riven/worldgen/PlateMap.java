package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/**
 * Seeded present-day plate domains and motion (riven-plates.json); no historical simulation or
 * terrain work. Plates are a power diagram of one weighted site per cell, read at warped
 * coordinates, so they come in many sizes with curving boundaries. Convergent boundaries carry
 * mountain belts, the source of high ground; a smooth blend of plate velocities gives signed uplift.
 */
public final class PlateMap {
    static final Rules RULES = Rules.load("riven-plates.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    private static final double SIZE, JITTER, WEIGHT, WARP, WARP_WAVELENGTH;
    private static final double CONVERGENT, DIVERGENT, BELT_HALF, BELT_MINIMUM, BELT_FULL;
    private static final double VARY_WAVELENGTH, VARY_FLOOR, VARY_PEAK, VARY_WIDTH, VARY_GAP;
    private static final double KERNEL_RADIUS, UPLIFT_SCALE;
    private static final long VARIATION;

    static {
        JsonObject j = RULES.json;
        VARIATION = Rules.seed(j, "variationSeed");
        JsonObject cells = j.getAsJsonObject("cells");
        SIZE = Rules.number(cells, "sizeBlocks", 256, 65536);
        JITTER = Rules.number(cells, "jitter", 0, 1);
        WEIGHT = Rules.number(cells, "weight", 0, 0.6);
        WARP = Rules.number(cells, "warpBlocks", 0, SIZE / 2);
        WARP_WAVELENGTH = Rules.number(cells, "warpWavelengthBlocks", 256, 262144);
        JsonObject b = j.getAsJsonObject("boundaries");
        CONVERGENT = Rules.number(b, "convergentMotion", -2, 0);
        DIVERGENT = Rules.number(b, "divergentMotion", 0, 2);
        JsonObject belts = j.getAsJsonObject("belts");
        BELT_HALF = Rules.number(belts, "halfWidthBlocks", 1, SIZE);
        BELT_MINIMUM = Rules.number(belts, "minimumWidth", 0.05, 1);
        BELT_FULL = Rules.number(belts, "fullMotion", -2, CONVERGENT - 1e-6);
        JsonObject vary = belts.getAsJsonObject("variation");
        VARY_WAVELENGTH = Rules.number(vary, "wavelengthBlocks", 64, 65536);
        VARY_FLOOR = Rules.number(vary, "floor", 0, 1);
        VARY_PEAK = Rules.number(vary, "peak", VARY_FLOOR, 4);
        VARY_WIDTH = Rules.number(vary, "width", 0, 0.9);
        VARY_GAP = Rules.number(vary, "gap", 0, 0.9);
        JsonObject u = j.getAsJsonObject("uplift");
        KERNEL_RADIUS = Rules.number(u, "kernelRadiusCells", 0.5, 2);
        UPLIFT_SCALE = Rules.number(u, "scale", 0.05, 10);
    }

    /** The plate cell size at the 8 km tuning (layout 1), in blocks. */
    public static final double CELL_BLOCKS = SIZE;

    private final long seed;
    private final double cellBlocks, warp, warpWavelength, beltHalf, varyWavelength;
    private final SeededNoise warpX, warpZ, varyStrength, varyWidth;

    public PlateMap(long worldSeed) {
        this(worldSeed, WorldScale.DEFAULT);
    }

    public PlateMap(long worldSeed, WorldScale world) {
        seed = SeededNoise.mix(worldSeed ^ VARIATION);
        cellBlocks = SIZE * world.layout;
        warp = WARP * world.layout;
        warpWavelength = WARP_WAVELENGTH * world.layout;
        beltHalf = BELT_HALF * world.layout;
        varyWavelength = VARY_WAVELENGTH * world.layout;
        warpX = new SeededNoise(SeededNoise.mix(seed ^ 0x5750585850L));
        warpZ = new SeededNoise(SeededNoise.mix(seed ^ 0x57505a5a50L));
        varyStrength = new SeededNoise(SeededNoise.mix(seed ^ 0x5653545250L));
        varyWidth = new SeededNoise(SeededNoise.mix(seed ^ 0x5657494450L));
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

        /** The strongest convergent belt here, 0 to 1, and its world gradient. */
        public final double belt, beltDx, beltDz;

        /** The neighbouring plate whose boundary with this one carries the belt (0 for none). */
        public final long beltSource;

        private Sample(
                long plateId,
                long neighborId,
                double distance,
                double vx,
                double vz,
                double normalMotion,
                double belt,
                double beltDx,
                double beltDz,
                long beltSource) {
            this.plateId = plateId;
            this.neighborId = neighborId;
            this.boundaryDistance = distance;
            this.velocityX = vx;
            this.velocityZ = vz;
            this.normalMotion = normalMotion;
            this.boundary =
                    normalMotion < CONVERGENT
                            ? Boundary.CONVERGENT
                            : normalMotion > DIVERGENT ? Boundary.DIVERGENT : Boundary.SHEAR;
            this.belt = belt;
            this.beltDx = beltDx;
            this.beltDz = beltDz;
            this.beltSource = beltSource;
        }
    }

    /** One plate cell's site: its id, position, power weight and velocity. */
    private static final class Site {
        int cx, cz;
        boolean set;
        long id;
        double x, z, w, vx, vz;
    }

    /**
     * Each thread's recent sites, direct-mapped by cell: a pure memo (a miss recomputes the same
     * values), so neighbouring samples skip the hashes and the velocity's trigonometry.
     */
    private final ThreadLocal<Site[]> sites =
            ThreadLocal.withInitial(
                    () -> {
                        Site[] cache = new Site[1024];
                        for (int i = 0; i < cache.length; i++) cache[i] = new Site();
                        return cache;
                    });

    private Site site(Site[] cache, int cx, int cz) {
        int slot = (cx * 0x45d9f3b ^ cz * 0x119de1f3) & (cache.length - 1);
        Site s = cache[slot];
        if (s.set && s.cx == cx && s.cz == cz) return s;
        long id = SeededNoise.cell(seed, cx, cz);
        double angle = SeededNoise.unit(SeededNoise.mix(id ^ 0x76656c6f63697479L)) * Math.PI * 2;
        s.cx = cx;
        s.cz = cz;
        s.id = id;
        s.x = siteX(cx, id);
        s.z = siteZ(cz, id);
        s.w = weight(id);
        s.vx = Math.cos(angle);
        s.vz = Math.sin(angle);
        s.set = true;
        return s;
    }

    /** This world's plate cell size in blocks. */
    public double cellBlocks() {
        return cellBlocks;
    }

    /**
     * An owner plate's neighbours within three cells, each boundary (radical axis) a line in
     * warped space, d = rx * nx + rz * nz + k from the owner's site, with its fixed facts: the
     * plates' relative motion, the belt's strength from it and its base half width.
     */
    private static final class Plan {
        int cx, cz, n;
        boolean set;
        final long[] id = new long[48];
        final double[] nx = new double[48], nz = new double[48], k = new double[48];
        final double[] motion = new double[48], strength = new double[48], base = new double[48];
    }

    /** Each thread's recent plans, direct-mapped by owner cell: a pure memo, like the sites. */
    private final ThreadLocal<Plan[]> plans =
            ThreadLocal.withInitial(
                    () -> {
                        Plan[] cache = new Plan[256];
                        for (int i = 0; i < cache.length; i++) cache[i] = new Plan();
                        return cache;
                    });

    private Plan plan(Plan[] cache, Site[] sites, int ocx, int ocz) {
        int slot = (ocx * 0x45d9f3b ^ ocz * 0x119de1f3) & (cache.length - 1);
        Plan plan = cache[slot];
        if (plan.set && plan.cx == ocx && plan.cz == ocz) return plan;
        // The owner's values copied out: a neighbour may reuse its site's cache slot.
        Site o = site(sites, ocx, ocz);
        double px = o.x, pz = o.z, pw = o.w, vx = o.vx, vz = o.vz;
        int n = 0;
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                if (dx == 0 && dz == 0) continue;
                Site s = site(sites, ocx + dx, ocz + dz);
                double ux = s.x - px, uz = s.z - pz, length = Math.sqrt(ux * ux + uz * uz);
                double m = ((s.vx - vx) * ux + (s.vz - vz) * uz) / length;
                double strength =
                        m < CONVERGENT
                                ? Math.min(1, (CONVERGENT - m) / (CONVERGENT - BELT_FULL))
                                : 0;
                plan.id[n] = s.id;
                plan.nx[n] = -ux / length;
                plan.nz[n] = -uz / length;
                plan.k[n] = (length * length - s.w + pw) / (2 * length);
                plan.motion[n] = m;
                plan.strength[n] = strength;
                plan.base[n] = beltHalf * (BELT_MINIMUM + (1 - BELT_MINIMUM) * strength);
                n++;
            }
        plan.n = n;
        plan.cx = ocx;
        plan.cz = ocz;
        plan.set = true;
        return plan;
    }

    public Sample sample(int blockX, int blockZ) {
        // Warped coordinates, and the warp's Jacobian for the belt's world gradient.
        HeightSample nx = new HeightSample(), nz = new HeightSample();
        warpX.sample(blockX / warpWavelength, blockZ / warpWavelength, nx);
        warpZ.sample(blockX / warpWavelength, blockZ / warpWavelength, nz);
        double x = blockX + warp * nx.height, z = blockZ + warp * nz.height;
        double jxx = 1 + warp * nx.dx / warpWavelength, jxz = warp * nx.dz / warpWavelength;
        double jzx = warp * nz.dx / warpWavelength, jzz = 1 + warp * nz.dz / warpWavelength;
        int cx = (int) Math.floor(x / cellBlocks), cz = (int) Math.floor(z / cellBlocks);
        Site[] cache = sites.get();
        long owner = 0;
        int ocx = 0, ocz = 0;
        double best = Double.POSITIVE_INFINITY, px = 0, pz = 0, vx = 0, vz = 0;
        // The owner: least power distance (squared distance less weight) within two cells. Its
        // values are copied out: a later cell may reuse its cache slot.
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                Site s = site(cache, cx + dx, cz + dz);
                double power = square(s.x - x) + square(s.z - z) - s.w;
                if (power < best) {
                    best = power;
                    owner = s.id;
                    ocx = cx + dx;
                    ocz = cz + dz;
                    px = s.x;
                    pz = s.z;
                    vx = s.vx;
                    vz = s.vz;
                }
            }
        Plan plan = plan(plans.get(), cache, ocx, ocz);
        double rx = x - px, rz = z - pz;
        // The nearest boundary; and whether any belt can reach here (else its noise is skipped).
        double edge = Double.POSITIVE_INFINITY, motion = 0;
        long neighbor = owner;
        boolean reach = false;
        for (int i = 0; i < plan.n; i++) {
            double d = rx * plan.nx[i] + rz * plan.nz[i] + plan.k[i];
            if (d < edge) {
                edge = d;
                neighbor = plan.id[i];
                motion = plan.motion[i];
            }
            if (plan.strength[i] > 0 && d < plan.base[i] * (1 + VARY_WIDTH)) reach = true;
        }
        double belt = 0, beltGx = 0, beltGz = 0;
        long source = 0;
        if (reach) {
            // Along a belt its strength and width wander with slow fields read at the warped
            // point, so ranges swell, narrow and break into massifs; each has its gradient.
            HeightSample ns = new HeightSample(), nw = new HeightSample();
            varyStrength.sample(x / varyWavelength, z / varyWavelength, ns);
            varyWidth.sample(x / varyWavelength, z / varyWavelength, nw);
            double ms = (ns.height + 1) / 2, mw = (nw.height + 1) / 2;
            double us = (ms - VARY_GAP) / (1 - VARY_GAP);
            double strengthFactor = VARY_FLOOR + (VARY_PEAK - VARY_FLOOR) * Rules.smooth(us);
            double strengthSlope =
                    us <= 0 || us >= 1
                            ? 0
                            : (VARY_PEAK - VARY_FLOOR)
                                    * 30
                                    * us
                                    * us
                                    * (us - 1)
                                    * (us - 1)
                                    / (1 - VARY_GAP)
                                    / 2;
            double sfx = strengthSlope * ns.dx / varyWavelength,
                    sfz = strengthSlope * ns.dz / varyWavelength;
            double widthFactor = 1 - VARY_WIDTH + 2 * VARY_WIDTH * mw;
            double wfx = VARY_WIDTH * nw.dx / varyWavelength,
                    wfz = VARY_WIDTH * nw.dz / varyWavelength;
            // Any convergent boundary may carry a belt here; the strongest counts.
            for (int i = 0; i < plan.n; i++) {
                double motionStrength = plan.strength[i];
                if (motionStrength <= 0) continue;
                double base = plan.base[i], width = base * widthFactor;
                double d = Math.max(0, rx * plan.nx[i] + rz * plan.nz[i] + plan.k[i]);
                double t = d / width;
                if (t >= 1) continue;
                // The variation moves strength along the belt; above 0.8 a belt eases toward 1
                // (value and slope continuous), so it never exceeds 1 and has no kink.
                double raw = motionStrength * strengthFactor;
                double strength = raw <= 0.8 ? raw : 1 - 0.2 * StrictMath.exp(-(raw - 0.8) / 0.2);
                double capSlope = raw <= 0.8 ? 1 : StrictMath.exp(-(raw - 0.8) / 0.2);
                double fall = 1 - Rules.smooth(t), value = strength * fall;
                if (value <= belt) continue;
                belt = value;
                source = plan.id[i];
                // In warped space: d(distance) is the boundary's normal, d(width) and d(strength)
                // come from the variation fields; then through the warp.
                double fallSlope = -30 * t * t * (t - 1) * (t - 1);
                double dwx = base * wfx, dwz = base * wfz;
                double gx =
                        capSlope * motionStrength * sfx * fall
                                + strength
                                        * fallSlope
                                        * (plan.nx[i] * width - d * dwx)
                                        / (width * width);
                double gz =
                        capSlope * motionStrength * sfz * fall
                                + strength
                                        * fallSlope
                                        * (plan.nz[i] * width - d * dwz)
                                        / (width * width);
                beltGx = gx * jxx + gz * jzx;
                beltGz = gx * jxz + gz * jzz;
            }
        }
        return new Sample(
                owner, neighbor, Math.max(0, edge), vx, vz, motion, belt, beltGx, beltGz, source);
    }

    private double siteX(int cell, long id) {
        return (cell + 0.5 + JITTER * (SeededNoise.unit(id) - 0.5)) * cellBlocks;
    }

    private double siteZ(int cell, long id) {
        return (cell + 0.5 + JITTER * (SeededNoise.unit(SeededNoise.mix(id)) - 0.5)) * cellBlocks;
    }

    /** A site's power weight: up to WEIGHT squared cells. */
    private double weight(long id) {
        return WEIGHT
                * SeededNoise.unit(SeededNoise.mix(id ^ 0x5745494748L))
                * cellBlocks
                * cellBlocks;
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
        Site[] cache = sites.get();
        double w = 0, wx = 0, wz = 0, a = 0, ax = 0, b = 0, bz = 0;
        double radius2 = KERNEL_RADIUS * KERNEL_RADIUS;
        for (int iz = -2; iz <= 2; iz++)
            for (int ix = -2; ix <= 2; ix++) {
                Site s = site(cache, cx + ix, cz + iz);
                double rx = x - s.x / cellBlocks, rz = z - s.z / cellBlocks;
                double q = 1 - (rx * rx + rz * rz) / radius2;
                if (q <= 0) continue;
                double q3 = q * q * q, weight = q3 * q;
                double dx = -8 * rx * q3 / radius2, dz = -8 * rz * q3 / radius2;
                double vx = s.vx, vz = s.vz;
                w += weight;
                wx += dx;
                wz += dz;
                a += weight * vx;
                ax += dx * vx;
                b += weight * vz;
                bz += dz * vz;
            }
        if (w <= 0) return 0;
        double vx = a / w, vz = b / w;
        double divergence = (ax - vx * wx) / w + (bz - vz * wz) / w;
        double t = (divergence - 0.05) / 1.95;
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * Signed present-day uplift from a compact blend of plate velocities: tanh(compression /
     * scale), raising where the blended field converges and lowering where it spreads. The
     * kernel and its first three derivatives vanish outside support; a two-cell window holds every
     * site whose kernel reaches the point, so no window change enters the field.
     */
    public void uplift(double blockX, double blockZ, HeightSample out) {
        double x = blockX / cellBlocks, z = blockZ / cellBlocks;
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        Site[] cache = sites.get();
        double w = 0, wx = 0, wz = 0, wxx = 0, wxz = 0, wzz = 0;
        double a = 0, ax = 0, az = 0, axx = 0, axz = 0;
        double b = 0, bx = 0, bz = 0, bxz = 0, bzz = 0;
        double radius2 = KERNEL_RADIUS * KERNEL_RADIUS;
        for (int iz = -2; iz <= 2; iz++)
            for (int ix = -2; ix <= 2; ix++) {
                Site s = site(cache, cx + ix, cz + iz);
                double rx = x - s.x / cellBlocks, rz = z - s.z / cellBlocks;
                double q = 1 - (rx * rx + rz * rz) / radius2;
                if (q <= 0) continue;
                double q2 = q * q, q3 = q2 * q, weight = q2 * q2;
                double dx = -8 * rx * q3 / radius2, dz = -8 * rz * q3 / radius2;
                double dxx = -8 * q3 / radius2 + 48 * rx * rx * q2 / (radius2 * radius2);
                double dxz = 48 * rx * rz * q2 / (radius2 * radius2);
                double dzz = -8 * q3 / radius2 + 48 * rz * rz * q2 / (radius2 * radius2);
                double vx = s.vx, vz = s.vz;
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
        if (w <= 0) {
            out.set(0, 0, 0);
            return;
        }
        double vx = a / w, vz = b / w;
        double vxx = (ax - vx * wx) / w, vxz = (az - vx * wz) / w;
        double vzx = (bx - vz * wx) / w, vzz = (bz - vz * wz) / w;
        double compression = -(vxx + vzz);
        double dx =
                -((axx - vx * wxx - 2 * vxx * wx) / w + (bxz - vz * wxz - vzx * wz - vzz * wx) / w);
        double dz =
                -((axz - vx * wxz - vxx * wz - vxz * wx) / w + (bzz - vz * wzz - 2 * vzz * wz) / w);
        double u = StrictMath.tanh(compression / UPLIFT_SCALE),
                du = (1 - u * u) / UPLIFT_SCALE / cellBlocks;
        out.set(u, dx * du, dz * du);
    }
}
