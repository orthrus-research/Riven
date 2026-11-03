package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.Settings;

import com.google.gson.JsonObject;

/**
 * The crust's setting classifier: which settings hold at the sky's floor, from facts the terrain
 * sample holds at its own column, with thresholds from riven-surface.json. The crust's setting
 * table (riven-surface.json tables.crust) orders them; each condition here stands on its own.
 */
final class CrustSettings implements Settings.Classifier<TerrainMap.Sample> {
    private final SurfaceRules rules;
    private final double shallowFrom, bar, green, reflective, cover, rough, steep, snowline, sea;
    private final double clayWavelength, clayThreshold;
    private final SeededNoise clay;

    CrustSettings(JsonObject t, SurfaceRules rules, TerrainHeightReference heights) {
        this.rules = rules;
        TerrainHeightReference.Feature shoal =
                heights.feature(t.get("shallowFromReference").getAsString());
        if (shoal.elevationOffset == null)
            throw new IllegalStateException("Shallow beds need an elevation reference");
        shallowFrom = heights.seaLevel + shoal.elevationOffset.min;
        bar = Rules.number(t, "barWithinBlocksOfChannelHead", 0, 16);
        green = Rules.number(t, "greenAboveWaterWeight", 0, 1);
        reflective = Rules.number(t, "reflectiveBelowShoaling", 0, 1);
        cover = Rules.number(t, "drylandCoverFrom", .001, 1);
        rough = Rules.number(t, "roughAboveSlope", 0, 16);
        steep = Rules.number(t, "steepAboveSlope", 0, 16);
        snowline = Rules.number(t, "snowlineCelsius", -60, 60);
        sea = heights.seaLevel;
        JsonObject patch = t.getAsJsonObject("clayPatch");
        clay = new SeededNoise(SeededNoise.mix(Rules.seed(patch, "variationSeed") ^ 0x434c4159L));
        clayWavelength = Rules.number(patch, "wavelengthBlocks", 4, 4096);
        clayThreshold = Rules.number(patch, "threshold", -1, 1);
    }

    @Override
    public long holds(TerrainMap.Sample s, int floor) {
        long h = Settings.bit(Settings.DEFAULT);
        boolean volume = s.rocky.hasVolume();
        if (s.sediment.weight > 0) h |= Settings.bit(Settings.SEDIMENT);
        if (!volume && s.rocky.weight > 0) h |= Settings.bit(Settings.ROCKY);
        if (s.sediment.mud) h |= Settings.bit(Settings.MUD);
        boolean shallow = floor >= shallowFrom;
        if (s.ocean.marine)
            h |= Settings.bit(shallow ? Settings.MARINE_SHALLOW : Settings.MARINE_DEEP);
        Hydro.Sample w = s.water;
        if (w.kind == Hydro.Kind.LAGOON)
            h |= Settings.bit(shallow ? Settings.LAGOON_SHALLOW : Settings.LAGOON_DEEP);
        if (s.waterY() > floor)
            h |=
                    Settings.bit(
                            w.bed == Hydro.Bed.SAND
                                    ? Settings.WATER_BED_SAND
                                    : w.bed != Hydro.Bed.DIRT
                                            ? Settings.WATER_BED_GRAVEL
                                            : clay(s)
                                                    ? Settings.WATER_BED_CLAY
                                                    : Settings.WATER_BED_DIRT);
        if (w.bar != null && floor <= Math.floor(w.channelHead) + bar)
            h |= Settings.bit(w.bar == Hydro.Bed.SAND ? Settings.BAR_SAND : Settings.BAR_GRAVEL);
        if ((w.kind == Hydro.Kind.OASIS || w.kind == Hydro.Kind.WETLAND) && w.weight > green)
            h |= Settings.bit(Settings.GREEN);
        // A volume's floor is the ground before it, with that ground's own cover policy.
        boolean bare =
                volume ? rules.bareStoneBeforeRocky(s) : !s.ocean.marine && rules.bareStone(s);
        if (bare) h |= Settings.bit(Settings.BARE_STONE);
        // Above the snowline, the climate cooled to the floor's own height: snow over rock where
        // the ground is bare, and snow over soil elsewhere.
        boolean snowline =
                !s.ocean.marine
                        && s.waterY() <= floor
                        && ClimateMap.celsiusAt(s.climate, floor - sea) < this.snowline;
        if (snowline && bare) h |= Settings.bit(Settings.SNOWCAP);
        if (s.shore.sandy)
            h |=
                    Settings.bit(
                            s.ocean.shoaling < reflective
                                    ? Settings.SHORE_REFLECTIVE
                                    : Settings.SHORE_SANDY);
        if (s.drylands.pavement >= cover && s.drylands.pavement >= s.drylands.sandCover)
            h |= Settings.bit(Settings.DRYLAND_PAVEMENT);
        else if (s.drylands.sandCover >= cover) h |= Settings.bit(Settings.DRYLAND_SAND);
        ClimateRegion region = s.climate.region;
        if (region != null && region.surface == ClimateRegion.Surface.SAND)
            h |= Settings.bit(Settings.CLIMATE_SAND);
        if (snowline || region != null && region.surface == ClimateRegion.Surface.SNOW)
            h |= Settings.bit(Settings.SNOW);
        double slope = Math.hypot(s.broadDx, s.broadDz);
        if (slope > steep) h |= Settings.bit(Settings.STEEP);
        if (slope > rough) h |= Settings.bit(Settings.ROUGH);
        return h;
    }

    /** Whether a dirt bed is clay here: a patch of value noise. */
    private boolean clay(TerrainMap.Sample s) {
        return clay.sample(s.x / clayWavelength, s.z / clayWavelength) > clayThreshold;
    }
}
