package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Earlier terrain evidence shared by all selectors. No finished surface or biome queries. */
public final class Terrain {
    public enum Level {
        OCEAN,
        LOW,
        MIDDLE,
        HIGH
    }

    public enum Character {
        FLOOR,
        PLAIN,
        HILLS,
        PLATEAU,
        RIDGE,
        DUNES
    }

    /**
     * Landform classes from the ground model (terrain-v129), what biomes key on: a strong spine
     * is mountain, a plateau's cap plateau and its edge scarp, else the province type's class.
     */
    public enum Landform {
        BASIN,
        PLAIN,
        HILLS,
        FOOTHILLS,
        RIFT,
        PLATEAU,
        SCARP,
        MOUNTAIN
    }

    public enum Fit {
        ANY,
        CALM,
        RUGGED,
        EDGE,
        PLATEAU,
        DUNES
    }

    private static final Rules RULES = Rules.load("riven-terrain.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private final double lowStart,
            lowEnd,
            highStart,
            highEnd,
            slopeStart,
            slopeEnd,
            edgeSpan,
            edgeDrop;
    public final Sites sites;

    /** The province types' landform classes, and the spine weights a mountain spans. */
    private final java.util.Map<String, Landform> typeLandform = new java.util.HashMap<>();

    private final double mountainFrom, mountainFull;

    /** Inland distance every land-stage adapter leaves to shore shaping. */
    public static double shoreReservation(TerrainHeightReference heights) {
        return heights.feature("coastal_plain").width.targetAverage
                * Rules.number(RULES.json, "shoreReservationInCoastalPlainWidths", 0, 4);
    }

    /**
     * Width over which carver and general-cut lowering eases to nothing toward surface water, so a
     * cut beside a river cannot leave the river's bank standing as a fin.
     */
    static double cutWaterSkirt() {
        return Rules.number(RULES.json, "cutWaterSkirtBlocks", 1, 256);
    }

    /** Capacity of the native column cache; it changes cost, never generated content. */
    static JsonObject nativeColumnCache() {
        return RULES.json.getAsJsonObject("nativeColumnCache");
    }

    public Terrain(long worldSeed, TerrainHeightReference heights) {
        JsonObject j = RULES.json;
        lowStart = Rules.number(j, "lowBlendStart", 0, 3);
        lowEnd = Rules.number(j, "lowBlendEnd", lowStart + 0.01, 3);
        highStart = Rules.number(j, "highBlendStart", lowEnd, 3);
        highEnd = Rules.number(j, "highBlendEnd", highStart + 0.01, 3);
        slopeStart = Rules.number(j, "ruggedSlopeStart", 0, 1);
        slopeEnd = Rules.number(j, "ruggedSlopeEnd", slopeStart + 0.001, 2);
        edgeSpan =
                heights.feature("inland_plain").width.targetAverage
                        * TerrainScale.HORIZONTAL
                        * Rules.number(j, "edgeSpanInPlainWidths", 0.1, 2);
        edgeDrop = heights.feature("scarp").magnitude.targetAverage;
        sites = Sites.create(worldSeed, heights, j.getAsJsonObject("sites"));
        JsonObject classes = j.getAsJsonObject("landforms");
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e :
                classes.getAsJsonObject("provinceTypes").entrySet())
            typeLandform.put(e.getKey(), Landform.valueOf(e.getValue().getAsString()));
        mountainFrom = Rules.number(classes, "mountainSpineFrom", 0, 1);
        mountainFull = Rules.number(classes, "mountainSpineFull", mountainFrom + .01, 1);
    }

    /** Filled once from base fields, then read by later stages. All mutable scratch is caller-owned. */
    public static final class Sample {
        public Level level = Level.OCEAN;
        public Character character = Character.FLOOR;
        public double low, middle, high, rugged, dunes, edge, waterfallSite;

        /** The column's landform class, how strongly it holds, and the plateau and scarp weights. */
        public Landform landform = Landform.PLAIN;

        public double strength, mountain, plateau, scarp;

        public double fit(double lowWeight, double middleWeight, double highWeight, Fit form) {
            double weight = low * lowWeight + middle * middleWeight + high * highWeight;
            switch (form) {
                case CALM:
                    return weight * (1 - rugged);
                case RUGGED:
                    return weight * rugged;
                case EDGE:
                    return weight * edge;
                case PLATEAU:
                    // A plateau's cap and its scarp (v129), not merely calm high ground.
                    return weight * Math.max(plateau, scarp);
                case DUNES:
                    return weight * dunes;
                default:
                    return weight;
            }
        }
    }

    /** Reusable admission factory for landmark and carver profiles. */
    public static final class Admission {
        private final double low, middle, high;
        private final Fit form;

        private Admission(JsonObject json) {
            com.google.gson.JsonArray levels = json.getAsJsonArray("terrainAdmission");
            if (levels == null || levels.size() != 3)
                throw new IllegalStateException("Three terrain weights required");
            double[] weights = new double[3];
            for (int i = 0; i < 3; i++) {
                weights[i] = levels.get(i).getAsDouble();
                if (!Double.isFinite(weights[i]) || weights[i] < 0 || weights[i] > 1)
                    throw new IllegalStateException("Terrain admission range");
            }
            low = weights[0];
            middle = weights[1];
            high = weights[2];
            form = Fit.valueOf(json.get("terrainFit").getAsString());
        }

        public static Admission create(JsonObject json) {
            return new Admission(json);
        }

        public double fit(TerrainMap.BaseSample base) {
            Sample sample = base.land;
            double weight = sample.low * low + sample.middle * middle + sample.high * high;
            double coastal = Math.max(base.shore.rock, base.shore.cliff);
            double fit =
                    form == Fit.RUGGED
                            ? weight * Math.max(sample.rugged, coastal)
                            : form == Fit.EDGE
                                    ? weight * Math.max(sample.edge, coastal)
                                    : sample.fit(low, middle, high, form);
            return fit * base.shore.detail();
        }
    }

    /** The selector character a landform class reads as. */
    private static Character character(Landform landform) {
        switch (landform) {
            case BASIN:
                return Character.FLOOR;
            case PLAIN:
                return Character.PLAIN;
            case PLATEAU:
            case SCARP:
                return Character.PLATEAU;
            case MOUNTAIN:
                return Character.RIDGE;
            default:
                return Character.HILLS;
        }
    }

    public void classify(double x, double z, TerrainMap.BaseSample base, Sample out) {
        ContinentalMap.Sample ocean = base.ocean;
        out.low = out.middle = out.high = out.rugged = out.dunes = out.edge = out.waterfallSite = 0;
        out.strength = out.mountain = out.plateau = out.scarp = 0;
        out.landform = Landform.PLAIN;
        if (ocean.marine) {
            out.level = Level.OCEAN;
            out.character = Character.FLOOR;
            return;
        }
        // Elevation classes read the ground's landform position (the province band's, S4).
        double position = base.base.position();
        out.low = 1 - Rules.smooth((position - lowStart) / (lowEnd - lowStart));
        out.high = Rules.smooth((position - highStart) / (highEnd - highStart));
        out.middle = Math.max(0, 1 - out.low - out.high);
        out.level =
                out.low >= out.middle && out.low >= out.high
                        ? Level.LOW
                        : out.high > out.middle ? Level.HIGH : Level.MIDDLE;
        double broadSlope = Math.hypot(base.broadDx, base.broadDz);
        out.rugged = Rules.smooth((broadSlope - slopeStart) / (slopeEnd - slopeStart));
        // Analytic broad-intent gradient estimates a transition opportunity, not a measured
        // cliff/drop.
        double reach = Math.hypot(base.intentDx, base.intentDz) * edgeSpan;
        out.edge =
                Rules.smooth(reach)
                        * (1 - out.low)
                        * Rules.smooth(broadSlope * edgeSpan / edgeDrop);
        // Landform class: the ground model's own features first, then the province's type.
        double spine = base.base.spine(), cap = base.base.plateau();
        out.mountain = Rules.smooth((spine - mountainFrom) / (mountainFull - mountainFrom));
        out.plateau = cap;
        out.scarp = 4 * cap * (1 - cap);
        if (out.mountain >= .5) {
            out.landform = Landform.MOUNTAIN;
            out.strength = out.mountain;
        } else if (cap >= .5) {
            out.landform = Landform.PLATEAU;
            out.strength = cap;
        } else if (out.scarp >= .5) {
            out.landform = Landform.SCARP;
            out.strength = out.scarp;
        } else {
            Landform byType =
                    base.province.type == null ? null : typeLandform.get(base.province.type.id);
            if (byType == null) byType = Landform.PLAIN;
            // Off a cap or spine, a tableland's ground is dissected hills and a range's foothills.
            if (byType == Landform.PLATEAU) byType = Landform.HILLS;
            if (byType == Landform.MOUNTAIN) byType = Landform.FOOTHILLS;
            out.landform = byType;
            out.strength = .5 + .5 * Math.max(0, Math.min(1, base.province.intensity));
        }
        out.character = character(out.landform);
        double hotDry =
                Rules.smooth((base.climate.celsius - 25) / 3)
                        * Rules.smooth((.35 - base.climate.moisture) / .08);
        out.dunes =
                hotDry * (1 - out.rugged) * (out.low + out.middle) * (1 - out.mountain) * (1 - cap);
        if (out.dunes > .5) out.character = Character.DUNES;
        out.waterfallSite =
                out.edge
                        * Rules.smooth((base.climate.celsius - 20) / 8)
                        * Rules.smooth((base.climate.moisture - .55) / .25);
    }
}
