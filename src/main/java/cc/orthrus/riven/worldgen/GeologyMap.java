package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Riven's geological history (riven-geology.json), phases 1a-1c. A folded lower sequence over a
 * gneiss basement (gabbro and layered anorthosite in the deep zone) is broken by faults (throw and
 * slip, tapering at their tips) and thrusts (older beds lifted over younger), intruded by granite
 * plutons and dolerite dikes, truncated by an erosion surface and covered by a flat upper sequence
 * with flood basalt near rifts; magma chambers with basalt feeder pipes and kimberlite pipes cut it
 * all, then today's surface does. Every event is
 * a height surface or a plan-view object of the column, so a column's rock is a short stack, and
 * block and boundary queries share one bed-index function: its runs are exact. Faults and plutons
 * come from cells cached in bounded memos; each is a pure function of seed, definitions and cell.
 */
public final class GeologyMap {
    private static final Rules RULES = Rules.load("riven-geology.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    /** A rock unit with its vanilla block and the properties later phases read. */
    public static final class Unit {
        public final String name;
        public final TerrainBlock block;
        public final int color;
        public final double solubility, permeability, strength;

        Unit(String name, JsonObject j) {
            this.name = name;
            block = TerrainBlock.valueOf(j.get("block").getAsString());
            color = Integer.parseInt(j.get("color").getAsString().substring(1), 16);
            solubility = Rules.number(j, "solubility", 0, 1);
            permeability = Rules.number(j, "permeability", 0, 1);
            strength = Rules.number(j, "strength", 0, 1);
        }
    }

    private static final class Formation {
        final Unit unit, interbed;
        final double thickness, bed, share;
        final boolean volcanic;
        final long salt;

        Formation(JsonObject j, int index, long seed) {
            unit = unit(j.get("unit").getAsString());
            volcanic = j.has("volcanic") && j.get("volcanic").getAsBoolean();
            thickness = Rules.number(j, "thickness", 1, 400);
            if (j.has("interbed")) {
                interbed = unit(j.get("interbed").getAsString());
                bed = Rules.number(j, "bed", 1, 64);
                share = Rules.number(j, "share", 0, 1);
            } else {
                interbed = null;
                bed = share = 0;
            }
            salt = SeededNoise.mix(seed ^ (0x464f524d4154494fL + index * 0x9e3779b97f4a7c15L));
        }
    }

    private static final Map<String, Unit> UNITS = new LinkedHashMap<>();
    private static final Formation[] LOWER, UPPER;
    private static final Unit BASEMENT, BELT, OLD_GRANITE, PLUTON, DIKE;
    private static final double BELT_WAVELENGTH,
            BELT_STRETCH,
            BELT_THRESHOLD,
            OLD_WAVELENGTH,
            OLD_THRESHOLD;
    private static final double EROSION_WAVELENGTH,
            EROSION_MIN,
            EROSION_MAX,
            EROSION_COMPRESSION,
            EROSION_HEIGHT,
            EROSION_ABOVE,
            THINNING,
            COAST_FLOOR,
            COAST_BLEND;
    private static final double THICKNESS_WAVELENGTH,
            THICKNESS_MIN,
            THICKNESS_MAX,
            VARIATION,
            VARIATION_WAVELENGTH;
    private static final double FOLD_MIN,
            FOLD_PER_UPLIFT,
            FOLD_ACROSS,
            FOLD_STRETCH,
            FOLD_ORIENTATION,
            CHEVRON;
    private static final double FAULT_CELL,
            FAULT_PER_CELL,
            FAULT_REACH,
            FAULT_BOOST,
            TIP_TAPER,
            ANGLE_JITTER;
    private static final double[] HALF_LENGTH, THROW, RIFT_THROW, SLIP, SHEAR_SLIP;
    private static final double THRUST_WAVELENGTH,
            THRUST_THRESHOLD,
            THRUST_COMPRESSION,
            THRUST_DEPTH,
            THRUST_SHIFT,
            THRUST_RAMP;
    private static final double PLUTON_CELL, PLUTON_CHANCE, ARC_CHANCE, ARC_REACH, DOME, DIKE_REACH;
    private static final double[] PLUTON_RADIUS, ROOF_TOP, DIKES, DIKE_LENGTH, DIKE_WIDTH;
    private static final double UPPER_EROSION_WAVELENGTH,
            UPPER_EROSION_MIN,
            UPPER_EROSION_MAX,
            UPPER_EROSION_COMPRESSION,
            UPPER_WAVELENGTH,
            UPPER_MIN,
            UPPER_MAX;
    private static final double DEEP_LEVEL,
            DEEP_VARIATION,
            DEEP_WAVELENGTH,
            DEEP_RIFT,
            LAYERED_WAVELENGTH,
            LAYERED_THRESHOLD,
            LAYER_BLOCKS,
            LAYER_SHARE,
            LAVA_LEVEL,
            LAVA_RIFT;
    private static final Unit GABBRO, LAYER, BASALT, KIMBERLITE, MAGMA;
    private static final double RIFT_REACH,
            RIFT_EXTENSION,
            CHAMBER_CELL,
            RIFT_CHANCE,
            VOLCANIC_ARC_CHANCE,
            CHAMBER_CHANCE,
            KIMBERLITE_CELL,
            KIMBERLITE_CHANCE,
            KIMBERLITE_TOP,
            KIMBERLITE_FLARE;
    private static final double[] CHAMBER_Y,
            CHAMBER_RADIUS,
            CHAMBER_HEIGHT,
            FEEDER_RADIUS,
            KIMBERLITE_TOP_RADIUS,
            KIMBERLITE_ROOT_RADIUS;
    private static final int TINT_DEPTH, TINT_BAND;
    private static final Map<ClimateRegion, Map<Unit, TerrainBlock[]>> TINTS = new HashMap<>();

    /** Upper bound on the run boundaries one column contributes. */
    public static final int MAXIMUM_BOUNDARIES;

    /** Salt for the deep zone's layered beds. */
    private static final long LAYER_SALT = 0x4c41594552534131L;

    /** The upper sequence's nominal thickness, and each formation's top above a cycle's base. */
    private static final double CYCLE;

    private static final double[] CYCLE_TOPS;

    /** Cycles of the upper sequence a column can need above its top (up to the highest surface). */
    private static final int CYCLES;

    static {
        JsonObject j = RULES.json;
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("units").entrySet())
            UNITS.put(e.getKey(), new Unit(e.getKey(), e.getValue().getAsJsonObject()));
        long seed = Rules.seed(j, "variationSeed");
        LOWER = formations(j.getAsJsonArray("lowerSequence"), 0, seed);
        UPPER = formations(j.getAsJsonArray("upperSequence"), 64, seed);
        JsonObject basement = j.getAsJsonObject("basement"),
                belt = basement.getAsJsonObject("belt"),
                old = basement.getAsJsonObject("pluton");
        BASEMENT = unit(basement.get("unit").getAsString());
        BELT = unit(belt.get("unit").getAsString());
        BELT_WAVELENGTH = Rules.number(belt, "wavelengthBlocks", 8, 4096);
        BELT_STRETCH = Rules.number(belt, "stretch", 1, 32);
        BELT_THRESHOLD = Rules.number(belt, "threshold", -1, 1);
        OLD_GRANITE = unit(old.get("unit").getAsString());
        OLD_WAVELENGTH = Rules.number(old, "wavelengthBlocks", 64, 16384);
        OLD_THRESHOLD = Rules.number(old, "threshold", -1, 1);
        JsonObject datum = j.getAsJsonObject("datum");
        EROSION_WAVELENGTH = Rules.number(datum, "erosionWavelengthBlocks", 64, 65536);
        EROSION_MIN = Rules.number(datum, "minimumErosionBlocks", 0, 512);
        EROSION_MAX = Rules.number(datum, "maximumErosionBlocks", EROSION_MIN, 512);
        EROSION_COMPRESSION = Rules.number(datum, "erosionPerCompression", 0, 512);
        EROSION_HEIGHT = Rules.number(datum, "erosionPerBlockAboveY", 0, 8);
        EROSION_ABOVE = Rules.number(datum, "erosionAboveY", -512, 384);
        THINNING = Rules.number(datum, "thinningPerCompression", 0, 0.95);
        COAST_FLOOR = Rules.number(datum, "coastFloorY", -256, 384);
        COAST_BLEND = Rules.number(datum, "coastBlendBlocks", 1, 4096);
        JsonObject thickness = j.getAsJsonObject("thickness");
        THICKNESS_WAVELENGTH = Rules.number(thickness, "wavelengthBlocks", 64, 65536);
        THICKNESS_MIN = Rules.number(thickness, "minimumScale", 0, 4);
        THICKNESS_MAX = Rules.number(thickness, "maximumScale", THICKNESS_MIN, 4);
        VARIATION = Rules.number(thickness, "variation", 0, 0.9);
        VARIATION_WAVELENGTH = Rules.number(thickness, "variationWavelengthBlocks", 16, 16384);
        JsonObject folds = j.getAsJsonObject("folds");
        FOLD_MIN = Rules.number(folds, "minimumAmplitudeBlocks", 0, 256);
        FOLD_PER_UPLIFT = Rules.number(folds, "amplitudePerUplift", 0, 512);
        FOLD_ACROSS = Rules.number(folds, "acrossWavelengthBlocks", 16, 8192);
        FOLD_STRETCH = Rules.number(folds, "alongStretch", 1, 32);
        FOLD_ORIENTATION = Rules.number(folds, "orientationWavelengthBlocks", 256, 65536);
        CHEVRON = Rules.number(folds, "chevron", 0.2, 1);
        JsonObject faults = j.getAsJsonObject("faults");
        FAULT_CELL = Rules.number(faults, "cellBlocks", 256, 16384);
        FAULT_PER_CELL = Rules.number(faults, "perCell", 0, 4);
        FAULT_REACH = Rules.number(faults, "boundaryReachBlocks", 0, 65536);
        FAULT_BOOST = Rules.number(faults, "boundaryBoost", 1, 8);
        HALF_LENGTH = range(faults, "halfLengthBlocks", 16, FAULT_CELL);
        THROW = range(faults, "throwBlocks", 0, 400);
        RIFT_THROW = range(faults, "riftThrowBlocks", 0, 400);
        SLIP = range(faults, "slipBlocks", 0, 400);
        SHEAR_SLIP = range(faults, "shearSlipBlocks", 0, 400);
        TIP_TAPER = Rules.number(faults, "tipTaper", 0.01, 1);
        ANGLE_JITTER = Rules.number(faults, "angleJitter", 0, Math.PI);
        JsonObject thrusts = j.getAsJsonObject("thrusts");
        THRUST_WAVELENGTH = Rules.number(thrusts, "wavelengthBlocks", 64, 65536);
        THRUST_THRESHOLD = Rules.number(thrusts, "threshold", -1, 1);
        THRUST_COMPRESSION = Rules.number(thrusts, "minimumCompression", 0, 1);
        THRUST_DEPTH = Rules.number(thrusts, "depthBlocks", 0, 512);
        THRUST_SHIFT = Rules.number(thrusts, "shiftBlocks", 0, 512);
        THRUST_RAMP = Rules.number(thrusts, "rampBlocks", 1, 4096);
        JsonObject plutons = j.getAsJsonObject("plutons");
        PLUTON = OLD_GRANITE;
        DIKE = unit("dolerite");
        PLUTON_CELL = Rules.number(plutons, "cellBlocks", 256, 16384);
        PLUTON_CHANCE = Rules.number(plutons, "chance", 0, 1);
        ARC_CHANCE = Rules.number(plutons, "arcChance", 0, 1);
        ARC_REACH = Rules.number(plutons, "arcReachBlocks", 0, 65536);
        PLUTON_RADIUS = range(plutons, "radiusBlocks", 16, PLUTON_CELL / 3);
        ROOF_TOP = range(plutons, "roofTopY", -512, 384);
        DOME = Rules.number(plutons, "domeBlocks", 0, 512);
        DIKES = range(plutons, "dikes", 0, 16);
        DIKE_LENGTH = range(plutons, "dikeLengthBlocks", 16, PLUTON_CELL / 2);
        DIKE_WIDTH = range(plutons, "dikeWidthBlocks", 1, 16);
        DIKE_REACH = Rules.number(plutons, "dikeReachAboveRoofBlocks", 0, 1024);
        JsonObject deep = j.getAsJsonObject("deep");
        DEEP_LEVEL = Rules.number(deep, "levelY", -512, 0);
        DEEP_VARIATION = Rules.number(deep, "variationBlocks", 0, 256);
        DEEP_WAVELENGTH = Rules.number(deep, "wavelengthBlocks", 64, 65536);
        DEEP_RIFT = Rules.number(deep, "riseUnderRiftBlocks", 0, 512);
        LAYERED_WAVELENGTH = Rules.number(deep, "layeredWavelengthBlocks", 64, 65536);
        LAYERED_THRESHOLD = Rules.number(deep, "layeredThreshold", -1, 1);
        LAYER = unit(deep.get("layerUnit").getAsString());
        LAYER_BLOCKS = Rules.number(deep, "layerBlocks", 2, 64);
        LAYER_SHARE = Rules.number(deep, "layerShare", 0, 1);
        LAVA_LEVEL = Rules.number(deep, "lavaLevelY", -512, 384);
        LAVA_RIFT = Rules.number(deep, "lavaRiseUnderRiftBlocks", 0, 512);
        GABBRO = unit("gabbro");
        BASALT = unit("basalt");
        KIMBERLITE = unit("kimberlite");
        MAGMA = unit("magma");
        JsonObject volcanics = j.getAsJsonObject("volcanics");
        RIFT_REACH = Rules.number(volcanics, "riftReachBlocks", 1, 65536);
        RIFT_EXTENSION = Rules.number(volcanics, "fullRiftExtension", 0.01, 1);
        CHAMBER_CELL = Rules.number(volcanics, "chamberCellBlocks", 512, 16384);
        RIFT_CHANCE = Rules.number(volcanics, "riftChance", 0, 1);
        VOLCANIC_ARC_CHANCE = Rules.number(volcanics, "arcChance", 0, 1);
        CHAMBER_CHANCE = Rules.number(volcanics, "chance", 0, 1);
        CHAMBER_Y = range(volcanics, "chamberCentreY", -512, 384);
        CHAMBER_RADIUS = range(volcanics, "chamberRadiusBlocks", 8, CHAMBER_CELL / 4);
        CHAMBER_HEIGHT = range(volcanics, "chamberHalfHeightBlocks", 4, 256);
        FEEDER_RADIUS = range(volcanics, "feederRadiusBlocks", 1, 64);
        KIMBERLITE_CELL = Rules.number(volcanics, "kimberliteCellBlocks", 512, 16384);
        KIMBERLITE_CHANCE = Rules.number(volcanics, "kimberliteChance", 0, 1);
        KIMBERLITE_TOP_RADIUS =
                range(volcanics, "kimberliteTopRadiusBlocks", 2, KIMBERLITE_CELL / 8);
        KIMBERLITE_ROOT_RADIUS = range(volcanics, "kimberliteRootRadiusBlocks", 0.5, 64);
        KIMBERLITE_TOP = Rules.number(volcanics, "kimberliteTopY", -256, 384);
        KIMBERLITE_FLARE = Rules.number(volcanics, "kimberliteFlare", 0.2, 8);
        JsonObject upper = j.getAsJsonObject("upper");
        UPPER_EROSION_WAVELENGTH = Rules.number(upper, "erosionWavelengthBlocks", 64, 65536);
        UPPER_EROSION_MIN = Rules.number(upper, "minimumErosionBlocks", 0, 512);
        UPPER_EROSION_MAX = Rules.number(upper, "maximumErosionBlocks", UPPER_EROSION_MIN, 512);
        UPPER_EROSION_COMPRESSION = Rules.number(upper, "erosionPerCompression", 0, 512);
        UPPER_WAVELENGTH = Rules.number(upper, "thicknessWavelengthBlocks", 64, 65536);
        UPPER_MIN = Rules.number(upper, "minimumScale", 0, 4);
        UPPER_MAX = Rules.number(upper, "maximumScale", UPPER_MIN, 4);
        JsonObject tint = j.getAsJsonObject("tint");
        TINT_DEPTH = (int) Rules.number(tint, "depthBlocks", 0, 128);
        TINT_BAND = (int) Rules.number(tint, "bandBlocks", 1, 64);
        JsonObject palettes = tint.getAsJsonObject("palettes");
        for (Map.Entry<String, JsonElement> e : tint.getAsJsonObject("regions").entrySet()) {
            JsonObject palette = palettes.getAsJsonObject(e.getValue().getAsString());
            if (palette == null) throw new IllegalStateException("Tint palette " + e.getValue());
            Map<Unit, TerrainBlock[]> byUnit = new HashMap<>();
            for (Map.Entry<String, JsonElement> p : palette.entrySet()) {
                JsonArray a = p.getValue().getAsJsonArray();
                TerrainBlock[] blocks = new TerrainBlock[a.size()];
                for (int i = 0; i < blocks.length; i++)
                    blocks[i] = TerrainBlock.valueOf(a.get(i).getAsString());
                if (blocks.length == 0) throw new IllegalStateException("Empty tint " + p.getKey());
                byUnit.put(unit(p.getKey()), blocks);
            }
            TINTS.put(ClimateRegion.valueOf(e.getKey()), byUnit);
        }
        CYCLE_TOPS = new double[UPPER.length + 1];
        double cycle = 0;
        // Volcanic formations depend on the column's rift weight; the repeating cycles leave them
        // out.
        for (int k = UPPER.length - 1; k >= 0; k--) {
            if (!UPPER[k].volcanic) cycle += UPPER[k].thickness;
            CYCLE_TOPS[k] = cycle;
        }
        CYCLE = cycle;
        CYCLES = (int) Math.ceil(TerrainHeightReference.DEFAULT.highestSurface * 2.0 / CYCLE) + 2;
        // Two lower stacks (thrust sheet and footwall), the upper stack and its repeating cycles,
        // four event surfaces and the tint bands.
        MAXIMUM_BOUNDARIES =
                2 * stackBound(LOWER, THICKNESS_MAX * (1 + VARIATION))
                        + stackBound(UPPER, UPPER_MAX)
                        + CYCLES * stackBound(UPPER, 1)
                        + 12
                        + (int) Math.ceil(512 / LAYER_BLOCKS)
                        + TINT_DEPTH / TINT_BAND
                        + 2;
    }

    private static Formation[] formations(JsonArray a, int saltBase, long seed) {
        Formation[] f = new Formation[a.size()];
        for (int i = 0; i < f.length; i++)
            f[i] = new Formation(a.get(i).getAsJsonObject(), saltBase + i, seed);
        return f;
    }

    private static int stackBound(Formation[] stack, double scale) {
        int bound = stack.length + 1;
        for (Formation f : stack)
            if (f.interbed != null) bound += (int) Math.ceil(f.thickness * scale / f.bed) + 1;
        return bound;
    }

    private static double[] range(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(lo >= min && hi >= lo && hi <= max)) throw new IllegalStateException("Range: " + key);
        return new double[] {lo, hi};
    }

    private static Unit unit(String name) {
        Unit u = UNITS.get(name);
        if (u == null) throw new IllegalStateException("Unknown rock unit " + name);
        return u;
    }

    /** A unit by its definition name (for later phases that treat particular rock specially). */
    static Unit named(String name) {
        return unit(name);
    }

    /** Every unit, in definition order (for legends and slices). */
    public static Iterable<Unit> units() {
        return UNITS.values();
    }

    /** A steep fault: centre, unit direction, half length, throw and slip (each side moves half). */
    static final class Fault {
        final double x, z, c, s, half, dy, slip;

        Fault(double x, double z, double angle, double half, double dy, double slip) {
            this.x = x;
            this.z = z;
            c = StrictMath.cos(angle);
            this.s = StrictMath.sin(angle);
            this.half = half;
            this.dy = dy;
            this.slip = slip;
        }
    }

    /** A granite body with a domed roof, and the dolerite dikes it sends out. */
    static final class Pluton {
        final double x, z, rx, rz, c, s, roof;
        final double[] dikeAngle, dikeLength, dikeWidth;

        Pluton(double x, double z, double rx, double rz, double angle, double roof, int dikes) {
            this.x = x;
            this.z = z;
            this.rx = rx;
            this.rz = rz;
            c = StrictMath.cos(angle);
            s = StrictMath.sin(angle);
            this.roof = roof;
            dikeAngle = new double[dikes];
            dikeLength = new double[dikes];
            dikeWidth = new double[dikes];
        }
    }

    private final long seed;
    private final PlateMap plates;
    private final SeededNoise thicknessField,
            erosionField,
            beltField,
            oldField,
            orientationField,
            thrustField,
            upperErosionField,
            upperField,
            deepField,
            layeredField;
    private final SeededNoise[] variation = new SeededNoise[LOWER.length],
            folds = new SeededNoise[3];
    private final CellMemo<Fault[]> faultCells = new CellMemo<>(1024);
    private final CellMemo<Pluton[]> plutonCells = new CellMemo<>(512);
    private final CellMemo<double[]> chamberCells = new CellMemo<>(512),
            kimberliteCells = new CellMemo<>(256);

    GeologyMap(long worldSeed, PlateMap plates) {
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        this.plates = plates;
        thicknessField = noise(0x544849434b4e4553L);
        erosionField = noise(0x45524f53494f4e31L);
        beltField = noise(0x42454c5453434849L);
        oldField = noise(0x504c55544f4e5321L);
        orientationField = noise(0x4f5249454e54494eL);
        thrustField = noise(0x5448525553545331L);
        upperErosionField = noise(0x5550504552455231L);
        upperField = noise(0x5550504552544831L);
        deepField = noise(0x4445455053555231L);
        layeredField = noise(0x4c41594552454431L);
        for (int i = 0; i < variation.length; i++)
            variation[i] = new SeededNoise(SeededNoise.mix(seed + 0x5641524941544531L * (i + 1)));
        for (int i = 0; i < folds.length; i++)
            folds[i] = new SeededNoise(SeededNoise.mix(seed + 0x464f4c4453455431L * (i + 1)));
    }

    private SeededNoise noise(long salt) {
        return new SeededNoise(SeededNoise.mix(seed ^ salt));
    }

    /** One column's rock: its event surfaces and the formation surfaces of each stack. */
    public static final class Column {
        /** lower[k] is lower formation k's top at the column (after faults); lower[n] its base. */
        final double[] lower = new double[LOWER.length + 1];

        /** upper[k] is upper formation k's top; upper[m] is the unconformity. */
        final double[] upper = new double[UPPER.length + 1];

        /** Diagnostics and the fields later phases read. */
        public double datum,
                erosion,
                fold,
                scale,
                unconformity,
                thrustY,
                thrustShift,
                faultDistance,
                faultThrow,
                plutonRoof,
                dikeTop;

        /** Outside every pluton: blocks to the nearest pluton wall, and that pluton's rim roof. */
        public double aureole, aureoleRoof;

        /**
         * The nearest magma chamber in plan: blocks beyond its radius (0 above or below it, +inf
         * with none near), its centre Y and its half height. The subsurface climate reads them.
         */
        public double chamberGap, chamberCentreY, chamberHalfHeight;

        /** Phase 1c: the deep surface, the lava level and water table fields, chambers and pipes. */
        public double deepY,
                lavaY,
                waterTable,
                rift,
                chamberBottom,
                chamberTop,
                feederBottom,
                kimberliteY;

        public boolean pluton, dike, layered, feeder;
        Unit basement = BASEMENT;
        Map<Unit, TerrainBlock[]> tint;

        /** The unit at block Y (tints aside). */
        public Unit unit(long y) {
            return unit(y, true);
        }

        /**
         * The formation at block Y: as {@link #unit}, except that interbeds, and the layers in the
         * deep gabbro, read as the main unit of the formation they lie in.
         */
        public Unit formation(long y) {
            return unit(y, false);
        }

        private Unit unit(long y, boolean beds) {
            // Young bodies cut everything older: kimberlite, then magma and its feeder pipe.
            if (y >= kimberliteY) return KIMBERLITE;
            if (y > chamberBottom && y <= chamberTop) return MAGMA;
            if (feeder && y > feederBottom) return BASALT;
            if (y > upper[0]) return stackUnit(UPPER, CYCLE_TOPS, cycleBase(y), y, beds);
            if (y > upper[UPPER.length]) return stackUnit(UPPER, upper, 0, y, beds);
            if (dike && y <= dikeTop) return DIKE;
            if (pluton && y <= plutonRoof) return PLUTON;
            Unit u = stackUnit(LOWER, lower, y > thrustY ? thrustShift : 0, y, beds);
            if (u != null) return u;
            if (y > deepY) return basement;
            if (!layered || !beds) return GABBRO;
            return Sites.random(LAYER_SALT, (int) index(deepY, LAYER_BLOCKS, y)) < LAYER_SHARE
                    ? LAYER
                    : GABBRO;
        }

        /** The unit's own rock at Y, without the climate tint near the surface (boulders). */
        public TerrainBlock rock(long y) {
            return unit(y).block;
        }

        /** The block at Y: the unit's own, or its climate tint in horizontal bands near the surface. */
        public TerrainBlock block(long y, int surface) {
            Unit u = unit(y);
            if (tint != null && y > surface - TINT_DEPTH && y <= surface) {
                TerrainBlock[] palette = tint.get(u);
                if (palette != null)
                    return palette[
                            (int)
                                    Math.floorMod(
                                            Math.floorDiv(y, (long) TINT_BAND),
                                            (long) palette.length)];
            }
            return u.block;
        }

        /**
         * Writes every Y in (min, surface + 1] where {@link #block} can change between Y - 1 and
         * Y (a superset is fine); returns the new count. The array needs {@link #MAXIMUM_BOUNDARIES}
         * free entries.
         */
        public int boundaries(int min, int surface, int[] out, int n) {
            int hi = surface + 1;
            n = add(Math.ceil(kimberliteY), min, hi, out, n);
            n = add(Math.floor(chamberBottom) + 1, min, hi, out, n);
            n = add(Math.floor(chamberTop) + 1, min, hi, out, n);
            if (feeder) n = add(Math.floor(feederBottom) + 1, min, hi, out, n);
            n = add(Math.floor(deepY) + 1, min, hi, out, n);
            if (layered)
                for (long m = 1; ; m++) {
                    long y = plane(deepY, LAYER_BLOCKS, m);
                    if (y <= min) break;
                    n = add(y, min, hi, out, n);
                }
            double unconformity = upper[UPPER.length];
            n = add(Math.floor(unconformity) + 1, min, hi, out, n);
            n = stackBoundaries(UPPER, upper, 0, min, hi, out, n);
            // Above its top the upper sequence repeats in nominal cycles (cyclic basin fill).
            for (int c = 0; c < CYCLES; c++) {
                double base = upper[0] + c * CYCLE;
                if (base >= hi) break;
                n = stackBoundaries(UPPER, CYCLE_TOPS, base, min, hi, out, n);
            }
            if (dike) n = add(Math.floor(dikeTop) + 1, min, hi, out, n);
            if (pluton) n = add(Math.floor(plutonRoof) + 1, min, hi, out, n);
            int below = (int) Math.min(hi, Math.floor(unconformity) + 1);
            if (thrustY < unconformity) {
                n = add(Math.floor(thrustY) + 1, min, hi, out, n);
                n = stackBoundaries(LOWER, lower, thrustShift, min, below, out, n);
            }
            n = stackBoundaries(LOWER, lower, 0, min, below, out, n);
            if (tint != null && TINT_DEPTH > 0) {
                int lo = surface - TINT_DEPTH;
                n = add(lo + 1, min, hi, out, n);
                for (long y = Math.floorDiv(lo, (long) TINT_BAND) * TINT_BAND + TINT_BAND;
                        y <= hi;
                        y += TINT_BAND) n = add(y, min, hi, out, n);
            }
            return n;
        }

        /** The base of the upper-sequence cycle that holds Y, above the sequence's top. */
        private double cycleBase(long y) {
            long c = Math.min(CYCLES - 1, (long) Math.floor((y - upper[0]) / CYCLE));
            double base = upper[0] + c * CYCLE;
            // Agree with the boundary planes: Y belongs to the cycle whose range holds it.
            while (c > 0 && y <= base) base = upper[0] + --c * CYCLE;
            while (c < CYCLES - 1 && y > base + CYCLE) base = upper[0] + ++c * CYCLE;
            return base;
        }

        private static Unit stackUnit(
                Formation[] stack, double[] tops, double offset, long y, boolean beds) {
            for (int k = 0; k < stack.length; k++) {
                if (y <= tops[k + 1] + offset) continue;
                Formation f = stack[k];
                double top = tops[k] + offset;
                if (f.interbed == null || !beds || y > top) return f.unit;
                long bed = index(top, f.bed, y);
                return Sites.random(f.salt, (int) bed) < f.share ? f.interbed : f.unit;
            }
            return null;
        }

        private static int stackBoundaries(
                Formation[] stack,
                double[] tops,
                double offset,
                int min,
                int hi,
                int[] out,
                int n) {
            for (int k = 0; k <= stack.length; k++)
                n = add(Math.floor(tops[k] + offset) + 1, min, hi, out, n);
            for (int k = 0; k < stack.length; k++) {
                Formation f = stack[k];
                if (f.interbed == null) continue;
                double top = tops[k] + offset, bottom = tops[k + 1] + offset;
                long first = Math.max(1, index(top, f.bed, Math.min(hi, (long) Math.floor(top))));
                for (long m = first; top - m * f.bed > bottom; m++) {
                    long y = plane(top, f.bed, m);
                    if (y <= min) break;
                    n = add(y, min, hi, out, n);
                }
            }
            return n;
        }

        private static int add(double y, int min, int hi, int[] out, int n) {
            if (y > min && y <= hi) out[n++] = (int) y;
            return n;
        }
    }

    /** The bed index of block Y below a surface at top, beds spacing thick (0 is the top bed). */
    static long index(double top, double spacing, long y) {
        return (long) Math.floor((top - y) / spacing);
    }

    /** The lowest Y whose bed index is below m: where bed m - 1 begins, counted upward. */
    static long plane(double top, double spacing, long m) {
        long y = (long) Math.floor(top - m * spacing) + 1;
        // Agree with index() exactly at floating-point edges.
        while (index(top, spacing, y - 1) < m) y--;
        while (index(top, spacing, y) >= m) y++;
        return y;
    }

    /** Fills the column's rock from earlier, smooth facts of the sample and its event cells. */
    public void column(double x, double z, TerrainMap.Sample s, Column out) {
        double compression = Math.max(0, Math.min(1, s.uplift));
        // Rifts follow the smooth extension of the blended plate velocities, never a plate switch.
        double rift = Rules.smooth(plates.extension(x, z) / RIFT_EXTENSION);
        out.rift = rift;
        // Land and sea floor bases do not meet at the coast: blend to one level across it.
        double inland = s.ocean.marine ? 0 : Rules.smooth(s.ocean.coastCoordinate / COAST_BLEND);
        double base = COAST_FLOOR + (s.baseHeight - COAST_FLOOR) * inland;

        // Faults move each side half their throw and slip, tapering at the tips.
        double dy = 0, sx = 0, sz = 0;
        out.faultDistance = Double.POSITIVE_INFINITY;
        out.faultThrow = 0;
        int fx = (int) Math.floor(x / FAULT_CELL), fz = (int) Math.floor(z / FAULT_CELL);
        for (int cz = fz - 1; cz <= fz + 1; cz++)
            for (int cx = fx - 1; cx <= fx + 1; cx++)
                for (Fault f : faults(cx, cz)) {
                    double px = x - f.x, pz = z - f.z;
                    double along = px * f.c + pz * f.s, across = -px * f.s + pz * f.c;
                    if (Math.abs(along) >= f.half) continue;
                    double taper = Rules.smooth((f.half - Math.abs(along)) / (TIP_TAPER * f.half));
                    double side = across >= 0 ? 0.5 : -0.5;
                    dy += side * f.dy * taper;
                    sx += side * f.slip * taper * f.c;
                    sz += side * f.slip * taper * f.s;
                    if (Math.abs(across) < out.faultDistance) {
                        out.faultDistance = Math.abs(across);
                        out.faultThrow = f.dy * taper;
                    }
                }
        // The lower sequence's fields read the slipped position; the datum takes the throw.
        double lx = x - sx, lz = z - sz;
        double e = erosionField.sample(lx / EROSION_WAVELENGTH, lz / EROSION_WAVELENGTH);
        out.erosion =
                EROSION_MIN
                        + (EROSION_MAX - EROSION_MIN) * Rules.smooth((e + 0.7) / 1.4)
                        + EROSION_COMPRESSION * compression
                        + EROSION_HEIGHT * Math.max(0, base - EROSION_ABOVE);
        out.fold = (FOLD_MIN + FOLD_PER_UPLIFT * compression) * foldPattern(lx, lz);
        out.datum = base + out.erosion + out.fold + dy;
        double t = thicknessField.sample(lx / THICKNESS_WAVELENGTH, lz / THICKNESS_WAVELENGTH);
        out.scale =
                (THICKNESS_MIN + (THICKNESS_MAX - THICKNESS_MIN) * Rules.smooth((t + 0.6) / 1.2))
                        * (1 - THINNING * compression);
        double top = out.datum;
        for (int k = 0; k < LOWER.length; k++) {
            out.lower[k] = top;
            double v = variation[k].sample(lx / VARIATION_WAVELENGTH, lz / VARIATION_WAVELENGTH);
            top -= LOWER[k].thickness * out.scale * (1 + VARIATION * v);
        }
        out.lower[LOWER.length] = top;

        // A thrust sheet lifts the sequence above a surface that rises to the sheet's edge.
        double sheet =
                Rules.smooth(
                                (thrustField.sample(x / THRUST_WAVELENGTH, z / THRUST_WAVELENGTH)
                                                - THRUST_THRESHOLD)
                                        / 0.3)
                        * Rules.smooth((compression - THRUST_COMPRESSION) / 0.2);
        out.thrustShift = THRUST_SHIFT;
        out.thrustY =
                sheet > 0
                        ? out.datum - THRUST_DEPTH + (1 - sheet) * THRUST_RAMP
                        : Double.POSITIVE_INFINITY;

        // The flat upper sequence rests on the unconformity, which truncates everything below.
        double ue =
                upperErosionField.sample(
                        x / UPPER_EROSION_WAVELENGTH, z / UPPER_EROSION_WAVELENGTH);
        double upperTop =
                base
                        + UPPER_EROSION_MIN
                        + (UPPER_EROSION_MAX - UPPER_EROSION_MIN) * Rules.smooth((ue + 0.7) / 1.4)
                        + UPPER_EROSION_COMPRESSION * compression;
        double us = upperField.sample(x / UPPER_WAVELENGTH, z / UPPER_WAVELENGTH);
        double upperScale = UPPER_MIN + (UPPER_MAX - UPPER_MIN) * Rules.smooth((us + 0.2) / 1.0);
        double thick = 0;
        for (Formation f : UPPER) thick += f.thickness * upperScale * (f.volcanic ? rift : 1);
        // The erosion surface cannot lie above the lower sequence it cut: where that top is lower,
        // the upper sequence rests on it directly and builds upward, its top formation continuing.
        double unconformity = Math.min(upperTop - thick, out.datum);
        top = unconformity;
        out.upper[UPPER.length] = top;
        for (int k = UPPER.length - 1; k >= 0; k--) {
            top += UPPER[k].thickness * upperScale * (UPPER[k].volcanic ? rift : 1);
            out.upper[k] = top;
        }
        out.unconformity = unconformity;

        // Plutons and their dikes cut the lower sequence and stop at the unconformity.
        out.pluton = out.dike = false;
        out.plutonRoof = out.dikeTop = Double.NEGATIVE_INFINITY;
        out.aureole = Double.POSITIVE_INFINITY;
        out.aureoleRoof = Double.NEGATIVE_INFINITY;
        int px = (int) Math.floor(x / PLUTON_CELL), pz = (int) Math.floor(z / PLUTON_CELL);
        for (int cz = pz - 1; cz <= pz + 1; cz++)
            for (int cx = px - 1; cx <= px + 1; cx++)
                for (Pluton p : plutons(cx, cz)) {
                    double ox = x - p.x, oz = z - p.z;
                    double u = (ox * p.c + oz * p.s) / p.rx, v = (-ox * p.s + oz * p.c) / p.rz;
                    double r2 = u * u + v * v;
                    if (r2 < 1) {
                        out.pluton = true;
                        out.plutonRoof = Math.max(out.plutonRoof, p.roof - DOME * r2);
                    } else {
                        double wall = (Math.sqrt(r2) - 1) * Math.min(p.rx, p.rz);
                        if (wall < out.aureole) {
                            out.aureole = wall;
                            out.aureoleRoof = p.roof - DOME;
                        }
                    }
                    double radius = Math.max(p.rx, p.rz), distance = Math.sqrt(ox * ox + oz * oz);
                    for (int i = 0; i < p.dikeAngle.length; i++) {
                        double c = StrictMath.cos(p.dikeAngle[i]),
                                sn = StrictMath.sin(p.dikeAngle[i]);
                        double along = ox * c + oz * sn, across = Math.abs(-ox * sn + oz * c);
                        if (along < radius * 0.6
                                || along > radius + p.dikeLength[i]
                                || across > p.dikeWidth[i] / 2) continue;
                        out.dike = true;
                        out.dikeTop =
                                Math.max(
                                        out.dikeTop,
                                        p.roof
                                                + DIKE_REACH
                                                        * (1
                                                                - distance
                                                                        / (radius
                                                                                + p.dikeLength[
                                                                                        i])));
                    }
                }
        out.plutonRoof = Math.min(out.plutonRoof, out.unconformity);
        out.aureoleRoof = Math.min(out.aureoleRoof, out.unconformity);
        if (out.pluton) out.aureole = Double.POSITIVE_INFINITY;
        out.dikeTop = Math.min(out.dikeTop, out.unconformity);
        if (out.thrustY >= out.unconformity) out.thrustY = Double.POSITIVE_INFINITY;

        // The deep zone, its layered intrusions and the lava level rise under rifts.
        out.deepY =
                DEEP_LEVEL
                        + DEEP_VARIATION
                                * deepField.sample(x / DEEP_WAVELENGTH, z / DEEP_WAVELENGTH)
                        + DEEP_RIFT * rift;
        out.layered =
                layeredField.sample(x / LAYERED_WAVELENGTH, z / LAYERED_WAVELENGTH)
                        > LAYERED_THRESHOLD;
        out.lavaY = LAVA_LEVEL + LAVA_RIFT * rift;
        out.waterTable = s.groundwaterHead;

        // Magma chambers with feeder pipes, then kimberlite pipes, cut everything.
        out.chamberBottom = out.chamberTop = Double.NEGATIVE_INFINITY;
        out.chamberGap = Double.POSITIVE_INFINITY;
        out.chamberCentreY = out.chamberHalfHeight = 0;
        out.feeder = false;
        out.feederBottom = Double.POSITIVE_INFINITY;
        int vx = (int) Math.floor(x / CHAMBER_CELL), vz = (int) Math.floor(z / CHAMBER_CELL);
        for (int cz = vz - 1; cz <= vz + 1; cz++)
            for (int cx = vx - 1; cx <= vx + 1; cx++) {
                double[] c = chamber(cx, cz);
                if (c == null) continue;
                double ox = x - c[0], oz = z - c[1], r2 = (ox * ox + oz * oz) / (c[3] * c[3]);
                double gap = Math.max(0, Math.sqrt(ox * ox + oz * oz) - c[3]);
                if (gap < out.chamberGap) {
                    out.chamberGap = gap;
                    out.chamberCentreY = c[2];
                    out.chamberHalfHeight = c[4];
                }
                if (r2 < 1) {
                    double half = c[4] * Math.sqrt(1 - r2);
                    out.chamberBottom = c[2] - half;
                    out.chamberTop = c[2] + half;
                }
                if (ox * ox + oz * oz < c[5] * c[5]) {
                    out.feeder = true;
                    out.feederBottom = Math.min(out.feederBottom, c[2]);
                }
            }
        out.kimberliteY = Double.POSITIVE_INFINITY;
        int kx = (int) Math.floor(x / KIMBERLITE_CELL), kz = (int) Math.floor(z / KIMBERLITE_CELL);
        double floor = TerrainHeightReference.DEFAULT.minimumY;
        for (int cz = kz - 1; cz <= kz + 1; cz++)
            for (int cx = kx - 1; cx <= kx + 1; cx++) {
                double[] k = kimberlite(cx, cz);
                if (k == null) continue;
                double d = Math.hypot(x - k[0], z - k[1]);
                if (d >= k[2]) continue;
                // The radius grows from root to top as a power: the Y where it reaches d.
                double share =
                        d <= k[3]
                                ? 0
                                : StrictMath.pow((d - k[3]) / (k[2] - k[3]), 1 / KIMBERLITE_FLARE);
                out.kimberliteY =
                        Math.min(out.kimberliteY, floor + share * (KIMBERLITE_TOP - floor));
            }

        double b =
                beltField.sample(
                        (x * 0.866 + z * 0.5) / BELT_WAVELENGTH,
                        (-x * 0.5 + z * 0.866) / (BELT_WAVELENGTH * BELT_STRETCH));
        double o = oldField.sample(x / OLD_WAVELENGTH, z / OLD_WAVELENGTH);
        out.basement = o > OLD_THRESHOLD ? OLD_GRANITE : b > BELT_THRESHOLD ? BELT : BASEMENT;
        out.tint = s.climate.region == null ? null : TINTS.get(s.climate.region);
    }

    /** A fault cell's faults, denser and stronger near plate boundaries. Pure per cell. */
    private Fault[] faults(int cx, int cz) {
        return faultCells.get(
                cx,
                cz,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x4641554c54533131L, cx, cz);
                    double centreX = (cx + 0.5) * FAULT_CELL, centreZ = (cz + 0.5) * FAULT_CELL;
                    PlateMap.Sample plate = plates.sample((int) centreX, (int) centreZ);
                    double near =
                            1 - Rules.smooth(plate.boundaryDistance / Math.max(1, FAULT_REACH));
                    double mean = FAULT_PER_CELL * (1 + (FAULT_BOOST - 1) * near);
                    int count = (int) mean + (unit(key, 1) < mean - (int) mean ? 1 : 0);
                    boolean rift = plate.boundary == PlateMap.Boundary.DIVERGENT && near > 0.2;
                    boolean shear = plate.boundary == PlateMap.Boundary.SHEAR && near > 0.2;
                    double baseAngle = unit(key, 2) * Math.PI;
                    List<Fault> list = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        long k = SeededNoise.mix(key + (i + 1) * 0x9e3779b97f4a7c15L);
                        double angle = baseAngle + (unit(k, 3) - 0.5) * 2 * ANGLE_JITTER;
                        double half = lerp(HALF_LENGTH, unit(k, 4));
                        double dy =
                                lerp(rift ? RIFT_THROW : THROW, unit(k, 5))
                                        * (unit(k, 6) < 0.5 ? -1 : 1);
                        double slip =
                                lerp(shear ? SHEAR_SLIP : SLIP, unit(k, 7))
                                        * (unit(k, 8) < 0.5 ? -1 : 1);
                        list.add(
                                new Fault(
                                        (cx + unit(k, 9)) * FAULT_CELL,
                                        (cz + unit(k, 10)) * FAULT_CELL,
                                        angle,
                                        half,
                                        dy,
                                        slip));
                    }
                    return list.toArray(new Fault[0]);
                });
    }

    /** A pluton cell's granite bodies, likelier near converging margins. Pure per cell. */
    private Pluton[] plutons(int cx, int cz) {
        return plutonCells.get(
                cx,
                cz,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x504c55544f4e5331L, cx, cz);
                    double centreX = (cx + 0.5) * PLUTON_CELL, centreZ = (cz + 0.5) * PLUTON_CELL;
                    PlateMap.Sample plate = plates.sample((int) centreX, (int) centreZ);
                    boolean arc =
                            plate.boundary == PlateMap.Boundary.CONVERGENT
                                    && plate.boundaryDistance < ARC_REACH;
                    if (unit(key, 1) >= (arc ? ARC_CHANCE : PLUTON_CHANCE)) return new Pluton[0];
                    double r = lerp(PLUTON_RADIUS, unit(key, 2));
                    int dikes = (int) Math.round(lerp(DIKES, unit(key, 3)));
                    Pluton p =
                            new Pluton(
                                    (cx + 0.2 + 0.6 * unit(key, 4)) * PLUTON_CELL,
                                    (cz + 0.2 + 0.6 * unit(key, 5)) * PLUTON_CELL,
                                    r,
                                    r * (0.6 + 0.4 * unit(key, 6)),
                                    unit(key, 7) * Math.PI,
                                    lerp(ROOF_TOP, unit(key, 8)),
                                    dikes);
                    for (int i = 0; i < dikes; i++) {
                        p.dikeAngle[i] = unit(key, 20 + i) * Math.PI * 2;
                        p.dikeLength[i] = lerp(DIKE_LENGTH, unit(key, 40 + i));
                        p.dikeWidth[i] = lerp(DIKE_WIDTH, unit(key, 60 + i));
                    }
                    return new Pluton[] {p};
                });
    }

    /** A chamber cell's magma chamber {x, z, centreY, radius, halfHeight, feederRadius}, or null. */
    private double[] chamber(int cx, int cz) {
        return chamberCells.get(
                cx,
                cz,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x4d41474d41434831L, cx, cz);
                    double centreX = (cx + 0.5) * CHAMBER_CELL, centreZ = (cz + 0.5) * CHAMBER_CELL;
                    PlateMap.Sample plate = plates.sample((int) centreX, (int) centreZ);
                    boolean near = plate.boundaryDistance < RIFT_REACH;
                    double chance =
                            near && plate.boundary == PlateMap.Boundary.DIVERGENT
                                    ? RIFT_CHANCE
                                    : near && plate.boundary == PlateMap.Boundary.CONVERGENT
                                            ? VOLCANIC_ARC_CHANCE
                                            : CHAMBER_CHANCE;
                    if (unit(key, 1) >= chance) return null;
                    return new double[] {
                        (cx + 0.25 + 0.5 * unit(key, 2)) * CHAMBER_CELL,
                        (cz + 0.25 + 0.5 * unit(key, 3)) * CHAMBER_CELL,
                        lerp(CHAMBER_Y, unit(key, 4)),
                        lerp(CHAMBER_RADIUS, unit(key, 5)),
                        lerp(CHAMBER_HEIGHT, unit(key, 6)),
                        lerp(FEEDER_RADIUS, unit(key, 7))
                    };
                });
    }

    /** A kimberlite cell's pipe {x, z, topRadius, rootRadius}, or null. */
    private double[] kimberlite(int cx, int cz) {
        return kimberliteCells.get(
                cx,
                cz,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x4b494d4245524c31L, cx, cz);
                    if (unit(key, 1) >= KIMBERLITE_CHANCE) return null;
                    return new double[] {
                        (cx + 0.2 + 0.6 * unit(key, 2)) * KIMBERLITE_CELL,
                        (cz + 0.2 + 0.6 * unit(key, 3)) * KIMBERLITE_CELL,
                        lerp(KIMBERLITE_TOP_RADIUS, unit(key, 4)),
                        lerp(KIMBERLITE_ROOT_RADIUS, unit(key, 5))
                    };
                });
    }

    private static double unit(long key, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(key + parameter * 0x9e3779b97f4a7c15L));
    }

    private static double lerp(double[] range, double t) {
        return range[0] + (range[1] - range[0]) * t;
    }

    /**
     * Folds from three fixed orientations, blended by a smooth partition: coordinates stay linear
     * within each orientation, so belts curve where weights shift and never shear.
     */
    private double foldPattern(double x, double z) {
        double w0 = 1 + orientationField.sample(x / FOLD_ORIENTATION, z / FOLD_ORIENTATION),
                w1 = 1 + orientationField.sample(x / FOLD_ORIENTATION + 37.1, z / FOLD_ORIENTATION),
                w2 = 1 + orientationField.sample(x / FOLD_ORIENTATION, z / FOLD_ORIENTATION + 53.7);
        w0 *= w0 * w0;
        w1 *= w1 * w1;
        w2 *= w2 * w2;
        double sum = w0 + w1 + w2, value = 0;
        double[] weights = {w0 / sum, w1 / sum, w2 / sum};
        for (int i = 0; i < 3; i++) {
            if (weights[i] < 1e-3) continue;
            double angle = i * Math.PI / 3, c = StrictMath.cos(angle), sn = StrictMath.sin(angle);
            double across = (x * c + z * sn) / FOLD_ACROSS,
                    along = (-x * sn + z * c) / (FOLD_ACROSS * FOLD_STRETCH);
            double n =
                    folds[i].sample(across, along) * 0.75
                            + folds[i].sample(across * 2.1, along * 2.1) * 0.25;
            value += weights[i] * Math.signum(n) * StrictMath.pow(Math.abs(n), CHEVRON);
        }
        return value;
    }
}
