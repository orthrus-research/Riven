package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provinces (riven-provinces.json): a seeded regional map that is the parent of biomes. Space
 * splits into jittered cells read at warped coordinates; a column belongs to its nearest cell, with
 * an intensity from 0 at the province's edge to 1 inside. Every province has two faces, a land type
 * and an ocean class, and each column takes the face of its own ground, so islands in a mostly sea
 * province and bays in a land one keep their kind. The land type is the ground's shape, chosen once
 * per world from facts no later shaping changes: provinces near a convergent or divergent plate
 * boundary take the tectonic types, and the rest split by a seeded level field and their distance
 * to the coast in the budget's proportions, counted over every province's land. Each cell
 * offsets climate by physical effects (continentality, the sea), blended between neighbouring
 * cells so effective climate changes smoothly across edges. Every answer is a pure function of
 * seed, definitions and coordinates; the budget is settled when the map is built.
 */
public final class ProvinceMap {
    static final Rules RULES = Rules.load("riven-provinces.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    /** The ocean classes, after the land types. */
    static final String[] OCEANS = {"warm-shelf", "warm-deep", "cold-shelf", "cold-deep", "frozen"};

    /** A province type: a land type or an ocean class. */
    public static final class Type {
        public final String id;
        public final int index;
        public final boolean land;
        final boolean enabled;
        final double min, max;

        /** A land type's ground band, in blocks above the sea (0 for ocean classes). */
        final double bandLow, bandHigh;

        /**
         * A land type's relief: amplitude in blocks, and the shares of it that are ridged
         * (mountain crests) and terraced (tableland steps); 0 for ocean classes.
         */
        final double relief, ridged, terraced;

        Type(
                String id,
                int index,
                boolean land,
                boolean enabled,
                double min,
                double max,
                double bandLow,
                double bandHigh,
                double relief,
                double ridged,
                double terraced) {
            this.id = id;
            this.index = index;
            this.land = land;
            this.enabled = enabled;
            this.min = min;
            this.max = max;
            this.bandLow = bandLow;
            this.bandHigh = bandHigh;
            this.relief = relief;
            this.ridged = ridged;
            this.terraced = terraced;
        }

        /** Whether the budget may give this type any land. */
        boolean allowed() {
            return enabled && max > 0;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** The definitions, read once: the packaged ones, or a variant a check builds. */
    static final class Definition {
        final double size, jitter, warp, warpWavelength, edge;
        final double reach, threshold, beltThreshold, levelWavelength, forelandReach;
        final int side;
        final Type[] types;
        final int landTypes, foreland, convergent, divergent, coastal, interior, rest, top, beside;
        final double warmCelsius, frozenCelsius;
        final double interiorBlocks, continentality, maritimeMoisture, maritimePull, mildCelsius;
        final double maxCelsius, maxMoisture;
        final long variation;
        final String fingerprint;

        Definition(JsonObject j, String fingerprint) {
            this.fingerprint = fingerprint;
            variation = Rules.seed(j, "variationSeed");
            JsonObject cells = j.getAsJsonObject("cells");
            size = Rules.number(cells, "sizeBlocks", 128, 16384);
            jitter = Rules.number(cells, "jitter", 0, 1);
            warp = Rules.number(cells, "warpBlocks", 0, 4096);
            warpWavelength = Rules.number(cells, "warpWavelengthBlocks", 64, 65536);
            edge = Rules.number(cells, "edgeBlocks", 1, 8192);
            side = (int) Rules.number(j.getAsJsonObject("budget"), "measureSide", 8, 512);
            List<Type> list = new ArrayList<>();
            for (JsonElement e : j.getAsJsonArray("types")) {
                JsonObject t = e.getAsJsonObject();
                String id = t.get("id").getAsString();
                JsonArray share = t.getAsJsonArray("share");
                double lo = share.get(0).getAsDouble(), hi = share.get(1).getAsDouble();
                if (!(lo >= 0 && lo <= hi && hi <= 1))
                    throw new IllegalStateException("riven-provinces.json " + id + ": share 0..1");
                for (Type other : list)
                    if (other.id.equals(id))
                        throw new IllegalStateException("riven-provinces.json: duplicate " + id);
                JsonArray band = t.getAsJsonArray("band");
                double low = band.get(0).getAsDouble(), high = band.get(1).getAsDouble();
                if (!(low >= 1 && low <= high && high <= 288))
                    throw new IllegalStateException("riven-provinces.json " + id + ": band 1..288");
                JsonObject r = t.getAsJsonObject("relief");
                double blocks = Rules.number(r, "blocks", 0, 200);
                double ridged = Rules.number(r, "ridged", 0, 1);
                double terraced = Rules.number(r, "terraced", 0, 1);
                list.add(
                        new Type(
                                id,
                                list.size(),
                                true,
                                t.get("enabled").getAsBoolean(),
                                lo,
                                hi,
                                low,
                                high,
                                blocks,
                                ridged,
                                terraced));
            }
            landTypes = list.size();
            for (String ocean : OCEANS)
                list.add(new Type(ocean, list.size(), false, true, 0, 1, 0, 0, 0, 0, 0));
            types = list.toArray(new Type[0]);
            JsonObject tectonic = j.getAsJsonObject("tectonic");
            convergent = landType(tectonic.get("convergent").getAsString());
            divergent = landType(tectonic.get("divergent").getAsString());
            reach = Rules.number(tectonic, "reachBlocks", 1, 16384);
            threshold = Rules.number(tectonic, "threshold", 0, 1);
            beltThreshold = Rules.number(tectonic, "beltThreshold", 0, 1);
            JsonObject stable = j.getAsJsonObject("stable");
            coastal = landType(stable.getAsJsonObject("low").get("coastal").getAsString());
            interior = landType(stable.getAsJsonObject("low").get("interior").getAsString());
            rest = landType(stable.getAsJsonObject("high").get("rest").getAsString());
            top = landType(stable.getAsJsonObject("high").get("top").getAsString());
            levelWavelength = Rules.number(stable, "levelWavelengthCells", 0.5, 64);
            JsonObject f = j.getAsJsonObject("foreland");
            foreland = landType(f.get("type").getAsString());
            beside = landType(f.get("beside").getAsString());
            forelandReach = Rules.number(f, "reachCells", 0.5, 3);
            int[] all = {convergent, divergent, foreland, coastal, interior, rest, top};
            for (int a = 0; a < all.length; a++)
                for (int b = 0; b < a; b++)
                    if (all[a] == all[b])
                        throw new IllegalStateException(
                                "riven-provinces.json: " + types[all[a]].id + " fills two slots");
            if (all.length != landTypes)
                throw new IllegalStateException(
                        "riven-provinces.json: every land type fills one slot");
            JsonObject oceans = j.getAsJsonObject("oceans");
            warmCelsius = Rules.number(oceans, "warmCelsius", -40, 60);
            frozenCelsius = Rules.number(oceans, "frozenCelsius", -60, warmCelsius);
            JsonObject c = j.getAsJsonObject("climate");
            interiorBlocks = Rules.number(c, "interiorBlocks", 1, 16384);
            maxCelsius = Rules.number(c, "maximumCelsius", 0, 20);
            maxMoisture = Rules.number(c, "maximumMoisture", 0, 1);
            continentality = Rules.number(c, "continentalityMoisture", 0, maxMoisture);
            maritimeMoisture = Rules.number(c, "maritimeMoisture", 0, maxMoisture);
            maritimePull = Rules.number(c, "maritimePull", 0, 1);
            mildCelsius = Rules.number(c, "mildCelsius", -40, 60);
        }

        private int landType(String id) {
            for (int i = 0; i < landTypes; i++) if (types[i].id.equals(id)) return i;
            throw new IllegalStateException("riven-provinces.json: unknown land type " + id);
        }
    }

    static final Definition PACKAGED = new Definition(RULES.json, FINGERPRINT);

    /** One column's province: its type, its nearest neighbour's, intensity and climate offsets. */
    public static final class Sample {
        public Type type, neighbour;
        public double intensity, celsiusOffset, moistureOffset;

        /**
         * The land ground's level here, in blocks above the sea: the cells' levels blended within
         * edgeBlocks of the nearest, like the climate offsets, so it never jumps at an edge.
         */
        public double ground;

        /** The province cell's grid index (i, j packed), for identity. */
        public long cell;

        /** Applies the offsets to a climate sample, its bands and region recomputed. */
        public void apply(ClimateMap.Sample climate) {
            climate.celsius += celsiusOffset;
            climate.moisture = Math.max(0, Math.min(1, climate.moisture + moistureOffset));
            climate.temperatureBand = TemperatureBand.at(climate.celsius);
            climate.moistureBand = MoistureBand.at(climate.moisture);
            climate.region = ClimateRegion.at(climate.temperatureBand, climate.moistureBand);
        }
    }

    /** What a cell's seed point says, sampled once. */
    private static final class Facts {
        final int i, j;
        final double x, z, coast, celsius, divergent, level;

        /** The province's mountain belt: over its measured land, else at its seed point. */
        double convergent;

        final boolean marine, deep;
        double area;

        Facts(
                int i,
                int j,
                double x,
                double z,
                double coast,
                double celsius,
                double convergent,
                double divergent,
                double level,
                boolean marine,
                boolean deep) {
            this.i = i;
            this.j = j;
            this.x = x;
            this.z = z;
            this.coast = coast;
            this.celsius = celsius;
            this.convergent = convergent;
            this.divergent = divergent;
            this.level = level;
            this.marine = marine;
            this.deep = deep;
        }
    }

    /** A cell: its land type, its ocean class and its climate offsets (one for both faces). */
    private static final class Cell {
        final double celsius, moisture;
        final Type land, ocean;

        /** The land face's ground level, in blocks above the sea, drawn inside its band. */
        final double ground;

        Cell(Type land, Type ocean, double celsius, double moisture, double ground) {
            this.land = land;
            this.ocean = ocean;
            this.celsius = celsius;
            this.moisture = moisture;
            this.ground = ground;
        }

        Type face(boolean marine) {
            return marine ? ocean : land;
        }
    }

    final Definition def;
    private final long seed;
    private final double size, edge, reach, interiorBlocks, half;
    private final SeededNoise warpX, warpZ, level;
    private final PlateMap plates;
    private final ContinentalMap continents;
    private final ClimateMap climate;
    private final Map<Long, Cell> world = new HashMap<>();

    /** The world square's land faces, which provinces outside it read for the beside type. */
    private final Map<Long, Type> faces = new HashMap<>();

    /** The world square's land-bearing provinces' seed points, for checks. */
    private final Map<Long, double[]> centres = new HashMap<>();

    private final CellMemo<Cell> outside = new CellMemo<>(1024);

    // The world's cuts, which cells outside the world square read without the budget: the level at
    // or below which a stable cell is low, at or above which a high cell is top, and the coast
    // distance at or beyond which a low cell is interior.
    private double lowCut = Double.NEGATIVE_INFINITY,
            topCut = Double.POSITIVE_INFINITY,
            interiorCut = Double.POSITIVE_INFINITY;

    /** Each land type's share of the world's land (foreland included). */
    public final double[] shares;

    private final int[] provinces;

    /** Minimums the tectonics could not meet, one line each. */
    public final List<String> shortfalls = new ArrayList<>();

    /**
     * Types past their maximum because a province no type had room for fell to its nearest type
     * (whole provinces hold a cap only to within one of them), one line each.
     */
    public final List<String> overruns = new ArrayList<>();

    /** The world square's provinces that hold land (the budget's), and all of them. */
    public final int landCells, cells;

    /** How long building the map took, in milliseconds (for checks and the log). */
    public final double buildMillis;

    public ProvinceMap(
            long worldSeed,
            WorldScale scale,
            PlateMap plates,
            ContinentalMap continents,
            ClimateMap climate) {
        this(worldSeed, scale, plates, continents, climate, PACKAGED);
    }

    ProvinceMap(
            long worldSeed,
            WorldScale scale,
            PlateMap plates,
            ContinentalMap continents,
            ClimateMap climate,
            Definition def) {
        long started = System.nanoTime();
        this.def = def;
        this.plates = plates;
        this.continents = continents;
        this.climate = climate;
        seed = SeededNoise.mix(worldSeed ^ def.variation);
        size = def.size * scale.layout;
        edge = def.edge * scale.layout;
        reach = def.reach * scale.layout;
        interiorBlocks = def.interiorBlocks * scale.layout;
        half = scale.side() / 2;
        warpX = new SeededNoise(SeededNoise.mix(seed ^ 0x5741525058L));
        warpZ = new SeededNoise(SeededNoise.mix(seed ^ 0x574152505aL));
        level = new SeededNoise(SeededNoise.mix(seed ^ 0x4c4556454cL));
        // The world square's cells (seed point inside), each sampled once.
        int i0 = (int) Math.floor(-half / size) - 1, i1 = (int) Math.floor(half / size) + 1;
        Map<Long, Facts> cells = new HashMap<>();
        for (int i = i0; i <= i1; i++)
            for (int j = i0; j <= i1; j++) {
                double[] p = seedPoint(i, j);
                if (Math.abs(p[0]) <= half && Math.abs(p[1]) <= half)
                    cells.put(key(i, j), facts(i, j));
            }
        // Their land areas, on a grid over the world square.
        // Their land areas and mountain belts, on a grid over the world square: a province's
        // belt is the mean over its land, a province-scale fact rather than one point's.
        Map<Long, int[]> area = new HashMap<>();
        Map<Long, double[]> belts = new HashMap<>();
        double step = scale.side() / def.side, origin = -half + step / 2;
        ContinentalMap.Sample c = new ContinentalMap.Sample();
        for (int a = 0; a < def.side; a++)
            for (int b = 0; b < def.side; b++) {
                double x = origin + a * step, z = origin + b * step;
                continents.sample(x, z, c);
                long owner = nearest(x, z);
                int[] counts = area.computeIfAbsent(owner, k -> new int[2]);
                counts[c.marine ? 1 : 0]++;
                if (!c.marine)
                    belts.computeIfAbsent(owner, k -> new double[1])[0] +=
                            plates.sample((int) Math.floor(x), (int) Math.floor(z)).belt;
            }
        // Every province holding measured land enters the budget, by its land area.
        List<Facts> land = new ArrayList<>();
        for (Facts f : cells.values()) {
            int[] counts = area.get(key(f.i, f.j));
            f.area = counts == null ? 0 : counts[0];
            if (f.area > 0) {
                f.convergent = belts.get(key(f.i, f.j))[0] / f.area;
                land.add(f);
            }
        }
        landCells = land.size();
        this.cells = cells.size();
        // A fixed order before anything reads the list, so no hash order reaches a result.
        land.sort(Comparator.comparingLong((Facts f) -> key(f.i, f.j)));
        shares = new double[def.types.length];
        provinces = new int[def.types.length];
        Map<Long, Type> base = assign(land);
        // Provinces without measured land still have a land face for the land they hold.
        for (Facts f : cells.values())
            if (!base.containsKey(key(f.i, f.j))) base.put(key(f.i, f.j), unbudgeted(f, base));
        double total = 0;
        for (Facts f : land) total += f.area;
        faces.putAll(base);
        for (Facts f : land) centres.put(key(f.i, f.j), new double[] {f.x, f.z, f.convergent});
        for (Facts f : cells.values()) {
            Type t = base.get(key(f.i, f.j));
            world.put(key(f.i, f.j), cell(f, t, ocean(f)));
            if (f.area > 0) {
                if (total > 0) shares[t.index] += f.area / total;
                provinces[t.index]++;
            }
        }
        buildMillis = (System.nanoTime() - started) / 1e6;
    }

    /** The budget over the world square's land cells: tectonic types, then the stable split. */
    private Map<Long, Type> assign(List<Facts> land) {
        Map<Long, Type> out = new HashMap<>();
        double total = 0;
        for (Facts f : land) total += f.area;
        double[] share = new double[def.types.length];
        int[] count = new int[def.types.length];
        // Tectonic types, nearest the boundary first, while under their maximum (one may always).
        for (int pass = 0; pass < 2; pass++) {
            int t = pass == 0 ? def.convergent : def.divergent;
            Type type = def.types[t];
            if (!type.allowed()) continue;
            List<Facts> candidates = new ArrayList<>();
            for (Facts f : land)
                if (!out.containsKey(key(f.i, f.j)) && near(f, t) >= limit(t)) candidates.add(f);
            candidates.sort(
                    Comparator.comparingDouble((Facts f) -> -near(f, t))
                            .thenComparingLong(this::tie));
            for (Facts f : candidates) {
                double a = total > 0 ? f.area / total : 0;
                if (count[t] > 0 && share[t] + a > type.max + 1e-9) continue;
                out.put(key(f.i, f.j), type);
                share[t] += a;
                count[t]++;
            }
            if (share[t] < type.min - 1e-9)
                shortfalls.add(
                        String.format(
                                Locale.ROOT,
                                "%s %.1f%% of land, budget %.0f-%.0f%% (too few cells near %s boundaries)",
                                type.id,
                                100 * share[t],
                                100 * type.min,
                                100 * type.max,
                                pass == 0 ? "convergent" : "divergent"));
        }
        // Foreland: cells within reach of a range province's seed point, nearest first, while
        // under its maximum, so mountains step down through foothills.
        Type fore = def.types[def.foreland];
        java.util.Set<Long> inBudget = new java.util.HashSet<>();
        for (Facts f : land) inBudget.add(key(f.i, f.j));
        if (fore.allowed()) {
            Map<Long, Double> gap = new HashMap<>();
            List<Facts> candidates = new ArrayList<>();
            for (Facts f : land) {
                if (out.containsKey(key(f.i, f.j))) continue;
                double d = besideDistance(f, out, inBudget);
                if (d <= def.forelandReach * size) {
                    gap.put(key(f.i, f.j), d);
                    candidates.add(f);
                }
            }
            candidates.sort(
                    Comparator.comparingDouble((Facts f) -> gap.get(key(f.i, f.j)))
                            .thenComparingLong(this::tie));
            for (Facts f : candidates) {
                double a = total > 0 ? f.area / total : 0;
                if (count[def.foreland] > 0 && share[def.foreland] + a > fore.max + 1e-9) continue;
                out.put(key(f.i, f.j), fore);
                share[def.foreland] += a;
                count[def.foreland]++;
            }
            if (share[def.foreland] < fore.min - 1e-9)
                shortfalls.add(
                        String.format(
                                Locale.ROOT,
                                "%s %.1f%% of land, budget %.0f-%.0f%% (too few cells beside %s)",
                                fore.id,
                                100 * share[def.foreland],
                                100 * fore.min,
                                100 * fore.max,
                                def.types[def.beside].id));
        }
        // The stable types' shares: the middle of each range, scaled to fill what is left, held
        // inside the range.
        int[] stable = {def.coastal, def.interior, def.rest, def.top};
        double left =
                Math.max(0, 1 - share[def.convergent] - share[def.divergent] - share[def.foreland]);
        double[] target = new double[def.types.length];
        boolean[] held = new boolean[def.types.length];
        for (int round = 0; round < 8; round++) {
            double free = left, weight = 0;
            for (int t : stable)
                if (held[t]) free -= target[t];
                else if (def.types[t].allowed())
                    weight += (def.types[t].min + def.types[t].max) / 2;
            boolean changed = false;
            for (int t : stable) {
                Type type = def.types[t];
                if (held[t] || !type.allowed()) continue;
                double v = weight > 0 ? Math.max(0, free) * (type.min + type.max) / 2 / weight : 0;
                if (v < type.min || v > type.max) {
                    target[t] = Math.max(type.min, Math.min(type.max, v));
                    held[t] = true;
                    changed = true;
                } else target[t] = v;
            }
            if (!changed) break;
        }
        List<Facts> rest = new ArrayList<>();
        for (Facts f : land) if (!out.containsKey(key(f.i, f.j))) rest.add(f);
        double stableArea = 0;
        for (Facts f : rest) stableArea += total > 0 ? f.area / total : 0;
        if (rest.isEmpty() || stableArea <= 0) return out;
        // Each stable cell's ranks among the stable cells, by area at its middle: its level (0 the
        // lowest) and its distance to the coast (0 the most coastal).
        Map<Long, double[]> rank = new HashMap<>();
        rest.sort(Comparator.comparingDouble((Facts f) -> f.level).thenComparingLong(this::tie));
        double sum = 0;
        for (Facts f : rest) {
            double a = f.area / total / stableArea;
            rank.put(key(f.i, f.j), new double[] {sum + a / 2, 0});
            sum += a;
        }
        rest.sort(Comparator.comparingDouble((Facts f) -> f.coast).thenComparingLong(this::tie));
        sum = 0;
        for (Facts f : rest) {
            double a = f.area / total / stableArea;
            rank.get(key(f.i, f.j))[1] = sum + a / 2;
            sum += a;
        }
        // Each type's home in rank space: the low types on the lowest levels, the interior one
        // farthest from the coast and the coastal one nearest it; the top type on the highest
        // levels, the rest between.
        double low = (target[def.coastal] + target[def.interior]) / stableArea,
                topShare = target[def.top] / stableArea;
        double[] home = new double[def.types.length], coastHome = new double[def.types.length];
        home[def.coastal] = home[def.interior] = low / 2;
        home[def.top] = 1 - topShare / 2;
        home[def.rest] = (low + 1 - topShare) / 2;
        coastHome[def.coastal] = 0;
        coastHome[def.interior] = 1;
        lowCut = quantile(rest, rank, 0, low);
        topCut = quantile(rest, rank, 0, 1 - topShare);
        // Every (cell, type) pair by how near the cell lies to the type's home; each cell takes its
        // nearest type with room, first up to the type's target share, then up to its maximum, so
        // no type passes its maximum (but for its first province).
        List<double[]> pairs = new ArrayList<>();
        for (int k = 0; k < rest.size(); k++) {
            double[] r = rank.get(key(rest.get(k).i, rest.get(k).j));
            for (int s : stable) {
                if (!def.types[s].allowed()) continue;
                boolean lowType = s == def.coastal || s == def.interior;
                double d =
                        Math.abs(r[0] - home[s])
                                + (lowType ? Math.abs(r[1] - coastHome[s]) / 2 : 0);
                pairs.add(new double[] {d, k, s});
            }
        }
        pairs.sort(
                Comparator.comparingDouble((double[] q) -> q[0])
                        .thenComparingLong(q -> tie(rest.get((int) q[1])))
                        .thenComparingDouble(q -> q[2]));
        double[] taken = new double[def.types.length];
        int[] provincesHeld = new int[def.types.length];
        boolean[] done = new boolean[rest.size()];
        for (int pass = 0; pass < 2; pass++)
            for (double[] q : pairs) {
                int k = (int) q[1], s = (int) q[2];
                if (done[k]) continue;
                double a = rest.get(k).area / total;
                // The target admits a cell by its middle; the maximum only by the whole cell.
                boolean over =
                        taken[s] + a > def.types[s].max + 1e-9
                                || (pass == 0 && taken[s] + a / 2 > target[s] + 1e-9);
                if (provincesHeld[s] > 0 && over) continue;
                Facts f = rest.get(k);
                out.put(key(f.i, f.j), def.types[s]);
                taken[s] += a;
                provincesHeld[s]++;
                done[k] = true;
            }
        // A cell no type has room for (whole provinces leave the room scattered) takes its nearest
        // type all the same, and the overrun is logged.
        for (double[] q : pairs) {
            int k = (int) q[1], s = (int) q[2];
            if (done[k]) continue;
            Facts f = rest.get(k);
            out.put(key(f.i, f.j), def.types[s]);
            taken[s] += f.area / total;
            done[k] = true;
            overruns.add(
                    String.format(
                            Locale.ROOT,
                            "%s %.1f%% of land, maximum %.0f%% (a province no type had room for)",
                            def.types[s].id,
                            100 * taken[s],
                            100 * def.types[s].max));
        }
        // A stable minimum whole provinces could not meet is logged, never forced.
        for (int s : stable) {
            Type type = def.types[s];
            if (type.allowed() && taken[s] < type.min - 1e-9)
                shortfalls.add(
                        String.format(
                                Locale.ROOT,
                                "%s %.1f%% of land, budget %.0f-%.0f%% (whole provinces)",
                                type.id,
                                100 * taken[s],
                                100 * type.min,
                                100 * type.max));
        }
        // The coast distance beyond which an outside low cell is interior: the most coastal
        // interior cell's.
        for (Facts f : rest)
            if (out.get(key(f.i, f.j)).index == def.interior)
                interiorCut = Math.min(interiorCut, f.coast);
        return out;
    }

    /** The level (by rank in the first slot) below which a share of the stable cells lie. */
    private static double quantile(
            List<Facts> rest, Map<Long, double[]> rank, int slot, double share) {
        double cut = Double.NEGATIVE_INFINITY;
        for (Facts f : rest)
            if (rank.get(key(f.i, f.j))[slot] <= share) cut = Math.max(cut, f.level);
        return cut;
    }

    /** The level a tectonic fact must reach: the province belt for convergent, nearness for divergent. */
    private double limit(int t) {
        return t == def.convergent ? def.beltThreshold : def.threshold;
    }

    /** How near a cell lies to a boundary of the tectonic type's kind, 0 to 1. */
    private double near(Facts f, int t) {
        return t == def.convergent ? f.convergent : f.divergent;
    }

    private long tie(Facts f) {
        return SeededNoise.cell(seed ^ 0x544945L, f.i, f.j);
    }

    /**
     * How far a cell's seed point lies from the nearest seed point of a beside-type province within
     * two cells, or infinity: assigned types where the budget has them, the tectonic rule beyond.
     */
    private double besideDistance(Facts f, Map<Long, Type> assigned, java.util.Set<Long> inBudget) {
        double best = Double.POSITIVE_INFINITY;
        for (int di = -2; di <= 2; di++)
            for (int dj = -2; dj <= 2; dj++) {
                if (di == 0 && dj == 0) continue;
                int i = f.i + di, j = f.j + dj;
                Type n = assigned.get(key(i, j));
                boolean beside;
                if (n != null) beside = n.index == def.beside;
                else if (inBudget.contains(key(i, j))) beside = false;
                else beside = tectonicAt(i, j) == def.beside;
                if (!beside) continue;
                double[] q = seedPoint(i, j);
                best = Math.min(best, Math.hypot(f.x - q[0], f.z - q[1]));
            }
        return best;
    }

    /** The tectonic type a cell gives, from plates alone (its nine-point belt), or -1. */
    private int tectonicAt(int i, int j) {
        double[] p = seedPoint(i, j);
        PlateMap.Sample plate = plates.sample((int) Math.floor(p[0]), (int) Math.floor(p[1]));
        double near = Math.max(0, 1 - plate.boundaryDistance / reach);
        return tectonic(beltAround(p), plate.boundary == PlateMap.Boundary.DIVERGENT ? near : 0);
    }

    /** The mean belt of nine points a third of a cell apart around a seed point. */
    private double beltAround(double[] p) {
        double belt = 0;
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++)
                belt +=
                        plates.sample(
                                                (int) Math.floor(p[0] + a * size / 3),
                                                (int) Math.floor(p[1] + b * size / 3))
                                        .belt
                                / 9;
        return belt;
    }

    /** The tectonic type a cell's seed point gives, or -1. */
    private int tectonic(Facts f) {
        return tectonic(f.convergent, f.divergent);
    }

    private int tectonic(double convergent, double divergent) {
        if (def.types[def.convergent].allowed() && convergent >= def.beltThreshold)
            return def.convergent;
        if (def.types[def.divergent].allowed() && divergent >= def.threshold) return def.divergent;
        return -1;
    }

    /**
     * A land face outside the budget, by the budget's own rules without its shares: the tectonic
     * type, else foreland beside the beside type, else the level's type by the world's cuts.
     */
    private Type unbudgeted(Facts f, Map<Long, Type> assigned) {
        int tec = tectonic(f);
        if (tec >= 0) return def.types[tec];
        if (def.types[def.foreland].allowed()
                && besideDistance(f, assigned, java.util.Collections.emptySet())
                        <= def.forelandReach * size) return def.types[def.foreland];
        if (f.level <= lowCut)
            return def.types[f.coast >= interiorCut ? def.interior : def.coastal];
        return def.types[f.level >= topCut ? def.top : def.rest];
    }

    private Cell cellAt(int i, int j) {
        Cell c = world.get(key(i, j));
        if (c != null) return c;
        return outside.get(
                i,
                j,
                () -> {
                    Facts f = facts(i, j);
                    return cell(f, unbudgeted(f, faces), ocean(f));
                });
    }

    /** A cell's climate offsets: continentality dries the interior, the sea wets and tempers the coast. */
    private Cell cell(Facts f, Type land, Type ocean) {
        double inland = f.marine ? 0 : Rules.smooth(f.coast / interiorBlocks), seaward = 1 - inland;
        double moisture = def.maritimeMoisture * seaward - def.continentality * inland;
        double celsius = def.maritimePull * seaward * (def.mildCelsius - f.celsius);
        moisture = Math.max(-def.maxMoisture, Math.min(def.maxMoisture, moisture));
        celsius = Math.max(-def.maxCelsius, Math.min(def.maxCelsius, celsius));
        // The land face's level inside its band: a pure draw from the cell's grid key.
        double u = SeededNoise.unit(SeededNoise.mix(seed ^ 0x47524f554e44L ^ key(f.i, f.j)));
        double ground = land.bandLow + (land.bandHigh - land.bandLow) * u;
        return new Cell(land, ocean, celsius, moisture, ground);
    }

    private Type ocean(Facts f) {
        int first = def.landTypes;
        if (f.celsius <= def.frozenCelsius) return def.types[first + 4];
        boolean warm = f.celsius >= def.warmCelsius;
        return def.types[first + (warm ? 0 : 2) + (f.deep ? 1 : 0)];
    }

    /** A cell's seed point and what it says, sampled once. */
    private Facts facts(int i, int j) {
        double[] p = seedPoint(i, j);
        ContinentalMap.Sample c = new ContinentalMap.Sample();
        continents.sample(p[0], p[1], c);
        ClimateMap.Sample s = new ClimateMap.Sample();
        climate.sample(p[0], p[1], s);
        PlateMap.Sample plate = plates.sample((int) Math.floor(p[0]), (int) Math.floor(p[1]));
        double near = Math.max(0, 1 - plate.boundaryDistance / reach);
        double w = def.levelWavelength * size;
        // The province's belt where no grid measures it: the mean of nine points around the seed
        // point (a province-scale fact, like the grid's mean over its land).
        double belt = beltAround(p);
        return new Facts(
                i,
                j,
                p[0],
                p[1],
                c.marine ? 0 : Math.max(0, c.distance),
                s.celsius,
                belt,
                plate.boundary == PlateMap.Boundary.DIVERGENT ? near : 0,
                level.sample(p[0] / w, p[1] / w),
                c.marine,
                c.deepOcean);
    }

    private double[] seedPoint(int i, int j) {
        long h = SeededNoise.cell(seed, i, j);
        double u = SeededNoise.unit(h), v = SeededNoise.unit(SeededNoise.mix(h));
        return new double[] {
            (i + 0.5 + def.jitter * (u - 0.5)) * size, (j + 0.5 + def.jitter * (v - 0.5)) * size
        };
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xffffffffL);
    }

    /** The warped point of (x, z), into out. */
    private void warp(double x, double z, double[] out) {
        double scale = size / def.size, w = def.warpWavelength * scale;
        out[0] = x + def.warp * scale * warpX.sample(x / w, z / w);
        out[1] = z + def.warp * scale * warpZ.sample(x / w, z / w);
    }

    /** The cell nearest a column. */
    private long nearest(double x, double z) {
        double[] warped = new double[2];
        warp(x, z, warped);
        int ci = (int) Math.floor(warped[0] / size), cj = (int) Math.floor(warped[1] / size);
        long best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (int di = -2; di <= 2; di++)
            for (int dj = -2; dj <= 2; dj++) {
                double[] p = seedPoint(ci + di, cj + dj);
                double d = Math.hypot(warped[0] - p[0], warped[1] - p[1]);
                if (d < bestD) {
                    bestD = d;
                    best = key(ci + di, cj + dj);
                }
            }
        return best;
    }

    /** A column's province, intensity and blended climate offsets; its ground read here. */
    public void sample(double x, double z, Sample out) {
        ContinentalMap.Sample c = new ContinentalMap.Sample();
        continents.sample(x, z, c);
        sample(x, z, c.marine, out);
    }

    /**
     * A column's province (the face of its ground: marine or land), intensity and blended climate
     * offsets.
     */
    public void sample(double x, double z, boolean marine, Sample out) {
        double[] warped = new double[2];
        warp(x, z, warped);
        double wx = warped[0], wz = warped[1];
        int ci = (int) Math.floor(wx / size), cj = (int) Math.floor(wz / size);
        double[] d = new double[25];
        int first = -1, second = -1;
        for (int k = 0; k < 25; k++) {
            double[] p = seedPoint(ci + k / 5 - 2, cj + k % 5 - 2);
            d[k] = Math.hypot(wx - p[0], wz - p[1]);
            if (first < 0 || d[k] < d[first]) {
                second = first;
                first = k;
            } else if (second < 0 || d[k] < d[second]) second = k;
        }
        Cell a = cellAt(ci + first / 5 - 2, cj + first % 5 - 2);
        Cell b = cellAt(ci + second / 5 - 2, cj + second % 5 - 2);
        out.type = a.face(marine);
        out.neighbour = b.face(marine);
        out.cell = key(ci + first / 5 - 2, cj + first % 5 - 2);
        out.intensity = Math.max(0, Math.min(1, (d[second] - d[first]) / edge));
        // Offsets blend over the cells within edge of the nearest, each weighted down smoothly by
        // how much farther it lies, so they never jump where the nearest cell changes.
        double celsius = 0, moisture = 0, ground = 0, weights = 0;
        for (int k = 0; k < 25; k++) {
            double t = (d[k] - d[first]) / edge;
            if (t >= 1) continue;
            double w = 1 - Rules.smooth(t);
            Cell c = k == first ? a : k == second ? b : cellAt(ci + k / 5 - 2, cj + k % 5 - 2);
            celsius += w * c.celsius;
            moisture += w * c.moisture;
            ground += w * c.ground;
            weights += w;
        }
        out.celsiusOffset = celsius / weights;
        out.moistureOffset = moisture / weights;
        out.ground = ground / weights;
    }

    /**
     * The land ground at a column, blended like its level: {level, relief blocks, ridged share,
     * terraced share} into out (see Sample.ground), without the faces.
     */
    public void ground(double x, double z, double[] out) {
        double[] warped = new double[2];
        warp(x, z, warped);
        double wx = warped[0], wz = warped[1];
        int ci = (int) Math.floor(wx / size), cj = (int) Math.floor(wz / size);
        double[] d = new double[25];
        int first = 0;
        for (int k = 0; k < 25; k++) {
            double[] p = seedPoint(ci + k / 5 - 2, cj + k % 5 - 2);
            d[k] = Math.hypot(wx - p[0], wz - p[1]);
            if (d[k] < d[first]) first = k;
        }
        double ground = 0, relief = 0, ridged = 0, terraced = 0, weights = 0;
        for (int k = 0; k < 25; k++) {
            double t = (d[k] - d[first]) / edge;
            if (t >= 1) continue;
            double w = 1 - Rules.smooth(t);
            Cell c = cellAt(ci + k / 5 - 2, cj + k % 5 - 2);
            ground += w * c.ground;
            relief += w * c.land.relief;
            ridged += w * c.land.ridged;
            terraced += w * c.land.terraced;
            weights += w;
        }
        out[0] = ground / weights;
        out[1] = relief / weights;
        out[2] = ridged / weights;
        out[3] = terraced / weights;
    }

    /** The width over which neighbouring provinces blend, in blocks (scaled with the layout). */
    public double edgeBlocks() {
        return edge;
    }

    /** For checks: {x, z, belt} of the world square's land-bearing provinces of a type. */
    List<double[]> centres(Type t) {
        List<double[]> out = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : centres.entrySet())
            if (faces.get(e.getKey()) == t) out.add(e.getValue());
        return out;
    }

    /** The province belt (mean over its land) at or above which it may take the convergent type. */
    double tectonicThreshold() {
        return def.beltThreshold;
    }

    /** How many of the world square's land provinces have a type. */
    public int provincesOf(Type t) {
        return provinces[t.index];
    }

    /** A line for the log, such as {@code 21 of 36 provinces hold land: plain 30%, ...}. */
    public String summary() {
        StringBuilder s = new StringBuilder();
        s.append(landCells).append(" of ").append(cells).append(" provinces hold land:");
        for (Type t : def.types)
            if (t.land && shares[t.index] > 0)
                s.append(String.format(Locale.ROOT, " %s %.0f%%", t.id, 100 * shares[t.index]));
        if (!shortfalls.isEmpty()) s.append("; short: ").append(String.join(", ", shortfalls));
        if (!overruns.isEmpty()) s.append("; over: ").append(String.join(", ", overruns));
        return s.append(String.format(Locale.ROOT, " (%.0f ms)", buildMillis)).toString();
    }
}
