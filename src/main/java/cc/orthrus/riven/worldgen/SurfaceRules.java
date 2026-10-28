package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/**
 * Inexpensive default cover policy shared by native terrain and distant previews. Soil comes
 * before rock (terrain-v90): rocky coasts show rock only in the splash zone and on cliff faces,
 * cut floors keep their soil where the climate grows it back, and high ground turns to rock above
 * a treeline set by the column's temperature.
 */
public final class SurfaceRules {
    public static final int VERSION = 9;
    private static final Definition DEFINITION = Definition.load();
    public static final String FINGERPRINT = DEFINITION.fingerprint;

    public enum CoverReason {
        CLIMATE,
        SEDIMENT,
        ROCKY_FORMATION,
        MARINE,
        ROCKY_SHORE,
        HIGH_ALTITUDE,
        MOUNTAIN,
        EXPOSED_CUT,
        DRY_SAND,
        DRY_PAVEMENT,
        WONDER_ROCK
    }

    public final int stoneAboveY, soilDepth;
    private final double mountainBlend, exposedCutDepth;

    public SurfaceRules(TerrainHeightReference heights) {
        TerrainHeightReference.Feature mountain = heights.feature(DEFINITION.reference);
        if (mountain.elevationOffset == null)
            throw new IllegalStateException("Stone cutoff needs an elevation reference");
        stoneAboveY = (int) Math.ceil(heights.seaLevel + mountain.elevationOffset.min);
        if (stoneAboveY < heights.lowestSurface || stoneAboveY > heights.highestSurface)
            throw new IllegalStateException("Stone cutoff outside allocated surface range");
        soilDepth = DEFINITION.soilDepth;
        mountainBlend = DEFINITION.mountainBlend;
        exposedCutDepth = soilDepth * DEFINITION.exposedLayers;
    }

    public CoverReason reason(TerrainMap.Sample sample) {
        if (sample.sediment.weight > 0) return CoverReason.SEDIMENT;
        if (sample.rocky.weight > 0) return CoverReason.ROCKY_FORMATION;
        if (sample.wonders.bare && sample.wonders.family != null) return CoverReason.WONDER_ROCK;
        return earlierReason(sample, sample.blockY());
    }

    private CoverReason earlierReason(TerrainMap.Sample sample, int surfaceY) {
        if (sample.ocean.marine) return CoverReason.MARINE;
        if (sample.shore.rocky() && rockFace(sample)) return CoverReason.ROCKY_SHORE;
        int treeline = treeline(sample.climate.celsius);
        if (surfaceY >= treeline) return CoverReason.HIGH_ALTITUDE;
        double mountain =
                "mountains".equals(sample.firstLandform)
                        ? 1
                        : "mountains".equals(sample.secondLandform) ? sample.blend : 0;
        if (mountain >= mountainBlend && surfaceY >= treeline - DEFINITION.mountainBelowTreeline)
            return CoverReason.MOUNTAIN;
        double cut = cutDepth(sample.climate);
        if (sample.carvers.appliedCut >= cut) return CoverReason.EXPOSED_CUT;
        if (DEFINITION.exposePlateauCuts && sample.plateaus.delta <= -cut)
            return CoverReason.EXPOSED_CUT;
        if (DEFINITION.exposeDrylandCuts && sample.drylands.yardangCut >= cut)
            return CoverReason.EXPOSED_CUT;
        if (DEFINITION.exposeColdCuts && sample.cold.delta <= -cut) return CoverReason.EXPOSED_CUT;
        if (DEFINITION.exposeVolcanicCuts && sample.volcanic.delta <= -cut)
            return CoverReason.EXPOSED_CUT;
        if (DEFINITION.exposeGeneralCuts && sample.cuts.cut >= cut) return CoverReason.EXPOSED_CUT;
        RegionalLandmarks.Family landmark = sample.landmarks.dominant;
        if ((landmark == RegionalLandmarks.Family.RIMMED_BASIN
                        || landmark == RegionalLandmarks.Family.BRANCHING_RAVINE)
                && sample.landmarks.appliedDisplacement <= -cut) return CoverReason.EXPOSED_CUT;
        return drylandCover(sample);
    }

    /**
     * Whether a rocky shore column shows rock: within the splash zone (wider on capes) or on a cliff
     * face. Inland of it a rocky coast carries soil like any other ground.
     */
    private static boolean rockFace(TerrainMap.Sample sample) {
        double splash = DEFINITION.splash + DEFINITION.capeSplash * sample.ocean.cape;
        return sample.shore.distance < splash
                || (sample.shore.kind == Shore.Kind.CLIFF
                        && sample.shore.distance < DEFINITION.cliffFace);
    }

    /**
     * The surface height from which ground shows rock: the stone line at the reference
     * temperature, higher in warmer climates and lower in colder ones, within its bounds.
     */
    public int treeline(double celsius) {
        double y =
                stoneAboveY + (celsius - DEFINITION.treelineCelsius) * DEFINITION.treelinePerDegree;
        return (int)
                Math.round(
                        Math.max(
                                stoneAboveY - DEFINITION.treelineBelow,
                                Math.min(stoneAboveY + DEFINITION.treelineAbove, y)));
    }

    /**
     * How deep a cut must be to show rock: one soil layer where nothing grows back quickly, up to
     * growingCutBlocks where plants return. Plants need more moisture the warmer it is (aridity),
     * and none return in frozen ground.
     */
    private double cutDepth(ClimateMap.Sample climate) {
        double arid =
                DEFINITION.aridMoisture
                        + DEFINITION.aridPerDegree * Math.max(0, climate.celsius - ARID_FROM);
        double grows =
                Rules.smooth((climate.moisture - arid) / DEFINITION.moistureSpan)
                        * Rules.smooth(
                                (climate.celsius - DEFINITION.cutCelsius[0])
                                        / (DEFINITION.cutCelsius[1] - DEFINITION.cutCelsius[0]));
        return exposedCutDepth + (DEFINITION.growingCut - exposedCutDepth) * grows;
    }

    /** The temperature above which aridity rises (degrees Celsius). */
    private static final double ARID_FROM = 10;

    /** A cover threshold changes blocks only; continuous terrain admission is unchanged. */
    public CoverReason drylandCover(TerrainMap.Sample sample) {
        double threshold = DEFINITION.minimumDrylandCover;
        if (sample.drylands.pavement >= threshold
                && sample.drylands.pavement >= sample.drylands.sandCover)
            return CoverReason.DRY_PAVEMENT;
        if (sample.drylands.sandCover >= threshold) return CoverReason.DRY_SAND;
        return CoverReason.CLIMATE;
    }

    public boolean bareStone(TerrainMap.Sample sample) {
        return stone(reason(sample));
    }

    /** KEEP in a volume delegates to the earlier surface, including its original cover policy. */
    public boolean bareStoneBeforeRocky(TerrainMap.Sample sample) {
        return stone(earlierReason(sample, (int) Math.floor(sample.rocky.parentHeight)));
    }

    private static boolean stone(CoverReason reason) {
        switch (reason) {
            case ROCKY_FORMATION:
            case ROCKY_SHORE:
            case HIGH_ALTITUDE:
            case MOUNTAIN:
            case EXPOSED_CUT:
            case WONDER_ROCK:
                return true;
            default:
                return false;
        }
    }

    private static final class Definition {
        final String fingerprint, reference;
        final int soilDepth;
        final boolean exposePlateauCuts,
                exposeDrylandCuts,
                exposeColdCuts,
                exposeVolcanicCuts,
                exposeGeneralCuts;
        final double mountainBlend, exposedLayers, minimumDrylandCover;
        final double splash, capeSplash, cliffFace;
        final double treelineCelsius, treelinePerDegree, treelineBelow, treelineAbove;
        final double mountainBelowTreeline, growingCut;
        final double aridMoisture, aridPerDegree, moistureSpan;
        final double[] cutCelsius;

        Definition(JsonObject json, String fingerprint) {
            if (json.get("schema").getAsInt() != 1)
                throw new IllegalStateException("Surface rule schema");
            this.fingerprint = fingerprint;
            reference = json.get("stoneAboveReference").getAsString();
            soilDepth = json.get("soilDepth").getAsInt();
            exposePlateauCuts =
                    json.has("exposePlateauCuts") && json.get("exposePlateauCuts").getAsBoolean();
            exposeDrylandCuts =
                    json.has("exposeDrylandCuts") && json.get("exposeDrylandCuts").getAsBoolean();
            exposeColdCuts =
                    json.has("exposeColdCuts") && json.get("exposeColdCuts").getAsBoolean();
            exposeVolcanicCuts =
                    json.has("exposeVolcanicCuts") && json.get("exposeVolcanicCuts").getAsBoolean();
            exposeGeneralCuts =
                    json.has("exposeGeneralCuts") && json.get("exposeGeneralCuts").getAsBoolean();
            minimumDrylandCover =
                    json.has("minimumDrylandCover")
                            ? Rules.number(json, "minimumDrylandCover", .001, 1)
                            : 1;
            mountainBlend = json.get("minimumMountainBlend").getAsDouble();
            JsonObject shore = json.getAsJsonObject("rockyShore");
            splash = Rules.number(shore, "splashBlocks", 0, 256);
            capeSplash = Rules.number(shore, "capeSplashBlocks", 0, 256);
            cliffFace = Rules.number(shore, "cliffFaceBlocks", 0, 256);
            JsonObject tree = json.getAsJsonObject("treeline");
            treelineCelsius = Rules.number(tree, "referenceCelsius", -40, 40);
            treelinePerDegree = Rules.number(tree, "blocksPerDegree", 0, 32);
            treelineBelow = Rules.number(tree, "lowestBelowStoneBlocks", 0, 256);
            treelineAbove = Rules.number(tree, "highestAboveStoneBlocks", 0, 256);
            mountainBelowTreeline = Rules.number(tree, "mountainBelowTreelineBlocks", 0, 256);
            JsonObject cut = json.getAsJsonObject("cutCover");
            aridMoisture = Rules.number(cut, "aridMoisture", 0, 1);
            aridPerDegree = Rules.number(cut, "aridMoisturePerDegreeAbove10", 0, 1);
            moistureSpan = Rules.number(cut, "moistureSpan", .01, 1);
            cutCelsius = range(cut, "celsiusRange");
            growingCut = Rules.number(cut, "growingCutBlocks", 1, 256);
            exposedLayers = json.get("exposedCutDepthInSoilLayers").getAsDouble();
            if (soilDepth < 1
                    || soilDepth > 16
                    || !Double.isFinite(mountainBlend)
                    || mountainBlend <= 0
                    || mountainBlend > 1
                    || !Double.isFinite(exposedLayers)
                    || exposedLayers <= 0) throw new IllegalStateException("Surface rule range");
        }

        private static double[] range(JsonObject json, String key) {
            com.google.gson.JsonArray a = json.getAsJsonArray(key);
            double[] r = {a.get(0).getAsDouble(), a.get(1).getAsDouble()};
            if (!(r[1] > r[0])) throw new IllegalStateException("Range: " + key);
            return r;
        }

        static Definition load() {
            Rules rules = Rules.load("riven-surface-rules.json", 1);
            return new Definition(rules.json, rules.fingerprint);
        }
    }
}
