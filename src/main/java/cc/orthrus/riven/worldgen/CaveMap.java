package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Riven's caves (riven-caves.json): voids rooted in the geology, with a character per realm. In
 * the layered deep: caverns scaled by solubility and strength, tunnels, karst tube levels and
 * vadose canyons, bedding-plane halls along formation boundaries, collapse domes and salt halls.
 * In the broken basement: sparse caverns, narrow tunnels, joint mazes and vein galleries where dikes
 * weathered out. In the molten root: pillar halls, lava rivers, lined geodes and air rooms over magma
 * chambers. Everywhere: fault fissures, rift chasms and lava tubes; the skin near the surface stays
 * sparse. Voids below the lava level hold lava; flood cells (FloodMap) fill their band with water up
 * to one flat level behind rock seals, and underground lakes (Lens) take their own chamber.
 * Entrances (sinkholes, rift chasm openings) are
 * part of every column sample; the 3D caves are carved only into native chunk columns from noise on
 * a lattice cached per chunk. Every void is a pure function of seed, definitions and coordinates.
 */
public final class CaveMap {
    static final Rules RULES = Rules.load("riven-caves.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    public static final String DECOR_FINGERPRINT = CaveDecor.FINGERPRINT;

    /** Cave families, as diagnostics report them (index 0 is solid rock). */
    public static final String[] FAMILIES = {
        "rock",
        "cavern",
        "pillar hall",
        "tunnel",
        "karst tube",
        "vadose canyon",
        "fissure",
        "collapse dome",
        "salt hall",
        "lava tube",
        "chamber room",
        "bedding plane",
        "joint maze",
        "vein gallery",
        "lava river",
        "geode"
    };

    private static final int CAVERN = 1,
            PILLAR_HALL = 2,
            TUNNEL = 3,
            KARST = 4,
            CANYON = 5,
            FISSURE = 6,
            DOME = 7,
            SALT_HALL = 8,
            LAVA_TUBE = 9,
            CHAMBER = 10,
            BEDDING = 11,
            JOINT = 12,
            VEIN = 13,
            LAVA_RIVER = 14,
            GEODE = 15;
    private static final int ROOF, WET_ROOF, FLOOR_MARGIN, CAP, LINING_CAP, H, V, CHUNK_CACHE;
    private static final double SKIN_DEPTH, SKIN_PENALTY, SKIN_NARROWING;
    private static final Set<GeologyMap.Unit> BASEMENT = new HashSet<>();
    private static final double CAVERN_WAVELENGTH,
            CAVERN_SQUASH,
            DETAIL_WAVELENGTH,
            DETAIL_WEIGHT,
            LAYERED_THRESHOLD,
            BASEMENT_THRESHOLD,
            SOLUBLE_BONUS,
            WEAK_BONUS,
            STRONG_PENALTY;
    private static final double HALL_WAVELENGTH_DEEP,
            HALL_SQUASH,
            HALL_THRESHOLD_DEEP,
            HALL_DETAIL_WAVELENGTH,
            HALL_DETAIL_WEIGHT,
            PILLAR_WAVELENGTH,
            PILLAR_THRESHOLD;
    private static final double TUNNEL_WAVELENGTH,
            TUNNEL_SQUASH,
            TUNNEL_WIDTH,
            WEAK_WIDENING,
            BASEMENT_NARROWING;
    private static final int KARST_LEVELS;
    private static final double KARST_FIRST,
            KARST_SPACING,
            KARST_WANDER,
            KARST_WAVELENGTH,
            TUBE_WIDTH,
            TUBE_HALF,
            CANYON_WIDTH,
            CANYON_HEIGHT,
            CANYON_THRESHOLD,
            KARST_SOLUBILITY;
    private static final double BEDDING_WAVELENGTH, BEDDING_THRESHOLD;
    private static final double[] BEDDING_HALF;
    private static final double[] JOINT_SPACING, JOINT_SEGMENT, JOINT_HEIGHT;
    private static final double JOINT_WIDTH,
            JOINT_VARIATION,
            JOINT_ORIENTATION,
            JOINT_SECOND,
            JOINT_MASK,
            JOINT_OPEN,
            JOINT_LEVEL,
            JOINT_ROOM,
            JOINT_ROOM_CHANCE,
            JOINT_DOMAIN,
            JOINT_DOMAIN_JITTER,
            JOINT_MASK_WAVELENGTH,
            JOINT_SHAFT,
            VEIN_MASK;
    private static final double FISSURE_THROW,
            FISSURE_WIDTH,
            CHASM_THROW,
            CHASM_WAVELENGTH,
            FISSURE_BOTTOM;
    private static final double[] CHASM_WIDTH;
    private static final double DOME_CELL, DOME_CHANCE, DOME_STRENGTH, DOME_FLOOR, DOME_SQUASH;
    private static final double[] DOME_RADIUS, DOME_Y;
    private static final double SALT_WAVELENGTH, SALT_THRESHOLD, TUBE_WAVELENGTH, LAVA_TUBE_WIDTH;
    private static final int TUBE_CLEARANCE;
    private static final double RIVER_WAVELENGTH,
            RIVER_WIDTH,
            RIVER_BELOW,
            RIVER_ABOVE,
            RIVER_WIDTH_VARIATION,
            RIVER_CEILING_VARIATION;
    private static final double GEODE_CELL, GEODE_CHANCE, GEODE_LINING;
    private static final double[] GEODE_RADIUS, GEODE_Y;
    private static final Set<GeologyMap.Unit> GEODE_HOSTS = new HashSet<>();
    private static final TerrainBlock[] GEODE_LININGS;
    private static final double CHAMBER_AIR;
    private static final double SINKHOLE_CELL,
            SINKHOLE_CHANCE,
            CHASM_MASK_WAVELENGTH,
            CHASM_MASK_THRESHOLD;
    private static final double[] SINKHOLE_RADIUS, SINKHOLE_DEPTH, CHASM_DEPTH;
    private static final GeologyMap.Unit MAGMA, BASALT, EVAPORITE, DOLERITE;

    static {
        JsonObject j = RULES.json;
        ROOF = integer(j, "roofBlocks", 1, 64);
        WET_ROOF = integer(j, "wetRoofBlocks", ROOF, 128);
        FLOOR_MARGIN = integer(j, "floorMarginBlocks", 1, 64);
        CAP = integer(j, "maximumIntervals", 4, 256);
        LINING_CAP = integer(j, "maximumLinings", 1, 128);
        JsonObject lattice = j.getAsJsonObject("lattice");
        H = integer(lattice, "horizontalBlocks", 1, 16);
        V = integer(lattice, "verticalBlocks", 1, 16);
        CHUNK_CACHE = integer(lattice, "chunkCache", 16, 4096);
        if (16 % H != 0) throw new IllegalStateException("Cave lattice must divide a chunk");
        JsonObject realms = j.getAsJsonObject("realms");
        SKIN_DEPTH = Rules.number(realms, "skinDepthBlocks", 0, 256);
        SKIN_PENALTY = Rules.number(realms, "skinCavernPenalty", 0, 1);
        SKIN_NARROWING = Rules.number(realms, "skinTunnelNarrowing", 0, 1);
        for (com.google.gson.JsonElement e : realms.getAsJsonArray("basementUnits"))
            BASEMENT.add(GeologyMap.named(e.getAsString()));
        JsonObject caverns = j.getAsJsonObject("caverns");
        CAVERN_WAVELENGTH = Rules.number(caverns, "wavelengthBlocks", 8, 4096);
        CAVERN_SQUASH = Rules.number(caverns, "verticalSquash", 0.25, 8);
        DETAIL_WAVELENGTH = Rules.number(caverns, "detailWavelengthBlocks", 4, 4096);
        DETAIL_WEIGHT = Rules.number(caverns, "detailWeight", 0, 1);
        LAYERED_THRESHOLD = Rules.number(caverns, "layeredThreshold", -1, 1);
        BASEMENT_THRESHOLD = Rules.number(caverns, "basementThreshold", -1, 1);
        SOLUBLE_BONUS = Rules.number(caverns, "solubleBonus", 0, 1);
        WEAK_BONUS = Rules.number(caverns, "weakBonus", 0, 1);
        STRONG_PENALTY = Rules.number(caverns, "strongPenalty", 0, 1);
        JsonObject halls = j.getAsJsonObject("pillarHalls");
        HALL_WAVELENGTH_DEEP = Rules.number(halls, "wavelengthBlocks", 8, 4096);
        HALL_SQUASH = Rules.number(halls, "verticalSquash", 0.25, 8);
        HALL_THRESHOLD_DEEP = Rules.number(halls, "threshold", -1, 1);
        HALL_DETAIL_WAVELENGTH = Rules.number(halls, "detailWavelengthBlocks", 4, 4096);
        HALL_DETAIL_WEIGHT = Rules.number(halls, "detailWeight", 0, 1);
        PILLAR_WAVELENGTH = Rules.number(halls, "pillarWavelengthBlocks", 2, 1024);
        PILLAR_THRESHOLD = Rules.number(halls, "pillarThreshold", -1, 1);
        JsonObject tunnels = j.getAsJsonObject("tunnels");
        TUNNEL_WAVELENGTH = Rules.number(tunnels, "wavelengthBlocks", 8, 4096);
        TUNNEL_SQUASH = Rules.number(tunnels, "verticalSquash", 0.25, 8);
        TUNNEL_WIDTH = Rules.number(tunnels, "width", 0, 1);
        WEAK_WIDENING = Rules.number(tunnels, "weakWidening", 0, 4);
        BASEMENT_NARROWING = Rules.number(tunnels, "basementNarrowing", 0, 1);
        JsonObject karst = j.getAsJsonObject("karst");
        KARST_LEVELS = integer(karst, "levels", 0, 12);
        KARST_FIRST = Rules.number(karst, "firstLevelAboveWaterTableBlocks", -256, 256);
        KARST_SPACING = Rules.number(karst, "levelSpacingBlocks", 4, 256);
        KARST_WANDER = Rules.number(karst, "levelWanderBlocks", 0, 64);
        KARST_WAVELENGTH = Rules.number(karst, "wavelengthBlocks", 8, 4096);
        TUBE_WIDTH = Rules.number(karst, "tubeWidth", 0, 1);
        TUBE_HALF = Rules.number(karst, "tubeHalfHeightBlocks", 0.5, 32);
        CANYON_WIDTH = Rules.number(karst, "canyonWidth", 0, 1);
        CANYON_HEIGHT = Rules.number(karst, "canyonHeightBlocks", 0, 128);
        CANYON_THRESHOLD = Rules.number(karst, "canyonThreshold", -1, 1);
        KARST_SOLUBILITY = Rules.number(karst, "minimumSolubility", 0, 1);
        JsonObject bedding = j.getAsJsonObject("beddingPlanes");
        BEDDING_WAVELENGTH = Rules.number(bedding, "maskWavelengthBlocks", 4, 4096);
        BEDDING_THRESHOLD = Rules.number(bedding, "threshold", -1, 1);
        BEDDING_HALF = range(bedding, "halfHeightBlocks", 0.5, 16);
        JsonObject joints = j.getAsJsonObject("jointMazes");
        JOINT_SPACING = range(joints, "spacingBlocks", 3, 256);
        JOINT_WIDTH = Rules.number(joints, "widthBlocks", 0.5, 16);
        JOINT_VARIATION = Rules.number(joints, "widthVariation", 0, 1);
        JOINT_ORIENTATION = Rules.number(joints, "orientationWavelengthBlocks", 64, 65536);
        JOINT_SECOND = Rules.number(joints, "secondSetAngle", 0, Math.PI);
        JOINT_MASK = Rules.number(joints, "maskThreshold", -1, 1);
        JOINT_SEGMENT = range(joints, "segmentBlocks", 2, 512);
        JOINT_OPEN = Rules.number(joints, "openShare", 0, 1);
        JOINT_LEVEL = Rules.number(joints, "levelBlocks", 4, 256);
        JOINT_HEIGHT = range(joints, "slotHeightBlocks", 1, JOINT_LEVEL);
        JOINT_ROOM = Rules.number(joints, "roomRadiusBlocks", 0, 32);
        JOINT_ROOM_CHANCE = Rules.number(joints, "roomChance", 0, 1);
        JOINT_DOMAIN = Rules.number(joints, "domainCellBlocks", 128, 8192);
        JOINT_DOMAIN_JITTER = Rules.number(joints, "domainJitter", 0, 0.9);
        JOINT_MASK_WAVELENGTH = Rules.number(joints, "maskWavelengthBlocks", 16, 4096);
        JOINT_SHAFT = Rules.number(joints, "shaftChance", 0, 1);
        // A diagonal slot stays one passage only while it keeps the cells within 1/sqrt(2) of its
        // plane, so the narrowest slot must be wider than that.
        if (JOINT_WIDTH / 2 * (1 - JOINT_VARIATION) <= Math.sqrt(0.5))
            throw new IllegalStateException("jointMazes: slots too narrow to stay connected");
        VEIN_MASK = Rules.number(j.getAsJsonObject("veinGalleries"), "maskThreshold", -1, 1);
        JsonObject fissures = j.getAsJsonObject("fissures");
        FISSURE_THROW = Rules.number(fissures, "minimumThrowBlocks", 0, 512);
        FISSURE_WIDTH = Rules.number(fissures, "widthBlocks", 0, 32);
        CHASM_THROW = Rules.number(fissures, "chasmThrowBlocks", FISSURE_THROW, 512);
        CHASM_WIDTH = range(fissures, "chasmWidthBlocks", 0, 64);
        CHASM_WAVELENGTH = Rules.number(fissures, "chasmWidthWavelengthBlocks", 8, 4096);
        FISSURE_BOTTOM = Rules.number(fissures, "bottomY", -512, 384);
        JsonObject domes = j.getAsJsonObject("domes");
        DOME_CELL = Rules.number(domes, "cellBlocks", 32, 4096);
        DOME_CHANCE = Rules.number(domes, "chance", 0, 1);
        DOME_RADIUS = range(domes, "radiusBlocks", 2, DOME_CELL / 2);
        DOME_Y = range(domes, "centreY", -512, 384);
        DOME_STRENGTH = Rules.number(domes, "maximumStrength", 0, 1);
        DOME_FLOOR = Rules.number(domes, "flatFloor", 0, 1);
        DOME_SQUASH = Rules.number(domes, "squash", 0.1, 2);
        JsonObject salt = j.getAsJsonObject("saltHalls");
        SALT_WAVELENGTH = Rules.number(salt, "wavelengthBlocks", 4, 4096);
        SALT_THRESHOLD = Rules.number(salt, "threshold", -1, 1);
        JsonObject lavaTubes = j.getAsJsonObject("lavaTubes");
        TUBE_WAVELENGTH = Rules.number(lavaTubes, "wavelengthBlocks", 8, 4096);
        LAVA_TUBE_WIDTH = Rules.number(lavaTubes, "width", 0, 1);
        TUBE_CLEARANCE = integer(lavaTubes, "clearanceBlocks", 0, 16);
        JsonObject rivers = j.getAsJsonObject("lavaRivers");
        RIVER_WAVELENGTH = Rules.number(rivers, "wavelengthBlocks", 8, 4096);
        RIVER_WIDTH = Rules.number(rivers, "width", 0, 1);
        RIVER_BELOW = Rules.number(rivers, "belowLavaBlocks", 0, 64);
        RIVER_ABOVE = Rules.number(rivers, "aboveLavaBlocks", 0, 64);
        RIVER_WIDTH_VARIATION = Rules.number(rivers, "widthVariation", 0, 1);
        RIVER_CEILING_VARIATION = Rules.number(rivers, "ceilingVariation", 0, 1);
        JsonObject geodes = j.getAsJsonObject("geodes");
        GEODE_CELL = Rules.number(geodes, "cellBlocks", 16, 4096);
        GEODE_CHANCE = Rules.number(geodes, "chance", 0, 1);
        GEODE_RADIUS = range(geodes, "radiusBlocks", 1, GEODE_CELL / 3);
        GEODE_Y = range(geodes, "centreY", -512, 384);
        GEODE_LINING = Rules.number(geodes, "liningBlocks", 0, 8);
        for (com.google.gson.JsonElement e : geodes.getAsJsonArray("hosts"))
            GEODE_HOSTS.add(GeologyMap.named(e.getAsString()));
        JsonArray linings = geodes.getAsJsonArray("linings");
        GEODE_LININGS = new TerrainBlock[linings.size()];
        for (int i = 0; i < GEODE_LININGS.length; i++)
            GEODE_LININGS[i] = TerrainBlock.valueOf(linings.get(i).getAsString());
        CHAMBER_AIR = Rules.number(j.getAsJsonObject("chambers"), "airShare", 0, 1);
        JsonObject entrances = j.getAsJsonObject("entrances");
        SINKHOLE_CELL = Rules.number(entrances, "sinkholeCellBlocks", 32, 4096);
        SINKHOLE_CHANCE = Rules.number(entrances, "sinkholeChance", 0, 1);
        SINKHOLE_RADIUS = range(entrances, "sinkholeRadiusBlocks", 1, SINKHOLE_CELL / 4);
        SINKHOLE_DEPTH = range(entrances, "sinkholeDepthBlocks", 1, 512);
        CHASM_MASK_WAVELENGTH = Rules.number(entrances, "chasmMaskWavelengthBlocks", 8, 65536);
        CHASM_MASK_THRESHOLD = Rules.number(entrances, "chasmMaskThreshold", -1, 1);
        CHASM_DEPTH = range(entrances, "chasmDepthBlocks", 1, 512);
        MAGMA = GeologyMap.named("magma");
        BASALT = GeologyMap.named("basalt");
        EVAPORITE = GeologyMap.named("evaporite");
        DOLERITE = GeologyMap.named("dolerite");
    }

    private static int integer(JsonObject j, String key, int min, int max) {
        double n = Rules.number(j, key, min, max);
        if (n != (int) n) throw new IllegalStateException(key);
        return (int) n;
    }

    private static double[] range(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(lo >= min && hi >= lo && hi <= max)) throw new IllegalStateException("Range: " + key);
        return new double[] {lo, hi};
    }

    /** Upper bound on the run boundaries a column's voids and linings contribute. */
    public static int maximumBoundaries() {
        return 2 * CAP + 2 * LINING_CAP + 2 * CaveDecor.CAP + 2 * POOL_CAP + 4;
    }

    /** Pools a column can lie in (CavePools): at most one per void, few voids at pool height. */
    private static final int POOL_CAP = 2, UNSTABLE_CAP = 2;

    /** One column's voids and linings: disjoint half-open intervals [lo, hi), ascending. */
    public static final class Voids {
        final int[] poolLo = new int[POOL_CAP], poolHi = new int[POOL_CAP];
        int pools;
        final int[] unstableLo = new int[UNSTABLE_CAP], unstableHi = new int[UNSTABLE_CAP];
        int unstables;
        final int[] lo = new int[CAP], hi = new int[CAP];
        final int[] liningLo = new int[LINING_CAP], liningHi = new int[LINING_CAP];
        final TerrainBlock[] liningBlock = new TerrainBlock[LINING_CAP];
        final int[] decorLo = new int[CaveDecor.CAP], decorHi = new int[CaveDecor.CAP];
        final TerrainBlock[] decorBlock = new TerrainBlock[CaveDecor.CAP];
        int count, linings, decorations;

        public void clear() {
            count = linings = decorations = pools = unstables = 0;
        }

        /** Adds pool water over [from, to) (CavePools); beyond the cap a pool is dropped. */
        void addPool(int from, int to) {
            if (to <= from || pools == POOL_CAP) return;
            poolLo[pools] = from;
            poolHi[pools++] = to;
        }

        /**
         * Marks the rock over [from, to) unstable (CaveDecor); false where it is not (beyond the
         * cap a floor stays rock).
         */
        boolean addUnstable(int from, int to) {
            if (to <= from || unstables == UNSTABLE_CAP) return false;
            unstableLo[unstables] = from;
            unstableHi[unstables++] = to;
            return true;
        }

        /**
         * Whether the rock at Y is unstable: a thin floor over an open drop (riven-cave-decor.json
         * unstable), which the world writes as its host rock's unstable stone. The block at Y is
         * still the rock (or a decoration) for every other purpose.
         */
        public boolean unstable(long y) {
            for (int i = 0; i < unstables; i++)
                if (y >= unstableLo[i] && y < unstableHi[i]) return true;
            return false;
        }

        /** The number of unstable floors, each [unstableLo(i), unstableHi(i)). */
        public int unstables() {
            return unstables;
        }

        public int unstableLo(int i) {
            return unstableLo[i];
        }

        public int unstableHi(int i) {
            return unstableHi[i];
        }

        /** The decoration at Y (riven-cave-decor.json), which wins over voids and rock, or null. */
        public TerrainBlock decor(long y) {
            for (int i = 0; i < decorations; i++)
                if (y >= decorLo[i] && y < decorHi[i]) return decorBlock[i];
            return null;
        }

        /** Adds a decoration run; an earlier run wins where runs overlap. */
        void addDecor(int from, int to, TerrainBlock block) {
            if (to <= from || decorations == CaveDecor.CAP) return;
            decorLo[decorations] = from;
            decorHi[decorations] = to;
            decorBlock[decorations++] = block;
        }

        public int count() {
            return count;
        }

        /** The lowest void's bottom (for diagnostics). */
        public int lowest() {
            return count == 0 ? Integer.MAX_VALUE : lo[0];
        }

        /** Whether block Y is a void. */
        public boolean voids(long y) {
            for (int i = 0; i < count; i++) {
                if (y < lo[i]) return false;
                if (y < hi[i]) return true;
            }
            return false;
        }

        /** The lining block at Y (a geode's shell), or null. */
        public TerrainBlock lining(long y) {
            for (int i = 0; i < linings; i++)
                if (y >= liningLo[i] && y < liningHi[i]) return liningBlock[i];
            return null;
        }

        /** Adds [from, to), merging with what is there; beyond the cap, a new void is dropped. */
        void add(int from, int to) {
            if (to <= from) return;
            int i = 0;
            while (i < count && hi[i] < from) i++;
            if (i < count && lo[i] <= to) {
                int start = Math.min(lo[i], from), end = Math.max(hi[i], to), j = i;
                while (j + 1 < count && lo[j + 1] <= end) end = Math.max(end, hi[++j]);
                lo[i] = start;
                hi[i] = end;
                int removed = j - i;
                if (removed > 0) {
                    System.arraycopy(lo, j + 1, lo, i + 1, count - j - 1);
                    System.arraycopy(hi, j + 1, hi, i + 1, count - j - 1);
                    count -= removed;
                }
                return;
            }
            if (count == CAP) return;
            System.arraycopy(lo, i, lo, i + 1, count - i);
            System.arraycopy(hi, i, hi, i + 1, count - i);
            lo[i] = from;
            hi[i] = to;
            count++;
        }

        /** Appends a lining run (runs arrive in ascending order and never overlap a void). */
        void addLining(int from, int to, TerrainBlock block) {
            if (to <= from || linings == LINING_CAP) return;
            if (linings > 0 && liningHi[linings - 1] == from && liningBlock[linings - 1] == block) {
                liningHi[linings - 1] = to;
                return;
            }
            liningLo[linings] = from;
            liningHi[linings] = to;
            liningBlock[linings++] = block;
        }

        /** The block a void holds at Y: lava, water, ice or air. */
        public TerrainBlock fill(long y, TerrainMap.Sample s) {
            if (y <= s.geology.lavaY) return TerrainBlock.LAVA;
            Hydro.Sample w = s.water;
            if (y >= w.aquiferMin && y <= w.aquiferMax)
                return y <= w.aquiferLevel ? TerrainBlock.WATER : TerrainBlock.AIR;
            // A cave pool (CavePools): still water held by rock and its rim, ice where frozen.
            for (int i = 0; i < pools; i++)
                if (y >= poolLo[i] && y < poolHi[i])
                    return RealmMap.cell(poolLo[i], s).frozen
                            ? TerrainBlock.ICE
                            : TerrainBlock.WATER;
            // A flood cell's band holds water up to its one flat level; rock seals it below and
            // along its edges, so water never hangs over air or stands beside it. Water that lies
            // in a frozen depth biome (permafrost) is ice.
            if (!s.flood.holds(y)) return TerrainBlock.AIR;
            for (int i = 0; i < count; i++)
                if (y >= lo[i] && y < hi[i])
                    return RealmMap.cell(lo[i], s).frozen ? TerrainBlock.ICE : TerrainBlock.WATER;
            return TerrainBlock.AIR;
        }

        /** Writes the voids' and linings' edges and the fill levels as run boundaries. */
        public int boundaries(TerrainMap.Sample s, int[] out, int n) {
            for (int i = 0; i < decorations; i++) {
                out[n++] = decorLo[i];
                out[n++] = decorHi[i];
            }
            for (int i = 0; i < linings; i++) {
                out[n++] = liningLo[i];
                out[n++] = liningHi[i];
            }
            if (count == 0) return n;
            for (int i = 0; i < count; i++) {
                out[n++] = lo[i];
                out[n++] = hi[i];
            }
            out[n++] = (int) Math.floor(s.geology.lavaY) + 1;
            if (s.flood.flooded) {
                out[n++] = s.flood.level + 1;
                out[n++] = s.flood.floor + 1;
            }
            if (s.water.aquiferMin <= s.water.aquiferMax) out[n++] = s.water.aquiferLevel + 1;
            for (int i = 0; i < pools; i++) {
                out[n++] = poolLo[i];
                out[n++] = poolHi[i];
            }
            return n;
        }
    }

    /** Noise lattice of one chunk column: per noise, (16 / H + 1)^2 columns of levels. */
    private static final class Lattice {
        final double[] cavern, hall, tunnelA, tunnelB;
        // The levels held: count of them from level first, of chunk column (chunkX, chunkZ).
        final int first, count;
        int chunkX, chunkZ;

        Lattice(int size, int first, int count) {
            cavern = new double[size];
            hall = new double[size];
            tunnelA = new double[size];
            tunnelB = new double[size];
            this.first = first;
            this.count = count;
        }
    }

    private final long seed;
    private final int minimumY, levels, side;
    private final SeededNoise[] karst = new SeededNoise[Math.max(1, KARST_LEVELS)],
            wander = new SeededNoise[Math.max(1, KARST_LEVELS)];
    private final SeededNoise canyonMask,
            salt,
            tubes,
            chasmMask,
            chasmWidth,
            pillars,
            rivers,
            bedding,
            jointTurn,
            jointSpacing,
            jointMask;
    private final CellMemo<Lattice> lattices = new CellMemo<>(Integer.highestOneBit(CHUNK_CACHE));
    private final CellMemo<double[]> domes = new CellMemo<>(1024),
            sinkholes = new CellMemo<>(1024),
            geodes = new CellMemo<>(2048);
    private final CellMemo<JointDomain> jointDomains = new CellMemo<>(1024);

    private final CaveDecor decor;
    private final CavePools pools;
    private final CaveSeeps seeps;

    /** A column's sample up to its cave entrances (TerrainMap.sample). */
    @FunctionalInterface
    interface Sampler {
        void sample(double x, double z, TerrainMap.Sample out);
    }

    CaveMap(long worldSeed, TerrainHeightReference heights, Sampler sampler) {
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        decor = new CaveDecor(worldSeed, heights);
        CavePools.Carver carver =
                new CavePools.Carver() {
                    @Override
                    public void sample(double x, double z, TerrainMap.Sample out) {
                        sampler.sample(x, z, out);
                    }

                    @Override
                    public void voids(int x, int z, TerrainMap.Sample s, int from, int to) {
                        carveVoids(x, z, s, null, from, to);
                    }
                };
        pools = new CavePools(worldSeed, carver);
        seeps = new CaveSeeps(worldSeed, carver);
        minimumY = heights.minimumY;
        levels = (heights.maximumYExclusive - heights.minimumY) / V + 2;
        side = 16 / H + 1;
        for (int k = 0; k < karst.length; k++) {
            karst[k] = noise(0x4b41525354310000L + k);
            wander[k] = noise(0x57414e4445520000L + k);
        }
        canyonMask = noise(0x43414e594f4e3131L);
        salt = noise(0x48414c4c53313131L);
        tubes = noise(0x5455424553313131L);
        chasmMask = noise(0x4348415349534d31L);
        chasmWidth = noise(0x4348415357494431L);
        pillars = noise(0x50494c4c41525331L);
        rivers = noise(0x4c41564152495631L);
        bedding = noise(0x42454444494e4731L);
        jointTurn = noise(0x4a4f494e54545531L);
        jointSpacing = noise(0x4a4f494e54535031L);
        jointMask = noise(0x4a4f494e544d5331L);
    }

    private SeededNoise noise(long salt) {
        return new SeededNoise(SeededNoise.mix(seed ^ salt));
    }

    /**
     * Smooth 3D value noise in [-1, 1], read through a fixed rotation per seed so its lattice never
     * lines up with the world's axes (tunnels would turn at right angles).
     */
    private static double rotated(long seed, double x, double y, double z) {
        double a = 0.61 + SeededNoise.unit(seed) * 0.9,
                b = 0.37 + SeededNoise.unit(SeededNoise.mix(seed)) * 0.9;
        double ca = StrictMath.cos(a),
                sa = StrictMath.sin(a),
                cb = StrictMath.cos(b),
                sb = StrictMath.sin(b);
        double x1 = x * ca - z * sa, z1 = x * sa + z * ca;
        double y1 = y * cb - z1 * sb, z2 = y * sb + z1 * cb;
        return noise3(seed, x1, y1, z2);
    }

    /** Smooth 3D value noise in [-1, 1]. */
    private static double noise3(long seed, double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double u = fade(fx), v = fade(fy), w = fade(fz);
        double c000 = corner(seed, ix, iy, iz),
                c100 = corner(seed, ix + 1, iy, iz),
                c010 = corner(seed, ix, iy + 1, iz),
                c110 = corner(seed, ix + 1, iy + 1, iz),
                c001 = corner(seed, ix, iy, iz + 1),
                c101 = corner(seed, ix + 1, iy, iz + 1),
                c011 = corner(seed, ix, iy + 1, iz + 1),
                c111 = corner(seed, ix + 1, iy + 1, iz + 1);
        double x00 = c000 + (c100 - c000) * u,
                x10 = c010 + (c110 - c010) * u,
                x01 = c001 + (c101 - c001) * u,
                x11 = c011 + (c111 - c011) * u;
        double y0 = x00 + (x10 - x00) * v, y1 = x01 + (x11 - x01) * v;
        return y0 + (y1 - y0) * w;
    }

    private static double corner(long seed, int x, int y, int z) {
        long h =
                SeededNoise.mix(
                        seed
                                ^ SeededNoise.mix(x * 0x9e3779b97f4a7c15L)
                                ^ SeededNoise.mix(y * 0xc2b2ae3d27d4eb4fL)
                                ^ SeededNoise.mix(z * 0xd1b54a32d192ed03L));
        return SeededNoise.unit(h) * 2 - 1;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** The chunk column's noise lattice; pure, and shared by every column of the chunk. */
    private Lattice lattice(int chunkX, int chunkZ) {
        return lattices.get(chunkX, chunkZ, () -> lattice(chunkX, chunkZ, 0, levels));
    }

    /** A thread's last few ranged lattices: a patch of columns reuses its chunks' levels. */
    private final ThreadLocal<Lattice[]> recent = ThreadLocal.withInitial(() -> new Lattice[4]);

    private Lattice rangedLattice(int chunkX, int chunkZ, int first, int count) {
        Lattice[] r = recent.get();
        for (Lattice l : r)
            if (l != null
                    && l.chunkX == chunkX
                    && l.chunkZ == chunkZ
                    && l.first == first
                    && l.count == count) return l;
        Lattice l = lattice(chunkX, chunkZ, first, count);
        l.chunkX = chunkX;
        l.chunkZ = chunkZ;
        System.arraycopy(r, 0, r, 1, r.length - 1);
        r[0] = l;
        return l;
    }

    /**
     * The noise lattice of a chunk column over levels [first, first + count): the same values as the
     * whole lattice, for carving a few heights without building every level.
     */
    private Lattice lattice(int chunkX, int chunkZ, int first, int count) {
        Lattice l = new Lattice(side * side * count, first, count);
        long cavernSeed = seed ^ 0x43415645524e3131L,
                detailSeed = seed ^ 0x44455441494c3131L,
                hallSeed = seed ^ 0x4445455043415631L,
                hallDetailSeed = seed ^ 0x48414c4c44455431L,
                aSeed = seed ^ 0x54554e4e454c4131L,
                bSeed = seed ^ 0x54554e4e454c4231L;
        for (int a = 0; a < side; a++)
            for (int b = 0; b < side; b++) {
                double x = chunkX * 16 + a * H, z = chunkZ * 16 + b * H;
                int base = (a * side + b) * count - first;
                for (int k = first; k < first + count; k++) {
                    double y = minimumY + k * V;
                    l.cavern[base + k] =
                            (1 - DETAIL_WEIGHT)
                                            * rotated(
                                                    cavernSeed,
                                                    x / CAVERN_WAVELENGTH,
                                                    y * CAVERN_SQUASH / CAVERN_WAVELENGTH,
                                                    z / CAVERN_WAVELENGTH)
                                    + DETAIL_WEIGHT
                                            * rotated(
                                                    detailSeed,
                                                    x / DETAIL_WAVELENGTH,
                                                    y * CAVERN_SQUASH / DETAIL_WAVELENGTH,
                                                    z / DETAIL_WAVELENGTH);
                    // A second octave breaks up the single-octave lattice cells, which
                    // read as boxes with flat walls at a high threshold.
                    l.hall[base + k] =
                            (1 - HALL_DETAIL_WEIGHT)
                                            * rotated(
                                                    hallSeed,
                                                    x / HALL_WAVELENGTH_DEEP,
                                                    y * HALL_SQUASH / HALL_WAVELENGTH_DEEP,
                                                    z / HALL_WAVELENGTH_DEEP)
                                    + HALL_DETAIL_WEIGHT
                                            * rotated(
                                                    hallDetailSeed,
                                                    x / HALL_DETAIL_WAVELENGTH,
                                                    y * HALL_SQUASH / HALL_DETAIL_WAVELENGTH,
                                                    z / HALL_DETAIL_WAVELENGTH);
                    l.tunnelA[base + k] =
                            rotated(
                                    aSeed,
                                    x / TUNNEL_WAVELENGTH,
                                    y * TUNNEL_SQUASH / TUNNEL_WAVELENGTH,
                                    z / TUNNEL_WAVELENGTH);
                    l.tunnelB[base + k] =
                            rotated(
                                    bSeed,
                                    x / TUNNEL_WAVELENGTH,
                                    y * TUNNEL_SQUASH / TUNNEL_WAVELENGTH,
                                    z / TUNNEL_WAVELENGTH);
                }
            }
        return l;
    }

    /** A dome cell's collapse hall {x, z, centreY, radius}, or null. */
    private double[] dome(int cx, int cz) {
        return domes.get(
                cx,
                cz,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x444f4d4553313131L, cx, cz);
                    if (unit(key, 1) >= DOME_CHANCE) return null;
                    return new double[] {
                        (cx + unit(key, 3)) * DOME_CELL,
                        (cz + unit(key, 4)) * DOME_CELL,
                        lerp(DOME_Y, unit(key, 5)),
                        lerp(DOME_RADIUS, unit(key, 2))
                    };
                });
    }

    /** A geode cell's cavity {x, z, centreY, radius, liningIndex}, or null. */
    private double[] geode(int cx, int cz) {
        return geodes.get(
                cx,
                cz,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x47454f4445533131L, cx, cz);
                    if (unit(key, 1) >= GEODE_CHANCE) return null;
                    return new double[] {
                        (cx + 0.2 + 0.6 * unit(key, 2)) * GEODE_CELL,
                        (cz + 0.2 + 0.6 * unit(key, 3)) * GEODE_CELL,
                        lerp(GEODE_Y, unit(key, 4)),
                        lerp(GEODE_RADIUS, unit(key, 5)),
                        Math.floor(unit(key, 6) * GEODE_LININGS.length)
                    };
                });
    }

    /** A sinkhole cell's shaft {x, z, radius, depth}, or null. */
    private double[] sinkhole(int cx, int cz) {
        return sinkholes.get(
                cx,
                cz,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x53494e4b484f4c31L, cx, cz);
                    if (unit(key, 1) >= SINKHOLE_CHANCE) return null;
                    return new double[] {
                        (cx + 0.15 + 0.7 * unit(key, 2)) * SINKHOLE_CELL,
                        (cz + 0.15 + 0.7 * unit(key, 3)) * SINKHOLE_CELL,
                        lerp(SINKHOLE_RADIUS, unit(key, 4)),
                        lerp(SINKHOLE_DEPTH, unit(key, 5))
                    };
                });
    }

    /**
     * Sets the column's entrance voids (sinkholes in soluble rock, rift chasm openings along major
     * faults) from 2D facts, so every sample and the distant preview carry them.
     */
    public void entrances(double x, double z, TerrainMap.Sample s) {
        Voids out = s.caves;
        out.clear();
        if (s.ocean.marine || s.water.wet || s.water.weight > 0 || s.waterY() > s.blockY()) return;
        int surface = s.blockY();
        GeologyMap.Column g = s.geology;
        if (Math.abs(g.faultThrow) >= CHASM_THROW
                && chasmMask.sample(x / CHASM_MASK_WAVELENGTH, z / CHASM_MASK_WAVELENGTH)
                        > CHASM_MASK_THRESHOLD
                && g.faultDistance < chasmWidth(x, z) / 2) {
            double d = (chasmWidth.sample(x / 97.0, z / 97.0) + 1) / 2;
            out.add(shaftFloor(surface - (int) lerp(CHASM_DEPTH, d), s), surface + 1);
            return;
        }
        if (g.unit(surface - 3).solubility < KARST_SOLUBILITY) return;
        int cx = (int) Math.floor(x / SINKHOLE_CELL), cz = (int) Math.floor(z / SINKHOLE_CELL);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                double[] h = sinkhole(cx + dx, cz + dz);
                if (h == null) continue;
                double ox = x - h[0], oz = z - h[1];
                double theta = StrictMath.atan2(oz, ox);
                double radius = h[2] * (1 + 0.18 * StrictMath.sin(3 * theta + h[3]));
                if (ox * ox + oz * oz < radius * radius) {
                    out.add(shaftFloor(surface - (int) h[3], s), surface + 1);
                    return;
                }
            }
    }

    /**
     * An entrance shaft ends two blocks above any water its column holds or holds back (a flood
     * cell's level, a dam's), so it opens onto no water.
     */
    private static int shaftFloor(int floor, TerrainMap.Sample s) {
        return s.flood.waterTop == Integer.MIN_VALUE
                ? floor
                : Math.max(floor, s.flood.waterTop + 2);
    }

    private double chasmWidth(double x, double z) {
        double n = (chasmWidth.sample(x / CHASM_WAVELENGTH, z / CHASM_WAVELENGTH) + 1) / 2;
        return lerp(CHASM_WIDTH, n);
    }

    /** Column-constant facts the walk reads, gathered once per column. */
    private static final class Plan {
        final double[] karstLo = new double[KARST_LEVELS],
                karstHi = new double[KARST_LEVELS],
                canyonHi = new double[KARST_LEVELS];
        final double[] beddingLo = new double[32], beddingHi = new double[32];
        final double[] domeLo = new double[9], domeHi = new double[9];
        final double[] geodeLo = new double[9],
                geodeHi = new double[9],
                shellLo = new double[9],
                shellHi = new double[9];
        final TerrainBlock[] geodeLining = new TerrainBlock[9];
        int beddings, domeCount, geodeCount;
        boolean nearFault, pillar;
        // The column's joint domain, its distance to the domain's edge, and per set the nearest
        // plane: its distance, index, key, segment length and phase, and the segment here.
        JointDomain jointDomain;
        double jointEdge;
        final double[] jointDistance = new double[2],
                jointLength = new double[2],
                jointPhase = new double[2];
        final long[] jointPlane = new long[2],
                jointPlaneKey = new long[2],
                jointIndex = new long[2],
                jointSegment = new long[2];
        // Each set's segment per joint level: 0 unknown, 1 closed, 2 open and crossed.
        final byte[][] jointLive = new byte[2][];
        int jointFirstLevel;
        double river;
        double fissure, salt, tube, chamberAir, surface;
        int c00, c10, c01, c11;
        double w00, w10, w01, w11;
    }

    /** Carves the column's 3D caves into its voids (after {@link #entrances}). */
    public void carve(int x, int z, TerrainMap.Sample s) {
        carve(x, z, s, null);
    }

    /**
     * As {@link #carve(int, int, TerrainMap.Sample)}, also recording each block's family (index
     * into {@link #FAMILIES}) from the allocation floor when {@code families} is given.
     */
    public void carve(int x, int z, TerrainMap.Sample s, byte[] families) {
        Voids out = s.caves;
        // Entrances arrive with every sample; decoration leaves voids touching them bare.
        int entrances = out.count;
        int[] entranceLo = Arrays.copyOf(out.lo, entrances),
                entranceHi = Arrays.copyOf(out.hi, entrances);
        if (!carveVoids(x, z, s, families, Integer.MIN_VALUE, Integer.MAX_VALUE)) return;
        pools.apply(x, z, s, out);
        seeps.apply(x, z, s, out);
        decor.decorate(x, z, s, out, entranceLo, entranceHi, entrances);
    }

    /**
     * Adds the column's 3D cave voids within [from, to] (and its lake chamber) to its sample,
     * without pools or decoration; false where the column has no room for caves.
     */
    private boolean carveVoids(
            int x, int z, TerrainMap.Sample s, byte[] families, int from, int to) {
        Voids out = s.caves;
        int surface = s.blockY();
        int top = surface - (wet(s) ? WET_ROOF : ROOF), bottom = minimumY + FLOOR_MARGIN;
        if (top <= bottom) return false;
        bottom = Math.max(bottom, from);
        top = Math.min(top, to);
        if (top < bottom) return true;
        GeologyMap.Column g = s.geology;
        // A carve over a few heights builds only the lattice levels it reads.
        Lattice l =
                from == Integer.MIN_VALUE && to == Integer.MAX_VALUE
                        ? lattice(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
                        : rangedLattice(
                                Math.floorDiv(x, 16),
                                Math.floorDiv(z, 16),
                                Math.max(0, (bottom - minimumY) / V),
                                Math.min(levels, (top - minimumY) / V + 2)
                                        - Math.max(0, (bottom - minimumY) / V));
        Plan p = plan(x, z, s, g, l);

        int[] planes = new int[GeologyMap.MAXIMUM_BOUNDARIES + 2];
        int planeCount = g.boundaries(bottom - 1, Math.max(bottom, top), planes, 0);
        Arrays.sort(planes, 0, planeCount);
        int next = 0;
        GeologyMap.Unit unit = g.unit(bottom);
        while (next < planeCount && planes[next] <= bottom) next++;
        int start = Integer.MIN_VALUE, liningStart = Integer.MIN_VALUE;
        TerrainBlock lining = null;
        FloodMap.Column flood = s.flood;
        Hydro.Sample w = s.water;
        // An underground lake is a chamber of its own, which caves may join above its waterline;
        // it comes first, so the column's void cap never drops it.
        if (w.aquiferMin <= w.aquiferMax)
            out.add(Math.max(w.aquiferMin, bottom), Math.min(w.aquiferMax, top) + 1);
        for (int y = bottom; y <= top; y++) {
            while (next < planeCount && planes[next] <= y) {
                next++;
                unit = g.unit(y);
            }
            // Flood seals and a lake's shell stay rock: they hold the water.
            int family =
                    flood.sealed(y) || (y >= w.aquiferShellLo && y <= w.aquiferShellHi)
                            ? 0
                            : family(y, unit, g, l, p);
            if (families != null) families[y - minimumY] = (byte) family;
            boolean carved = family != 0;
            if (carved && start == Integer.MIN_VALUE) start = y;
            else if (!carved && start != Integer.MIN_VALUE) {
                out.add(start, y);
                start = Integer.MIN_VALUE;
            }
            // Geode shells line the rock around their cavities.
            TerrainBlock shell = carved ? null : shellAt(y, unit, p);
            if (shell != lining) {
                if (lining != null) out.addLining(liningStart, y, lining);
                lining = shell;
                liningStart = y;
            }
        }
        if (start != Integer.MIN_VALUE) out.add(start, top + 1);
        if (lining != null) out.addLining(liningStart, top + 1, lining);
        return true;
    }

    /** For checks: pool cell (i, j)'s pool as {x, z, level}, or null (CavePools). */
    int[] poolSite(int i, int j) {
        return pools.site(i, j);
    }

    /** For checks: seep cell (i, j)'s seep as {x, z, band bottom, wet blocks}, or null. */
    int[] seepSite(int i, int j) {
        return seeps.site(i, j);
    }

    /** The skin depth below the surface (riven-caves.json realms). */
    static double skinDepth() {
        return SKIN_DEPTH;
    }

    /** Whether a unit belongs to the broken basement (riven-caves.json realms). */
    static boolean basement(GeologyMap.Unit unit) {
        return BASEMENT.contains(unit);
    }

    /** Whether a column lies under the sea or surface water (its caves keep a thicker roof). */
    static boolean wet(TerrainMap.Sample s) {
        return s.ocean.marine || s.water.wet || s.waterY() > s.blockY();
    }

    private Plan plan(int x, int z, TerrainMap.Sample s, GeologyMap.Column g, Lattice l) {
        Plan p = new Plan();
        p.surface = s.blockY();
        int lx = Math.floorMod(x, 16), lz = Math.floorMod(z, 16), a = lx / H, b = lz / H;
        double fa = (lx % H) / (double) H, fb = (lz % H) / (double) H;
        // Corner offsets into the lattice, less its first level, so v[c + k] reads level k.
        p.c00 = (a * side + b) * l.count - l.first;
        p.c10 = ((a + 1) * side + b) * l.count - l.first;
        p.c01 = (a * side + b + 1) * l.count - l.first;
        p.c11 = ((a + 1) * side + b + 1) * l.count - l.first;
        p.w00 = (1 - fa) * (1 - fb);
        p.w10 = fa * (1 - fb);
        p.w01 = (1 - fa) * fb;
        p.w11 = fa * fb;
        for (int k = 0; k < KARST_LEVELS; k++) {
            double level =
                    g.waterTable
                            + KARST_FIRST
                            - k * KARST_SPACING
                            + KARST_WANDER * wander[k].sample(x / 300.0, z / 300.0);
            double n = karst[k].sample(x / KARST_WAVELENGTH, z / KARST_WAVELENGTH);
            double q = n / TUBE_WIDTH, half = q * q < 1 ? TUBE_HALF * Math.sqrt(1 - q * q) : -1;
            p.karstLo[k] = level - half;
            p.karstHi[k] = half < 0 ? Double.NEGATIVE_INFINITY : level + half;
            double mask = canyonMask.sample(x / 180.0 + k * 7.3, z / 180.0);
            p.canyonHi[k] =
                    Math.abs(n) < CANYON_WIDTH && mask > CANYON_THRESHOLD
                            ? level + CANYON_HEIGHT * Rules.smooth((mask - CANYON_THRESHOLD) / 0.3)
                            : Double.NEGATIVE_INFINITY;
            if (p.canyonHi[k] > Double.NEGATIVE_INFINITY)
                p.karstLo[k] = Math.min(p.karstLo[k], level);
        }
        // Bedding-plane halls hug formation boundaries where a mask allows, each boundary its own.
        double[][] stacks = {g.lower, g.upper};
        for (int si = 0; si < stacks.length; si++)
            for (int k = 0; k < stacks[si].length && p.beddings < p.beddingLo.length; k++) {
                double plane = stacks[si][k];
                double m =
                        bedding.sample(
                                x / BEDDING_WAVELENGTH + k * 3.1 + si * 17.7,
                                z / BEDDING_WAVELENGTH);
                if (m <= BEDDING_THRESHOLD) continue;
                double half = lerp(BEDDING_HALF, Rules.smooth((m - BEDDING_THRESHOLD) / 0.4));
                p.beddingLo[p.beddings] = plane - half * 0.6;
                p.beddingHi[p.beddings++] = plane + half;
            }
        double throwSize = Math.abs(g.faultThrow);
        p.fissure =
                throwSize >= CHASM_THROW
                        ? chasmWidth(x, z) / 2
                        : throwSize >= FISSURE_THROW ? FISSURE_WIDTH / 2 : -1;
        p.nearFault = p.fissure > 0 && g.faultDistance < p.fissure * 1.5;
        // Two vertical joint sets, straight within the column's joint domain.
        placeJoints(x, z, p);
        joint(x, z, 0, p);
        joint(x, z, 1, p);
        int levels =
                Math.floorDiv((int) Math.floor(p.surface), (int) JOINT_LEVEL)
                        - Math.floorDiv(minimumY, (int) JOINT_LEVEL)
                        + 3;
        p.jointFirstLevel = Math.floorDiv(minimumY, (int) JOINT_LEVEL) - 1;
        p.jointLive[0] = new byte[Math.max(1, levels)];
        p.jointLive[1] = new byte[Math.max(1, levels)];
        p.pillar = pillars.sample(x / PILLAR_WAVELENGTH, z / PILLAR_WAVELENGTH) > PILLAR_THRESHOLD;
        p.river = Math.abs(rivers.sample(x / RIVER_WAVELENGTH, z / RIVER_WAVELENGTH));
        p.salt = salt.sample(x / SALT_WAVELENGTH, z / SALT_WAVELENGTH);
        p.tube = Math.abs(tubes.sample(x / TUBE_WAVELENGTH, z / TUBE_WAVELENGTH));
        p.chamberAir =
                g.chamberTop > Double.NEGATIVE_INFINITY
                        ? g.chamberTop - CHAMBER_AIR * (g.chamberTop - g.chamberBottom)
                        : Double.POSITIVE_INFINITY;
        int dx0 = (int) Math.floor(x / DOME_CELL), dz0 = (int) Math.floor(z / DOME_CELL);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                double[] d = dome(dx0 + dx, dz0 + dz);
                if (d == null) continue;
                double h2 = ((x - d[0]) * (x - d[0]) + (z - d[1]) * (z - d[1])) / (d[3] * d[3]);
                if (h2 >= 1) continue;
                double half = DOME_SQUASH * d[3] * Math.sqrt(1 - h2);
                p.domeLo[p.domeCount] = Math.max(d[2] - half, d[2] - DOME_FLOOR * d[3]);
                p.domeHi[p.domeCount++] = d[2] + half;
            }
        int gx0 = (int) Math.floor(x / GEODE_CELL), gz0 = (int) Math.floor(z / GEODE_CELL);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                double[] d = geode(gx0 + dx, gz0 + dz);
                if (d == null) continue;
                double r2 = (x - d[0]) * (x - d[0]) + (z - d[1]) * (z - d[1]);
                double outer = d[3] + GEODE_LINING;
                if (r2 >= outer * outer) continue;
                int i = p.geodeCount++;
                double shell = Math.sqrt(outer * outer - r2);
                p.shellLo[i] = d[2] - shell;
                p.shellHi[i] = d[2] + shell;
                if (r2 < d[3] * d[3]) {
                    double cavity = Math.sqrt(d[3] * d[3] - r2);
                    p.geodeLo[i] = d[2] - cavity;
                    p.geodeHi[i] = d[2] + cavity;
                } else {
                    p.geodeLo[i] = Double.POSITIVE_INFINITY;
                    p.geodeHi[i] = Double.NEGATIVE_INFINITY;
                }
                p.geodeLining[i] = GEODE_LININGS[(int) d[4]];
            }
        return p;
    }

    /**
     * A joint domain: a structural block of about domainCellBlocks whose two joint sets are straight
     * planes of one orientation and spacing each, read from the slow turn and spacing fields at its
     * point. Planes are measured from that point, so they stay straight anywhere in the world.
     */
    private static final class JointDomain {
        final double x, z;
        final long key;
        // The domain's grid cell: a memo may hand out a rebuilt copy, so domains compare by cell.
        final int i, j;
        final double[] c = new double[2], s = new double[2], spacing = new double[2];

        JointDomain(int i, int j, double x, double z, long key, double turn, double spacing) {
            this.i = i;
            this.j = j;
            this.x = x;
            this.z = z;
            this.key = key;
            for (int set = 0; set < 2; set++) {
                double angle = turn + set * JOINT_SECOND;
                c[set] = StrictMath.cos(angle);
                s[set] = StrictMath.sin(angle);
                this.spacing[set] = spacing * (set == 0 ? 1 : 1.3);
            }
        }
    }

    private JointDomain jointDomain(int i, int j) {
        return jointDomains.get(
                i,
                j,
                () -> {
                    long key = SeededNoise.cell(seed ^ 0x4a4f494e54444f31L, i, j);
                    double margin = (1 - JOINT_DOMAIN_JITTER) / 2;
                    double x = (i + margin + JOINT_DOMAIN_JITTER * unit(key, 1)) * JOINT_DOMAIN,
                            z = (j + margin + JOINT_DOMAIN_JITTER * unit(key, 2)) * JOINT_DOMAIN;
                    double turn =
                            jointTurn.sample(x / JOINT_ORIENTATION, z / JOINT_ORIENTATION)
                                    * Math.PI;
                    double spacing =
                            lerp(
                                    JOINT_SPACING,
                                    (jointSpacing.sample(x / 700.0, z / 700.0) + 1) / 2);
                    return new JointDomain(i, j, x, z, key, turn, spacing);
                });
    }

    /** The joint domain nearest (x, z); with edge, also how far (x, z) lies from its edge. */
    private JointDomain jointDomain(double x, double z, double[] edge) {
        int ci = (int) Math.floor(x / JOINT_DOMAIN), cj = (int) Math.floor(z / JOINT_DOMAIN);
        JointDomain owner = null;
        double best = Double.POSITIVE_INFINITY;
        JointDomain[] near = new JointDomain[9];
        for (int dj = -1; dj <= 1; dj++)
            for (int di = -1; di <= 1; di++) {
                JointDomain d = jointDomain(ci + di, cj + dj);
                near[(dj + 1) * 3 + di + 1] = d;
                double r = (x - d.x) * (x - d.x) + (z - d.z) * (z - d.z);
                if (r < best) {
                    best = r;
                    owner = d;
                }
            }
        if (edge != null) {
            edge[0] = Double.POSITIVE_INFINITY;
            for (JointDomain d : near) {
                if (d == owner) continue;
                double dx = d.x - owner.x, dz = d.z - owner.z, length = Math.hypot(dx, dz);
                edge[0] =
                        Math.min(
                                edge[0],
                                length / 2 - ((x - owner.x) * dx + (z - owner.z) * dz) / length);
            }
        }
        return owner;
    }

    private void placeJoints(double x, double z, Plan p) {
        double[] edge = new double[1];
        p.jointDomain = jointDomain(x, z, edge);
        p.jointEdge = edge[0];
    }

    private long planeKey(JointDomain d, int set, long plane) {
        return SeededNoise.mix(
                seed
                        ^ d.key
                        ^ (0x4a4f494e54504c31L + set)
                        ^ SeededNoise.mix(plane * 0x9e3779b97f4a7c15L));
    }

    private static long segmentKey(long planeKey, long segment) {
        return SeededNoise.mix(planeKey ^ SeededNoise.mix(segment * 0xc2b2ae3d27d4eb4fL));
    }

    /**
     * Joint set {@code set} at a column: the distance in blocks to its nearest plane, and that
     * plane's segment here. Each plane breaks into segments of its own length and phase, so slots
     * end and the neighbouring planes' segments overlap en echelon.
     */
    private void joint(double x, double z, int set, Plan p) {
        JointDomain d = p.jointDomain;
        double lx = x - d.x, lz = z - d.z, c = d.c[set], s = d.s[set];
        double u = (lx * c + lz * s) / d.spacing[set], f = u - Math.floor(u);
        long plane = (long) Math.floor(u) + (f < 0.5 ? 0 : 1);
        long key = planeKey(d, set, plane);
        double length = lerp(JOINT_SEGMENT, unit(key, 1)), phase = unit(key, 2);
        long segment = (long) Math.floor((-lx * s + lz * c) / length + phase);
        p.jointDistance[set] = Math.min(f, 1 - f) * d.spacing[set];
        p.jointPlane[set] = plane;
        p.jointPlaneKey[set] = key;
        p.jointLength[set] = length;
        p.jointPhase[set] = phase;
        p.jointIndex[set] = segment;
        p.jointSegment[set] = segmentKey(key, segment);
    }

    /** The shared floor of a domain's joint level: every slot of the level rises from it. */
    private static int levelBase(JointDomain d, long level) {
        double room = JOINT_LEVEL - JOINT_HEIGHT[1];
        long h = SeededNoise.mix(d.key ^ (level * 0xa0761d6478bd642fL));
        return (int) (level * JOINT_LEVEL + Math.floor(room * unit(h, 1)));
    }

    /**
     * Whether a segment opens at a level: its own share (openShare), and the mask read once at its
     * midpoint, so a segment opens whole or not at all.
     */
    private boolean segmentOpens(
            JointDomain d,
            int set,
            long plane,
            double length,
            double phase,
            long segment,
            long key,
            long level) {
        if (unit(SeededNoise.mix(key + level * 0xd1b54a32d192ed03L), 1) >= JOINT_OPEN) return false;
        double t = (segment + 0.5 - phase) * length, offset = plane * d.spacing[set];
        double mx = d.x + offset * d.c[set] - t * d.s[set],
                mz = d.z + offset * d.s[set] + t * d.c[set];
        return jointMask.sample(
                        mx / JOINT_MASK_WAVELENGTH + level * 0.618, mz / JOINT_MASK_WAVELENGTH)
                > JOINT_MASK;
    }

    /**
     * Whether the column's segment of a set is open at a level and crossed there by an open segment
     * of the other set inside the same domain: only crossed segments open, so every slot meets the
     * maze at the level's shared floor and none stands sealed in the rock.
     */
    private boolean jointLive(Plan p, int set, long level) {
        int index = (int) (level - p.jointFirstLevel);
        byte[] live = p.jointLive[set];
        if (index < 0 || index >= live.length) return false;
        if (live[index] != 0) return live[index] == 2;
        boolean result =
                segmentOpens(
                                p.jointDomain,
                                set,
                                p.jointPlane[set],
                                p.jointLength[set],
                                p.jointPhase[set],
                                p.jointIndex[set],
                                p.jointSegment[set],
                                level)
                        && crossed(p, set, level);
        live[index] = (byte) (result ? 2 : 1);
        return result;
    }

    private boolean crossed(Plan p, int set, long level) {
        JointDomain d = p.jointDomain;
        int other = 1 - set;
        double spA = d.spacing[set], spB = d.spacing[other];
        // Along the segment the other set's plane coordinate changes linearly; each integer it
        // passes is a crossing.
        double cos = d.c[set] * d.c[other] + d.s[set] * d.s[other],
                sin = -d.s[set] * d.c[other] + d.c[set] * d.s[other];
        double offset = p.jointPlane[set] * spA;
        double t0 = (p.jointIndex[set] - p.jointPhase[set]) * p.jointLength[set],
                t1 = t0 + p.jointLength[set];
        double u0 = (offset * cos + t0 * sin) / spB, u1 = (offset * cos + t1 * sin) / spB;
        long k0 = (long) Math.ceil(Math.min(u0, u1)), k1 = (long) Math.floor(Math.max(u0, u1));
        // Every crossing lies within a segment's length of the column, so a column that far from
        // its domain's edge needs no domain test.
        boolean inside = p.jointEdge > p.jointLength[set] + 2;
        for (long k = k0; k <= k1; k++) {
            double t = (k * spB - offset * cos) / sin;
            double lx = offset * d.c[set] - t * d.s[set], lz = offset * d.s[set] + t * d.c[set];
            if (!inside) {
                JointDomain at = jointDomain(d.x + lx, d.z + lz, null);
                if (at.i != d.i || at.j != d.j) continue;
            }
            long key = planeKey(d, other, k);
            double length = lerp(JOINT_SEGMENT, unit(key, 1)), phase = unit(key, 2);
            long segment = (long) Math.floor((-lx * d.s[other] + lz * d.c[other]) / length + phase);
            if (segmentOpens(d, other, k, length, phase, segment, segmentKey(key, segment), level))
                return true;
        }
        return false;
    }

    /**
     * Whether a joint slot is open at Y. The maze stacks in levels levelBlocks tall; in each level
     * an open, crossed segment rises from the level's shared floor over its own height, and a share
     * (shaftChance) reaches down to the floor of the level below, so passages connect within and
     * between levels. At a share (roomChance) of the crossings of two open slots, a round room up
     * to roomRadiusBlocks opens around them.
     */
    private boolean jointOpen(int y, Plan p, double ta) {
        double width = JOINT_WIDTH / 2 * (1 + JOINT_VARIATION * ta);
        long level = Math.floorDiv(y, (int) JOINT_LEVEL);
        double room = 0;
        if (p.jointDistance[0] < JOINT_ROOM && p.jointDistance[1] < JOINT_ROOM) {
            long crossing =
                    SeededNoise.mix(p.jointSegment[0] ^ Long.rotateLeft(p.jointSegment[1], 17));
            if (unit(crossing, 1) < JOINT_ROOM_CHANCE
                    && jointLive(p, 0, level)
                    && jointLive(p, 1, level)) {
                double r = JOINT_ROOM * (0.5 + 0.5 * unit(crossing, 2));
                double d2 =
                        p.jointDistance[0] * p.jointDistance[0]
                                + p.jointDistance[1] * p.jointDistance[1];
                if (d2 < r * r) room = r;
            }
        }
        for (int set = 0; set < 2; set++) {
            if (p.jointDistance[set] >= Math.max(width, room)) continue;
            // This level's slot, or a shaft of the level above reaching down into this one.
            for (long l = level; l <= level + 1; l++) {
                if (!jointLive(p, set, l)) continue;
                long h = SeededNoise.mix(p.jointSegment[set] + l * 0xd1b54a32d192ed03L);
                int base = levelBase(p.jointDomain, l),
                        top = base + (int) Math.round(lerp(JOINT_HEIGHT, unit(h, 2)));
                int bottom = unit(h, 4) < JOINT_SHAFT ? levelBase(p.jointDomain, l - 1) : base;
                if (y >= bottom && y < top) return true;
            }
        }
        return false;
    }

    /** The family that carves block Y, or 0 where the rock stands. */
    private int family(int y, GeologyMap.Unit unit, GeologyMap.Column g, Lattice l, Plan p) {
        if (unit == MAGMA) return y > p.chamberAir ? CHAMBER : 0;
        double t = (y - minimumY) / (double) V;
        int k = (int) t;
        double f = t - k;
        double strength = unit.strength, soluble = unit.solubility;
        boolean skin = p.surface - y < SKIN_DEPTH;
        double ta = at(l.tunnelA, k, f, p), tb = at(l.tunnelB, k, f, p);
        // Everywhere: fault fissures and rift chasms, lava tubes in basalt.
        if (p.nearFault
                && y >= FISSURE_BOTTOM
                && g.faultDistance < p.fissure * (0.75 + 0.5 * Math.abs(ta))) return FISSURE;
        if (unit == BASALT
                && p.tube < LAVA_TUBE_WIDTH
                && g.unit(y + TUBE_CLEARANCE) == BASALT
                && g.unit(y - TUBE_CLEARANCE) == BASALT) return LAVA_TUBE;

        if (y <= g.deepY) {
            // The molten root: pillar halls, lava rivers and geodes.
            for (int i = 0; i < p.geodeCount; i++)
                if (y >= p.geodeLo[i] && y <= p.geodeHi[i] && GEODE_HOSTS.contains(unit))
                    return GEODE;
            // The 3D tunnel noise swells and pinches the channel and lifts its roof unevenly.
            if (p.river < RIVER_WIDTH * (1 + RIVER_WIDTH_VARIATION * tb)
                    && y >= g.lavaY - RIVER_BELOW
                    && y <= g.lavaY + RIVER_ABOVE * (1 + RIVER_CEILING_VARIATION * ta))
                return LAVA_RIVER;
            if (!p.pillar && at(l.hall, k, f, p) > HALL_THRESHOLD_DEEP) return PILLAR_HALL;
            double width = TUNNEL_WIDTH * BASEMENT_NARROWING;
            return ta * ta + tb * tb < width * width ? TUNNEL : 0;
        }
        double cavern = at(l.cavern, k, f, p);
        if (BASEMENT.contains(unit)) {
            // The broken basement: sparse caverns, joint mazes, vein galleries, narrow tunnels.
            double threshold =
                    BASEMENT_THRESHOLD
                            + STRONG_PENALTY * Math.max(0, strength - 0.7) / 0.3
                            + (skin ? SKIN_PENALTY : 0);
            if (cavern > threshold) return CAVERN;
            if (!skin && jointOpen(y, p, ta)) return JOINT;
            if (unit == DOLERITE && tb > VEIN_MASK) return VEIN;
            double width = TUNNEL_WIDTH * BASEMENT_NARROWING * (skin ? SKIN_NARROWING : 1);
            return ta * ta + tb * tb < width * width ? TUNNEL : 0;
        }
        // The layered deep: caverns, tunnels, karst, bedding planes, domes, salt halls.
        double threshold =
                LAYERED_THRESHOLD
                        - SOLUBLE_BONUS * soluble
                        - (strength < 0.5 ? WEAK_BONUS : 0)
                        + (skin ? SKIN_PENALTY : 0);
        if (cavern > threshold) return CAVERN;
        double width =
                TUNNEL_WIDTH * (1 + WEAK_WIDENING * (1 - strength)) * (skin ? SKIN_NARROWING : 1);
        if (ta * ta + tb * tb < width * width) return TUNNEL;
        if (soluble >= KARST_SOLUBILITY)
            for (int i = 0; i < p.karstLo.length; i++)
                if (y >= p.karstLo[i]) {
                    if (y <= p.karstHi[i]) return KARST;
                    if (y <= p.canyonHi[i]) return CANYON;
                }
        if (!skin)
            for (int i = 0; i < p.beddings; i++)
                if (y >= p.beddingLo[i] && y <= p.beddingHi[i]) return BEDDING;
        if (strength <= DOME_STRENGTH)
            for (int i = 0; i < p.domeCount; i++)
                if (y >= p.domeLo[i] && y <= p.domeHi[i]) return DOME;
        if (unit == EVAPORITE && p.salt > SALT_THRESHOLD) return SALT_HALL;
        return 0;
    }

    /** A geode's lining block at Y in its host rock, or null. */
    private static TerrainBlock shellAt(int y, GeologyMap.Unit unit, Plan p) {
        if (!GEODE_HOSTS.contains(unit)) return null;
        for (int i = 0; i < p.geodeCount; i++)
            if (y >= p.shellLo[i] && y <= p.shellHi[i]) return p.geodeLining[i];
        return null;
    }

    private static double at(double[] v, int k, double f, Plan p) {
        double lo =
                v[p.c00 + k] * p.w00
                        + v[p.c10 + k] * p.w10
                        + v[p.c01 + k] * p.w01
                        + v[p.c11 + k] * p.w11;
        double hi =
                v[p.c00 + k + 1] * p.w00
                        + v[p.c10 + k + 1] * p.w10
                        + v[p.c01 + k + 1] * p.w01
                        + v[p.c11 + k + 1] * p.w11;
        return lo + (hi - lo) * f;
    }

    private static double unit(long key, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(key + parameter * 0x9e3779b97f4a7c15L));
    }

    private static double lerp(double[] range, double t) {
        return range[0] + (range[1] - range[0]) * t;
    }
}
