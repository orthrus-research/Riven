package cc.orthrus.riven.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The realms of the descent (riven-realms.json). In the crust, every block lies in a realm by its
 * rock and depth (skin, layered deep, broken basement, molten root) and takes a depth biome by
 * the first rule of its realm that holds; each depth biome also says what its caves hold. Below
 * the crust floor, horizontal realms run to the world floor, each with its own biome and rock.
 * Boundaries are open. Below each deep realm's top the rock above thins out over a dithered band,
 * and every top below the crust floor undulates. Blocks, realms and biomes are pure functions of
 * seed, definitions and coordinates.
 */
public final class RealmMap {
    public static final Rules RULES = Rules.load("riven-realms.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final List<Realm> REALMS = parse();

    /** The crust's realms by rock and depth (riven-caves.json), from the surface down. */
    public enum Crust {
        SKIN,
        LAYERED,
        BASEMENT,
        ROOT
    }

    /** What a depth biome's cave floors hold (riven-cave-decor.json gives the thresholds). */
    public enum Floor {
        NONE,
        LITTER,
        SAND,
        SALT,
        ICE,
        GRAVEL,
        ASH,
        MAGMA
    }

    /** What a depth biome's cave ceilings hold. */
    public enum Ceiling {
        NONE,
        ROOTS,
        COBWEBS
    }

    /** A depth biome's own formation material, besides calcite dripstone. */
    public enum Formation {
        NONE,
        ICE,
        SALT,
        BASALT
    }

    /** A depth biome of the crust and what its caves hold. */
    public static final class Depth {
        public final int index;
        public final String biome;
        public final Floor floor;
        public final Ceiling ceiling;
        public final boolean dripstone, life, mud, frozen, faultDripstone;
        public final Formation formation;

        Depth(int index, String biome, JsonObject j) {
            known(
                    j,
                    "floor",
                    "ceiling",
                    "dripstone",
                    "faultDripstone",
                    "formation",
                    "life",
                    "mud",
                    "frozen");
            this.index = index;
            this.biome = biome;
            floor = Floor.valueOf(text(j, "floor", "none"));
            ceiling = Ceiling.valueOf(text(j, "ceiling", "none"));
            dripstone = flag(j, "dripstone");
            life = flag(j, "life");
            mud = flag(j, "mud");
            frozen = flag(j, "frozen");
            faultDripstone = flag(j, "faultDripstone");
            formation = Formation.valueOf(text(j, "formation", "none"));
        }

        private static String text(JsonObject j, String key, String fallback) {
            return (j.has(key) ? j.get(key).getAsString() : fallback).toUpperCase(Locale.ROOT);
        }

        private static boolean flag(JsonObject j, String key) {
            return j.has(key) && j.get(key).getAsBoolean();
        }
    }

    /** A crust rule: its realm, conditions (absent ones always hold) and depth biome. */
    private static final class Rule {
        final Crust realm;
        final Depth depth;
        final boolean[] temperatures, moistures;
        final Set<GeologyMap.Unit> units;
        final double solubility, permeability, floodBand, aboveLava;
        final double[] celsius, humidity, air;
        final boolean conditional, climate, flooded;

        Rule(JsonObject j, Map<String, Depth> depths) {
            known(
                    j,
                    "realm",
                    "biome",
                    "temperature",
                    "moisture",
                    "units",
                    "minimumSolubility",
                    "minimumPermeability",
                    "floodBandBlocks",
                    "flooded",
                    "aboveLavaBlocks",
                    "celsius",
                    "humidity",
                    "air");
            realm = Crust.valueOf(j.get("realm").getAsString().toUpperCase(Locale.ROOT));
            depth = depths.get(j.get("biome").getAsString());
            if (depth == null)
                throw new IllegalStateException("Unknown depth biome: " + j.get("biome"));
            temperatures = bands(j, "temperature", TemperatureBand.values());
            moistures = bands(j, "moisture", MoistureBand.values());
            if (j.has("units")) {
                units = new HashSet<>();
                for (JsonElement e : j.getAsJsonArray("units"))
                    units.add(GeologyMap.named(e.getAsString()));
            } else units = null;
            solubility =
                    j.has("minimumSolubility") ? Rules.number(j, "minimumSolubility", 0, 1) : 0;
            permeability =
                    j.has("minimumPermeability") ? Rules.number(j, "minimumPermeability", 0, 1) : 0;
            floodBand = j.has("floodBandBlocks") ? Rules.number(j, "floodBandBlocks", 0, 512) : NaN;
            // In a flood cell's band (riven-caves.json fill), where its caves hold water.
            flooded = j.has("flooded") && j.get("flooded").getAsBoolean();
            aboveLava = j.has("aboveLavaBlocks") ? Rules.number(j, "aboveLavaBlocks", 0, 512) : NaN;
            // The subsurface climate at the block (riven-subsurface-climate.json), as ranges.
            celsius = range(j, "celsius");
            humidity = range(j, "humidity");
            air = range(j, "air");
            climate = celsius != null || humidity != null || air != null;
            conditional = j.entrySet().size() > 2;
        }

        private static <E extends Enum<E>> boolean[] bands(JsonObject j, String key, E[] values) {
            if (!j.has(key)) return null;
            boolean[] allowed = new boolean[values.length];
            for (JsonElement e : j.getAsJsonArray(key))
                allowed[Enum.valueOf(values[0].getDeclaringClass(), e.getAsString()).ordinal()] =
                        true;
            return allowed;
        }

        private static double[] range(JsonObject j, String key) {
            if (!j.has(key)) return null;
            com.google.gson.JsonArray a = j.getAsJsonArray(key);
            double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
            if (!(lo <= hi)) throw new IllegalStateException("Range: " + key);
            return new double[] {lo, hi};
        }

        private static boolean within(double[] range, double v) {
            return range == null || (v >= range[0] && v <= range[1]);
        }

        /** climate is the block's subsurface climate when the rule tests it, else null. */
        boolean holds(
                long y,
                GeologyMap.Unit unit,
                TerrainMap.Sample s,
                SubsurfaceClimate.Reading climate) {
            GeologyMap.Column g = s.geology;
            return (temperatures == null || temperatures[s.biome.temperatureCore])
                    && (moistures == null || moistures[s.biome.moistureCore])
                    && (units == null || units.contains(unit))
                    && unit.solubility >= solubility
                    && unit.permeability >= permeability
                    && (Double.isNaN(floodBand)
                            || (y <= g.waterTable && y > g.waterTable - floodBand))
                    && (!flooded || s.flood.holds(y))
                    && (Double.isNaN(aboveLava) || y <= g.lavaY + aboveLava)
                    && (!this.climate
                            || (within(celsius, climate.celsius)
                                    && within(humidity, climate.humidity)
                                    && within(air, climate.air)));
        }
    }

    /** A misspelt key would read as a condition that always holds, so every key must be known. */
    private static void known(JsonObject j, String... keys) {
        Set<String> allowed = new HashSet<>(java.util.Arrays.asList(keys));
        for (Map.Entry<String, JsonElement> e : j.entrySet())
            if (!allowed.contains(e.getKey()))
                throw new IllegalStateException(
                        "Unknown key in riven-realms.json crust: " + e.getKey());
    }

    private static final double NaN = Double.NaN;
    private static final GeologyMap.Unit MAGMA = GeologyMap.named("magma");
    private static final int SURFACE_BAND;
    private static final List<Depth> DEPTHS;
    private static final Rule[][] CRUST_RULES = new Rule[Crust.values().length][];

    static {
        JsonObject crust = RULES.json.getAsJsonObject("crust");
        SURFACE_BAND = (int) Rules.number(crust, "surfaceBandBlocks", 4, 64);
        Map<String, Depth> depths = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : crust.getAsJsonObject("biomes").entrySet())
            depths.put(
                    e.getKey(),
                    new Depth(depths.size(), e.getKey(), e.getValue().getAsJsonObject()));
        DEPTHS = Collections.unmodifiableList(new ArrayList<>(depths.values()));
        List<List<Rule>> byRealm = new ArrayList<>();
        for (int k = 0; k < Crust.values().length; k++) byRealm.add(new ArrayList<>());
        for (JsonElement e : crust.getAsJsonArray("rules")) {
            Rule rule = new Rule(e.getAsJsonObject(), depths);
            List<Rule> realm = byRealm.get(rule.realm.ordinal());
            if (!realm.isEmpty() && !realm.get(realm.size() - 1).conditional)
                throw new IllegalStateException("A crust rule follows " + rule.realm + "'s last");
            realm.add(rule);
        }
        for (Crust realm : Crust.values()) {
            List<Rule> rules = byRealm.get(realm.ordinal());
            if (rules.isEmpty() || rules.get(rules.size() - 1).conditional)
                throw new IllegalStateException("The last crust rule of " + realm + " must hold");
            CRUST_RULES[realm.ordinal()] = rules.toArray(new Rule[0]);
        }
        // Every depth biome is one of Riven's registered underground (void-class) biomes.
        for (String id : depths.keySet()) {
            RivenBiomeTable.Definition d = RivenBiomeTable.byId(id);
            if (d == null || !d.parent.equals("void"))
                throw new IllegalStateException("Depth biome is not an underground biome: " + id);
        }
    }

    public static final class Realm {
        public final String id, biome;
        public final int top;

        /** The realm's representative rock (the preview draws it), and its boundary band's rock. */
        public final TerrainBlock rock, transition;

        final Geology geology;

        Realm(JsonObject j) {
            id = j.get("id").getAsString();
            biome = j.get("biome").getAsString();
            top = j.get("top").getAsInt();
            rock = TerrainBlock.valueOf(j.get("rock").getAsString());
            transition =
                    j.has("transition")
                            ? TerrainBlock.valueOf(j.get("transition").getAsString())
                            : null;
            geology = j.has("geology") ? new Geology(j.getAsJsonObject("geology")) : null;
        }
    }

    /**
     * A realm's rock: zones by depth below its top (their boundaries warped), rock bodies within a
     * zone from domain-warped cells picked by weight, inclusions in a host rock (pods as small warped
     * cells, veins as thin noise sheets) and seams as thin noise sheets through every rock.
     */
    static final class Geology {
        final double[] zoneTo;
        final TerrainBlock[][] rocks;
        final double[][] weights;
        final double zoneWarp, zoneWavelength, body, bodyStretch, bodyWarp;
        final TerrainBlock[] inclusionHost, inclusionRock;
        final boolean[] veins;
        final double[] inclusionAmount, inclusionScale;
        final TerrainBlock[] seamRock, seamCore;
        final double[] seamScale, seamWidth, seamPresence, seamPresenceScale;

        Geology(JsonObject j) {
            zoneWarp = Rules.number(j, "zoneWarpBlocks", 0, 256);
            zoneWavelength = Rules.number(j, "zoneWavelengthBlocks", 16, 4096);
            body = Rules.number(j, "bodyBlocks", 4, 1024);
            bodyStretch = Rules.number(j, "bodyStretchY", 0.1, 4);
            bodyWarp = Rules.number(j, "bodyWarpBlocks", 0, 512);
            com.google.gson.JsonArray zones = j.getAsJsonArray("zones");
            zoneTo = new double[zones.size()];
            rocks = new TerrainBlock[zones.size()][];
            weights = new double[zones.size()][];
            for (int i = 0; i < zones.size(); i++) {
                JsonObject z = zones.get(i).getAsJsonObject();
                // The last zone runs to the realm's floor.
                boolean last = i == zones.size() - 1;
                if (last == z.has("toDepth"))
                    throw new IllegalStateException("Every realm zone but the last has a toDepth");
                zoneTo[i] = last ? Double.POSITIVE_INFINITY : Rules.number(z, "toDepth", 1, 4096);
                if (i > 0 && zoneTo[i] <= zoneTo[i - 1])
                    throw new IllegalStateException("Realm zones must deepen");
                JsonObject rs = z.getAsJsonObject("rocks");
                rocks[i] = new TerrainBlock[rs.size()];
                weights[i] = new double[rs.size()];
                int n = 0;
                for (Map.Entry<String, JsonElement> e : rs.entrySet()) {
                    rocks[i][n] = TerrainBlock.valueOf(e.getKey());
                    weights[i][n++] = e.getValue().getAsDouble();
                }
            }
            com.google.gson.JsonArray inc =
                    j.has("inclusions")
                            ? j.getAsJsonArray("inclusions")
                            : new com.google.gson.JsonArray();
            inclusionHost = new TerrainBlock[inc.size()];
            inclusionRock = new TerrainBlock[inc.size()];
            veins = new boolean[inc.size()];
            inclusionAmount = new double[inc.size()];
            inclusionScale = new double[inc.size()];
            for (int i = 0; i < inc.size(); i++) {
                JsonObject o = inc.get(i).getAsJsonObject();
                inclusionHost[i] = TerrainBlock.valueOf(o.get("host").getAsString());
                inclusionRock[i] = TerrainBlock.valueOf(o.get("rock").getAsString());
                String form = o.get("form").getAsString();
                if (!form.equals("pods") && !form.equals("veins"))
                    throw new IllegalStateException("Unknown inclusion form: " + form);
                veins[i] = form.equals("veins");
                // Pods take a share of the host's cells; veins a half-width about the sheet.
                inclusionAmount[i] =
                        veins[i]
                                ? Rules.number(o, "width", 0, 0.5)
                                : Rules.number(o, "share", 0, 1);
                inclusionScale[i] = Rules.number(o, "scaleBlocks", 2, 512);
            }
            com.google.gson.JsonArray se =
                    j.has("seams") ? j.getAsJsonArray("seams") : new com.google.gson.JsonArray();
            seamRock = new TerrainBlock[se.size()];
            seamCore = new TerrainBlock[se.size()];
            seamScale = new double[se.size()];
            seamWidth = new double[se.size()];
            seamPresence = new double[se.size()];
            seamPresenceScale = new double[se.size()];
            for (int i = 0; i < se.size(); i++) {
                JsonObject o = se.get(i).getAsJsonObject();
                seamRock[i] = TerrainBlock.valueOf(o.get("rock").getAsString());
                seamCore[i] =
                        o.has("core") ? TerrainBlock.valueOf(o.get("core").getAsString()) : null;
                seamScale[i] = Rules.number(o, "scaleBlocks", 4, 4096);
                seamWidth[i] = Rules.number(o, "width", 0, 0.5);
                seamPresence[i] = Rules.number(o, "presence", 0, 1);
                seamPresenceScale[i] = Rules.number(o, "presenceBlocks", 8, 8192);
            }
        }
    }

    private final long seed;
    private final int crustFloor, worldFloor, band, amplitude;
    private final double wavelength, cell;
    private final SeededNoise boundary;

    public RealmMap(long worldSeed, TerrainHeightReference heights) {
        JsonObject t = RULES.json.getAsJsonObject("transition");
        seed = worldSeed ^ Rules.seed(RULES.json, "seed");
        crustFloor = heights.minimumY;
        worldFloor = heights.worldMinimumY;
        band = (int) Rules.number(t, "band", 1, 256);
        amplitude = (int) Rules.number(t, "boundaryAmplitude", 0, 256);
        wavelength = Rules.number(t, "boundaryWavelength", 16, 8192);
        cell = Rules.number(t, "ditherCell", 1, 64);
        boundary = new SeededNoise(SeededNoise.mix(seed + 1));
        if (REALMS.get(0).top != crustFloor)
            throw new IllegalStateException("The first realm starts at the crust floor");
        for (int k = 1; k < REALMS.size(); k++)
            if (REALMS.get(k - 1).top - REALMS.get(k).top <= 2 * (amplitude + band))
                throw new IllegalStateException(
                        "Realm " + REALMS.get(k).id + " is too thin for its transitions");
        if (REALMS.get(REALMS.size() - 1).top - worldFloor <= amplitude + band)
            throw new IllegalStateException("The last realm is too thin above the world floor");
    }

    private static List<Realm> parse() {
        List<Realm> realms = new ArrayList<>();
        for (JsonElement e : RULES.json.getAsJsonArray("realms"))
            realms.add(new Realm(e.getAsJsonObject()));
        if (realms.isEmpty()) throw new IllegalStateException("riven-realms.json lists no realms");
        return Collections.unmodifiableList(realms);
    }

    /** The realms below the crust floor, top first. */
    public static List<Realm> realms() {
        return REALMS;
    }

    /** The crust's depth biomes, in definition order (Depth.index). */
    public static List<Depth> depths() {
        return DEPTHS;
    }

    /** Cells within this many blocks below their column's ground report the column's biome. */
    public static int surfaceBand() {
        return SURFACE_BAND;
    }

    /** The crust realm of block Y, whose rock is unit, in a sampled column. */
    public static Crust crust(long y, GeologyMap.Unit unit, TerrainMap.Sample s) {
        if (y <= s.geology.deepY || unit == MAGMA) return Crust.ROOT;
        if (s.blockY() - y < CaveMap.skinDepth() && !CaveMap.wet(s)) return Crust.SKIN;
        return CaveMap.basement(unit) ? Crust.BASEMENT : Crust.LAYERED;
    }

    /**
     * The depth biome block Y reports: that of its 4-block cell row's centre, as cubes report
     * biomes per 4x4x4 cell, so what a cave holds changes where its reported biome does.
     */
    public static Depth cell(long y, TerrainMap.Sample s) {
        return depth(Math.floorDiv(y, 4L) * 4 + 2, s);
    }

    /**
     * The depth biome of block Y in a sampled column: the first rule of its realm that holds. Rules
     * read the formation, not the bed, so a biome does not flicker across thin interbeds.
     */
    public static Depth depth(long y, TerrainMap.Sample s) {
        GeologyMap.Unit unit = s.geology.formation(y);
        SubsurfaceClimate.Reading climate = null;
        for (Rule rule : CRUST_RULES[crust(y, unit, s).ordinal()]) {
            if (rule.climate && climate == null)
                climate = SubsurfaceClimate.at(y, s, -1, new SubsurfaceClimate.Reading());
            if (rule.holds(y, unit, s, climate)) return rule.depth;
        }
        throw new IllegalStateException("No crust rule holds"); // the last rule always holds
    }

    public int band() {
        return band;
    }

    /** The tops of every realm in one column; the first is the crust floor, the rest undulate. */
    public void tops(int x, int z, double[] out) {
        out[0] = crustFloor;
        double offset = amplitude * boundary.sample(x / wavelength, z / wavelength);
        for (int k = 1; k < REALMS.size(); k++) out[k] = REALMS.get(k).top + offset;
    }

    /** Realm k's top at a column. */
    private double top(int k, double x, double z) {
        return k == 0
                ? crustFloor
                : REALMS.get(k).top + amplitude * boundary.sample(x / wavelength, z / wavelength);
    }

    /** The realm index at height y for a column's tops, or -1 in the crust. */
    public static int realm(double y, double[] tops) {
        for (int k = tops.length - 1; k >= 0; k--) if (y < tops[k]) return k;
        return -1;
    }

    /**
     * The block at (x, y, z) below the crust floor, for a column's tops. crustBlock is the crust's
     * own block at its floor in this column, which the first realm's transition mixes with.
     */
    public TerrainBlock block(int x, int y, int z, double[] tops, TerrainBlock crustBlock) {
        if (y < worldFloor || y >= crustFloor) return TerrainBlock.AIR;
        if (y == worldFloor) return TerrainBlock.BEDROCK;
        int k = realm(y, tops);
        double depth = tops[k] - y;
        if (depth <= band) {
            // The rock above fades from certain at the top to absent at the band's bottom; where
            // the realm has a boundary rock, it rims the rocks above and below in between.
            double t = depth / band, d = dither(x, y, z);
            if (d > t) return k == 0 ? crustBlock : rock(k - 1, x, y, z);
            TerrainBlock transition = REALMS.get(k).transition;
            if (transition != null && d >= t * t * t) return transition;
        }
        return rock(k, x, y, z);
    }

    private static final long SALT_ZONE = 0x5a4f4e45L,
            SALT_BODY = 0x424f4459L,
            SALT_WARP = 0x57415250L,
            SALT_INCLUSION = 0x494e434cL,
            SALT_SEAM = 0x5345414dL;

    /** Realm k's own rock at a block. */
    private TerrainBlock rock(int k, int x, int y, int z) {
        Realm realm = REALMS.get(k);
        Geology g = realm.geology;
        if (g == null) return realm.rock;
        long salt = (k + 1) * 0x632be59bd9b4e019L;
        // Seams cross every rock of the realm, where present.
        for (int i = 0; i < g.seamRock.length; i++) {
            double p = g.seamPresenceScale[i];
            if (noise(salt + SALT_SEAM + 2 * i + 1, x / p, y / p, z / p) < 1 - g.seamPresence[i])
                continue;
            double q = g.seamScale[i];
            double d = Math.abs(sheet(salt + SALT_SEAM + 2 * i, x / q, y / q, z / q));
            if (d < g.seamWidth[i])
                return g.seamCore[i] != null && d < g.seamWidth[i] / 3
                        ? g.seamCore[i]
                        : g.seamRock[i];
        }
        // The rock body holding the block: a cell whose walls smooth noise pushes about.
        double size = g.body, tall = g.body * g.bodyStretch, f = size / 2;
        double fx = x / f, fy = y / f, fz = z / f;
        int cx = (int) Math.floor((x + warp(salt, 0, g.bodyWarp, fx, fy, fz)) / size);
        int cy = (int) Math.floor((y + warp(salt, 1, g.bodyWarp, fx, fy, fz)) / tall);
        int cz = (int) Math.floor((z + warp(salt, 2, g.bodyWarp, fx, fy, fz)) / size);
        // The body's zone, by its centre's depth below the realm's top, so zones meet along body
        // walls; its rock by the zone's weights, from one uniform draw per body, so weights are
        // volume shares.
        double xc = (cx + 0.5) * size, yc = (cy + 0.5) * tall, zc = (cz + 0.5) * size;
        double w = g.zoneWavelength;
        double zd =
                top(k, xc, zc)
                        - yc
                        + g.zoneWarp * (2 * noise(salt + SALT_ZONE, xc / w, yc / w, zc / w) - 1);
        int zone = 0;
        while (zone < g.zoneTo.length - 1 && zd >= g.zoneTo[zone]) zone++;
        double draw = corner(salt + SALT_BODY + zone, cx, cy, cz);
        TerrainBlock rock = g.rocks[zone][Math.max(0, Rules.choose(g.weights[zone], draw))];
        for (int i = 0; i < g.inclusionHost.length; i++) {
            if (g.inclusionHost[i] != rock) continue;
            double s = g.inclusionScale[i];
            long is = salt + SALT_INCLUSION + i;
            boolean in =
                    g.veins[i]
                            ? Math.abs(sheet(is, x / s, y / s, z / s)) < g.inclusionAmount[i]
                            : pod(is, x, y, z, s) < g.inclusionAmount[i];
            if (in) return g.inclusionRock[i];
        }
        return rock;
    }

    /** A displacement of up to amount blocks along one axis, from smooth noise. */
    private double warp(long salt, int axis, double amount, double fx, double fy, double fz) {
        return amount * (2 * noise(salt + SALT_WARP + axis, fx, fy, fz) - 1);
    }

    /** A uniform draw shared by every block of one pod cell, size blocks wide, its walls warped. */
    private double pod(long salt, int x, int y, int z, double size) {
        double f = size / 2, fx = x / f, fy = y / f, fz = z / f, a = size / 2;
        return corner(
                salt,
                (int) Math.floor((x + warp(salt, 0, a, fx, fy, fz)) / size),
                (int) Math.floor((y + warp(salt, 1, a, fx, fy, fz)) / size),
                (int) Math.floor((z + warp(salt, 2, a, fx, fy, fz)) / size));
    }

    /**
     * Smooth 3D gradient noise about 0, its lattice one unit apart. Its zero set is a sheet that
     * wanders freely, where value noise's level sets keep to the lattice's axes.
     */
    private double sheet(long salt, double fx, double fy, double fz) {
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
        double rx = fx - ix, ry = fy - iy, rz = fz - iz;
        double u = fade(rx), v = fade(ry), w = fade(rz);
        double c00 =
                lerp(
                        slope(salt, ix, iy, iz, rx, ry, rz),
                        slope(salt, ix + 1, iy, iz, rx - 1, ry, rz),
                        u);
        double c10 =
                lerp(
                        slope(salt, ix, iy + 1, iz, rx, ry - 1, rz),
                        slope(salt, ix + 1, iy + 1, iz, rx - 1, ry - 1, rz),
                        u);
        double c01 =
                lerp(
                        slope(salt, ix, iy, iz + 1, rx, ry, rz - 1),
                        slope(salt, ix + 1, iy, iz + 1, rx - 1, ry, rz - 1),
                        u);
        double c11 =
                lerp(
                        slope(salt, ix, iy + 1, iz + 1, rx, ry - 1, rz - 1),
                        slope(salt, ix + 1, iy + 1, iz + 1, rx - 1, ry - 1, rz - 1),
                        u);
        return lerp(lerp(c00, c10, v), lerp(c01, c11, v), w);
    }

    /**
     * A lattice corner's gradient dotted with an offset. Each component is drawn from [-1, 1): a
     * fixed set of directions (Perlin's 12 cube edges) lets both ends of a lattice edge lack a
     * component along it, which zeroes the noise along the whole edge and threads straight rods
     * through every sheet.
     */
    private double slope(long salt, int x, int y, int z, double dx, double dy, double dz) {
        long h = hash(salt, x, y, z);
        double gx = (h >>> 43) * 0x1.0p-20 - 1,
                gy = (h >>> 22 & 0x1fffff) * 0x1.0p-20 - 1,
                gz = (h >>> 1 & 0x1fffff) * 0x1.0p-20 - 1;
        return gx * dx + gy * dy + gz * dz;
    }

    /** Smooth 3D value noise in [0, 1), its lattice one unit apart. */
    private double noise(long salt, double fx, double fy, double fz) {
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
        double u = fade(fx - ix), v = fade(fy - iy), w = fade(fz - iz);
        double c00 = lerp(corner(salt, ix, iy, iz), corner(salt, ix + 1, iy, iz), u);
        double c10 = lerp(corner(salt, ix, iy + 1, iz), corner(salt, ix + 1, iy + 1, iz), u);
        double c01 = lerp(corner(salt, ix, iy, iz + 1), corner(salt, ix + 1, iy, iz + 1), u);
        double c11 =
                lerp(corner(salt, ix, iy + 1, iz + 1), corner(salt, ix + 1, iy + 1, iz + 1), u);
        return lerp(lerp(c00, c10, v), lerp(c01, c11, v), w);
    }

    private double corner(long salt, int x, int y, int z) {
        return SeededNoise.unit(hash(salt, x, y, z));
    }

    private long hash(long salt, int x, int y, int z) {
        return SeededNoise.mix(
                seed
                        ^ SeededNoise.mix(salt)
                        ^ SeededNoise.mix(x * 0x9e3779b97f4a7c15L)
                        ^ SeededNoise.mix(y * 0xc2b2ae3d27d4eb4fL)
                        ^ SeededNoise.mix(z * 0xd1b54a32d192ed03L));
    }

    /** A value in [0, 1): clustered value noise with some per-block grain, so transitions look broken up. */
    private double dither(int x, int y, int z) {
        double fx = x / cell, fy = y / cell, fz = z / cell;
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
        double u = fade(fx - ix), v = fade(fy - iy), w = fade(fz - iz);
        double c00 = lerp(lattice(ix, iy, iz), lattice(ix + 1, iy, iz), u);
        double c10 = lerp(lattice(ix, iy + 1, iz), lattice(ix + 1, iy + 1, iz), u);
        double c01 = lerp(lattice(ix, iy, iz + 1), lattice(ix + 1, iy, iz + 1), u);
        double c11 = lerp(lattice(ix, iy + 1, iz + 1), lattice(ix + 1, iy + 1, iz + 1), u);
        double smooth = lerp(lerp(c00, c10, v), lerp(c01, c11, v), w);
        return 0.7 * smooth + 0.3 * lattice(x * 7919, y * 7919, z * 7919);
    }

    private double lattice(int x, int y, int z) {
        return SeededNoise.unit(
                SeededNoise.mix(
                        seed
                                ^ SeededNoise.mix(x * 0x9e3779b97f4a7c15L)
                                ^ SeededNoise.mix(y * 0xc2b2ae3d27d4eb4fL)
                                ^ SeededNoise.mix(z * 0xd1b54a32d192ed03L)));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
