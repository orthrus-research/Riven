/* SPDX-License-Identifier: MIT
 * Independently authored Riven contribution. See SOURCES.md; no external implementation imported.
 */
/* Riven adaptation (2026-10-20): immutable Java-8 value classes and switch, and coordinate
 * access; (2026-10-23) per-continent variation and cached cell plans. The conservative region
 * bounds for native water admission were removed: nothing has read them since that was retired.
 * Geometry, salts and original point ownership remain. Extensions remain MIT.
 * Packaged grant/notices: META-INF/licenses/continents/{MIT.txt,SOURCES.md}.
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
        CELLULAR(0x524956434F4E5433L),
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

        public static Tuning defaults(Reference r) {
            // 5700-block spacing is the current continent convention. Coastal reference affects
            // skirts only.
            return new Tuning(
                    5700,
                    1900,
                    72,
                    520,
                    430,
                    3800,
                    .88,
                    .27,
                    180,
                    1350,
                    220,
                    100,
                    r.coastalPlainWidth * r.horizontalScale);
        }

        public Tuning withRadius(double v) {
            return new Tuning(
                    spacing,
                    v,
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

        public Tuning withDetail(double amplitude, double wavelength) {
            return new Tuning(
                    spacing,
                    radius,
                    amplitude,
                    wavelength,
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

        public Tuning withOccurrence(double v) {
            return new Tuning(
                    spacing,
                    radius,
                    detailAmplitude,
                    detailWavelength,
                    warpAmplitude,
                    warpWavelength,
                    v,
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

    public static final class CellularStyle {
        public final double insetScale;
        public final double connectionProbability;

        public CellularStyle(double insetScale, double connectionProbability) {
            if (!Double.isFinite(insetScale)
                    || insetScale < 1
                    || insetScale > 2
                    || !unit(connectionProbability))
                throw new IllegalArgumentException("cellular style");
            this.insetScale = insetScale;
            this.connectionProbability = connectionProbability;
        }

        public double insetScale() {
            return insetScale;
        }

        public double connectionProbability() {
            return connectionProbability;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof CellularStyle)) return false;
            CellularStyle other = (CellularStyle) value;
            return Double.compare(insetScale, other.insetScale) == 0
                    && Double.compare(connectionProbability, other.connectionProbability) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(insetScale, connectionProbability);
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.ROOT,
                    "CellularStyle[insetScale=%s,connectionProbability=%s]",
                    insetScale,
                    connectionProbability);
        }

        public static CellularStyle defaults() {
            return new CellularStyle(1, .92);
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
        public final double jitter, sizeMin, sizeMax, sizeSplit, largeShare, elongationMax;
        public final double roughnessMin, roughnessMax;
        public final double bayProbability, shelterReach, shelterFloor, shelterConcavity;
        public final double straitBlend;
        final double[] weights;

        /** Null: no continent carries a crest. */
        public final Skeleton skeleton;

        public Variation(
                double jitter,
                double sizeMin,
                double sizeMax,
                double sizeSplit,
                double largeShare,
                double elongationMax,
                double roughnessMin,
                double roughnessMax,
                double bayProbability,
                double shelterReach,
                double shelterFloor,
                double shelterConcavity,
                double straitBlend,
                double[] familyWeights,
                Skeleton skeleton,
                double layout) {
            int n = Family.values().length;
            if (!(jitter >= 0 && jitter <= .4)
                    || !(sizeMin >= .4
                            && sizeMin <= sizeSplit
                            && sizeSplit <= sizeMax
                            && sizeMax <= 2)
                    || !unit(largeShare)
                    || !(elongationMax >= 1 && elongationMax <= 4)
                    || !(roughnessMin >= 0 && roughnessMin <= roughnessMax && roughnessMax <= 4)
                    || !unit(bayProbability)
                    || !positive(shelterReach)
                    || !(shelterFloor >= 0
                            && shelterFloor < shelterConcavity
                            && shelterConcavity <= 4)
                    || !positive(straitBlend)
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
            this.sizeSplit = sizeSplit;
            this.largeShare = largeShare;
            this.elongationMax = elongationMax;
            this.roughnessMin = roughnessMin;
            this.roughnessMax = roughnessMax;
            this.bayProbability = bayProbability;
            this.shelterReach = shelterReach;
            this.shelterFloor = shelterFloor;
            this.shelterConcavity = shelterConcavity;
            this.straitBlend = straitBlend;
            this.weights = familyWeights.clone();
            this.skeleton = skeleton;
            if (!(layout >= .25 && layout <= 16)) throw new IllegalArgumentException("layout");
            // Coast detail is self-similar across layouts: octave k (wavelength 2.3^k detail
            // wavelengths, amplitude 2^k) fades in as the layout passes 2.3^(k-1), so scale 1
            // adds nothing and larger worlds keep their bays and headlands at map scale.
            double octaves = log(layout) / log(2.3), bound = 0;
            for (int k = 0; k < macro.length; k++) {
                macro[k] = clamp(octaves - k, 0, 1);
                bound += macro[k] * (2 << k);
                macroScale[k] = StrictMath.pow(2.3, k + 1);
                double a = .6 * k + .4;
                macroCos[k] = cos(a);
                macroSin[k] = sin(a);
            }
            macroBound = bound;
        }

        /** Weights of the layout-scaled coast octaves and their summed amplitude bound. */
        final double[] macro = new double[3];

        /** Each layout octave's wavelength in detail wavelengths and its rotation. */
        final double[] macroScale = new double[3],
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
         * Radius multiplier of one continent from its uniform draw {@code u} in [0, 1): two modes,
         * so a world reads as a few continents among minor islands. A {@code largeShare} of
         * continents spans sizeSplit..sizeMax, the rest sizeMin..sizeSplit; the draw is rescaled
         * within each mode so both stay uniform.
         */
        double size(double u) {
            return u < largeShare
                    ? lerp(sizeSplit, sizeMax, u / largeShare)
                    : lerp(sizeMin, sizeSplit, (u - largeShare) / (1 - largeShare));
        }

        /** Major-to-minor axis ratio; squared draw keeps most continents near round. */
        double elongation(double u) {
            return 1 + (elongationMax - 1) * u * u;
        }

        double roughness(double u) {
            return lerp(roughnessMin, roughnessMax, u);
        }
    }

    /**
     * Riven extension (2026-10-23): per-continent crest. A {@code rangeShare} of built mainland
     * continents carries a crest line in its shape frame (Spine continents along their own six
     * nodes, others a bent three-point line along the major axis), with a strength drawn from
     * strengthMin..strengthMax times the continent's size. Columns read the owner's crest weight:
     * strength, a smooth falloff over crestHalfWidth radii of shape-frame distance, and a fade
     * to zero at the coast over coastClear scaled radii. It is an uplift opportunity for earlier
     * elevation, not terrain.
     */
    public static final class Skeleton {
        public final double rangeShare,
                strengthMin,
                strengthMax,
                halfWidth,
                length,
                bend,
                coastClear;

        public Skeleton(
                double rangeShare,
                double strengthMin,
                double strengthMax,
                double halfWidthInRadii,
                double lengthInRadii,
                double bendInRadii,
                double coastClearInRadii) {
            if (!unit(rangeShare)
                    || !(strengthMin >= 0 && strengthMin <= strengthMax && strengthMax <= 2)
                    || !(halfWidthInRadii > 0 && halfWidthInRadii <= 1)
                    || !(lengthInRadii >= 0 && lengthInRadii <= 1.5)
                    || !(bendInRadii >= 0 && bendInRadii <= .6)
                    || !(coastClearInRadii > 0 && coastClearInRadii <= 1))
                throw new IllegalArgumentException("skeleton");
            this.rangeShare = rangeShare;
            this.strengthMin = strengthMin;
            this.strengthMax = strengthMax;
            this.halfWidth = halfWidthInRadii;
            this.length = lengthInRadii;
            this.bend = bendInRadii;
            this.coastClear = coastClearInRadii;
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

        /**
         * Cape (0..1): land on the convex side of the shelter concavity, a headland or point. Read
         * from the probes shelter already takes, at no extra cost.
         */
        public double cape;

        public double warpU, warpV, headland;

        /**
         * The owner continent's crest weight (0 off its range, at sea and at the coast) and its
         * world gradient; the warp's shear is not differentiated.
         */
        public double crest, crestDx, crestDz;
    }

    /**
     * One continent cell: its anchor and draws, filled on first use, then its eligibility and its
     * family's geometry plan (the parts of the shape that depend only on the cell). Every field is
     * a pure function of the kernel and cell, so reusing or replacing an entry changes no result.
     */
    static final class Cell {
        Kernel kernel;
        long gx, gz, id;
        double ax, az, size, elongation, rough, axis, factor, support;
        boolean facts;
        double suitability, islandBias;
        boolean archipelago;

        /** 0: earlier facts reject it, 1: seeded open sea, 2: built. */
        int status;

        Kernel shape;
        double c, s;

        /** Crest strength (0: no range) and its shape-frame polyline, (x, z) pairs. */
        double crestStrength;

        int crestPoints;
        final double[] crest = new double[12];
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
        boolean certify;
        double positiveClearance;
        double headland;

        /**
         * Cell cache indexed by cell coordinates modulo 8: the at most 7 x 7 candidate window of
         * one query never collides, so its entries stay valid for the whole query.
         */
        final Cell[] cells = new Cell[64];

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
            double shear = 3.75 * tuning.warpAmplitude / tuning.warpWavelength;
            stretch = (1 + shear) * (1 + shear);
            jitter = variation == null ? .08 : variation.jitter;
            largestSupport =
                    2 * largest + detailBound(variation == null ? 1 : variation.roughnessMax);
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
            if (reach > 3) throw new IllegalArgumentException("continental supports too large");
        }

        /** The largest distance at which any continent's shape can reach from its anchor. */
        public final double largestSupport() {
            return largestSupport;
        }

        /** Largest coastal detail at a roughness; legacy keeps the fixed .025-spacing bound. */
        private double detailBound(double roughness) {
            return variation == null
                    ? .025 * tuning.spacing
                    : roughness * tuning.detailAmplitude * (1.75 + variation.macroBound);
        }

        /** The kernel whose geometry builds this continent. */
        Kernel geometryOf(long id) {
            return this;
        }

        public final void sample(double x, double z, Sample out, Scratch scratch) {
            sample(x, z, out, scratch, true, false);
        }

        /** As {@link #sample}, without shelter or crest: for probes and reads of the score. */
        public final void sampleScore(double x, double z, Sample out, Scratch scratch) {
            sample(x, z, out, scratch, false, false);
        }

        /** As {@link #sampleScore}, plus the crest weight and gradient (for base elevation). */
        public final void sampleCrest(double x, double z, Sample out, Scratch scratch) {
            sample(x, z, out, scratch, false, true);
        }

        public final double shearX(double z) {
            return tuning.warpAmplitude
                    * noise(seed ^ 0x534845415258L, z / tuning.warpWavelength, .31);
        }

        public final double shearZ(double u) {
            return tuning.warpAmplitude
                    * noise(seed ^ 0x53484541525AL, u / tuning.warpWavelength, .73);
        }

        public final double warpStretch() {
            return stretch;
        }

        /** Independent occurrence draw, or a stratified 2 x 2 block quota when configured. */
        private boolean occupied(long gx, long gz, long id, double suitability) {
            int quota = tuning.stratifiedOccupancy;
            if (quota == 0) return random(id, 3) < tuning.occurrence * (.55 + .45 * suitability);
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
            double u = x + shearX(z), v = z + shearZ(u), o = tuning.corridorOffset;
            return min(
                            gridDistance(u + o, tuning.corridorPeriod),
                            gridDistance(v + o, tuning.corridorPeriod))
                    - tuning.corridorHalfWidth;
        }

        /** The cached cell, with its anchor and draws; replaced in place on a miss. */
        private Cell cellAt(Scratch scratch, long gx, long gz) {
            int slot = (int) (gx & 7) | (int) (gz & 7) << 3;
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
            if (variation != null) {
                c.size = variation.size(random(id, 300));
                c.elongation = variation.elongation(random(id, 301));
                c.rough = variation.roughness(random(id, 303));
            }
            // Geometry runs in a frame scaled by size and stretched by the axis ratio; scaling
            // its score by size / sqrt(elongation) keeps the score Lipschitz bound.
            c.axis = sqrt(c.elongation);
            c.factor = c.size / c.axis;
            // Fixed maximum-detail bound keeps anchor admission independent of coast-detail
            // tuning.
            c.support = 2 * p.radius * c.size * c.axis + detailBound(c.rough);
            c.facts = c.planned = false;
            return c;
        }

        /** Eligibility, island flag and frame, from earlier facts at the anchor only. */
        private void facts(Cell c) {
            Tuning p = tuning;
            double worldAz =
                    c.az
                            - p.warpAmplitude
                                    * noise(seed ^ 0x53484541525AL, c.ax / p.warpWavelength, .73);
            double worldAx =
                    c.ax
                            - p.warpAmplitude
                                    * noise(
                                            seed ^ 0x534845415258L,
                                            worldAz / p.warpWavelength,
                                            .31);
            c.suitability = earlier.suitability(worldAx, worldAz);
            c.islandBias = earlier.islandBias(worldAx, worldAz);
            if (!unit(c.suitability) || !unit(c.islandBias))
                throw new IllegalArgumentException("earlier facts");
            c.archipelago = random(c.id, 4) < c.islandBias;
            c.status =
                    c.suitability < p.eligibilityFloor
                            ? 0
                            : !occupied(c.gx, c.gz, c.id, c.suitability) ? 1 : 2;
            if (c.status == 2) {
                double angle = TAU * random(c.id, 5);
                c.c = cos(angle);
                c.s = sin(angle);
                c.shape = geometryOf(c.id);
            }
            c.crestStrength = 0;
            c.crestPoints = 0;
            Skeleton k = variation == null ? null : variation.skeleton;
            if (k != null && c.status == 2 && !c.archipelago && random(c.id, 310) < k.rangeShare) {
                double r = p.radius;
                c.crestStrength = lerp(k.strengthMin, k.strengthMax, random(c.id, 311)) * c.size;
                if (c.shape.family == Family.SPINE) {
                    // The spine's own centreline (its plan's node positions).
                    for (int i = 0; i < 6; i++) {
                        c.crest[2 * i] = r * (-1.22 + .49 * i);
                        c.crest[2 * i + 1] =
                                r
                                        * (.48 * (random(c.id, 40 + i) - .5)
                                                + .18 * sin(i * 1.7 + random(c.id, 49) * TAU));
                    }
                    c.crestPoints = 6;
                } else {
                    for (int i = 0; i < 3; i++) {
                        c.crest[2 * i] = r * k.length * (i - 1);
                        c.crest[2 * i + 1] = r * k.bend * (2 * random(c.id, 312 + i) - 1);
                    }
                    c.crestPoints = 3;
                }
            }
            c.facts = true;
        }

        private void sample(
                double x, double z, Sample out, Scratch scratch, boolean traits, boolean crests) {
            if (!Double.isFinite(x)
                    || !Double.isFinite(z)
                    || abs(x) > 30_000_000
                    || abs(z) > 30_000_000)
                throw new IllegalArgumentException(
                        "absolute coordinates outside supported Minecraft range");
            Tuning p = tuning;
            // Two triangular shears are globally invertible: det(J)=1. Avoid fold-prone vector
            // warps.
            double u =
                    x + p.warpAmplitude * noise(seed ^ 0x534845415258L, z / p.warpWavelength, .31);
            double v =
                    z + p.warpAmplitude * noise(seed ^ 0x53484541525AL, u / p.warpWavelength, .73);
            long cx = (long) floor(u / p.spacing), cz = (long) floor(v / p.spacing);
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
            out.crest = out.crestDx = out.crestDz = 0;
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
                if (!c.facts) facts(c);
                // Varied continents reach full ocean depth (-transitionWidth) at their support
                // edge, so a shallow island field cannot end in a step to the deep sea there.
                double bound = c.support - dist - (variation == null ? 0 : p.transitionWidth);
                Reason reason;
                double score, cut = Double.POSITIVE_INFINITY, shaped = 0;
                if (c.status == 0) {
                    out.rejectedEarlier++;
                    score = min(bound, -p.transitionWidth);
                    reason = Reason.EARLIER_INELIGIBLE;
                } else if (c.status == 1) {
                    out.rejectedSeed++;
                    score = min(bound, -p.transitionWidth);
                    reason = Reason.SEEDED_OPEN_SEA;
                } else {
                    out.admitted++;
                    double lx = c.c * wx + c.s * wz, lz = -c.s * wx + c.c * wz;
                    double qx = lx / (c.size * c.axis), qz = lz * c.axis / c.size;
                    long id = c.id;
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
                                    : c.rough * p.detailAmplitude * roughCoast(id, u, v);
                    scratch.positiveClearance = 0;
                    scratch.headland = 0;
                    double body = c.factor * shapeScore(c, qx, qz, scratch);
                    score = min(bound, body + detail);
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
                    shaped = body;
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
                        out.family = geometryOf(c.id).family;
                        out.size = c.size;
                        out.elongation = c.elongation;
                        out.roughness = c.rough;
                        owner = c.status == 2 ? c : null;
                        ownerBody = shaped;
                    }
                } else if (variation != null && score > second) {
                    second = score;
                    runnerUp = c.status == 2 ? c : null;
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
            if (crests && variation != null && variation.skeleton != null && !out.marine)
                crest(scratch, live, u, v, out);
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
                sum += probe;
                land += clamp(.5 + probe / 64, 0, 1);
            }
            double shore = here > 0 ? clamp(4 - land, 0, 1) : clamp(land - 1, 0, 1);
            return shore * (sum - 4 * here) / h;
        }

        /**
         * Crest weight at a land column: the largest crest of any continent in the query window
         * (owner or not, so merged land has no seam where ownership switches), faded to zero at
         * the coast by the union score, and its world gradient by forward differences over 4
         * blocks (the coast fade's own slope is left out; it only shapes the last coastClear
         * radii). A crest ends well inside its continent's support, so the window's edge never
         * cuts one.
         */
        private void crest(Scratch scratch, int live, double u, double v, Sample out) {
            for (int n = 0; n < live; n++) if (!scratch.window[n].facts) facts(scratch.window[n]);
            out.crest = crestMax(scratch, live, u, v, out.score);
            if (out.crest <= 0) return;
            out.crestDx = (crestMax(scratch, live, u + 4, v, out.score) - out.crest) / 4;
            out.crestDz = (crestMax(scratch, live, u, v + 4, out.score) - out.crest) / 4;
        }

        /** The largest crest in the window, each faded at the coast by its own continent's size. */
        private double crestMax(Scratch scratch, int live, double u, double v, double score) {
            double best = 0, clear = variation.skeleton.coastClear * tuning.radius;
            for (int n = 0; n < live; n++) {
                Cell c = scratch.window[n];
                if (c.crestStrength > 0)
                    best =
                            max(
                                    best,
                                    crestShape(c, u - c.ax, v - c.az)
                                            * fade(clamp(score / (clear * c.size), 0, 1)));
            }
            return best;
        }

        /**
         * One continent's crest weight before the coast fade: strength, tapered over the outer
         * quarter of the crest at each end, times a smooth falloff with shape-frame distance to the polyline. The
         * half-width wanders by up to 20% along the crest and the distance by up to 8% of it
         * (noise at the continent radius, from the cell id), so ranges end and narrow irregularly instead of
         * following the segments' straight contours.
         */
        private double crestShape(Cell c, double wx, double wz) {
            Skeleton k = variation.skeleton;
            double r = tuning.radius;
            double lx = c.c * wx + c.s * wz, lz = -c.s * wx + c.c * wz;
            double qx = lx / (c.size * c.axis), qz = lz * c.axis / c.size;
            double best = Double.POSITIVE_INFINITY;
            int segments = c.crestPoints - 1;
            for (int i = 0; i < segments; i++) {
                double ax = c.crest[2 * i], az = c.crest[2 * i + 1];
                double bx = c.crest[2 * i + 2], bz = c.crest[2 * i + 3];
                double t =
                        clamp(
                                ((qx - ax) * (bx - ax) + (qz - az) * (bz - az))
                                        / (sq(bx - ax) + sq(bz - az)),
                                0,
                                1);
                best = min(best, sq(qx - lerp(ax, bx, t)) + sq(qz - lerp(az, bz, t)));
            }
            // Position along the crest by projection on its own axis (crest x is monotone), so it
            // is continuous where the nearest segment flips inside a bend.
            double first = c.crest[0], last = c.crest[2 * segments];
            double along = clamp((qx - first) / (last - first), 0, 1);
            double width =
                    k.halfWidth * r * (1 + .2 * noise(c.id ^ 0x4352455354574CL, along * 4, .5));
            double d = sqrt(best) + .08 * width * noise(c.id ^ 0x435245535444L, qx / r, qz / r);
            double t = 1 - d / width;
            if (t <= 0) return 0;
            double taper = fade(clamp(min(along, 1 - along) / .25, 0, 1));
            return c.crestStrength * taper * fade(t);
        }

        /**
         * Crest-chain nodes near a column: every continent in the column's query window that
         * carries a crest gets max(1, round(length / spacing)) nodes, evenly spaced along its
         * crest axis; each node within {@code radius} blocks of the column is written to {@code
         * out} as (cell x, cell z, node index, world x, world z, tangent angle). Returns the node
         * count, at most out.length / 6. Nodes are pure functions of the cell, so a node is the
         * same from every column that sees it.
         */
        public final int crestNodes(
                double x, double z, double spacing, double radius, Scratch scratch, double[] out) {
            if (variation == null || variation.skeleton == null) return 0;
            Tuning p = tuning;
            double u =
                    x + p.warpAmplitude * noise(seed ^ 0x534845415258L, z / p.warpWavelength, .31);
            double v =
                    z + p.warpAmplitude * noise(seed ^ 0x53484541525AL, u / p.warpWavelength, .73);
            long cx = (long) floor(u / p.spacing), cz = (long) floor(v / p.spacing);
            int side = 2 * reach + 1, n = 0;
            for (int k = 0; k < side * side; k++) {
                Cell c = cellAt(scratch, cx + k % side - reach, cz + k / side - reach);
                double edge = c.support + radius;
                if (sq(u - c.ax) + sq(v - c.az) > edge * edge) continue;
                if (!c.facts) facts(c);
                if (c.crestStrength <= 0) continue;
                int segments = c.crestPoints - 1;
                double first = c.crest[0], last = c.crest[2 * segments];
                int count = max(1, (int) round((last - first) * c.size * c.axis / spacing));
                for (int i = 0; i < count; i++) {
                    double qx = first + (last - first) * (i + .5) / count;
                    int s = 0;
                    while (s < segments - 1 && c.crest[2 * s + 2] < qx) s++;
                    double ax = c.crest[2 * s], az = c.crest[2 * s + 1];
                    double bx = c.crest[2 * s + 2], bz = c.crest[2 * s + 3];
                    double qz = lerp(az, bz, (qx - ax) / (bx - ax));
                    double lx = qx * c.size * c.axis, lz = qz * c.size / c.axis;
                    double nu = c.ax + c.c * lx - c.s * lz, nv = c.az + c.s * lx + c.c * lz;
                    // Back through the two shears to world coordinates.
                    double worldZ =
                            nv
                                    - p.warpAmplitude
                                            * noise(
                                                    seed ^ 0x53484541525AL,
                                                    nu / p.warpWavelength,
                                                    .73);
                    double worldX =
                            nu
                                    - p.warpAmplitude
                                            * noise(
                                                    seed ^ 0x534845415258L,
                                                    worldZ / p.warpWavelength,
                                                    .31);
                    if (sq(worldX - x) + sq(worldZ - z) > radius * radius) continue;
                    if (n + 6 > out.length) return n / 6;
                    double tx = (bx - ax) * c.size * c.axis, tz = (bz - az) * c.size / c.axis;
                    out[n++] = c.gx;
                    out[n++] = c.gz;
                    out[n++] = i;
                    out[n++] = worldX;
                    out[n++] = worldZ;
                    out[n++] = atan2(c.s * tx + c.c * tz, c.c * tx - c.s * tz);
                }
            }
            return n / 6;
        }

        /**
         * Continents whose support plus {@code margin} reaches a column and that carry a crest:
         * their cell coordinates as (x, z) pairs in {@code out}. Returns the count.
         */
        public final int crestCells(
                double x, double z, double margin, Scratch scratch, long[] out) {
            if (variation == null || variation.skeleton == null) return 0;
            Tuning p = tuning;
            double u =
                    x + p.warpAmplitude * noise(seed ^ 0x534845415258L, z / p.warpWavelength, .31);
            double v =
                    z + p.warpAmplitude * noise(seed ^ 0x53484541525AL, u / p.warpWavelength, .73);
            long cx = (long) floor(u / p.spacing), cz = (long) floor(v / p.spacing);
            int side = 2 * reach + 1, n = 0;
            for (int k = 0; k < side * side && n + 2 <= out.length; k++) {
                Cell c = cellAt(scratch, cx + k % side - reach, cz + k / side - reach);
                double edge = c.support + margin;
                if (sq(u - c.ax) + sq(v - c.az) > edge * edge) continue;
                if (!c.facts) facts(c);
                if (c.crestStrength <= 0) continue;
                out[n++] = c.gx;
                out[n++] = c.gz;
            }
            return n / 2;
        }

        /**
         * Built continents whose support plus {@code margin} reaches a column, with or without a
         * crest: their cell coordinates as (x, z) pairs in {@code out}. Returns the count. (Riven
         * extension for per-continent drainage.)
         */
        public final int continentCells(
                double x, double z, double margin, Scratch scratch, long[] out) {
            Tuning p = tuning;
            double u =
                    x + p.warpAmplitude * noise(seed ^ 0x534845415258L, z / p.warpWavelength, .31);
            double v =
                    z + p.warpAmplitude * noise(seed ^ 0x53484541525AL, u / p.warpWavelength, .73);
            long cx = (long) floor(u / p.spacing), cz = (long) floor(v / p.spacing);
            int side = 2 * reach + 1, n = 0;
            for (int k = 0; k < side * side && n + 2 <= out.length; k++) {
                Cell c = cellAt(scratch, cx + k % side - reach, cz + k / side - reach);
                double edge = c.support + margin;
                if (sq(u - c.ax) + sq(v - c.az) > edge * edge) continue;
                if (!c.facts) facts(c);
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
            double r = c.support + tuning.warpAmplitude;
            box[0] = c.ax - r;
            box[1] = c.az - r;
            box[2] = c.ax + r;
            box[3] = c.az + r;
            return c.id;
        }

        /**
         * Trunk river paths of one continent, a pure function of its cell. About every {@code
         * spacing} world blocks along the crest (jittered up to 30%) a path leaves the crest
         * perpendicular to it, on alternating flanks, with a steady turn (up to 0.3 radians per
         * kilometre) plus smooth wander (up to 0.6 radians, 900-block wavelength), in world
         * steps of about {@code step}, until the continent's shape (before detail) lies more than
         * its detail bound plus two steps below the coast, or after {@code maxSteps}. Paths are
         * written to {@code out} as (point count, x0, z0, x1, z1, ...) in world coordinates, from
         * the crest outward. Returns the path count.
         */
        public final int trunkPaths(
                long gx,
                long gz,
                double spacing,
                double step,
                int maxSteps,
                Scratch scratch,
                double[] out) {
            if (variation == null || variation.skeleton == null) return 0;
            Tuning p = tuning;
            Cell c = cellAt(scratch, gx, gz);
            if (!c.facts) facts(c);
            if (c.crestStrength <= 0) return 0;
            int segments = c.crestPoints - 1, paths = 0, at = 0;
            double first = c.crest[0], last = c.crest[2 * segments];
            int count = max(1, (int) round((last - first) * c.size * c.axis / spacing));
            boolean flip = random(c.id, 320) < .5;
            double sea = detailBound(c.rough) + 2 * step;
            for (int i = 0; i < count; i++) {
                // Start points jitter up to 30% of the spacing along the crest.
                double qx =
                        first
                                + (last - first)
                                        * (i + .5 + .6 * (random(c.id, 340 + i) - .5))
                                        / count;
                int s = 0;
                while (s < segments - 1 && c.crest[2 * s + 2] < qx) s++;
                double ax = c.crest[2 * s], az = c.crest[2 * s + 1];
                double bx = c.crest[2 * s + 2], bz = c.crest[2 * s + 3];
                double qz = lerp(az, bz, (qx - ax) / (bx - ax));
                double tx = bx - ax, tz = bz - az, length = hypot(tx, tz);
                double side = ((i & 1) == 0) == flip ? 1 : -1;
                double ox = -tz / length * side, oz = tx / length * side, nx = ox, nz = oz;
                double turn = (random(c.id, 330 + i) - .5) * .6 / 1000;
                long wander = c.id ^ (0x5452554e4b57L + i * 0x9e3779b97f4a7c15L);
                if (at + 1 + 2 * (maxSteps + 1) > out.length) break;
                int head = at++, points = 0;
                for (int k = 0; k <= maxSteps; k++) {
                    double lx = qx * c.size * c.axis, lz = qz * c.size / c.axis;
                    double nu = c.ax + c.c * lx - c.s * lz, nv = c.az + c.s * lx + c.c * lz;
                    double worldZ =
                            nv
                                    - p.warpAmplitude
                                            * noise(
                                                    seed ^ 0x53484541525AL,
                                                    nu / p.warpWavelength,
                                                    .73);
                    double worldX =
                            nu
                                    - p.warpAmplitude
                                            * noise(
                                                    seed ^ 0x534845415258L,
                                                    worldZ / p.warpWavelength,
                                                    .31);
                    out[at++] = worldX;
                    out[at++] = worldZ;
                    points++;
                    if (k > 0 && c.factor * shapeScore(c, qx, qz, scratch) < -sea) break;
                    // One world step along the current direction, measured through the frame.
                    double scale = hypot(nx * c.size * c.axis, nz * c.size / c.axis);
                    qx += nx * step / scale;
                    qz += nz * step / scale;
                    // Heading: a steady turn plus up to 0.6 radians of smooth wander (900-block
                    // wavelength) from the distance travelled, so courses bend irregularly
                    // without drifting.
                    double travelled = (k + 1) * step;
                    double a = turn * travelled + .6 * noise(wander, travelled / 900, .37);
                    double ca = cos(a), sa = sin(a);
                    nx = ox * ca - oz * sa;
                    nz = ox * sa + oz * ca;
                }
                out[head] = points;
                paths++;
            }
            return paths;
        }

        /**
         * Three rotated octaves in -1.75..1.75 (coves and points at three scales), plus the
         * layout-scaled octaves above them when the world is larger than the 8 km tuning.
         */
        private double roughCoast(long id, double u, double v) {
            double w = tuning.detailWavelength, w2 = w / 2.3, w3 = w / 5.3, macro = 0;
            for (int k = 0; k < variation.macro.length; k++) {
                double weight = variation.macro[k];
                if (weight <= 0) break;
                double wk = w * variation.macroScale[k];
                double ca = variation.macroCos[k], sa = variation.macroSin[k];
                macro +=
                        weight
                                * (2 << k)
                                * noise(
                                        id ^ (0x524F5547484DL + k),
                                        (ca * u + sa * v) / wk,
                                        (-sa * u + ca * v) / wk);
            }
            return macro
                    + noise(id ^ 0x524F554748314CL, u / w, v / w)
                    + .5
                            * noise(
                                    id ^ 0x524F554748324CL,
                                    (.8 * u + .6 * v) / w2,
                                    (-.6 * u + .8 * v) / w2)
                    + .25
                            * noise(
                                    id ^ 0x524F554748334CL,
                                    (.28 * u - .96 * v) / w3,
                                    (.96 * u + .28 * v) / w3);
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
                CellularStyle cellular,
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
                        new Cellular(seed, p, r, e, cellular),
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

        public Harmonic(long seed, Tuning p, Reference r, Earlier e) {
            this(seed, p, r, e, HarmonicStyle.defaults());
        }

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

        public Spine(long seed, Tuning p, Reference r, Earlier e) {
            this(seed, p, r, e, SpineStyle.defaults());
        }

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

    /** F3: seven jittered sub-sites; eroded pairwise bisectors clip rounded cells. Selected adjacency
     * bridges are a separate union. This is a finite local mosaic, NOT a global Voronoi simulation. */
    public static final class Cellular extends Kernel {
        public final CellularStyle style;

        /** Plan layout: sites, per-pair bisector terms, then bridge endpoints. */
        private static final int SITES = 0, PAIRS = 24, BRIDGES = PAIRS + 4 * 64;

        public Cellular(long seed, Tuning p, Reference r, Earlier e) {
            this(seed, p, r, e, CellularStyle.defaults());
        }

        public Cellular(long seed, Tuning p, Reference r, Earlier e, CellularStyle style) {
            super(Family.CELLULAR, seed, p, r, e);
            this.style = java.util.Objects.requireNonNull(style);
        }

        /**
         * Plan: (x, z, radius) of each sub-site; for each ordered pair the bisector direction,
         * offset and length; then the connected edges of the bounded Prim tree over the sites
         * (no recursive selected-neighbor queries).
         */
        void plan(Cell c) {
            long id = c.id;
            boolean islands = c.archipelago;
            double r = tuning.radius;
            int count = 4 + (int) (4 * random(id, 59));
            double[] g = c.plan(BRIDGES + 1 + 2 * 8);
            g[SITES] = 0;
            g[SITES + 1] = 0;
            for (int i = 1; i < count; i++) {
                double a = TAU * (i - 1 + .8 * (random(id, 60 + i) - .5)) / (count - 1);
                double orbit = r * (.42 + .72 * random(id, 70 + i));
                g[SITES + 3 * i] = orbit * cos(a);
                g[SITES + 3 * i + 1] = orbit * sin(a);
            }
            for (int i = 0; i < count; i++)
                g[SITES + 3 * i + 2] =
                        (islands
                                        ? (.32 + .12 * random(id, 130 + i))
                                        : (.42 + .42 * random(id, 130 + i)))
                                * r;
            for (int i = 0; i < count; i++)
                for (int j = 0; j < count; j++)
                    if (i != j) {
                        double xi = g[SITES + 3 * i], zi = g[SITES + 3 * i + 1];
                        double xj = g[SITES + 3 * j], zj = g[SITES + 3 * j + 1];
                        double dx = xj - xi, dz = zj - zi;
                        int at = PAIRS + 4 * (8 * i + j);
                        g[at] = dx;
                        g[at + 1] = dz;
                        g[at + 2] = (sq(xj) + sq(zj) - sq(xi) - sq(zi)) / 2;
                        g[at + 3] = hypot(dx, dz);
                    }
            int edges = 0;
            if (!islands) {
                boolean[] joined = new boolean[count];
                joined[0] = true;
                for (int edge = 1; edge < count; edge++) {
                    double best = Double.POSITIVE_INFINITY;
                    int from = 0, to = 0;
                    for (int i = 0; i < count; i++)
                        if (joined[i])
                            for (int j = 0; j < count; j++)
                                if (!joined[j]) {
                                    double d =
                                            sq(g[SITES + 3 * i] - g[SITES + 3 * j])
                                                    + sq(
                                                            g[SITES + 3 * i + 1]
                                                                    - g[SITES + 3 * j + 1]);
                                    if (d < best) {
                                        best = d;
                                        from = i;
                                        to = j;
                                    }
                                }
                    joined[to] = true;
                    if (random(id, 80 + to) < style.connectionProbability) {
                        g[BRIDGES + 1 + 2 * edges] = from;
                        g[BRIDGES + 2 + 2 * edges] = to;
                        edges++;
                    }
                }
            }
            g[BRIDGES] = edges;
            c.count = count;
        }

        protected double geometry(Cell c, double x, double z, Scratch s) {
            boolean islands = c.archipelago;
            double[] g = c.plan;
            int count = c.count;
            double r = tuning.radius, result = -2 * r;
            double inset = style.insetScale * ((islands ? .09 : .04) * r + .025 * tuning.spacing);
            for (int i = 0; i < count; i++) {
                double score =
                        g[SITES + 3 * i + 2]
                                - hypot(x - g[SITES + 3 * i], z - g[SITES + 3 * i + 1]);
                for (int j = 0; j < count; j++)
                    if (i != j) {
                        int at = PAIRS + 4 * (8 * i + j);
                        double clearance = (g[at + 2] - x * g[at] - z * g[at + 1]) / g[at + 3];
                        score = min(score, clearance - inset);
                    }
                result = max(result, score);
            }
            // Irregular count/spacing/radius plus a sparse tree avoid a repeated radial flower
            // motif.
            for (int e = 0, edges = (int) g[BRIDGES]; e < edges; e++) {
                int from = (int) g[BRIDGES + 1 + 2 * e], to = (int) g[BRIDGES + 2 + 2 * e];
                result =
                        max(
                                result,
                                tuning.bridgeHalfWidth
                                        - capsuleDistance(
                                                x,
                                                z,
                                                g[SITES + 3 * from],
                                                g[SITES + 3 * from + 1],
                                                g[SITES + 3 * to],
                                                g[SITES + 3 * to + 1]));
            }
            return result;
        }
    }

    /** F4: seven compact elliptical potential kernels; sum/threshold joins create organic necks.
     * Threshold is dimensionless; output is block-equivalent score, not a Euclidean signed distance. */
    public static final class Potential extends Kernel {
        public final PotentialStyle style;

        public Potential(long seed, Tuning p, Reference r, Earlier e) {
            this(seed, p, r, e, PotentialStyle.defaults());
        }

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

    public static Kernel create(Family family, long seed, Tuning p, Reference r, Earlier e) {
        switch (family) {
            case HARMONIC:
                return new Harmonic(seed, p, r, e);
            case SPINE:
                return new Spine(seed, p, r, e);
            case CELLULAR:
                return new Cellular(seed, p, r, e);
            case POTENTIAL:
                return new Potential(seed, p, r, e);
            default:
                throw new IllegalArgumentException("family");
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
