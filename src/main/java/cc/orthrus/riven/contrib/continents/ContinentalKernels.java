/* SPDX-License-Identifier: MIT
 * Independently authored Riven contribution. See SOURCES.md; no external implementation imported.
 */
/* Riven adaptation (2026-10-20): immutable Java-8 value classes and switch, and coordinate
 * access; (2026-10-23) per-continent variation and cached cell plans. The conservative region
 * bounds for native water admission were removed: nothing has read them since that was retired.
 * Geometry, salts and original point ownership remain. Extensions remain MIT.
 * Packaged grant/notices: META-INF/licenses/continents/{MIT.txt,SOURCES.md}.
 * Riven modification (2026-11-03): the family factory, the kernels' default-style
 * constructors, the default tuning and the tuning copies (withRadius, withDetail,
 * withOccurrence) removed; Riven never called them.
 */
package cc.orthrus.riven.contrib.continents;

import static java.lang.StrictMath.*;

/** Seed-only horizontal geometry. No Minecraft, loader, IO, cache, executor or global mutable state. */
public final class ContinentalKernels {
    private ContinentalKernels() {}

    private static final double TAU = 2 * PI;

    public enum Family {
        HARMONIC(0x524956434F4E5431L),
        SPINE(0x524956434F4E5432L),
        POTENTIAL(0x524956434F4E5434L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        LAND,
        OUTSIDE_SUPPORT,
        EARLIER_INELIGIBLE,
        SEEDED_OPEN_SEA,
        SHAPE_OCEAN,
        BAY,
        OCEAN_CORRIDOR,
        EARLIER_RESERVATION
    }

    /** Immutable reference snapshot supplied from context.terrainHeights, not an alternate Y definition. */
    public static final class Reference {
        public final int seaY;
        public final int waterTopY;
        public final int lowestSurfaceY;
        public final int highestSurfaceY;
        public final double coastalPlainWidth;
        public final double horizontalScale;
        public final String fingerprint;

        public Reference(
                int seaY,
                int waterTopY,
                int lowestSurfaceY,
                int highestSurfaceY,
                double coastalPlainWidth,
                double horizontalScale,
                String fingerprint) {
            if (waterTopY + 1 != seaY
                    || lowestSurfaceY >= seaY
                    || highestSurfaceY <= seaY
                    || !positive(coastalPlainWidth)
                    || !positive(horizontalScale)
                    || fingerprint == null
                    || fingerprint.isEmpty()) throw new IllegalArgumentException("reference");

            this.seaY = seaY;
            this.waterTopY = waterTopY;
            this.lowestSurfaceY = lowestSurfaceY;
            this.highestSurfaceY = highestSurfaceY;
            this.coastalPlainWidth = coastalPlainWidth;
            this.horizontalScale = horizontalScale;
            this.fingerprint = fingerprint;
        }

        public int seaY() {
            return seaY;
        }

        public int waterTopY() {
            return waterTopY;
        }

        public int lowestSurfaceY() {
            return lowestSurfaceY;
        }

        public int highestSurfaceY() {
            return highestSurfaceY;
        }

        public double coastalPlainWidth() {
            return coastalPlainWidth;
        }

        public double horizontalScale() {
            return horizontalScale;
        }

        public String fingerprint() {
            return fingerprint;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Reference)) return false;
            Reference other = (Reference) value;
            return seaY == other.seaY
                    && waterTopY == other.waterTopY
                    && lowestSurfaceY == other.lowestSurfaceY
                    && highestSurfaceY == other.highestSurfaceY
                    && Double.compare(coastalPlainWidth, other.coastalPlainWidth) == 0
                    && Double.compare(horizontalScale, other.horizontalScale) == 0
                    && java.util.Objects.equals(fingerprint, other.fingerprint);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    seaY,
                    waterTopY,
                    lowestSurfaceY,
                    highestSurfaceY,
                    coastalPlainWidth,
                    horizontalScale,
                    fingerprint);
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.ROOT,
                    "Reference[seaY=%s,waterTopY=%s,lowestSurfaceY=%s,highestSurfaceY=%s,coastalPlainWidth=%s,horizontalScale=%s,fingerprint=%s]",
                    seaY,
                    waterTopY,
                    lowestSurfaceY,
                    highestSurfaceY,
                    coastalPlainWidth,
                    horizontalScale,
                    fingerprint);
        }
    }

    /** All distances are physical blocks. Continental scale deliberately does NOT use TerrainScale.HORIZONTAL. */
    public static final class Tuning {
        public final double spacing;
        public final double radius;
        public final double detailAmplitude;
        public final double detailWavelength;
        public final double warpAmplitude;
        public final double warpWavelength;
        public final double occurrence;
        public final double eligibilityFloor;
        public final double bridgeHalfWidth;
        public final double bayDepth;
        public final double bayHalfWidth;
        public final double corridorHalfWidth;

        /** Riven adaptation (2026-10-22): shift of the corridor lattice along both axes. */
        public final double corridorOffset;

        /** Riven adaptation (2026-10-22): corridor lattice period; the original is 3 spacings. */
        public final double corridorPeriod;

        /**
         * Riven adaptation (2026-10-22): when 1-4, exactly this many cells of every 2 x 2 block
         * hold a continent (seeded rank), replacing the independent occurrence draw.
         */
        public final int stratifiedOccupancy;

        public final double transitionWidth;

        public Tuning(
                double spacing,
                double radius,
                double detailAmplitude,
                double detailWavelength,
                double warpAmplitude,
                double warpWavelength,
                double occurrence,
                double eligibilityFloor,
                double bridgeHalfWidth,
                double bayDepth,
                double bayHalfWidth,
                double corridorHalfWidth,
                double transitionWidth) {
            this(
                    spacing,
                    radius,
                    detailAmplitude,
                    detailWavelength,
                    warpAmplitude,
                    warpWavelength,
                    occurrence,
                    eligibilityFloor,
                    bridgeHalfWidth,
                    bayDepth,
                    bayHalfWidth,
                    corridorHalfWidth,
                    transitionWidth,
                    0,
                    3 * spacing,
                    0);
        }

        public Tuning(
                double spacing,
                double radius,
                double detailAmplitude,
                double detailWavelength,
                double warpAmplitude,
                double warpWavelength,
                double occurrence,
                double eligibilityFloor,
                double bridgeHalfWidth,
                double bayDepth,
                double bayHalfWidth,
                double corridorHalfWidth,
                double transitionWidth,
                double corridorOffset,
                double corridorPeriod,
                int stratifiedOccupancy) {
            double[] lengths = {
                spacing,
                radius,
                detailWavelength,
                warpWavelength,
                bridgeHalfWidth,
                bayDepth,
                bayHalfWidth,
                corridorHalfWidth,
                transitionWidth
            };
            for (double v : lengths)
                if (!positive(v)) throw new IllegalArgumentException("positive lengths");
            if (!nonnegative(detailAmplitude)
                    || !nonnegative(warpAmplitude)
                    || !unit(occurrence)
                    || !unit(eligibilityFloor)
                    || spacing < 1024
                    || spacing > 30_000_000
                    // Riven adaptation (2026-10-22): supports may overlap neighbouring cells so
                    // continents can merge; the 3 x 3 search stays exact while support plus the
                    // transition stays inside the 1.42-spacing unsearched-anchor bound below.
                    || radius > .65 * spacing
                    || radius < .18 * spacing
                    || detailWavelength < 32
                    || warpWavelength < max(512, 4 * warpAmplitude)
                    || detailAmplitude > .025 * spacing
                    || warpAmplitude > .15 * spacing
                    || 2 * radius + .025 * spacing + transitionWidth > 1.4 * spacing
                    || bridgeHalfWidth < 2 * detailAmplitude + 8
                    || bridgeHalfWidth > .3 * radius
                    || bayDepth > 1.2 * radius
                    || bayHalfWidth > .35 * radius
                    || corridorHalfWidth < 8
                    || transitionWidth > .15 * spacing)
                throw new IllegalArgumentException("unsupported tuning envelope");

            this.spacing = spacing;
            this.radius = radius;
            this.detailAmplitude = detailAmplitude;
            this.detailWavelength = detailWavelength;
            this.warpAmplitude = warpAmplitude;
            this.warpWavelength = warpWavelength;
            this.occurrence = occurrence;
            this.eligibilityFloor = eligibilityFloor;
            this.bridgeHalfWidth = bridgeHalfWidth;
            this.bayDepth = bayDepth;
            this.bayHalfWidth = bayHalfWidth;
            this.corridorHalfWidth = corridorHalfWidth;
            this.transitionWidth = transitionWidth;
            if (!(corridorPeriod >= spacing && corridorPeriod <= 30_000_000))
                throw new IllegalArgumentException("corridor period");
            this.corridorPeriod = corridorPeriod;
            if (!(corridorOffset >= 0 && corridorOffset < corridorPeriod))
                throw new IllegalArgumentException("corridor offset");
            this.corridorOffset = corridorOffset;
            if (stratifiedOccupancy < 0 || stratifiedOccupancy > 4)
                throw new IllegalArgumentException("stratified occupancy");
            this.stratifiedOccupancy = stratifiedOccupancy;
        }

        public double spacing() {
            return spacing;
        }

        public double radius() {
            return radius;
        }

        public double detailAmplitude() {
            return detailAmplitude;
        }

        public double detailWavelength() {
            return detailWavelength;
        }

        public double warpAmplitude() {
            return warpAmplitude;
        }

        public double warpWavelength() {
            return warpWavelength;
        }

        public double occurrence() {
            return occurrence;
        }

        public double eligibilityFloor() {
            return eligibilityFloor;
        }

        public double bridgeHalfWidth() {
            return bridgeHalfWidth;
        }

        public double bayDepth() {
            return bayDepth;
        }

        public double bayHalfWidth() {
            return bayHalfWidth;
        }

        public double corridorHalfWidth() {
            return corridorHalfWidth;
        }

        public double transitionWidth() {
            return transitionWidth;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Tuning)) return false;
            Tuning other = (Tuning) value;
            return Double.compare(spacing, other.spacing) == 0
                    && Double.compare(radius, other.radius) == 0
                    && Double.compare(detailAmplitude, other.detailAmplitude) == 0
                    && Double.compare(detailWavelength, other.detailWavelength) == 0
                    && Double.compare(warpAmplitude, other.warpAmplitude) == 0
                    && Double.compare(warpWavelength, other.warpWavelength) == 0
                    && Double.compare(occurrence, other.occurrence) == 0
                    && Double.compare(eligibilityFloor, other.eligibilityFloor) == 0
                    && Double.compare(bridgeHalfWidth, other.bridgeHalfWidth) == 0
                    && Double.compare(bayDepth, other.bayDepth) == 0
                    && Double.compare(bayHalfWidth, other.bayHalfWidth) == 0
                    && Double.compare(corridorHalfWidth, other.corridorHalfWidth) == 0
                    && Double.compare(transitionWidth, other.transitionWidth) == 0
                    && Double.compare(corridorOffset, other.corridorOffset) == 0
                    && Double.compare(corridorPeriod, other.corridorPeriod) == 0
                    && stratifiedOccupancy == other.stratifiedOccupancy;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    spacing,
                    radius,
                    detailAmplitude,
                    detailWavelength,
                    warpAmplitude,
                    warpWavelength,
                    occurrence,
                    eligibilityFloor,
                    bridgeHalfWidth,
                    bayDepth,
                    bayHalfWidth,
                    corridorHalfWidth,
                    transitionWidth,
                    corridorOffset,
                    corridorPeriod,
                    stratifiedOccupancy);
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.ROOT,
                    "Tuning[spacing=%s,radius=%s,detailAmplitude=%s,detailWavelength=%s,warpAmplitude=%s,warpWavelength=%s,occurrence=%s,eligibilityFloor=%s,bridgeHalfWidth=%s,bayDepth=%s,bayHalfWidth=%s,corridorHalfWidth=%s,transitionWidth=%s,corridorOffset=%s,corridorPeriod=%s,stratifiedOccupancy=%s]",
                    spacing,
                    radius,
                    detailAmplitude,
                    detailWavelength,
                    warpAmplitude,
                    warpWavelength,
                    occurrence,
                    eligibilityFloor,
                    bridgeHalfWidth,
                    bayDepth,
                    bayHalfWidth,
                    corridorHalfWidth,
                    transitionWidth,
                    corridorOffset,
                    corridorPeriod,
                    stratifiedOccupancy);
        }
    }

    /** Earlier immutable seed-owned facts only. Implementations must be pure, bounded and thread-safe.
     * Suitability and island bias in [0,1]; reservation clearance >0 permits land, <=0 reserves marine.
     * No method may consult finished terrain, hydrology plans or this sampler. Values must be finite.
     * Selection uses ONLY these facts at stable anchors, never a query-point-derived eligibility gate.
     */
    public interface Earlier {
        double suitability(double anchorX, double anchorZ);

        double islandBias(double anchorX, double anchorZ);

        double marineClearance(double worldX, double worldZ);
    }

    /** Family controls stay inside the proven common support envelope. */
    public static final class HarmonicStyle {
        public final double harmonicStrength;
        public final double satelliteScale;

        public HarmonicStyle(double harmonicStrength, double satelliteScale) {
            if (!unit(harmonicStrength) || !unit(satelliteScale))
                throw new IllegalArgumentException("harmonic style");
            this.harmonicStrength = harmonicStrength;
            this.satelliteScale = satelliteScale;
        }

        public double harmonicStrength() {
            return harmonicStrength;
        }

        public double satelliteScale() {
            return satelliteScale;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof HarmonicStyle)) return false;
            HarmonicStyle other = (HarmonicStyle) value;
            return Double.compare(harmonicStrength, other.harmonicStrength) == 0
                    && Double.compare(satelliteScale, other.satelliteScale) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(harmonicStrength, satelliteScale);
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.ROOT,
                    "HarmonicStyle[harmonicStrength=%s,satelliteScale=%s]",
                    harmonicStrength,
                    satelliteScale);
        }

        public static HarmonicStyle defaults() {
            return new HarmonicStyle(1, 1);
        }
    }

    public static final class SpineStyle {
        public final double widthScale;
        public final double spurScale;

        public SpineStyle(double widthScale, double spurScale) {
            if (!unit(widthScale) || widthScale < .5 || !unit(spurScale))
                throw new IllegalArgumentException("spine style");
            this.widthScale = widthScale;
            this.spurScale = spurScale;
        }

        public double widthScale() {
            return widthScale;
        }

        public double spurScale() {
            return spurScale;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof SpineStyle)) return false;
            SpineStyle other = (SpineStyle) value;
            return Double.compare(widthScale, other.widthScale) == 0
                    && Double.compare(spurScale, other.spurScale) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(widthScale, spurScale);
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.ROOT,
                    "SpineStyle[widthScale=%s,spurScale=%s]",
                    widthScale,
                    spurScale);
        }

        public static SpineStyle defaults() {
            return new SpineStyle(1, 1);
        }
    }

    public static final class PotentialStyle {
        public final double mainlandThreshold;
        public final double islandThreshold;

        public PotentialStyle(double mainlandThreshold, double islandThreshold) {
            if (!unit(mainlandThreshold)
                    || !unit(islandThreshold)
                    || mainlandThreshold < .12
                    || mainlandThreshold > .8
                    || islandThreshold < .12
                    || islandThreshold > .8) throw new IllegalArgumentException("potential style");
            this.mainlandThreshold = mainlandThreshold;
            this.islandThreshold = islandThreshold;
        }

        public double mainlandThreshold() {
            return mainlandThreshold;
        }

        public double islandThreshold() {
            return islandThreshold;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof PotentialStyle)) return false;
            PotentialStyle other = (PotentialStyle) value;
            return Double.compare(mainlandThreshold, other.mainlandThreshold) == 0
                    && Double.compare(islandThreshold, other.islandThreshold) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(mainlandThreshold, islandThreshold);
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.ROOT,
                    "PotentialStyle[mainlandThreshold=%s,islandThreshold=%s]",
                    mainlandThreshold,
                    islandThreshold);
        }

        public static PotentialStyle defaults() {
            return new PotentialStyle(.31, .26);
        }
    }

    /**
     * Riven extension (2026-10-23): per-continent variation. Each admitted continent draws its own
     * outline family, size, area-preserving elongation and coast roughness from its seeded id, and
     * anchors jitter further from the lattice. Shelter is the owner's coast concavity at the column (see
     * Kernel.concavity), so embayments are read from the outline instead of carved; the legacy ray-tube bay stays as an optional per-continent draw.
     */
    public static final class Variation {
        public final double jitter, sizeMin, sizeMax, sizeSkew, elongationMax;

        /**
         * Clustering: one smooth field over anchors (wavelength clusterWavelength, layout blocks)
         * raises sizes by up to clusterSize of the size range where it is high and lowers
         * occupancy by up to clusterOccupancy where it is low, so continents gather in clusters
         * with archipelagos between them and open ocean beyond.
         */
        public final double clusterWavelength, clusterSize, clusterOccupancy;

        /**
         * The island tier: each cell draws up to islandSlots islands, each with chance
         * islandDensity (times islandOpenSea in a cell with no continent), raised toward 1 by the
         * cluster. They lie within islandReach radii of the anchor, scattered or, by
         * islandChainShare, strung along a chain; radii islandMin..islandMax continent radii,
         * log-scaled with islandSkew. An open-sea cell with islands is built as island-only.
         */
        public final int islandSlots;

        public final double islandDensity, islandOpenSea, islandReach, islandMin, islandMax;
        public final double islandSkew, islandChainShare;

        /** Largest axis ratio of an island (uniform draw); chain islands lie along their chain. */
        public final double islandElongation;

        public final double roughnessMin, roughnessMax;
        public final double bayProbability, shelterReach, shelterFloor, shelterConcavity;
        public final double straitBlend;

        /**
         * Warp octaves above the base shear pair: each is a shear pair in its own rotated frame,
         * at the base wavelength divided by lacunarity^k and amplitude times gain^k.
         */
        public final int warpOctaves;

        public final double warpLacunarity, warpGain;

        /**
         * Amplitude growth per layout coast octave (each 2.3 times the last's wavelength): 2.3
         * keeps relative roughness the same at every scale, lower smooths the large scales.
         */
        public final double macroGain;

        /** Layout octaves added beyond log(layout) / log(2.3): bays and peninsulas at map scale. */
        public final double macroShift;

        /**
         * Coast style: a smooth world field (wavelength styleWavelength, layout blocks) that
         * multiplies coast detail from styleMin on calm stretches to styleMax on rugged ones;
         * styleContrast spreads the field's values toward both ends.
         */
        public final double styleWavelength, styleMin, styleMax, styleContrast;

        /**
         * Fjords: each built continent tries fjordSlots mouths, evenly spaced around its shape
         * frame with jitter, each kept with fjordChance, a quarter of it on calm coast rising to all
         * of it as the coast style there grows rugged.
         * A fjord starts offshore and walks inland fjordLengthMin..fjordLengthMax radii with a
         * heading that wanders up to fjordWander radians a step, narrowing from about fjordWidth
         * blocks; fjordBranch of them fork, and lakeChance continue past their head as a chain of
         * lakes. Carved as the distance to a tapered tube, so the score stays a distance.
         */
        public final int fjordSlots;

        public final double fjordChance, fjordLengthMin, fjordLengthMax, fjordWidth, fjordWander;
        public final double fjordBranch, lakeChance;

        /**
         * Peninsulas: peninsulaSlots tries, each kept with peninsulaChance (more on rugged coast),
         * growing outward from inside the shape peninsulaLengthMin..Max radii, from about
         * peninsulaWidth blocks at the root to a third of it at the tip; joined as a tube.
         */
        public final int peninsulaSlots;

        public final double peninsulaChance, peninsulaLengthMin, peninsulaLengthMax;
        public final double peninsulaWidth;

        /**
         * Coast detail is clamped to this share of its worst-case sum, which bounds supports
         * strictly while typical detail stays far below the sum.
         */
        public final double detailShare;

        final double[] weights;

        public Variation(
                double jitter,
                double sizeMin,
                double sizeMax,
                double sizeSkew,
                double clusterWavelength,
                double clusterSize,
                double clusterOccupancy,
                int islandSlots,
                double islandDensity,
                double islandOpenSea,
                double islandReach,
                double islandMin,
                double islandMax,
                double islandSkew,
                double islandChainShare,
                double islandElongation,
                double elongationMax,
                double roughnessMin,
                double roughnessMax,
                double bayProbability,
                double shelterReach,
                double shelterFloor,
                double shelterConcavity,
                double straitBlend,
                int warpOctaves,
                double warpLacunarity,
                double warpGain,
                double macroGain,
                double macroShift,
                double styleWavelength,
                double styleMin,
                double styleMax,
                double styleContrast,
                int fjordSlots,
                double fjordChance,
                double fjordLengthMin,
                double fjordLengthMax,
                double fjordWidth,
                double fjordWander,
                double fjordBranch,
                double lakeChance,
                int peninsulaSlots,
                double peninsulaChance,
                double peninsulaLengthMin,
                double peninsulaLengthMax,
                double peninsulaWidth,
                double detailShare,
                double[] familyWeights,
                double layout) {
            int n = Family.values().length;
            if (!(jitter >= 0 && jitter <= .4)
                    || !(sizeMin >= .2 && sizeMin <= sizeMax && sizeMax <= 2)
                    || !(sizeSkew >= .25 && sizeSkew <= 8)
                    || !positive(clusterWavelength)
                    || !unit(clusterSize)
                    || !unit(clusterOccupancy)
                    || islandSlots < 0
                    || islandSlots > 8
                    || !unit(islandDensity)
                    || !unit(islandOpenSea)
                    || !(islandReach >= 0 && islandReach <= 3)
                    || !(islandMin > 0 && islandMin <= islandMax && islandMax <= 1)
                    || !(islandSkew >= .25 && islandSkew <= 8)
                    || !unit(islandChainShare)
                    || !(islandElongation >= 1 && islandElongation <= 4)
                    || !(elongationMax >= 1 && elongationMax <= 4)
                    || !(roughnessMin >= 0 && roughnessMin <= roughnessMax && roughnessMax <= 4)
                    || !unit(bayProbability)
                    || !positive(shelterReach)
                    || !(shelterFloor >= 0
                            && shelterFloor < shelterConcavity
                            && shelterConcavity <= 4)
                    || !positive(straitBlend)
                    || warpOctaves < 0
                    || warpOctaves > 4
                    || !(warpLacunarity >= 1.5 && warpLacunarity <= 4)
                    || !unit(warpGain)
                    || !(macroGain >= 1 && macroGain <= 4)
                    || !(macroShift >= 0 && macroShift <= 3)
                    || !positive(styleWavelength)
                    || !(styleMin >= 0 && styleMin <= styleMax && styleMax <= 4)
                    || !(styleContrast > 0 && styleContrast <= 8)
                    || fjordSlots < 0
                    || fjordSlots > 24
                    || !unit(fjordChance)
                    || !(fjordLengthMin > 0
                            && fjordLengthMin <= fjordLengthMax
                            && fjordLengthMax <= 1.2)
                    || !positive(fjordWidth)
                    || !(fjordWander >= 0 && fjordWander <= 1.5)
                    || !unit(fjordBranch)
                    || !unit(lakeChance)
                    || peninsulaSlots < 0
                    || peninsulaSlots > 12
                    || !unit(peninsulaChance)
                    || !(peninsulaLengthMin > 0
                            && peninsulaLengthMin <= peninsulaLengthMax
                            && peninsulaLengthMax <= .6)
                    || !positive(peninsulaWidth)
                    || !(detailShare > 0 && detailShare <= 1)
                    || familyWeights.length != n) throw new IllegalArgumentException("variation");
            double total = 0;
            for (int i = 0; i < n; i++) {
                if (!nonnegative(familyWeights[i]))
                    throw new IllegalArgumentException("variation family");
                total += familyWeights[i];
            }
            if (!(total > 0)) throw new IllegalArgumentException("no continental family enabled");
            this.jitter = jitter;
            this.sizeMin = sizeMin;
            this.sizeMax = sizeMax;
            this.sizeSkew = sizeSkew;
            this.clusterWavelength = clusterWavelength;
            this.clusterSize = clusterSize;
            this.clusterOccupancy = clusterOccupancy;
            this.islandSlots = islandSlots;
            this.islandDensity = islandDensity;
            this.islandOpenSea = islandOpenSea;
            this.islandReach = islandReach;
            this.islandMin = islandMin;
            this.islandMax = islandMax;
            this.islandSkew = islandSkew;
            this.islandChainShare = islandChainShare;
            this.islandElongation = islandElongation;
            this.elongationMax = elongationMax;
            this.roughnessMin = roughnessMin;
            this.roughnessMax = roughnessMax;
            this.bayProbability = bayProbability;
            this.shelterReach = shelterReach;
            this.shelterFloor = shelterFloor;
            this.shelterConcavity = shelterConcavity;
            this.straitBlend = straitBlend;
            this.warpOctaves = warpOctaves;
            this.warpLacunarity = warpLacunarity;
            this.warpGain = warpGain;
            this.macroGain = macroGain;
            this.macroShift = macroShift;
            this.styleWavelength = styleWavelength;
            this.styleMin = styleMin;
            this.styleMax = styleMax;
            this.styleContrast = styleContrast;
            this.fjordSlots = fjordSlots;
            this.fjordChance = fjordChance;
            this.fjordLengthMin = fjordLengthMin;
            this.fjordLengthMax = fjordLengthMax;
            this.fjordWidth = fjordWidth;
            this.fjordWander = fjordWander;
            this.fjordBranch = fjordBranch;
            this.lakeChance = lakeChance;
            this.peninsulaSlots = peninsulaSlots;
            this.peninsulaChance = peninsulaChance;
            this.peninsulaLengthMin = peninsulaLengthMin;
            this.peninsulaLengthMax = peninsulaLengthMax;
            this.peninsulaWidth = peninsulaWidth;
            this.detailShare = detailShare;
            this.weights = familyWeights.clone();
            if (!(layout >= .25 && layout <= 16)) throw new IllegalArgumentException("layout");
            // Coast detail is self-similar across layouts: octave k (wavelength 2.3^(k+1) detail
            // wavelengths, amplitude macroGain^(k+1)) fades in as the layout passes 2.3^k, so
            // scale 1 adds nothing and larger worlds keep their bays and headlands at map scale.
            double octaves = log(layout) / log(2.3) + macroShift, bound = 0;
            for (int k = 0; k < macro.length; k++) {
                macro[k] = clamp(octaves - k, 0, 1);
                macroAmplitude[k] = StrictMath.pow(macroGain, k + 1);
                bound += macro[k] * macroAmplitude[k];
                macroScale[k] = StrictMath.pow(2.3, k + 1);
                double a = .6 * k + .4;
                macroCos[k] = cos(a);
                macroSin[k] = sin(a);
            }
            macroBound = bound;
        }

        /** Weights of the layout-scaled coast octaves and their summed amplitude bound. */
        final double[] macro = new double[3];

        /** Each layout octave's amplitude and wavelength in detail units, and its rotation. */
        final double[] macroAmplitude = new double[3],
                macroScale = new double[3],
                macroCos = new double[3],
                macroSin = new double[3];

        final double macroBound;

        Family family(long id) {
            double total = 0;
            for (double w : weights) total += w;
            double pick = random(id, 302) * total;
            for (int i = 0; i < weights.length; i++) {
                pick -= weights[i];
                if (pick < 0 && weights[i] > 0) return Family.values()[i];
            }
            for (int i = weights.length - 1; ; i--) if (weights[i] > 0) return Family.values()[i];
        }

        /**
         * Radius multiplier of one continent from its uniform draw {@code u} in [0, 1) and the
         * cluster field at its anchor: log-scaled from sizeMin to sizeMax, so areas spread like a
         * power law; sizeSkew above 1 favours small ones, and the cluster shifts the draw by up to
         * clusterSize of the range either way.
         */
        double size(double u, double cluster) {
            double t = clamp(pow(u, sizeSkew) + clusterSize * (2 * cluster - 1), 0, 1);
            return sizeMin * pow(sizeMax / sizeMin, t);
        }

        /** Major-to-minor axis ratio; squared draw keeps most continents near round. */
        double elongation(double u) {
            return 1 + (elongationMax - 1) * u * u;
        }

        double roughness(double u) {
            return lerp(roughnessMin, roughnessMax, u);
        }
    }

    /** Caller owns one per active thread. Fields overwritten on every query. Score is NOT exact distance. */
    public static final class Sample {
        public double score,
                rawScore,
                landWeight,
                reservationClearance,
                bayClearance,
                corridorClearance;
        public long owner;
        public boolean marine, archipelago;
        public Reason reason;
        public int candidates, admitted, rejectedEarlier, rejectedSeed;

        /**
         * Owner traits for later classification: its outline family, radius multiplier, axis ratio,
         * coast roughness, how far the column sits inside it (0 at the coast, 1 at one scaled
         * radius) and shelter (0..1, the owner's coast concavity or a legacy bay).
         */
        public Family family;

        public double size, elongation, roughness, interior, shelter;

        /** The coast style multiplier at the column (1 for the single-family outline). */
        public double coastStyle;

        /**
         * Cape (0..1): land on the convex side of the shelter concavity, a headland or point. Read
         * from the probes shelter already takes, at no extra cost.
         */
        public double cape;

        public double warpU, warpV, headland;
    }

    /**
     * One continent cell: its anchor and draws, filled on first use, then its eligibility and its
     * family's geometry plan (the parts of the shape that depend only on the cell). Every field is
     * a pure function of the kernel and cell, so reusing or replacing an entry changes no result.
     */
    static final class Cell {
        Kernel kernel;
        long gx, gz, id;
        double ax, az, size, elongation, rough, axis, factor, support, cluster, detailCap;

        /** Built for its islands only: no continent body, no shelter of its own. */
        boolean islandOnly;

        /** Status and islands were decided with the draws (see cellAt), not with the facts. */
        boolean early;

        /** The frame (rotation and family) is set: by the facts, or by a neighbour's claim check. */
        boolean framed;

        /**
         * Islands from the anchor in layout blocks: (x, z, radius, cos, sin, root of the axis
         * ratio) each.
         */
        int islandCount;

        final double[] islands = new double[6 * 8];

        /**
         * Fjords, lakes and peninsulas as tapered polylines from the anchor in layout blocks:
         * per feature (kind, points, centre x, centre z, reach) then (x, z, half-width) per point;
         * kind 0 is carved (fjords, lakes), 1 joined (peninsulas). featureLength is the used part.
         */
        double[] features = new double[0];

        int featureLength;

        /** Scratch while planning: the world heading at the end of the last path. */
        double featureHeading;

        boolean facts;
        double suitability, islandBias;
        boolean archipelago;

        /** 0: earlier facts reject it, 1: seeded open sea, 2: built. */
        int status;

        Kernel shape;
        double c, s;

        boolean planned;
        int count;
        double[] plan = new double[0];

        double[] plan(int length) {
            if (plan.length < length) plan = new double[length];
            return plan;
        }
    }

    /** Caller-owned working memory; no allocations once warm. */
    public static final class Scratch {
        /** The last warp or unwarp result: (u, v) or (x, z). */
        final double[] uv = new double[2];

        boolean certify;
        double positiveClearance;
        double headland;

        /**
         * Cell cache indexed by cell coordinates modulo 16: the at most 11 x 11 candidate window of
         * one query never collides, so its entries stay valid for the whole query.
         */
        final Cell[] cells = new Cell[256];

        Cell[] window = new Cell[0];
        double[] distance = new double[0];
        int[] order = new int[0];

        void reserve(int count) {
            if (window.length >= count) return;
            window = new Cell[count];
            distance = new double[count];
            order = new int[count];
        }
    }

    public abstract static class Kernel {
        public final Family family;
        public final Tuning tuning;
        public final Reference reference;
        protected final long seed;
        private final Earlier earlier;
        private final double stretch;

        /**
         * Warp octaves: amplitude, wavelength and frame rotation of each shear pair; octave 0 is
         * the base pair (tuning's warp, unrotated), the rest come from the variation.
         */
        private final double[] warpA, warpL, warpC, warpS;

        /** The most the warp moves a point along either world axis. */
        private final double displacement;

        /** Null keeps the single-family legacy outline exactly. */
        protected final Variation variation;

        /** Anchor half-jitter in spacings, the largest support and the search half-width in cells. */
        private final double jitter, largestSupport;

        private final int reach;

        protected Kernel(
                Family family,
                long worldSeed,
                Tuning tuning,
                Reference reference,
                Earlier earlier) {
            this(family, family.salt, worldSeed, tuning, reference, earlier, null);
        }

        Kernel(
                Family family,
                long salt,
                long worldSeed,
                Tuning tuning,
                Reference reference,
                Earlier earlier,
                Variation variation) {
            this.family = family;
            this.seed = mix(worldSeed ^ salt);
            this.tuning = java.util.Objects.requireNonNull(tuning);
            this.reference = java.util.Objects.requireNonNull(reference);
            this.earlier = java.util.Objects.requireNonNull(earlier);
            this.variation = variation;
            double largest =
                    variation == null
                            ? tuning.radius
                            : tuning.radius * variation.sizeMax * sqrt(variation.elongationMax);
            int octaves = 1 + (variation == null ? 0 : variation.warpOctaves);
            warpA = new double[octaves];
            warpL = new double[octaves];
            warpC = new double[octaves];
            warpS = new double[octaves];
            double lipschitz = 1, moved = 0;
            for (int k = 0; k < octaves; k++) {
                warpA[k] =
                        k == 0
                                ? tuning.warpAmplitude
                                : tuning.warpAmplitude * StrictMath.pow(variation.warpGain, k);
                warpL[k] =
                        k == 0
                                ? tuning.warpWavelength
                                : tuning.warpWavelength
                                        / StrictMath.pow(variation.warpLacunarity, k);
                // Golden-angle frames keep the octaves' shear axes apart.
                double angle = 2.399963229728653 * k;
                warpC[k] = k == 0 ? 1 : cos(angle);
                warpS[k] = k == 0 ? 0 : sin(angle);
                // Each shear's slope is at most 3.75 amplitude / wavelength (value noise).
                double shear = 3.75 * warpA[k] / warpL[k];
                lipschitz *= (1 + shear) * (1 + shear);
                moved += (abs(warpC[k]) + abs(warpS[k])) * warpA[k];
            }
            stretch = lipschitz;
            displacement = moved;
            jitter = variation == null ? .08 : variation.jitter;
            largestSupport =
                    max(2 * largest, islandExtent())
                            + detailBound(variation == null ? 1 : variation.roughnessMax);
            // Every anchor outside the searched block lies at least (reach + .5 - jitter) spacings
            // away, which must cover the largest support plus the coastal transition.
            reach =
                    max(
                            1,
                            (int)
                                    ceil(
                                            (largestSupport + tuning.transitionWidth)
                                                            / tuning.spacing
                                                    - .5
                                                    + jitter));
            if (reach > 5) throw new IllegalArgumentException("continental supports too large");
        }

        /** The largest distance at which any continent's shape can reach from its anchor. */
        public final double largestSupport() {
            return largestSupport;
        }

        /**
         * The farthest an island's shape reaches from its cell's anchor, before detail: a chain's
         * centre lies within half the reach, its islands within half the reach of the centre
         * along it and 12% across. 0 without islands.
         */
        private double islandExtent() {
            if (variation == null || variation.islandSlots == 0) return 0;
            return tuning.radius
                    * (1.12 * variation.islandReach
                            + variation.islandMax * sqrt(variation.islandElongation));
        }

        /** Largest coastal detail at a roughness; legacy keeps the fixed .025-spacing bound. */
        private double detailBound(double roughness) {
            return variation == null
                    ? .025 * tuning.spacing
                    : variation.detailShare
                            * (roughness
                                    * tuning.detailAmplitude
                                    * ((1 + variation.macroBound) * variation.styleMax
                                            + .75 * min(1, variation.styleMax)));
        }

        /** Feature plans by cell id; cleared whole when full. */
        private final java.util.concurrent.ConcurrentHashMap<Long, double[]> featurePlans =
                new java.util.concurrent.ConcurrentHashMap<>();

        private static final int FEATURE_PLANS = 4096;

        /** The kernel whose geometry builds this continent. */
        Kernel geometryOf(long id) {
            return this;
        }

        public final void sample(double x, double z, Sample out, Scratch scratch) {
            sample(x, z, out, scratch, true);
        }

        /** As {@link #sample}, without shelter: for probes and reads of the score. */
        public final void sampleScore(double x, double z, Sample out, Scratch scratch) {
            sample(x, z, out, scratch, false);
        }

        /**
         * World to layout coordinates, into {@code out} as (u, v). Octave 0 is a pair of
         * triangular shears; each further octave is a shear pair in a rotated frame. A shear
         * moves one coordinate by a function of the other, so it never folds at any amplitude,
         * and the composition stays globally invertible with det(J) = 1.
         */
        public final void warp(double x, double z, double[] out) {
            double u = x + warpA[0] * noise(seed ^ 0x534845415258L, z / warpL[0], .31);
            double v = z + warpA[0] * noise(seed ^ 0x53484541525AL, u / warpL[0], .73);
            for (int k = 1; k < warpA.length; k++) {
                double c = warpC[k], s = warpS[k];
                double p = c * u + s * v, q = -s * u + c * v;
                p += warpA[k] * noise(seed ^ (0x5741525058L + k), q / warpL[k], .31);
                q += warpA[k] * noise(seed ^ (0x574152505AL + k), p / warpL[k], .73);
                u = c * p - s * q;
                v = s * p + c * q;
            }
            out[0] = u;
            out[1] = v;
        }

        /**
         * The cluster field at an anchor in layout coordinates, 0..1: two rotated octaves of value
         * noise, spread and faded so about a quarter of anchors sit near each end.
         */
        final double cluster(double u, double v) {
            double w = variation.clusterWavelength, w2 = .43 * w;
            double n =
                    .65 * noise(seed ^ 0x434C55535431L, u / w, v / w)
                            + .35
                                    * noise(
                                            seed ^ 0x434C55535432L,
                                            (.6 * u - .8 * v) / w2,
                                            (.8 * u + .6 * v) / w2);
            return fade(clamp(.5 + 1.2 * n, 0, 1));
        }

        /**
         * Coast style at layout coordinates: two rotated octaves of value noise, spread by the
         * contrast and faded into styleMin..styleMax. One world field, so merged continents share
         * their stretches; 1 for the single-family outline.
         */
        final double coastStyle(double u, double v) {
            if (variation == null) return 1;
            double w = variation.styleWavelength, w2 = .43 * w;
            double n =
                    .65 * noise(seed ^ 0x5354594C4531L, u / w, v / w)
                            + .35
                                    * noise(
                                            seed ^ 0x5354594C4532L,
                                            (.8 * u + .6 * v) / w2,
                                            (-.6 * u + .8 * v) / w2);
            double t = fade(clamp(.5 + .5 * variation.styleContrast * n, 0, 1));
            return lerp(variation.styleMin, variation.styleMax, t);
        }

        /** Layout to world coordinates, into {@code out} as (x, z): the warp's exact inverse. */
        public final void unwarp(double u, double v, double[] out) {
            for (int k = warpA.length - 1; k >= 1; k--) {
                double c = warpC[k], s = warpS[k];
                double p = c * u + s * v, q = -s * u + c * v;
                q -= warpA[k] * noise(seed ^ (0x574152505AL + k), p / warpL[k], .73);
                p -= warpA[k] * noise(seed ^ (0x5741525058L + k), q / warpL[k], .31);
                u = c * p - s * q;
                v = s * p + c * q;
            }
            double z = v - warpA[0] * noise(seed ^ 0x53484541525AL, u / warpL[0], .73);
            out[0] = u - warpA[0] * noise(seed ^ 0x534845415258L, z / warpL[0], .31);
            out[1] = z;
        }

        public final double warpStretch() {
            return stretch;
        }

        /** Independent occurrence draw, or a stratified 2 x 2 block quota when configured. */
        private boolean occupied(
                long gx, long gz, long id, double suitability, double cellCluster) {
            int quota = tuning.stratifiedOccupancy;
            if (quota == 0)
                return variation == null
                        ? random(id, 3) < tuning.occurrence * (.55 + .45 * suitability)
                        : random(id, 3)
                                < tuning.occurrence
                                        * (1 - variation.clusterOccupancy * (1 - cellCluster));
            long bx = Math.floorDiv(gx, 2), bz = Math.floorDiv(gz, 2);
            long block = cell(seed ^ 0x5354524154494659L, bx, bz);
            int index = (int) ((gx - 2 * bx) + 2 * (gz - 2 * bz));
            double mine = random(block, 10 + index);
            int rank = 0;
            for (int k = 0; k < 4; k++) {
                if (k == index) continue;
                double other = random(block, 10 + k);
                if (other < mine || (other == mine && k < index)) rank++;
            }
            return rank < quota;
        }

        public final double scaffoldClearance(double x, double z) {
            double[] uv = new double[2];
            warp(x, z, uv);
            double u = uv[0], v = uv[1], o = tuning.corridorOffset;
            return min(
                            gridDistance(u + o, tuning.corridorPeriod),
                            gridDistance(v + o, tuning.corridorPeriod))
                    - tuning.corridorHalfWidth;
        }

        /** The cached cell, with its anchor and draws; replaced in place on a miss. */
        private Cell cellAt(Scratch scratch, long gx, long gz) {
            int slot = (int) (gx & 15) | (int) (gz & 15) << 4;
            Cell c = scratch.cells[slot];
            if (c == null) c = scratch.cells[slot] = new Cell();
            if (c.kernel == this && c.gx == gx && c.gz == gz) return c;
            Tuning p = tuning;
            long id = cell(seed, gx, gz);
            c.kernel = this;
            c.gx = gx;
            c.gz = gz;
            c.id = id;
            c.ax = (gx + .5 + 2 * jitter * (random(id, 1) - .5)) * p.spacing;
            c.az = (gz + .5 + 2 * jitter * (random(id, 2) - .5)) * p.spacing;
            c.size = c.elongation = c.rough = 1;
            c.cluster = .5;
            if (variation != null) {
                c.cluster = cluster(c.ax, c.az);
                c.size = variation.size(random(id, 300), c.cluster);
                c.elongation = variation.elongation(random(id, 301));
                c.rough = variation.roughness(random(id, 303));
            }
            // Geometry runs in a frame scaled by size and stretched by the axis ratio; scaling
            // its score by size / sqrt(elongation) keeps the score Lipschitz bound.
            c.axis = sqrt(c.elongation);
            c.factor = c.size / c.axis;
            // Fixed maximum-detail bound keeps anchor admission independent of coast-detail
            // tuning.
            c.detailCap = detailBound(c.rough);
            // Varied occupancy without an eligibility floor or a quota reads only the id and the
            // cluster, so status and islands are known here and each support is exact: the body
            // of a built continent, the islands actually drawn (plus the transition, which the
            // cone subtracts), nothing for empty open sea.
            c.early = variation != null && p.eligibilityFloor <= 0 && p.stratifiedOccupancy == 0;
            if (c.early) {
                c.status = occupied(gx, gz, id, 1, c.cluster) ? 2 : 1;
                c.islandOnly = false;
                c.islandCount = 0;
                if (variation.islandSlots > 0) islands(c);
                double reachOut =
                        c.status == 2 && !c.islandOnly ? 2 * p.radius * c.size * c.axis : 0;
                for (int i = 0; i < c.islandCount; i++) {
                    int at = 6 * i;
                    reachOut =
                            max(
                                    reachOut,
                                    hypot(c.islands[at], c.islands[at + 1])
                                            + c.islands[at + 2] * c.islands[at + 5]
                                            + p.transitionWidth);
                }
                c.support = reachOut > 0 ? reachOut + c.detailCap : 0;
            } else c.support = max(2 * p.radius * c.size * c.axis, islandExtent()) + c.detailCap;
            c.facts = c.planned = c.framed = false;
            return c;
        }

        /** Eligibility, island flag and frame, from earlier facts at the anchor only. */
        private void facts(Cell c, Scratch scratch) {
            Tuning p = tuning;
            double[] world = new double[2];
            unwarp(c.ax, c.az, world);
            double worldAx = world[0], worldAz = world[1];
            c.suitability = earlier.suitability(worldAx, worldAz);
            c.islandBias = earlier.islandBias(worldAx, worldAz);
            if (!unit(c.suitability) || !unit(c.islandBias))
                throw new IllegalArgumentException("earlier facts");
            c.archipelago = random(c.id, 4) < c.islandBias;
            if (!c.early) {
                c.status =
                        c.suitability < p.eligibilityFloor
                                ? 0
                                : !occupied(c.gx, c.gz, c.id, c.suitability, c.cluster) ? 1 : 2;
                c.islandOnly = false;
                c.islandCount = 0;
                if (variation != null && variation.islandSlots > 0 && c.status != 0) islands(c);
            }
            if (c.status == 2) {
                double angle = TAU * random(c.id, 5);
                c.c = cos(angle);
                c.s = sin(angle);
                c.shape = geometryOf(c.id);
                c.framed = true;
            }
            c.featureLength = 0;
            if (variation != null && c.status == 2 && !c.islandOnly) {
                // Plans are pure functions of the cell, shared read-only through a bounded cache
                // (a clear never changes a result); a miss plans into a fresh buffer.
                double[] plan = featurePlans.get(c.id);
                if (plan == null) {
                    c.features = new double[64];
                    features(c, scratch);
                    plan = java.util.Arrays.copyOf(c.features, c.featureLength);
                    if (featurePlans.size() >= FEATURE_PLANS) featurePlans.clear();
                    featurePlans.put(c.id, plan);
                }
                c.features = plan;
                c.featureLength = plan.length;
            }
            c.facts = true;
        }

        /**
         * A cell's islands, from its id and cluster: scattered within the reach or strung along a
         * chain through a centre within half of it. An open-sea cell that draws any becomes
         * island-only.
         */
        private void islands(Cell c) {
            Variation w = variation;
            double r = tuning.radius, reach = w.islandReach * r;
            double chance =
                    w.islandDensity * (c.status == 2 ? 1 : w.islandOpenSea) * (.3 + .7 * c.cluster);
            boolean chain = random(c.id, 400) < w.islandChainShare;
            double heading = TAU * random(c.id, 401), dx = cos(heading), dz = sin(heading);
            double centre = .5 * reach * sqrt(random(c.id, 402)), turn = TAU * random(c.id, 403);
            double bx = centre * cos(turn), bz = centre * sin(turn);
            int n = 0;
            for (int i = 0; i < w.islandSlots; i++) {
                if (random(c.id, 410 + i) >= chance) continue;
                double ix, iz;
                if (chain) {
                    double along = .5 * reach * (2 * (i + .5) / w.islandSlots - 1);
                    double across = .12 * reach * (2 * random(c.id, 430 + i) - 1);
                    ix = bx + dx * along - dz * across;
                    iz = bz + dz * along + dx * across;
                } else {
                    double rho = reach * sqrt(random(c.id, 430 + i)),
                            a = TAU * random(c.id, 450 + i);
                    ix = rho * cos(a);
                    iz = rho * sin(a);
                }
                double t = pow(random(c.id, 470 + i), w.islandSkew);
                double e = random(c.id, 490 + i);
                double a =
                        chain
                                ? heading + .5 * (random(c.id, 510 + i) - .5)
                                : TAU * random(c.id, 510 + i);
                int at = 6 * n;
                c.islands[at] = ix;
                c.islands[at + 1] = iz;
                c.islands[at + 2] = r * w.islandMin * pow(w.islandMax / w.islandMin, t);
                c.islands[at + 3] = cos(a);
                c.islands[at + 4] = sin(a);
                c.islands[at + 5] = sqrt(1 + (w.islandElongation - 1) * e);
                n++;
            }
            c.islandCount = n;
            if (c.status == 1 && n > 0) {
                c.status = 2;
                c.islandOnly = true;
            }
        }

        private void sample(double x, double z, Sample out, Scratch scratch, boolean traits) {
            if (!Double.isFinite(x)
                    || !Double.isFinite(z)
                    || abs(x) > 30_000_000
                    || abs(z) > 30_000_000)
                throw new IllegalArgumentException(
                        "absolute coordinates outside supported Minecraft range");
            Tuning p = tuning;
            // Shear pairs are globally invertible: det(J)=1. Avoid fold-prone vector warps.
            warp(x, z, scratch.uv);
            double u = scratch.uv[0], v = scratch.uv[1];
            long cx = (long) floor(u / p.spacing), cz = (long) floor(v / p.spacing);
            double style = coastStyle(u, v);
            out.coastStyle = style;
            out.warpU = u;
            out.warpV = v;
            // Varied Potential continents also need their kernel clearance to deepen the sea.
            scratch.certify = variation != null;
            out.score = -p.spacing;
            out.owner = 0;
            out.archipelago = false;
            out.headland = 0;
            double raw = -p.spacing;
            out.reason = Reason.OUTSIDE_SUPPORT;
            out.candidates = out.admitted = out.rejectedEarlier = out.rejectedSeed = 0;
            out.family = family;
            out.size = out.elongation = out.roughness = 1;
            out.interior = out.shelter = out.cape = 0;
            double soft = -p.spacing, ownerBody = 0, second = Double.NEGATIVE_INFINITY;
            double secondBody = 0;
            Cell owner = null, runnerUp = null;
            // Traits blend the owner's concavity with a runner-up within this of the union.
            double traitBlend = traits && variation != null ? traitBlend() : 0;
            int side = 2 * reach + 1, count = side * side, live = 0;
            scratch.reserve(count);
            for (int k = 0; k < count; k++) {
                Cell c = cellAt(scratch, cx + k % side - reach, cz + k / side - reach);
                double wx = u - c.ax, wz = v - c.az, edge = c.support + p.transitionWidth;
                // Squared rejection first; hypot only for anchors whose support can reach.
                if (wx * wx + wz * wz > edge * edge * (1 + 1e-12)) continue;
                double dist = hypot(wx, wz);
                if (dist > edge) continue;
                // Varied continents are visited nearest first (ties by cell), so the bound below
                // prunes early; the legacy outline keeps its row order.
                int at = live++;
                if (variation != null)
                    while (at > 0 && scratch.distance[at - 1] > dist) {
                        scratch.window[at] = scratch.window[at - 1];
                        scratch.distance[at] = scratch.distance[at - 1];
                        at--;
                    }
                scratch.window[at] = c;
                scratch.distance[at] = dist;
            }
            for (int n = 0; n < live; n++) {
                Cell c = scratch.window[n];
                double dist = scratch.distance[n], wx = u - c.ax, wz = v - c.az;
                // Branch and bound: a varied candidate scores at most its support bound. On
                // land it cannot raise the union unless the bound exceeds it (or, with traits near
                // the coast, come within the trait blend of it); at sea it must also come within
                // straitBlend to touch the smooth union. Skipping changes no result.
                if (variation != null) {
                    double most = c.support - dist - p.transitionWidth;
                    if (out.score > 0
                            ? most
                                    <= out.score
                                            - (out.score < variation.shelterReach ? traitBlend : 0)
                            : most <= out.score - variation.straitBlend) continue;
                }
                out.candidates++;
                if (!c.facts) facts(c, scratch);
                // Varied continents reach full ocean depth (-transitionWidth) at their support
                // edge, so a shallow island field cannot end in a step to the deep sea there.
                double bound = c.support - dist - (variation == null ? 0 : p.transitionWidth);
                Reason reason;
                double score, cut = Double.POSITIVE_INFINITY, shaped = 0;
                // A rejected cell adds nothing to the varied union (its starting -spacing): at
                // -transitionWidth it lifted its whole support disc to shelf depth, a circle once
                // occupancy left cells open.
                double rejected = variation == null ? -p.transitionWidth : -p.spacing;
                if (c.status == 0) {
                    out.rejectedEarlier++;
                    score = min(bound, rejected);
                    reason = Reason.EARLIER_INELIGIBLE;
                } else if (c.status == 1) {
                    out.rejectedSeed++;
                    score = min(bound, rejected);
                    reason = Reason.SEEDED_OPEN_SEA;
                } else {
                    out.admitted++;
                    double lx = c.c * wx + c.s * wz, lz = -c.s * wx + c.c * wz;
                    double qx = lx / (c.size * c.axis), qz = lz * c.axis / c.size;
                    long id = c.id;
                    scratch.positiveClearance = 0;
                    scratch.headland = 0;
                    double body =
                            c.islandOnly ? -p.spacing : c.factor * shapeScore(c, qx, qz, scratch);
                    if (c.featureLength > 0) body = joined(c, wx, wz, body);
                    body = withIslands(c, wx, wz, body);
                    // Shelter's probes read the same shape (body, peninsulas and islands), so
                    // island rims read as capes, as satellites' did.
                    shaped = body;
                    // The support bound's pruning again, with the tighter bound the body gives:
                    // detail is at most detailCap and cuts only lower, so a candidate whose body
                    // plus detailCap cannot reach the union changes nothing (the same proof as
                    // above), and its coast detail is never computed.
                    if (variation != null) {
                        double most = body + c.detailCap;
                        if (out.score > 0
                                ? most
                                        <= out.score
                                                - (out.score < variation.shelterReach
                                                        ? traitBlend
                                                        : 0)
                                : most <= out.score - variation.straitBlend) continue;
                    }
                    // Bounded coastal detail, independent wavelength and amplitude; never
                    // changes site admission.
                    double detail =
                            variation == null
                                    ? p.detailAmplitude
                                            * (.7
                                                            * noise(
                                                                    id ^ 0x434F41535431L,
                                                                    u / p.detailWavelength,
                                                                    v / p.detailWavelength)
                                                    + .3
                                                            * noise(
                                                                    id ^ 0x434F41535432L,
                                                                    (.8 * u + .6 * v)
                                                                            / (.43
                                                                                    * p.detailWavelength),
                                                                    (-.6 * u + .8 * v)
                                                                            / (.43
                                                                                    * p.detailWavelength)))
                                    : clamp(
                                            c.rough
                                                    * p.detailAmplitude
                                                    * roughCoast(id, u, v, style),
                                            -c.detailCap,
                                            c.detailCap);
                    score = min(bound, body + detail);
                    // Fjords and lakes turn land into water without deepening the sea, as a bay
                    // cut does, but they join the score before rawScore: a fjord is a steep open
                    // coast, not bay shelter (which the coast typer reads as marsh).
                    if (c.featureLength > 0 && score > 0)
                        score = min(score, max(carved(c, wx, wz, score), -score));
                    // Riven adaptation (2026-10-22): a bay cuts only its own continent, before
                    // the union, so a neighbor's land may refill it and the ray never crosses
                    // another landmass. A bay closed off that way is an inland sea. The cut
                    // turns land into water but never deepens sea: min(g, max(cut, -g)) keeps
                    // the classification of min(g, cut) without a trench across the shelf.
                    if (variation == null || random(id, 304) < variation.bayProbability) {
                        double ba = TAU * random(id, 6), bc = cos(ba), bs = sin(ba);
                        double along = bc * qx + bs * qz, across = -bs * qx + bc * qz;
                        double depth = p.bayDepth * (.68 + .32 * random(id, 8));
                        double width = p.bayHalfWidth * (.65 + .70 * random(id, 9));
                        double inward = along - (p.radius - depth);
                        double bend =
                                .75
                                        * width
                                        * sin(
                                                max(0, inward) / (.38 * p.radius)
                                                        + TAU * random(id, 7));
                        cut = -c.factor * rayTube(inward, across - bend, width);
                    }
                    reason =
                            min(score, cut) > 0
                                    ? Reason.LAND
                                    : score > 0 ? Reason.BAY : Reason.SHAPE_OCEAN;
                }
                raw = max(raw, score);
                score = min(score, max(cut, -score));
                if (variation != null) soft = smoothMax(soft, score, variation.straitBlend);
                // Max union; fixed iteration order resolves exact ties independently of call
                // order.
                if (score > out.score) {
                    if (variation != null) {
                        second = out.score;
                        runnerUp = owner;
                        secondBody = ownerBody;
                    }
                    out.score = score;
                    out.owner = c.id;
                    out.archipelago = c.archipelago;
                    out.reason = reason;
                    out.headland = reason == Reason.LAND ? scratch.headland : 0;
                    if (variation != null) {
                        out.family = c.shape.family;
                        out.size = c.size;
                        out.elongation = c.elongation;
                        out.roughness = c.rough;
                        owner = c.status == 2 && !c.islandOnly ? c : null;
                        ownerBody = shaped;
                    }
                } else if (variation != null && score > second) {
                    second = score;
                    runnerUp = c.status == 2 && !c.islandOnly ? c : null;
                    secondBody = shaped;
                }
            }
            double union = out.score;
            // Varied continents meet at sea through a smooth union, so a strait floor is rounded
            // instead of creased along its midline; it never rises above half the hard depth, so
            // sea stays sea and a strait never reads as a coastline.
            if (variation != null && out.score < 0)
                out.score = max(out.score, min(soft, .5 * out.score));
            out.rawScore = raw;
            // Below rawScore only where a bay set the union.
            out.bayClearance = out.score < raw ? out.score : p.spacing;
            // Explicit, positive-width connected ocean scaffold, shared parameter convention across
            // families.
            double corridor =
                    min(
                                    gridDistance(u + p.corridorOffset, p.corridorPeriod),
                                    gridDistance(v + p.corridorOffset, p.corridorPeriod))
                            - p.corridorHalfWidth;
            out.corridorClearance = corridor;
            if (corridor < out.score) {
                out.score = corridor;
                out.reason = Reason.OCEAN_CORRIDOR;
            }
            double reservation = earlier.marineClearance(x, z);
            if (!Double.isFinite(reservation))
                throw new IllegalArgumentException("reservation clearance");
            out.reservationClearance = reservation;
            if (reservation < out.score) {
                out.score = reservation;
                out.reason = Reason.EARLIER_RESERVATION;
            }
            out.marine =
                    out.score <= 0; // zero belongs to marine; never infer ownership from a blended
            // height.
            if (!out.marine) out.reason = Reason.LAND;
            out.landWeight = smooth(0, p.transitionWidth, max(0, out.score));
            double bayShelter =
                    out.bayClearance < raw && out.bayClearance <= out.corridorClearance
                            ? fade(clamp((raw - out.bayClearance) / p.bayDepth, 0, 1))
                            : 0;
            double concavity = 0;
            if (traits && abs(out.score) < variation.shelterReach) {
                if (owner != null)
                    concavity = concavity(owner, u - owner.ax, v - owner.az, ownerBody, scratch);
                // Ownership switches where two continents' scores tie, so the runner-up's
                // concavity is blended in, half and half at the tie: shelter and cape do not
                // jump across the seam. A non-admitted continent contributes none.
                // The union only grows, so past shelterReach the runner-up may have been pruned.
                double gap = union - second;
                if (union < variation.shelterReach && gap < traitBlend) {
                    double w = .5 + .5 * fade(gap / traitBlend);
                    concavity =
                            w * concavity
                                    + (1 - w)
                                            * (runnerUp == null
                                                    ? 0
                                                    : concavity(
                                                            runnerUp,
                                                            u - runnerUp.ax,
                                                            v - runnerUp.az,
                                                            secondBody,
                                                            scratch));
                }
            }
            out.shelter =
                    variation == null
                            ? bayShelter
                            : max(
                                    fade(
                                            clamp(
                                                    (concavity - variation.shelterFloor)
                                                            / (variation.shelterConcavity
                                                                    - variation.shelterFloor),
                                                    0,
                                                    1)),
                                    bayShelter);
            if (variation != null && !out.marine)
                out.cape =
                        fade(
                                clamp(
                                        (-concavity - variation.shelterFloor)
                                                / (variation.shelterConcavity
                                                        - variation.shelterFloor),
                                        0,
                                        1));
            out.interior = clamp(out.score / (p.radius * out.size), 0, 1);
        }

        /**
         * A family's geometry in the continent frame, from the cell's plan. Varied Potential
         * continents are flat outside their kernels; below that floor their sea deepens with
         * distance to the nearest kernel instead of staying shelf-deep.
         */
        private double shapeScore(Cell c, double qx, double qz, Scratch scratch) {
            Kernel shape = c.shape;
            if (!c.planned) {
                shape.plan(c);
                c.planned = true;
            }
            double g = shape.geometry(c, qx, qz, scratch);
            return variation != null && shape.family == Family.POTENTIAL
                    ? min(g, -scratch.positiveClearance)
                    : g;
        }

        /** Score width over which a runner-up continent shares the owner's concavity. */
        private double traitBlend() {
            return variation.shelterReach * .25;
        }

        /**
         * Concavity of the owner continent's coast at bay scale: the five-point Laplacian of its
         * shape score (before detail) with step h = shelterReach / 2, times h. A straight coast
         * gives about 0, a bay or a channel between lobes of radius r about +h / r, a headland
         * about -h / r. Only the owner's shape counts, so a strait between continents stays open.
         * The probes must also find the shore: a sea column needs land at two of them and a land
         * column sea at one (smooth over 64 blocks), so creases a family's score carries far out
         * to sea (cell bisectors, union seams) never read as bays.
         */
        private double concavity(Cell c, double wx, double wz, double here, Scratch scratch) {
            double h = variation.shelterReach / 2, sum = 0, land = 0;
            for (int k = 0; k < 4; k++) {
                double px = wx + (k == 0 ? h : k == 1 ? -h : 0),
                        pz = wz + (k == 2 ? h : k == 3 ? -h : 0);
                double lx = c.c * px + c.s * pz, lz = -c.s * px + c.c * pz;
                double probe =
                        c.factor
                                * shapeScore(
                                        c, lx / (c.size * c.axis), lz * c.axis / c.size, scratch);
                if (c.featureLength > 0) probe = joined(c, px, pz, probe);
                probe = withIslands(c, px, pz, probe);
                sum += probe;
                land += clamp(.5 + probe / 64, 0, 1);
            }
            double shore = here > 0 ? clamp(4 - land, 0, 1) : clamp(land - 1, 0, 1);
            return shore * (sum - 4 * here) / h;
        }

        /**
         * Built continents whose support plus {@code margin} reaches a column:
         * their cell coordinates as (x, z) pairs in {@code out}. Returns the count. (Riven
         * extension for per-continent drainage.)
         */
        public final int continentCells(
                double x, double z, double margin, Scratch scratch, long[] out) {
            Tuning p = tuning;
            warp(x, z, scratch.uv);
            double u = scratch.uv[0], v = scratch.uv[1];
            long cx = (long) floor(u / p.spacing), cz = (long) floor(v / p.spacing);
            int side = 2 * reach + 1, n = 0;
            for (int k = 0; k < side * side && n + 2 <= out.length; k++) {
                Cell c = cellAt(scratch, cx + k % side - reach, cz + k / side - reach);
                double edge = c.support + margin;
                if (sq(u - c.ax) + sq(v - c.az) > edge * edge) continue;
                if (!c.facts) facts(c, scratch);
                if (c.status != 2) continue;
                out[n++] = c.gx;
                out[n++] = c.gz;
            }
            return n / 2;
        }

        /**
         * A continent's id (the owner its land reports) and a conservative world-space box around
         * its support, {min x, min z, max x, max z}: the warp moves a point at most the warp
         * amplitude. (Riven extension for per-continent drainage.)
         */
        public final long continentBounds(long gx, long gz, Scratch scratch, double[] box) {
            Cell c = cellAt(scratch, gx, gz);
            double r = c.support + displacement;
            box[0] = c.ax - r;
            box[1] = c.az - r;
            box[2] = c.ax + r;
            box[3] = c.az + r;
            return c.id;
        }

        /**
         * Three rotated octaves in -1.75..1.75 (coves and points at three scales), plus the
         * layout-scaled octaves above them when the world is larger than the 8 km tuning. The
         * coast style scales the base octave and the layout octaves; the two small octaves take at
         * most 1, so a calm stretch smooths at every scale and a rugged one gains structure
         * (inlets within bays) instead of speckle.
         */
        private double roughCoast(long id, double u, double v, double style) {
            double w = tuning.detailWavelength, w2 = w / 2.3, w3 = w / 5.3, macro = 0;
            for (int k = 0; k < variation.macro.length; k++) {
                double weight = variation.macro[k];
                if (weight <= 0) break;
                double wk = w * variation.macroScale[k];
                double ca = variation.macroCos[k], sa = variation.macroSin[k];
                macro +=
                        weight
                                * variation.macroAmplitude[k]
                                * noise(
                                        id ^ (0x524F5547484DL + k),
                                        (ca * u + sa * v) / wk,
                                        (-sa * u + ca * v) / wk);
            }
            double calm = min(1, style);
            return style * (macro + noise(id ^ 0x524F554748314CL, u / w, v / w))
                    + calm
                            * (.5
                                    * noise(
                                            id ^ 0x524F554748324CL,
                                            (.8 * u + .6 * v) / w2,
                                            (-.6 * u + .8 * v) / w2))
                    + calm
                            * (.25
                                    * noise(
                                            id ^ 0x524F554748334CL,
                                            (.28 * u - .96 * v) / w3,
                                            (.96 * u + .28 * v) / w3));
        }

        /**
         * Plans a built continent's fjords, lakes and peninsulas, a pure function of its cell.
         * Mouths and roots are spaced evenly around the shape frame (where the coast lies near
         * one radius in every direction) with jitter; paths walk in the frame and are stored as
         * offsets from the anchor, so a fjord runs across the coast however the continent is
         * stretched. Widths shrink with small continents.
         */
        private void features(Cell c, Scratch scratch) {
            Variation w = variation;
            double r = tuning.radius, scale = min(1, c.size), span = w.styleMax - w.styleMin;
            double[] q = new double[2];
            for (int i = 0; i < w.fjordSlots; i++) {
                double a = TAU * (i + .5 + .8 * (random(c.id, 600 + i) - .5)) / w.fjordSlots;
                double coast = coastRadius(c, a, scratch);
                if (coast < 0) continue;
                // The mouth lies just offshore of the continent's own coast on this ray.
                q[0] = (coast + .12 * r) * cos(a);
                q[1] = (coast + .12 * r) * sin(a);
                double mx = offsetX(c, q), mz = offsetZ(c, q);
                double rugged =
                        span <= 0
                                ? 1
                                : clamp(
                                        (coastStyle(c.ax + mx, c.az + mz) - w.styleMin) / span,
                                        0,
                                        1);
                if (random(c.id, 640 + i) >= w.fjordChance * (.25 + .75 * rugged)) continue;
                // A mouth in a neighbour's land would leave the fjord an inland channel.
                if (claimed(c, c.ax + mx, c.az + mz)) continue;
                double length = r * lerp(w.fjordLengthMin, w.fjordLengthMax, random(c.id, 660 + i));
                double width = scale * w.fjordWidth * lerp(.6, 1.4, random(c.id, 680 + i));
                long walk = c.id ^ (0x464A4F5244L + 31L * i);
                double heading = a + PI;
                int head = path(c, 0, q, heading, length, 10, width, .3 * width, walk);
                if (random(c.id, 700 + i) < w.fjordBranch) {
                    // A fork from the middle of the fjord, turned up to 0.9 radians off it.
                    int mid = head + 5 + 3 * 4;
                    double[] f = c.features;
                    q[0] = f[mid];
                    q[1] = f[mid + 1];
                    double turn =
                            (random(c.id, 720 + i) < .5 ? -1 : 1)
                                    * lerp(.5, .9, random(c.id, 740 + i));
                    pathFrom(
                            c,
                            0,
                            f[mid],
                            f[mid + 1],
                            c.featureHeading + turn,
                            .45 * length,
                            4,
                            .8 * f[mid + 2],
                            .2 * width,
                            walk ^ 0x464F524BL);
                }
                if (random(c.id, 760 + i) < w.lakeChance) {
                    // Lakes beyond the head, along its last heading: glacial finger lakes.
                    double[] f = c.features;
                    int end = head + 5 + 3 * 10;
                    double x = f[end],
                            z = f[end + 1],
                            dx = cos(c.featureHeading),
                            dz = sin(c.featureHeading);
                    int lakes = 2 + (int) (3 * random(c.id, 780 + i));
                    for (int k = 0; k < lakes; k++) {
                        // A gap, then a lake drawn out along the valley.
                        x += dx * width * lerp(1.2, 2.4, random(c.id, 800 + 8 * i + k));
                        z += dz * width * lerp(1.2, 2.4, random(c.id, 800 + 8 * i + k));
                        double long_ = width * lerp(2.5, 6, random(c.id, 850 + 8 * i + k));
                        double half = width * lerp(.4, .9, random(c.id, 900 + 8 * i + k));
                        double bend = .35 * (random(c.id, 950 + 8 * i + k) - .5);
                        double ex = cos(c.featureHeading + bend), ez = sin(c.featureHeading + bend);
                        lake(c, x, z, x + ex * long_, z + ez * long_, half);
                        x += ex * long_;
                        z += ez * long_;
                    }
                }
            }
            for (int i = 0; i < w.peninsulaSlots; i++) {
                double a = TAU * (i + .5 + .8 * (random(c.id, 1000 + i) - .5)) / w.peninsulaSlots;
                double coast = coastRadius(c, a, scratch);
                if (coast < .3 * r) continue;
                // The root lies just inside the continent's own coast on this ray.
                q[0] = (coast - .15 * r) * cos(a);
                q[1] = (coast - .15 * r) * sin(a);
                double mx = offsetX(c, q), mz = offsetZ(c, q);
                double rugged =
                        span <= 0
                                ? 1
                                : clamp(
                                        (coastStyle(c.ax + mx, c.az + mz) - w.styleMin) / span,
                                        0,
                                        1);
                if (random(c.id, 1020 + i) >= w.peninsulaChance * (.4 + .6 * rugged)) continue;
                double length =
                        r
                                * (.15
                                        + lerp(
                                                w.peninsulaLengthMin,
                                                w.peninsulaLengthMax,
                                                random(c.id, 1040 + i)));
                double width = scale * w.peninsulaWidth * lerp(.7, 1.3, random(c.id, 1060 + i));
                path(c, 1, q, a, length, 5, width, width / 3, c.id ^ (0x50454E494EL + 31L * i));
            }
        }

        /**
         * Where the continent's own score (body and detail) crosses the coast along a shape-frame ray at
         * angle a, in frame blocks: scanned inward from two radii in tenths of a radius, then
         * bisected five times (to 0.3% of a radius). -1 when the ray finds no land. Each probe resets the scratch state shapeScore
         * reads, as sample does, and the caller's state is restored, so the plan is a pure
         * function of the cell.
         */
        private double coastRadius(Cell c, double a, Scratch scratch) {
            boolean certify = scratch.certify;
            double clearance = scratch.positiveClearance, headland = scratch.headland;
            double r = tuning.radius, ca = cos(a), sa = sin(a), outside = -1;
            for (int k = 20; k >= 1; k--) {
                double rho = .1 * k * r;
                if (body(c, rho * ca, rho * sa, scratch) >= 0) {
                    outside = k == 20 ? -2 : rho + .1 * r;
                    break;
                }
            }
            double found = -1;
            if (outside > 0) {
                double lo = outside - .1 * r, hi = outside;
                for (int i = 0; i < 5; i++) {
                    double mid = .5 * (lo + hi);
                    if (body(c, mid * ca, mid * sa, scratch) >= 0) lo = mid;
                    else hi = mid;
                }
                found = .5 * (lo + hi);
            }
            scratch.certify = certify;
            scratch.positiveClearance = clearance;
            scratch.headland = headland;
            return found;
        }

        /** The continent's own score at a shape-frame point: its body plus the clamped detail. */
        private double body(Cell c, double qx, double qz, Scratch scratch) {
            scratch.certify = true;
            scratch.positiveClearance = 0;
            scratch.headland = 0;
            double[] q = {qx, qz};
            double u = c.ax + offsetX(c, q), v = c.az + offsetZ(c, q);
            double detail =
                    clamp(
                            c.rough
                                    * tuning.detailAmplitude
                                    * roughCoast(c.id, u, v, coastStyle(u, v)),
                            -c.detailCap,
                            c.detailCap);
            return c.factor * shapeScore(c, qx, qz, scratch) + detail;
        }

        /**
         * Whether another built continent's own score (body and detail) is land at layout point
         * (u, v). Read through a private per-thread scratch, so the query's cell cache is never
         * touched, and from bodies alone (never features), so planning does not recurse.
         */
        private boolean claimed(Cell c, double u, double v) {
            Scratch own = planScratch.get();
            long cx = (long) floor(u / tuning.spacing), cz = (long) floor(v / tuning.spacing);
            for (int k = 0, side = 2 * reach + 1; k < side * side; k++) {
                Cell n = cellAt(own, cx + k % side - reach, cz + k / side - reach);
                if (n.id == c.id || n.status != 2 || n.islandOnly) continue;
                double wx = u - n.ax, wz = v - n.az;
                if (wx * wx + wz * wz > n.support * n.support) continue;
                if (n.shape == null || !n.framed) {
                    double angle = TAU * random(n.id, 5);
                    n.c = cos(angle);
                    n.s = sin(angle);
                    n.shape = geometryOf(n.id);
                    n.framed = true;
                }
                double lx = n.c * wx + n.s * wz, lz = -n.s * wx + n.c * wz;
                if (body(n, lx / (n.size * n.axis), lz * n.axis / n.size, own) > 0) return true;
            }
            return false;
        }

        private final ThreadLocal<Scratch> planScratch = ThreadLocal.withInitial(Scratch::new);

        /** World offset from the anchor of a shape-frame point (qx, qz). */
        private double offsetX(Cell c, double[] q) {
            double lx = q[0] * c.size * c.axis, lz = q[1] * c.size / c.axis;
            return c.c * lx - c.s * lz;
        }

        private double offsetZ(Cell c, double[] q) {
            double lx = q[0] * c.size * c.axis, lz = q[1] * c.size / c.axis;
            return c.s * lx + c.c * lz;
        }

        /**
         * A wandering path from shape-frame point q along heading (frame radians), length frame
         * blocks in steps, half-width from w0 to w1, stored as offsets. Returns its start index;
         * its last frame heading is left in featureHeading.
         */
        private int path(
                Cell c,
                int kind,
                double[] q,
                double heading,
                double length,
                int steps,
                double w0,
                double w1,
                long walk) {
            int start = c.featureLength;
            reserve(c, 5 + 3 * (steps + 1));
            double[] f = c.features;
            f[start] = kind;
            f[start + 1] = steps + 1;
            double qx = q[0], qz = q[1], step = length / steps;
            for (int k = 0; k <= steps; k++) {
                if (k > 0) {
                    heading += turn(walk, k);
                    qx += step * cos(heading);
                    qz += step * sin(heading);
                }
                q[0] = qx;
                q[1] = qz;
                int at = start + 5 + 3 * k;
                f[at] = offsetX(c, q);
                f[at + 1] = offsetZ(c, q);
                f[at + 2] = width(walk, k, steps, w0, w1);
            }
            // The last world heading, for forks and lakes.
            int last = start + 5 + 3 * steps;
            c.featureHeading = atan2(f[last + 1] - f[last - 2], f[last] - f[last - 3]);
            seal(c, start, steps + 1);
            return start;
        }

        /** As {@link #path} from a world offset and world heading (forks). */
        private void pathFrom(
                Cell c,
                int kind,
                double x,
                double z,
                double heading,
                double length,
                int steps,
                double w0,
                double w1,
                long walk) {
            int start = c.featureLength;
            reserve(c, 5 + 3 * (steps + 1));
            double[] f = c.features;
            f[start] = kind;
            f[start + 1] = steps + 1;
            double step = length * c.size, h = heading;
            for (int k = 0; k <= steps; k++) {
                if (k > 0) {
                    h += turn(walk, k);
                    x += step / steps * cos(h);
                    z += step / steps * sin(h);
                }
                int at = start + 5 + 3 * k;
                f[at] = x;
                f[at + 1] = z;
                f[at + 2] = width(walk, k, steps, w0, w1) / (k == 0 ? 1.5 : 1);
            }
            seal(c, start, steps + 1);
        }

        /**
         * A path's turn at step k: smooth value noise along the path (a coherent bend over a few
         * steps, not a jitter), up to fjordWander radians.
         */
        private double turn(long walk, int k) {
            return variation.fjordWander * noise(walk ^ 0x5455524EL, .45 * k, .37);
        }

        /**
         * Half-width at step k of steps: the taper from w0 to w1, pinching and swelling by up to
         * 35% along the path, flared by half again at the first point (a fjord's mouth).
         */
        private static double width(long walk, int k, int steps, double w0, double w1) {
            double swell = 1 + .35 * noise(walk ^ 0x5357454CL, .6 * k, .21);
            return lerp(w0, w1, k / (double) steps) * swell * (k == 0 ? 1.5 : 1);
        }

        /** A carved lake: a capsule from (x0, z0) to (x1, z1), narrower at both ends. */
        private void lake(Cell c, double x0, double z0, double x1, double z1, double half) {
            int start = c.featureLength;
            reserve(c, 5 + 9);
            double[] f = c.features;
            f[start] = 0;
            f[start + 1] = 3;
            double[] at = {
                x0, z0, .45 * half, .5 * (x0 + x1), .5 * (z0 + z1), half, x1, z1, .45 * half
            };
            System.arraycopy(at, 0, f, start + 5, 9);
            seal(c, start, 3);
        }

        private void reserve(Cell c, int more) {
            if (c.features.length < c.featureLength + more)
                c.features = java.util.Arrays.copyOf(c.features, 2 * (c.featureLength + more));
        }

        /** Stores a feature's bounding circle and closes it. */
        private void seal(Cell c, int start, int points) {
            double[] f = c.features;
            double cx = 0, cz = 0;
            for (int k = 0; k < points; k++) {
                cx += f[start + 5 + 3 * k];
                cz += f[start + 6 + 3 * k];
            }
            cx /= points;
            cz /= points;
            double reach = 0;
            for (int k = 0; k < points; k++) {
                int at = start + 5 + 3 * k;
                reach = max(reach, hypot(f[at] - cx, f[at + 1] - cz) + f[at + 2]);
            }
            f[start + 2] = cx;
            f[start + 3] = cz;
            f[start + 4] = reach;
            c.featureLength = start + 5 + 3 * points;
        }

        /**
         * Distance from (x, z) to the nearest tapered tube of a kind, less its half-width there:
         * negative inside. Features whose bounding circle cannot beat {@code best} are skipped.
         */
        private static double tube(Cell c, int kind, double x, double z, double best) {
            double[] f = c.features;
            for (int at = 0; at < c.featureLength; ) {
                int points = (int) f[at + 1];
                int next = at + 5 + 3 * points;
                if ((int) f[at] == kind && reaches(x - f[at + 2], z - f[at + 3], f[at + 4], best)) {
                    if (points == 1)
                        best = min(best, hypot(x - f[at + 5], z - f[at + 6]) - f[at + 7]);
                    else
                        for (int k = 0; k + 1 < points; k++) {
                            int p = at + 5 + 3 * k;
                            double ax = f[p], az = f[p + 1], bx = f[p + 3], bz = f[p + 4];
                            double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
                            double t =
                                    l2 <= 0 ? 0 : clamp(((x - ax) * vx + (z - az) * vz) / l2, 0, 1);
                            double w = lerp(f[p + 2], f[p + 5], t);
                            double d = hypot(x - ax - t * vx, z - az - t * vz) - w;
                            // Rough walls: up to a quarter of the width, at about twice its scale.
                            if (d < .25 * w)
                                d -= .25 * w * noise(c.id ^ 0x57414C4CL, x / (2 * w), z / (2 * w));
                            best = min(best, d);
                        }
                }
                at = next;
            }
            return best;
        }

        /**
         * The body joined with the cell's islands: ellipses of area-preserving axis ratio e^2,
         * each scored as its radius less the stretched distance (at most e from the true one).
         */
        private static double withIslands(Cell c, double wx, double wz, double body) {
            for (int i = 0; i < c.islandCount; i++) {
                int at = 6 * i;
                double ix = wx - c.islands[at], iz = wz - c.islands[at + 1];
                double ca = c.islands[at + 3], sa = c.islands[at + 4], e = c.islands[at + 5];
                double along = ca * ix + sa * iz, across = -sa * ix + ca * iz;
                double r = c.islands[at + 2];
                // Exact skips (see reaches): an island no larger than the body cannot raise it,
                // and one clearly out of reach (q >= distance / e) leaves it too.
                if (r <= body) continue;
                double limit = (r - body) * e, margin = 1e-6 * (1 + Math.abs(r) + Math.abs(body));
                if (limit > 0 && ix * ix + iz * iz > (limit + margin) * e * (limit + margin) * e)
                    continue;
                body = max(body, r - hypot(along / e, across * e));
            }
            return body;
        }

        /**
         * Whether a feature's bounding circle (centre at offset (dx, dz), reach) can beat best:
         * exactly hypot(dx, dz) - reach < best. Clear misses skip the hypot: best <= -reach can
         * never hold it (the distance is never negative, rounding is monotonic and negation
         * exact), and a squared distance past (reach + best) by a margin far above rounding error
         * cannot either; the rest take the exact expression, so results are bit-identical.
         */
        private static boolean reaches(double dx, double dz, double reach, double best) {
            if (best <= -reach) return false;
            double limit = reach + best + 1e-6 * (1 + Math.abs(reach) + Math.abs(best));
            if (dx * dx + dz * dz > limit * limit) return false;
            return hypot(dx, dz) - reach < best;
        }

        /** The body joined with its peninsulas. */
        private static double joined(Cell c, double x, double z, double body) {
            return max(body, -tube(c, 1, x, z, -body));
        }

        /** The carve of fjords and lakes: the tube distance (negative inside), at most the score. */
        private static double carved(Cell c, double x, double z, double score) {
            return tube(c, 0, x, z, score);
        }

        /** Precomputes the parts of this family's shape that depend only on the cell. */
        abstract void plan(Cell c);

        protected abstract double geometry(Cell c, double x, double z, Scratch scratch);
    }

    /**
     * Riven extension (2026-10-23): every continent draws its own family by weight and builds with
     * that family's geometry and style; the world is no longer one outline family.
     */
    public static final class Varied extends Kernel {
        private final Kernel[] shapes;

        public Varied(
                long seed,
                Tuning p,
                Reference r,
                Earlier e,
                Variation variation,
                HarmonicStyle harmonic,
                SpineStyle spine,
                PotentialStyle potential) {
            super(
                    null,
                    0x524956564152594CL,
                    seed,
                    p,
                    r,
                    e,
                    java.util.Objects.requireNonNull(variation));
            shapes =
                    new Kernel[] {
                        new Harmonic(seed, p, r, e, harmonic),
                        new Spine(seed, p, r, e, spine),
                        new Potential(seed, p, r, e, potential)
                    };
        }

        @Override
        Kernel geometryOf(long id) {
            return shapes[variation.family(id).ordinal()];
        }

        void plan(Cell c) {
            throw new IllegalStateException("cells build with their family's kernel");
        }

        protected double geometry(Cell c, double x, double z, Scratch s) {
            throw new IllegalStateException("cells build with their family's kernel");
        }
    }

    /** F1: star-shaped harmonic mainland, paired capes, finite satellite island necklace. */
    public static final class Harmonic extends Kernel {
        public final HarmonicStyle style;

        public Harmonic(long seed, Tuning p, Reference r, Earlier e, HarmonicStyle style) {
            super(Family.HARMONIC, seed, p, r, e);
            this.style = java.util.Objects.requireNonNull(style);
        }

        /** Plan: three harmonic phases, then (x, z, radius) of each satellite. */
        void plan(Cell c) {
            long id = c.id;
            boolean islands = c.archipelago;
            double r = tuning.radius;
            int count = islands ? 7 : 3;
            double[] g = c.plan(3 + 3 * count);
            g[0] = TAU * random(id, 10);
            g[1] = TAU * random(id, 11);
            g[2] = TAU * random(id, 12);
            for (int i = 0; i < count; i++) {
                double angle = TAU * (i + .3 * random(id, 20 + i)) / count;
                double orbit = r * (islands ? .88 : 1.18);
                g[3 + 3 * i] = orbit * cos(angle);
                g[4 + 3 * i] = orbit * sin(angle);
                g[5 + 3 * i] =
                        style.satelliteScale
                                * r
                                * (islands
                                        ? (.19 + .13 * random(id, 30 + i))
                                        : (.15 + .07 * random(id, 30 + i)));
            }
            c.count = count;
        }

        protected double geometry(Cell c, double x, double z, Scratch s) {
            boolean islands = c.archipelago;
            double[] g = c.plan;
            double r = tuning.radius, result;
            if (islands) result = -r;
            else {
                double a = atan2(z, x), d = hypot(x, z);
                double radial =
                        r
                                * (.92
                                        + style.harmonicStrength
                                                * (.19 * cos(2 * a + g[0])
                                                        + .13 * sin(3 * a + g[1])
                                                        + .075 * cos(5 * a + g[2])));
                result = radial - d;
            }
            for (int i = 0; i < c.count; i++)
                result = max(result, g[5 + 3 * i] - hypot(x - g[3 + 3 * i], z - g[4 + 3 * i]));
            if (!islands) {
                // Intentional narrow positive neck between an outboard cape and the mainland.
                double end = 1.54 * r;
                double cape =
                        max(
                                tuning.bridgeHalfWidth - capsuleDistance(x, z, .62 * r, 0, end, 0),
                                .28 * r - hypot(x - end, z));
                s.headland = clamp((cape - result) / max(1, tuning.bridgeHalfWidth), 0, 1);
                result =
                        max(
                                result,
                                tuning.bridgeHalfWidth - capsuleDistance(x, z, .62 * r, 0, end, 0));
                result = max(result, .28 * r - hypot(x - end, z));
            }
            return result;
        }
    }

    /** F2: finite monotone six-node swept skeleton, variable-width necks and two terminal spurs. */
    public static final class Spine extends Kernel {
        public final SpineStyle style;

        public Spine(long seed, Tuning p, Reference r, Earlier e, SpineStyle style) {
            super(Family.SPINE, seed, p, r, e);
            this.style = java.util.Objects.requireNonNull(style);
        }

        /** Plan: (x, z, half-width) of the six skeleton nodes. */
        void plan(Cell c) {
            long id = c.id;
            double r = tuning.radius;
            double[] g = c.plan(18);
            for (int i = 0; i < 6; i++) {
                g[3 * i] = r * (-1.22 + .49 * i);
                g[3 * i + 1] =
                        r
                                * (.48 * (random(id, 40 + i) - .5)
                                        + .18 * sin(i * 1.7 + random(id, 49) * TAU));
                g[3 * i + 2] = r * style.widthScale * (.28 + .27 * random(id, 50 + i));
            }
            c.count = 6;
        }

        protected double geometry(Cell c, double x, double z, Scratch s) {
            long id = c.id;
            boolean islands = c.archipelago;
            double[] g = c.plan;
            double r = tuning.radius, result = -2 * r;
            for (int i = 0; i < 5; i++) {
                if (islands && (i == 1 || i == 3)) continue;
                double ax = g[3 * i], az = g[3 * i + 1], bx = g[3 * i + 3], bz = g[3 * i + 4];
                double t =
                        clamp(
                                ((x - ax) * (bx - ax) + (z - az) * (bz - az))
                                        / (sq(bx - ax) + sq(bz - az)),
                                0,
                                1);
                double width = lerp(g[3 * i + 2], g[3 * i + 5], t);
                if (i == 2 && !islands)
                    width = lerp(tuning.bridgeHalfWidth, width, pow(abs(2 * t - 1), .65));
                result = max(result, width - hypot(x - lerp(ax, bx, t), z - lerp(az, bz, t)));
            }
            if (!islands)
                for (int i = 1; i <= 4; i += 3) {
                    double direction = i == 1 ? 1 : -1;
                    double cape =
                            .24 * r * style.spurScale
                                    - capsuleDistance(
                                            x,
                                            z,
                                            g[3 * i],
                                            g[3 * i + 1],
                                            g[3 * i] + .18 * r,
                                            g[3 * i + 1] + direction * .95 * r);
                    s.headland =
                            max(
                                    s.headland,
                                    clamp((cape - result) / max(1, tuning.bridgeHalfWidth), 0, 1));
                    result = max(result, cape);
                }
            if (islands) {
                // Removing segments alone is insufficient: wide endpoint caps can overlap across a
                // gap. A fixed maximum-detail margin keeps these two local cuts open at every
                // allowed amplitude.
                double gap = max(.07 * r, .025 * tuning.spacing + 16);
                for (int i = 0; i < 2; i++) {
                    double width =
                            gap
                                    + .035
                                            * r
                                            * (.5
                                                    + .5
                                                            * sin(
                                                                    z / (.3 * r)
                                                                            + TAU
                                                                                    * random(
                                                                                            id,
                                                                                            220
                                                                                                    + i)));
                    result = min(result, abs(x - islandGapCenter(id, i, z, r)) - width);
                }
            }
            return result;
        }

        static double islandGapCenter(long id, int index, double z, double radius) {
            return radius
                    * ((index == 0 ? -.485 : .495)
                            + .09 * sin(z / (.48 * radius) + TAU * random(id, 200 + index))
                            + .035 * sin(z / (.19 * radius) + TAU * random(id, 210 + index)));
        }
    }

    /** F4: seven compact elliptical potential kernels; sum/threshold joins create organic necks.
     * Threshold is dimensionless; output is block-equivalent score, not a Euclidean signed distance. */
    public static final class Potential extends Kernel {
        public final PotentialStyle style;

        public Potential(long seed, Tuning p, Reference r, Earlier e, PotentialStyle style) {
            super(Family.POTENTIAL, seed, p, r, e);
            this.style = java.util.Objects.requireNonNull(style);
            if (p.detailAmplitude
                    >= p.radius * .65 * min(style.mainlandThreshold, style.islandThreshold))
                throw new IllegalArgumentException(
                        "detail could create land outside potential supports");
        }

        /** Plan: (x, z, rx, rz) of the seven kernels. */
        void plan(Cell c) {
            long id = c.id;
            boolean islands = c.archipelago;
            double r = tuning.radius;
            double[] g = c.plan(28);
            for (int i = 0; i < 7; i++) {
                double a = TAU * random(id, 90 + i);
                double orbit = i == 0 ? 0 : r * (.48 + .64 * random(id, 100 + i));
                g[4 * i] = orbit * cos(a);
                g[4 * i + 1] = orbit * sin(a);
                double rx = r * (islands ? .40 : (.62 + .28 * random(id, 110 + i)));
                g[4 * i + 2] = rx;
                g[4 * i + 3] = rx * (.62 + .36 * random(id, 120 + i));
            }
            c.count = 7;
        }

        protected double geometry(Cell c, double x, double z, Scratch s) {
            boolean islands = c.archipelago;
            double[] g = c.plan;
            double r = tuning.radius, sum = 0;
            if (s.certify) s.positiveClearance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 7; i++) {
                double ax = g[4 * i], az = g[4 * i + 1], rx = g[4 * i + 2], rz = g[4 * i + 3];
                double q = sq((x - ax) / rx) + sq((z - az) / rz);
                if (s.certify)
                    s.positiveClearance =
                            min(s.positiveClearance, max(0, (sqrt(q) - 1) * min(rx, rz)));
                if (q < 1) sum += pow(1 - q, 3) * (i == 0 && !islands ? 1.2 : 1);
            }
            // Islands use less overlapping supports; mainland's accumulated field merges
            // neighboring lobes.
            double threshold = islands ? style.islandThreshold : style.mainlandThreshold;
            double score = (sum - threshold) * r * .65;
            if (!islands) {
                if (s.certify) {
                    s.positiveClearance =
                            min(
                                    s.positiveClearance,
                                    max(
                                            0,
                                            capsuleDistance(x, z, -.62 * r, 0, .98 * r, 0)
                                                    - tuning.bridgeHalfWidth
                                                    - tuning.detailAmplitude));
                    s.positiveClearance =
                            min(
                                    s.positiveClearance,
                                    max(
                                            0,
                                            hypot(x - 1.15 * r, z)
                                                    - .27 * r
                                                    - tuning.detailAmplitude));
                }
                double cape =
                        max(
                                tuning.bridgeHalfWidth
                                        - capsuleDistance(x, z, -.62 * r, 0, .98 * r, 0),
                                .27 * r - hypot(x - 1.15 * r, z));
                s.headland = clamp((cape - score) / max(1, tuning.bridgeHalfWidth), 0, 1);
                score =
                        max(
                                score,
                                tuning.bridgeHalfWidth
                                        - capsuleDistance(x, z, -.62 * r, 0, .98 * r, 0));
                score = max(score, .27 * r - hypot(x - 1.15 * r, z));
            }
            return score;
        }
    }

    /** Polynomial smooth maximum: equals max(a, b) once they differ by k, at most k / 4 above it. */
    static double smoothMax(double a, double b, double k) {
        double h = max(k - abs(a - b), 0) / k;
        return max(a, b) + h * h * k / 4;
    }

    private static double rayTube(double along, double across, double radius) {
        return radius - hypot(min(along, 0), across);
    }

    private static double gridDistance(double x, double spacing) {
        return abs(x - spacing * floor(x / spacing + .5));
    }

    static double capsuleDistance(double x, double z, double ax, double az, double bx, double bz) {
        double t =
                clamp(
                        ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / (sq(bx - ax) + sq(bz - az)),
                        0,
                        1);
        return hypot(x - lerp(ax, bx, t), z - lerp(az, bz, t));
    }

    static double noise(long seed, double x, double z) {
        long ix = (long) floor(x), iz = (long) floor(z);
        double u = fade(x - ix), v = fade(z - iz);
        return lerp(
                lerp(
                        2 * random(cell(seed, ix, iz), 0) - 1,
                        2 * random(cell(seed, ix + 1, iz), 0) - 1,
                        u),
                lerp(
                        2 * random(cell(seed, ix, iz + 1), 0) - 1,
                        2 * random(cell(seed, ix + 1, iz + 1), 0) - 1,
                        u),
                v);
    }

    static long cell(long seed, long x, long z) {
        return mix(
                seed
                        ^ mix(x + 0x6A09E667F3BCC909L)
                        ^ Long.rotateLeft(mix(z + 0x3C6EF372FE94F82BL), 29));
    }

    static long mix(long v) {
        v ^= v >>> 30;
        v *= 0xBF58476D1CE4E5B9L;
        v ^= v >>> 27;
        v *= 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    static double random(long id, int slot) {
        return (mix(id + 0x9E3779B97F4A7C15L * (slot + 1L)) >>> 11) * 0x1.0p-53;
    }

    static double fade(double t) {
        return t * t * t * (t * (6 * t - 15) + 10);
    }

    static double smooth(double a, double b, double x) {
        double t = clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double sq(double x) {
        return x * x;
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double clamp(double x, double a, double b) {
        return max(a, min(b, x));
    }

    private static boolean positive(double v) {
        return Double.isFinite(v) && v > 0;
    }

    private static boolean nonnegative(double v) {
        return Double.isFinite(v) && v >= 0;
    }

    private static boolean unit(double v) {
        return Double.isFinite(v) && v >= 0 && v <= 1;
    }
}
