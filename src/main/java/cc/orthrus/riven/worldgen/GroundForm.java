package cc.orthrus.riven.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The form of a column's ground and the soil it holds (riven-surface-rules.json form): the facts
 * a column reads from its neighbourhood, and the soil depth they decide. Materials follow form:
 * the crust's settings read them (rock on steps, thin soil on steep and convex ground, snow held
 * by the steps and the north faces), and later stages read the same values through
 * {@link #facts}.
 *
 * <p>Every neighbourhood read is a bounded set of world-aligned columns, each answered by the same
 * pure function of (x, z), {@link TerrainMap#sampleSurface}: the 8 neighbours at the column's
 * spacing (one block in native cubes and fine preview cells, the cell's width in a coarser
 * preview cell) and, for curvature, the nodes of a world-aligned lattice. A bounded,
 * direct-mapped memo shared by every thread keeps recent answers; it only caches a pure function,
 * so what it holds or evicts, and which thread filled it, never changes a result.
 */
public final class GroundForm {
    private static final Definition DEFINITION = Definition.load();

    /** The facts of one column at one spacing. Mutable scratch of the thread that asked. */
    public static final class Facts {
        public int x, z, spacing;

        /** The largest fall to one of the 8 neighbours, in blocks per block of spacing (0 at least). */
        public double drop;

        /**
         * The ground's slope over the 3 x 3 stencil (a Sobel gradient of the real heights: each axis
         * difference averaged over three rows), in blocks per block. Steady on a staircase whose
         * single falls alternate.
         */
        public double gradient;

        /**
         * Whether the column is part of a rock face: it falls to a neighbour and its slope reaches
         * the face threshold, which a low-frequency dither raises and lowers so a face's edges form
         * ragged patches, never a lattice.
         */
        public boolean face;

        /** This column's dither, -1 to 1 (world-aligned value noise): later steps may share it. */
        public double dither;

        /** The rise toward +Z in blocks per block: above 0 the ground faces north (-Z). */
        public double north;

        /** Curvature as blocks below the mean of the lattice around it: above 0 a hollow, below 0 convex. */
        public double hollow;

        /** The soil the column holds, in blocks counting its top. */
        public double soil;

        /** Whether the ground's rock weathers to grus (gravelly soil). */
        public boolean grus;

        /** The column's own rock unit at its ground, or null. */
        public GeologyMap.Unit unit;

        boolean held;
    }

    /** Columns the shared memo holds: a power of two. */
    private static final int SLOTS = 1 << 16;

    /** One column's surface, immutable so threads may share it. */
    private static final class Surface {
        final long key;
        final int level, generation;
        final double height;

        Surface(long key, int generation, int level, double height) {
            this.key = key;
            this.generation = generation;
            this.level = level;
            this.height = height;
        }
    }

    /** A thread's scratch: its sample for misses, its facts, its spacing. */
    private static final class Memo {
        final TerrainMap.Sample scratch = new TerrainMap.Sample();
        final Facts facts = new Facts();
        int spacing = 1;
        Neighbours neighbours;
        // Columns this thread sampled for their neighbours (diagnostics: GroundForm cost).
        long misses;
    }

    private final TerrainMap terrain;
    private final SeededNoise dither = new SeededNoise(DEFINITION.ditherSeed),
            fine = new SeededNoise(SeededNoise.mix(DEFINITION.ditherSeed ^ 0x46494e45L));
    private final double seaLevel;
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);

    // Shared by every thread, direct-mapped and bounded: each entry is a pure function of its
    // column, so what it holds, which thread wrote it and what it evicts never change a result.
    private final AtomicReferenceArray<Surface> surfaces = new AtomicReferenceArray<>(SLOTS);

    // Raised by forget(): held surfaces of an earlier generation no longer answer (checks only).
    private volatile int generation;

    GroundForm(TerrainMap terrain, TerrainHeightReference heights) {
        this.terrain = terrain;
        seaLevel = heights.seaLevel;
    }

    /** The definitions' fingerprint is riven-surface-rules.json's (SurfaceRules.FINGERPRINT). */
    static Definition definition() {
        return DEFINITION;
    }

    /**
     * Sets the spacing this thread's columns read their neighbours at, returning the one it
     * replaces: 1 everywhere but a preview cell wider than a block, which reads its neighbouring
     * cells at its own width.
     */
    public int spacing(int spacing) {
        if (spacing < 1) throw new IllegalArgumentException("spacing " + spacing);
        Memo m = memo.get();
        int was = m.spacing;
        m.spacing = spacing;
        return was;
    }

    /**
     * Where a thread's missing neighbours come from: a full sample the caller keeps for a column
     * it will resolve next, or null to sample the column's height alone. Either gives the same
     * surface (AssembledColumnCheck compares them).
     */
    public interface Neighbours {
        TerrainMap.Sample neighbour(int x, int z);
    }

    /** Sets this thread's source of missing neighbours (null: none), returning the one it replaces. */
    public Neighbours neighbours(Neighbours neighbours) {
        Memo m = memo.get();
        Neighbours was = m.neighbours;
        m.neighbours = neighbours;
        return was;
    }

    /** How many columns this thread has sampled for their neighbours (cost diagnostics). */
    long misses() {
        return memo.get().misses;
    }

    /** Forgets every held surface and this thread's facts (checks: a column resolved alone). */
    public void forget() {
        generation++;
        memo.get().facts.held = false;
    }

    /**
     * Records a column's finished surface from its full sample, so its neighbours read it
     * without sampling it again; the value is the one {@link TerrainMap#sampleSurface} gives.
     */
    public void record(TerrainMap.Sample s) {
        store((int) Math.floor(s.x), (int) Math.floor(s.z), s);
    }

    /** The visible level of a column: its ground, or the water standing over it. */
    public int level(int x, int z) {
        return surface(x, z).level;
    }

    /** The surface height of a column as a real number: its ground, or the water over it. */
    double height(int x, int z) {
        return surface(x, z).height;
    }

    private Surface surface(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        Surface held = surfaces.get((int) SeededNoise.mix(key) & (SLOTS - 1));
        if (held != null && held.key == key && held.generation == generation) return held;
        Memo m = memo.get();
        m.misses++;
        TerrainMap.Sample full = m.neighbours == null ? null : m.neighbours.neighbour(x, z);
        if (full != null) return store(x, z, full);
        terrain.sampleSurface(x, z, m.scratch);
        return store(x, z, m.scratch);
    }

    private Surface store(int x, int z, TerrainMap.Sample s) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        boolean wet = s.waterOverSurface();
        Surface surface =
                new Surface(
                        key,
                        generation,
                        wet ? s.waterY() : s.blockY(),
                        wet ? s.waterY() : s.height);
        surfaces.set((int) SeededNoise.mix(key) & (SLOTS - 1), surface);
        return surface;
    }

    /**
     * The facts of a sampled column at this thread's spacing. The result is this thread's scratch,
     * valid until its next call; the same column at the same spacing is answered again unchanged.
     */
    public Facts facts(TerrainMap.Sample s) {
        Memo m = memo.get();
        Facts f = m.facts;
        int x = (int) Math.floor(s.x), z = (int) Math.floor(s.z), sp = m.spacing;
        if (f.held && f.x == x && f.z == z && f.spacing == sp) return f;
        store(x, z, s);
        f.x = x;
        f.z = z;
        f.spacing = sp;
        Definition d = DEFINITION;
        int ground = s.blockY();
        int drop = 0;
        for (int dz = -sp; dz <= sp; dz += sp)
            for (int dx = -sp; dx <= sp; dx += sp)
                if (dx != 0 || dz != 0) drop = Math.max(drop, ground - level(x + dx, z + dz));
        f.drop = drop / (double) sp;
        // Slope from the real heights, not the whole-block levels: a staircase's rounding would
        // make it alternate with the stairs' phase and comb a face's edges.
        double east = height(x + sp, z), west = height(x - sp, z);
        double south = height(x, z + sp), north = height(x, z - sp);
        double gx =
                (height(x + sp, z - sp) + 2 * east + height(x + sp, z + sp))
                        - (height(x - sp, z - sp) + 2 * west + height(x - sp, z + sp));
        double gz =
                (height(x - sp, z + sp) + 2 * south + height(x + sp, z + sp))
                        - (height(x - sp, z - sp) + 2 * north + height(x + sp, z - sp));
        gx /= 8.0 * sp;
        gz /= 8.0 * sp;
        f.gradient = Math.sqrt(gx * gx + gz * gz);
        f.north = gz;
        f.dither =
                (1 - d.ditherFine) * dither.sample(x / d.ditherWavelength, z / d.ditherWavelength)
                        + d.ditherFine
                                * fine.sample(
                                        x / d.ditherFineWavelength, z / d.ditherFineWavelength);
        f.face = f.drop >= d.faceFall && f.gradient >= d.faceSlope * (1 + d.ditherShare * f.dither);
        f.hollow =
                sp >= d.lattice
                        ? (east + west + south + north) / 4.0 - height(x, z)
                        : latticeHollow(x, z, d.lattice);
        f.unit = s.geology.unit(ground);
        f.grus = f.unit != null && d.grus.contains(f.unit.name);
        f.soil = soil(s, f, ground, d);
        f.held = true;
        return f;
    }

    /** The lattice's curvature at a column: its 4 surrounding nodes' curvature, bilinearly. */
    private double latticeHollow(int x, int z, int lattice) {
        int nx = Math.floorDiv(x, lattice) * lattice, nz = Math.floorDiv(z, lattice) * lattice;
        double tx = (x - nx) / (double) lattice, tz = (z - nz) / (double) lattice;
        double a = nodeHollow(nx, nz, lattice), b = nodeHollow(nx + lattice, nz, lattice);
        double c = nodeHollow(nx, nz + lattice, lattice);
        double e = nodeHollow(nx + lattice, nz + lattice, lattice);
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + e * tx) * tz;
    }

    private double nodeHollow(int x, int z, int lattice) {
        return (height(x + lattice, z)
                                + height(x - lattice, z)
                                + height(x, z + lattice)
                                + height(x, z - lattice))
                        / 4
                - height(x, z);
    }

    /**
     * Soil depth: the climate's depth at the column's own height (thin where cold or dry, thick
     * where warm and wet), less what its slope sheds, more in hollows and less on convex ground,
     * scaled by how its rock weathers.
     */
    private double soil(TerrainMap.Sample s, Facts f, int ground, Definition d) {
        double celsius = ClimateMap.celsiusAt(s.climate, ground - seaLevel);
        double warm = Rules.smooth((celsius - d.coldCelsius) / (d.warmCelsius - d.coldCelsius));
        double wet =
                Rules.smooth(
                        (s.climate.moisture - d.dryMoisture) / (d.wetMoisture - d.dryMoisture));
        double climate = wet * (d.coldShare + (1 - d.coldShare) * warm);
        double depth = d.thin + (d.thick - d.thin) * climate;
        depth -= d.slopeLoss * Math.max(f.gradient, Rules.hypot(s.broadDx, s.broadDz));
        depth += d.hollowGain * Math.max(-d.hollowLimit, Math.min(d.hollowLimit, f.hollow));
        Double rock = f.unit == null ? null : d.rock.get(f.unit.name);
        depth *= rock == null ? d.rockDefault : rock;
        return Math.max(0, Math.min(d.maximum, depth));
    }

    /** riven-surface-rules.json form: steps, soil and snow. */
    static final class Definition {
        final double thinSlope, faceSlope, faceFall, ditherWavelength, ditherShare;
        final double ditherFine, ditherFineWavelength;
        final long ditherSeed;
        final double coldCelsius, warmCelsius, dryMoisture, wetMoisture, coldShare, thin, thick;
        final double slopeLoss, hollowGain, hollowLimit, maximum, rockDefault;
        final double bareBelow, grusBelow;
        final int lattice, soilLayersMaximum;
        final double northCelsius, northFullSlope, shedSlope;
        final Map<String, Double> rock = new HashMap<>();
        final java.util.Set<String> grus = new java.util.HashSet<>();

        Definition(JsonObject form) {
            JsonObject steps = form.getAsJsonObject("steps");
            thinSlope = Rules.number(steps, "thinFromFall", .5, 64);
            JsonObject face = steps.getAsJsonObject("face");
            faceSlope = Rules.number(face, "fromSlope", .1, 64);
            faceFall = Rules.number(face, "fromFall", 0, 64);
            JsonObject dither = face.getAsJsonObject("dither");
            ditherWavelength = Rules.number(dither, "wavelengthBlocks", 2, 4096);
            ditherShare = Rules.number(dither, "share", 0, .9);
            ditherFineWavelength = Rules.number(dither, "fineWavelengthBlocks", 2, 4096);
            ditherFine = Rules.number(dither, "fineWeight", 0, 1);
            ditherSeed = SeededNoise.mix(Rules.seed(dither, "variationSeed") ^ 0x524f434bL);
            JsonObject soil = form.getAsJsonObject("soil");
            JsonObject climate = soil.getAsJsonObject("climate");
            coldCelsius = Rules.number(climate, "coldCelsius", -60, 60);
            warmCelsius = Rules.number(climate, "warmCelsius", coldCelsius + 1, 80);
            dryMoisture = Rules.number(climate, "dryMoisture", 0, 1);
            wetMoisture = Rules.number(climate, "wetMoisture", dryMoisture + .01, 1);
            coldShare = Rules.number(climate, "coldShare", 0, 1);
            thin = Rules.number(climate, "thinBlocks", 0, 16);
            thick = Rules.number(climate, "thickBlocks", thin, 16);
            slopeLoss = Rules.number(soil, "blocksLostPerSlope", 0, 16);
            JsonObject hollow = soil.getAsJsonObject("curvature");
            lattice = (int) Rules.number(hollow, "latticeBlocks", 2, 256);
            if (lattice != Rules.number(hollow, "latticeBlocks", 2, 256))
                throw new IllegalStateException("Range: latticeBlocks");
            hollowGain = Rules.number(hollow, "blocksPerBlock", 0, 4);
            hollowLimit = Rules.number(hollow, "limitBlocks", 0, 64);
            JsonObject rocks = soil.getAsJsonObject("rock");
            rockDefault = Rules.number(rocks, "default", 0, 4);
            for (Map.Entry<String, JsonElement> e : rocks.entrySet())
                if (!e.getKey().equals("default"))
                    rock.put(e.getKey(), Rules.number(rocks, e.getKey(), 0, 4));
            for (JsonElement g : soil.getAsJsonArray("grus")) grus.add(g.getAsString());
            maximum = Rules.number(soil, "maximumBlocks", 1, 16);
            bareBelow = Rules.number(soil, "bareBelowBlocks", 0, maximum);
            grusBelow = Rules.number(soil, "grusBelowBlocks", 0, maximum);
            double layers = Rules.number(soil, "soilLayersMaximum", 0, 15);
            if (layers != Math.rint(layers))
                throw new IllegalStateException("Range: soilLayersMaximum");
            soilLayersMaximum = (int) layers;
            JsonObject snow = form.getAsJsonObject("snow");
            northCelsius = Rules.number(snow, "northFacingCelsius", 0, 30);
            northFullSlope = Rules.number(snow, "northFullFromSlope", .01, 16);
            shedSlope = Rules.number(snow, "shedFromSlope", .1, 64);
        }

        static Definition load() {
            return new Definition(
                    Rules.load("riven-surface-rules.json", 1).json.getAsJsonObject("form"));
        }
    }
}
