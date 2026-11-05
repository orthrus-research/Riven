package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Earlier inland height potential. Marine ownership, water and later shaping remain downstream. */
public final class BaseTerrainMap {
    private static final Rules RULES = Rules.load("riven-base-terrain.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    private final BaseElevationMap elevation;
    private final RegionalProfileMap profiles;

    /** The province fields (level, relief, ridged, plateau, spine, cap, below) as a plan (S4). */
    private final ProvinceGround ground;

    private final double seaLevel, groundAmplitude, groundAmplitudePerBlock;

    /** Character: patch wavelength, and the patch value at which strength starts and is full. */
    private final double characterScale, characterStart, characterFull, characterFloor;

    /** Ridged relief: base wavelength and its multiplier on the ridged share's amplitude. */
    private final double ridgeScale, ridgeGain, rollingGain, ridgeWarp, ridgeWarpScale;

    /** What a full spine weight brings: lift in blocks, and the relief and ridged share it pulls to. */
    private final double spineLift, spineRelief, spineRidged;

    /**
     * Plateaus: the scarp's width in plateau share, the edge field's amplitude (share) and
     * wavelength, how much of the ground's amplitude a cap loses, and the scarp's terraced share.
     */
    private final double scarpWidth, plateauEdge, plateauEdgeScale, plateauFlatten, scarpTerrace;

    /** The spine weights over which a plateau's cap yields to a range. */
    private final double spineYieldFrom, spineYieldFull;

    /** Landform palettes: each field's wavelength in blocks, and how much the character varies them. */
    private final double hillScale, spurScale, swellScale, hollowScale, variety;

    /** Terrace step height, and the share of a step that is riser. */
    private final double terraceBlocks, terraceRiser;

    /** The soft ceiling: heights above the start bend toward the allocation's top. */
    private final double ceilingStart, ceiling;

    private final SeededNoise character,
            ridge1,
            ridge2,
            ridge3,
            warpX,
            warpZ,
            edge1,
            edge2,
            hillA,
            hillB,
            spurN,
            swellN,
            hollowN;

    /** The ridged sum's scale and offset (see the ridges in sample). */
    private static final double RIDGE_SPREAD = 1.421, RIDGE_CENTRE = .622;

    /** Each ridge octave's rotation (radians), so no two share lattice axes. */
    private static final double[] RIDGE_COS = {Math.cos(.52), Math.cos(1.91), Math.cos(2.77)},
            RIDGE_SIN = {Math.sin(.52), Math.sin(1.91), Math.sin(2.77)};

    private final SeededNoise body, detail, wide;
    private final double bodyScale, detailScale, wideScale;
    public final int minimumSurfaceY;

    BaseTerrainMap(
            long seed,
            BaseElevationMap elevation,
            ProvinceMap provinces,
            TerrainHeightReference heights,
            RegionalProfileMap profiles) {
        if (elevation == null || provinces == null || heights == null || profiles == null)
            throw new NullPointerException();
        this.elevation = elevation;
        ground =
                new ProvinceGround(
                        provinces,
                        provinces.edgeBlocks()
                                * Rules.number(RULES.json, "groundStepInEdges", .05, 1));
        this.profiles = profiles;
        seaLevel = heights.seaLevel;
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
        groundAmplitude = Rules.number(j, "groundAmplitudeBlocks", 0, 64);
        groundAmplitudePerBlock = Rules.number(j, "groundAmplitudePerBlock", 0, 1);
        JsonObject c = j.getAsJsonObject("character");
        characterScale = Rules.number(c, "wavelengthBlocks", 64, 8192);
        characterStart = Rules.number(c, "strongFrom", 0, 1);
        characterFull = Rules.number(c, "strongAt", characterStart, 1);
        characterFloor = Rules.number(c, "calmStrength", 0, 1);
        JsonObject r = j.getAsJsonObject("ridges");
        ridgeScale = Rules.number(r, "wavelengthBlocks", 32, 8192);
        ridgeGain = Rules.number(r, "gain", 0, 12);
        ridgeWarp = Rules.number(r, "warpBlocks", 0, 4096);
        ridgeWarpScale = Rules.number(r, "warpWavelengthBlocks", 64, 16384);
        rollingGain = Rules.number(c, "rollingGain", 0, 8);
        JsonObject sp = j.getAsJsonObject("spine");
        spineLift = Rules.number(sp, "liftBlocks", 0, 320);
        spineRelief = Rules.number(sp, "reliefBlocks", 0, 320);
        spineRidged = Rules.number(sp, "ridged", 0, 1);
        JsonObject pl = j.getAsJsonObject("plateau");
        scarpWidth = Rules.number(pl, "scarpWidth", .01, .5);
        plateauEdge = Rules.number(pl, "edgeAmplitude", 0, .45);
        plateauEdgeScale = Rules.number(pl, "edgeWavelengthBlocks", 64, 8192);
        plateauFlatten = Rules.number(pl, "flatten", 0, 1);
        scarpTerrace = Rules.number(pl, "scarpTerrace", 0, 1);
        spineYieldFrom = Rules.number(pl, "spineYieldFrom", 0, 1);
        spineYieldFull = Rules.number(pl, "spineYieldFull", spineYieldFrom + .01, 1);
        JsonObject lf = j.getAsJsonObject("palette");
        hillScale = Rules.number(lf, "hillWavelengthBlocks", 32, 4096);
        spurScale = Rules.number(lf, "spurWavelengthBlocks", 32, 4096);
        swellScale = Rules.number(lf, "swellWavelengthBlocks", 64, 16384);
        hollowScale = Rules.number(lf, "hollowWavelengthBlocks", 16, 4096);
        variety = Rules.number(lf, "variety", 0, 1);
        JsonObject t = j.getAsJsonObject("terraces");
        terraceBlocks = Rules.number(t, "stepBlocks", 2, 64);
        terraceRiser = Rules.number(t, "riserShare", .05, .9);
        ceiling = heights.highestSurface - Rules.number(j, "ceilingMarginBlocks", 0, 64);
        ceilingStart = ceiling - Rules.number(j, "ceilingSoftBlocks", 1, 128);
        character = new SeededNoise(SeededNoise.mix(seed ^ 0x4348415241435452L));
        ridge1 = new SeededNoise(SeededNoise.mix(seed ^ 0x5249444745303131L));
        ridge2 = new SeededNoise(SeededNoise.mix(seed ^ 0x5249444745303232L));
        ridge3 = new SeededNoise(SeededNoise.mix(seed ^ 0x5249444745303333L));
        warpX = new SeededNoise(SeededNoise.mix(seed ^ 0x5249444745575858L));
        warpZ = new SeededNoise(SeededNoise.mix(seed ^ 0x52494447455f575aL));
        edge1 = new SeededNoise(SeededNoise.mix(seed ^ 0x504c4154454131L));
        edge2 = new SeededNoise(SeededNoise.mix(seed ^ 0x504c4154454132L));
        hillA = new SeededNoise(SeededNoise.mix(seed ^ 0x48494c4c41L));
        hillB = new SeededNoise(SeededNoise.mix(seed ^ 0x48494c4c42L));
        spurN = new SeededNoise(SeededNoise.mix(seed ^ 0x53505552L));
        swellN = new SeededNoise(SeededNoise.mix(seed ^ 0x5357454c4cL));
        hollowN = new SeededNoise(SeededNoise.mix(seed ^ 0x484f4c4c4f57L));
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

        /** The province ground (blocks above the sea) and its landform position (0..3). */
        private double ground, position;

        /** The continents' spine weight and a plateau's cap weight here (REGIONAL), else 0. */
        private double spine, plateau;

        private double minimumY, maximumY;

        /** Province fields and their x and z derivatives (ProvinceGround.sample). */
        private final double[] fields = new double[3 * ProvinceGround.FIELDS];

        private final HeightSample patch = new HeightSample(),
                ridgeA = new HeightSample(),
                ridgeB = new HeightSample(),
                ridgeC = new HeightSample(),
                warpA = new HeightSample(),
                warpB = new HeightSample(),
                edgeA = new HeightSample(),
                edgeB = new HeightSample(),
                landA = new HeightSample(),
                landB = new HeightSample();

        public double ground() {
            return ground;
        }

        /** The landform position elevation classes read: the ground's (REGIONAL), else intent's. */
        public double position() {
            return position;
        }

        public double spine() {
            return spine;
        }

        public double plateau() {
            return plateau;
        }

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

        /**
         * The room land stages may raise and cut within: on province ground (REGIONAL) from one
         * block above the sea to the soft ceiling, so the bands, spines and plateaus are legal
         * parents; the intent elevation's landform band otherwise.
         */
        public double minimumY() {
            return minimumY;
        }

        public double maximumY() {
            return maximumY;
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
        out.minimumY = seaLevel + 1;
        out.maximumY = ceiling;
        profiles.sample(x, z, out.region);
        wide.sample(x / wideScale + .53, z / wideScale + .17, out.wide);
        // S4: the province bands set the level; their characters shape it. Every term below
        // carries its exact derivative.
        double[] f = out.fields;
        ground.sample(x, z, f);
        int fx = ProvinceGround.FIELDS, fz = 2 * ProvinceGround.FIELDS;
        // Plateaus: the level stands on the cap where the plateau share, warped by an edge
        // field that leaves mesas outside the rim and notches canyons into it, passes one
        // half, and on the ground below elsewhere, joined by a scarp; the cap loses its ridges.
        double cap = 0, capDx = 0, capDz = 0;
        if (f[3] > 0) {
            double ps = plateauEdgeScale;
            edge1.gradient(x / ps + .17, z / ps + .61, out.edgeA);
            edge2.gradient(3 * x / ps + .44, 3 * z / ps + .29, out.edgeB);
            double rim = plateauEdge * (out.edgeA.height + .5 * out.edgeB.height) / 1.5;
            double ex = plateauEdge * (out.edgeA.dx + 1.5 * out.edgeB.dx) / (1.5 * ps);
            double ez = plateauEdge * (out.edgeA.dz + 1.5 * out.edgeB.dz) / (1.5 * ps);
            double q = (f[3] + rim - .5) / scarpWidth + .5;
            cap = Rules.smooth(q);
            double slope = smoothSlope(q) / scarpWidth;
            capDx = slope * (f[fx + 3] + ex);
            capDz = slope * (f[fz + 3] + ez);
            // A spine wins over a cap: ranges keep their ridges where they cross a plateau.
            double yq = (f[4] - spineYieldFrom) / (spineYieldFull - spineYieldFrom);
            double yieldW = Rules.smooth(yq);
            if (yieldW > 0) {
                double ys = smoothSlope(yq) / (spineYieldFull - spineYieldFrom);
                capDx = capDx * (1 - yieldW) - cap * ys * f[fx + 4];
                capDz = capDz * (1 - yieldW) - cap * ys * f[fz + 4];
                cap *= 1 - yieldW;
            }
            double gap = f[5] - f[6];
            f[0] = f[6] + gap * cap;
            f[fx] = f[fx + 6] + (f[fx + 5] - f[fx + 6]) * cap + gap * capDx;
            f[fz] = f[fz + 6] + (f[fz + 5] - f[fz + 6]) * cap + gap * capDz;
            f[fx + 2] = f[fx + 2] * (1 - cap) - f[2] * capDx;
            f[fz + 2] = f[fz + 2] * (1 - cap) - f[2] * capDz;
            f[2] *= 1 - cap;
        }
        // The continents' spines raise ranges along the land's own axis: the weight lifts the
        // level and pulls relief and ridged share toward a range's, wherever the province.
        double spine = f[4];
        out.spine = Math.max(0, Math.min(1, spine));
        out.plateau = cap;
        if (spine > 0) {
            double sx = f[fx + 4], sz = f[fz + 4];
            f[0] += spineLift * spine;
            f[fx] += spineLift * sx;
            f[fz] += spineLift * sz;
            for (int k = 1; k <= 2; k++) {
                double target = k == 1 ? spineRelief : spineRidged, gap = target - f[k];
                f[fx + k] = f[fx + k] * (1 - spine) + gap * sx;
                f[fz + k] = f[fz + k] * (1 - spine) + gap * sz;
                f[k] += gap * spine;
            }
        }
        double level = f[0], groundDx = f[fx], groundDz = f[fz];
        // Character strength: a slow patch field, calm over most of a province.
        character.sample(x / characterScale + .11, z / characterScale + .83, out.patch);
        double patchSpan = characterFull - characterStart;
        double t = (.5 + .5 * out.patch.height - characterStart) / patchSpan;
        double strength = characterFloor + (1 - characterFloor) * Rules.smooth(t);
        double strengthSlope =
                (1 - characterFloor) * smoothSlope(t) / patchSpan * .5 / characterScale;
        double strengthDx = strengthSlope * out.patch.dx, strengthDz = strengthSlope * out.patch.dz;
        double mean = seaLevel + level;
        out.ground = level;
        out.position = elevation.position(mean);
        // The rolling part carries the unridged share of the province's relief.
        double unridged = 1 - f[2];
        double envelope =
                groundAmplitude
                        + groundAmplitudePerBlock * level
                        + rollingGain * f[1] * strength * unridged;
        double envelopeDx =
                groundAmplitudePerBlock * groundDx
                        + rollingGain
                                * (f[fx + 1] * strength * unridged
                                        + f[1] * strengthDx * unridged
                                        - f[1] * strength * f[fx + 2]);
        double envelopeDz =
                groundAmplitudePerBlock * groundDz
                        + rollingGain
                                * (f[fz + 1] * strength * unridged
                                        + f[1] * strengthDz * unridged
                                        - f[1] * strength * f[fz + 2]);
        double fraction = out.region.amplitudeFraction();
        double amplitude = envelope * fraction;
        double amplitudeDx = envelopeDx * fraction + envelope * out.region.amplitudeDx();
        double amplitudeDz = envelopeDz * fraction + envelope * out.region.amplitudeDz();
        // A plateau's cap is calm: it keeps a share of the ground's amplitude.
        if (cap > 0) {
            double flat = 1 - plateauFlatten * cap;
            amplitudeDx = amplitudeDx * flat - amplitude * plateauFlatten * capDx;
            amplitudeDz = amplitudeDz * flat - amplitude * plateauFlatten * capDz;
            amplitude *= flat;
        }
        double w = out.region.wideShare(), fineShare = out.region.fineShare();
        double c = 1 - w - fineShare;
        double coarse = w * out.wide.height + c * out.body.height;
        double n = coarse + fineShare * out.detail.height;
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
                        + fineShare * out.detail.dx / detailScale
                        + out.region.fineDx() * out.detail.height;
        double nz =
                coarseDz
                        + fineShare * out.detail.dz / detailScale
                        + out.region.fineDz() * out.detail.height;
        double meanDx = groundDx;
        double meanDz = groundDz;
        out.amplitude = amplitude;
        out.noise = n;
        // Ridges: crests and valleys where the province is ridged; mountains are never calm,
        // so a ridged province keeps at least its ridged share of strength.
        double ridges = 0, ridgesDx = 0, ridgesDz = 0;
        double keep = Math.max(strength, f[2]);
        double keepDx = strength >= f[2] ? strengthDx : f[fx + 2];
        double keepDz = strength >= f[2] ? strengthDz : f[fz + 2];
        double ridgeAmplitude = ridgeGain * f[1] * f[2] * keep;
        if (ridgeAmplitude > 0) {
            double ampDx =
                    ridgeGain
                            * (f[fx + 1] * f[2] * keep
                                    + f[1] * f[fx + 2] * keep
                                    + f[1] * f[2] * keepDx);
            double ampDz =
                    ridgeGain
                            * (f[fz + 1] * f[2] * keep
                                    + f[1] * f[fz + 2] * keep
                                    + f[1] * f[2] * keepDz);
            // A domain warp bends the crests into long, curving range lines; (jxx jxz; jzx
            // jzz) is its Jacobian, which carries the octaves' derivatives back to x and z.
            warpX.gradient(x / ridgeWarpScale + .31, z / ridgeWarpScale + .72, out.warpA);
            warpZ.gradient(x / ridgeWarpScale + .66, z / ridgeWarpScale + .15, out.warpB);
            double bend = ridgeWarp / ridgeWarpScale;
            double px = x + ridgeWarp * out.warpA.height, pz = z + ridgeWarp * out.warpB.height;
            double jxx = 1 + bend * out.warpA.dx, jxz = bend * out.warpA.dz;
            double jzx = bend * out.warpB.dx, jzz = 1 + bend * out.warpB.dz;
            // Three rotated gradient octaves: their zero lines, the crests, follow no axis.
            octave(ridge1, px, pz, ridgeScale, 0, .27, .64, out.ridgeA);
            octave(ridge2, px, pz, ridgeScale / 2.1, 1, .51, .08, out.ridgeB);
            octave(ridge3, px, pz, ridgeScale / 4.4, 2, .93, .38, out.ridgeC);
            double a = ridged(out.ridgeA.height), b = ridged(out.ridgeB.height);
            double cc = ridged(out.ridgeC.height);
            double ad = ridgedSlope(out.ridgeA.height);
            double bd = ridgedSlope(out.ridgeB.height);
            double cd = ridgedSlope(out.ridgeC.height);
            double ax = ad * out.ridgeA.dx, az = ad * out.ridgeA.dz;
            double bx = bd * out.ridgeB.dx, bz = bd * out.ridgeB.dz;
            double cx = cd * out.ridgeC.dx, cz = cd * out.ridgeC.dz;
            // Sharper crests where the first octave is high: small ridges ride on big ones.
            double g = .35 + .65 * a;
            double r = a + .5 * b * g + .25 * cc * g;
            double rpx = ax + .5 * (bx * g + b * .65 * ax) + .25 * (cx * g + cc * .65 * ax);
            double rpz = az + .5 * (bz * g + b * .65 * az) + .25 * (cz * g + cc * .65 * az);
            double rx = rpx * jxx + rpz * jzx, rz = rpx * jxz + rpz * jzz;
            // Centred for gradient octaves: the shape's mean and its 95th percentile above
            // the mean match the value-noise ridges these replaced (mean 0.922, p95 +0.516
            // of r here), so bands keep their heights and only the pattern changes.
            double shape = r / RIDGE_SPREAD - RIDGE_CENTRE;
            ridges = ridgeAmplitude * shape;
            ridgesDx = ampDx * shape + ridgeAmplitude * rx / RIDGE_SPREAD;
            ridgesDz = ampDz * shape + ridgeAmplitude * rz / RIDGE_SPREAD;
        }
        // Landform palettes: the province type's rounded hills, spurs, broad swells and
        // hollows. The character varies the hills and spurs; a plateau's cap stays calm.
        double forms = 0, formsDx = 0, formsDz = 0;
        {
            double calm = 1 - cap, calmDx = -capDx, calmDz = -capDz;
            double vary = 1 - variety + variety * strength;
            double varyDx = variety * strengthDx, varyDz = variety * strengthDz;
            if (f[7] > 0) {
                // Rounded hills: two octaves shaped into domes, u (1.5 - 0.5 u^2).
                octave(hillA, x, z, hillScale, 0, .21, .57, out.landA);
                octave(hillB, x, z, hillScale / 2.3, 1, .68, .12, out.landB);
                double u = (out.landA.height + .45 * out.landB.height) / 1.45;
                double hx = (out.landA.dx + .45 * out.landB.dx) / 1.45;
                double hz = (out.landA.dz + .45 * out.landB.dz) / 1.45;
                double s = u * (1.5 - .5 * u * u), ds = 1.5 - 1.5 * u * u;
                double a = f[7] * vary * calm;
                forms += a * s;
                formsDx +=
                        a * ds * hx
                                + s
                                        * (f[fx + 7] * vary * calm
                                                + f[7] * varyDx * calm
                                                + f[7] * vary * calmDx);
                formsDz +=
                        a * ds * hz
                                + s
                                        * (f[fz + 7] * vary * calm
                                                + f[7] * varyDz * calm
                                                + f[7] * vary * calmDz);
            }
            if (f[8] > 0) {
                // Spurs: small ridged crests and valleys, centred on their mean.
                octave(spurN, x, z, spurScale, 2, .35, .91, out.landA);
                double r = ridged(out.landA.height) - .45, dr = ridgedSlope(out.landA.height);
                double a = f[8] * vary * calm;
                forms += a * r;
                formsDx +=
                        a * dr * out.landA.dx
                                + r
                                        * (f[fx + 8] * vary * calm
                                                + f[8] * varyDx * calm
                                                + f[8] * vary * calmDx);
                formsDz +=
                        a * dr * out.landA.dz
                                + r
                                        * (f[fz + 8] * vary * calm
                                                + f[8] * varyDz * calm
                                                + f[8] * vary * calmDz);
            }
            if (f[9] > 0) {
                // Broad swells: one slow octave.
                octave(swellN, x, z, swellScale, 1, .84, .43, out.landB);
                forms += f[9] * out.landB.height;
                formsDx += f[9] * out.landB.dx + f[fx + 9] * out.landB.height;
                formsDz += f[9] * out.landB.dz + f[fz + 9] * out.landB.height;
            }
            if (f[10] > 0) {
                // Hollows: a flat floor with shallow dips where the field falls below zero.
                octave(hollowN, x, z, hollowScale, 0, .52, .26, out.landA);
                double u = out.landA.height;
                if (u < 0) {
                    double dip = u * u, a = f[10] * calm;
                    forms -= a * dip;
                    formsDx -=
                            a * 2 * u * out.landA.dx + dip * (f[fx + 10] * calm + f[10] * calmDx);
                    formsDz -=
                            a * 2 * u * out.landA.dz + dip * (f[fz + 10] * calm + f[10] * calmDz);
                }
            }
        }
        ridges += forms;
        ridgesDx += formsDx;
        ridgesDz += formsDz;
        out.coarseHeight = mean + amplitude * coarse + ridges;
        out.fineContribution = amplitude * fineShare * out.detail.height;
        double height = mean + amplitude * n + ridges;
        double dx = meanDx + amplitudeDx * n + amplitude * nx + ridgesDx;
        double dz = meanDz + amplitudeDz * n + amplitude * nz + ridgesDz;
        // Terraces: flat steps with short risers on a plateau's scarp, a broken escarpment.
        double steps = scarpTerrace * 4 * cap * (1 - cap);
        if (steps > 0) {
            double stepsDx = scarpTerrace * 4 * (1 - 2 * cap) * capDx;
            double stepsDz = scarpTerrace * 4 * (1 - 2 * cap) * capDz;
            double q = (height - seaLevel) / terraceBlocks, floor = Math.floor(q);
            double rise = Rules.smooth((q - floor - (1 - terraceRiser)) / terraceRiser);
            double stepped = seaLevel + (floor + rise) * terraceBlocks;
            double slope =
                    q - floor < 1 - terraceRiser
                            ? 0
                            : smoothSlope((q - floor - (1 - terraceRiser)) / terraceRiser)
                                    / terraceRiser;
            double k = 1 - steps + steps * slope;
            double gap = stepped - height;
            height = height + gap * steps;
            dx = dx * k + gap * stepsDx;
            dz = dz * k + gap * stepsDz;
        }
        // The soft ceiling bends peaks toward the allocation's top.
        if (height > ceilingStart) {
            double span = ceiling - ceilingStart, u = (height - ceilingStart) / span;
            double bent = ceilingStart + span * Math.tanh(u);
            double k = 1 - Math.tanh(u) * Math.tanh(u);
            height = bent;
            dx *= k;
            dz *= k;
        }
        out.height = height;
        out.dx = dx;
        out.dz = dz;
        out.broadDx = meanDx + amplitudeDx * coarse + amplitude * coarseDx + ridgesDx;
        out.broadDz = meanDz + amplitudeDz * coarse + amplitude * coarseDz + ridgesDz;
    }

    /**
     * One gradient octave at (x, z), wavelength scale, rotated by octave k's angle; its
     * derivatives are with respect to x and z.
     */
    private static void octave(
            SeededNoise noise,
            double x,
            double z,
            double scale,
            int k,
            double ox,
            double oz,
            HeightSample out) {
        double c = RIDGE_COS[k], s = RIDGE_SIN[k];
        noise.gradient((c * x - s * z) / scale + ox, (s * x + c * z) / scale + oz, out);
        double nx = out.dx, nz = out.dz;
        out.dx = (nx * c + nz * s) / scale;
        out.dz = (-nx * s + nz * c) / scale;
    }

    /** One ridged octave, (1 - |n|)^2, and its slope with respect to n. */
    private static double ridged(double n) {
        double v = 1 - Math.abs(n);
        return v * v;
    }

    private static double ridgedSlope(double n) {
        return -2 * (1 - Math.abs(n)) * Math.signum(n);
    }

    private static double smoothSlope(double t) {
        if (t <= 0 || t >= 1) return 0;
        return 30 * t * t * (t - 1) * (t - 1);
    }

    /** Clears private inland observations when the composing stage instead owns a marine column. */
    void clear(Sample out) {
        out.height = out.dx = out.dz = out.broadDx = out.broadDz = 0;
        out.amplitude = out.noise = out.coarseHeight = out.fineContribution = 0;
        out.ground = 0;
        out.position = -1;
        out.spine = out.plateau = 0;
        out.minimumY = out.maximumY = 0;
        elevation.clear(out.elevation);
        profiles.clear(out.region);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
