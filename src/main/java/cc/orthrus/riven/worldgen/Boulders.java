package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boulders (riven-boulders.json, boulders-v1): rock lying on the land, made of the geological unit
 * under each site's soil, as pure functions of seed, definitions and coordinates. Every kind is a heightfield:
 * each column of a site's footprint holds rock from its ground block up to the site's top there,
 * so a boulder never overhangs and anything that reads a column's ground (tree sites, the preview)
 * sees the boulder's top. Footprints never overlap, so each column belongs to at most one site.
 */
public final class Boulders {
    private static final Rules RULES = Rules.load("riven-boulders.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    private static final class Kind {
        final String id, model;
        final double minRadius, maxRadius, minHeight, maxHeight, minAspect, maxAspect;
        final double roughness, exponent, shrink, jitter;
        final int minTiers, maxTiers, minTierHeight, maxTierHeight;

        Kind(String id, JsonObject j, double radiusCap) {
            this.id = id;
            model = j.get("model").getAsString();
            if (!model.equals("dome") && !model.equals("tor") && !model.equals("spire"))
                throw new IllegalStateException("Unsupported boulder model: " + model);
            double[] r = range(j, "radius", .5, radiusCap);
            minRadius = r[0];
            maxRadius = r[1];
            double[] a = range(j, "aspect", .2, 1);
            minAspect = a[0];
            maxAspect = a[1];
            double[] h = model.equals("tor") ? new double[] {1, 1} : range(j, "height", .5, 16);
            minHeight = h[0];
            maxHeight = h[1];
            roughness = j.has("roughness") ? Rules.number(j, "roughness", 0, 2) : 0;
            exponent = j.has("exponent") ? Rules.number(j, "exponent", .25, 4) : 1;
            shrink = j.has("shrink") ? Rules.number(j, "shrink", .3, 1) : 1;
            jitter = j.has("jitter") ? Rules.number(j, "jitter", 0, 2) : 0;
            double[] t = model.equals("tor") ? range(j, "tiers", 1, 8) : new double[] {1, 1};
            minTiers = (int) t[0];
            maxTiers = (int) t[1];
            double[] th = model.equals("tor") ? range(j, "tierHeight", 1, 4) : new double[] {1, 1};
            minTierHeight = (int) th[0];
            maxTierHeight = (int) th[1];
        }
    }

    private static final class Owner {
        final double chance;
        final Kind[] kinds;
        final double[] weights;

        Owner(double chance, Kind[] kinds, double[] weights) {
            this.chance = chance;
            this.kinds = kinds;
            this.weights = weights;
        }
    }

    /**
     * One site: its centre column, the kind, its rock, and per footprint column the rock's bottom
     * (the column's ground block) and exclusive top; bottom == top where the column holds none.
     */
    public static final class Site {
        public final int x, z, radius;
        public final String kind;
        public final TerrainBlock rock;
        final int[] bottom, top;

        Site(int x, int z, int radius, String kind, TerrainBlock rock, int[] bottom, int[] top) {
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.kind = kind;
            this.rock = rock;
            this.bottom = bottom;
            this.top = top;
        }

        private int index(int bx, int bz) {
            int dx = bx - x, dz = bz - z;
            if (Math.abs(dx) > radius || Math.abs(dz) > radius) return -1;
            return (dz + radius) * (2 * radius + 1) + dx + radius;
        }

        /** The lowest rock Y in column (bx, bz), or Integer.MIN_VALUE where the site holds none. */
        public int bottom(int bx, int bz) {
            int i = index(bx, bz);
            return i < 0 || bottom[i] == top[i] ? Integer.MIN_VALUE : bottom[i];
        }

        /** The Y above the site's rock in column (bx, bz); meaningful where bottom is not MIN_VALUE. */
        public int top(int bx, int bz) {
            int i = index(bx, bz);
            return i < 0 ? Integer.MIN_VALUE : top[i];
        }

        /** Rock blocks in the site. */
        public int blocks() {
            int n = 0;
            for (int i = 0; i < top.length; i++) n += top[i] - bottom[i];
            return n;
        }
    }

    private static final Site NONE = new Site(0, 0, 0, "", null, new int[0], new int[0]);

    private final long seed;
    private final int cell, spacing, reach, slope;
    private final Map<String, Owner> owners = new HashMap<>();
    private final TerrainMap terrain;
    private final CellMemo<Site> sites = new CellMemo<>(1 << 13);
    private final ThreadLocal<TerrainMap.Sample> scratch =
            ThreadLocal.withInitial(TerrainMap.Sample::new);

    public Boulders(long worldSeed, TerrainMap terrain) {
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        cell = (int) Rules.number(j, "cellBlocks", 8, 128);
        reach = (int) Rules.number(j, "maximumRadiusBlocks", 1, 8);
        spacing = (int) Rules.number(j, "spacingBlocks", 2 * reach + 1, 64);
        slope = (int) Rules.number(j, "maximumSlopeBlocks", 0, 8);
        // Neighbour checks reach one cell; cells two apart are always farther than the spacing.
        if (spacing > cell + 1) throw new IllegalStateException("Range: spacingBlocks");
        Map<String, Kind> kinds = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("kinds").entrySet())
            kinds.put(e.getKey(), new Kind(e.getKey(), e.getValue().getAsJsonObject(), reach));
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("biomes").entrySet()) {
            JsonObject b = e.getValue().getAsJsonObject();
            List<Kind> list = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (Map.Entry<String, JsonElement> w : b.getAsJsonObject("kinds").entrySet()) {
                Kind k = kinds.get(w.getKey());
                if (k == null)
                    throw new IllegalStateException("Unknown boulder kind " + w.getKey());
                list.add(k);
                weights.add(w.getValue().getAsDouble());
            }
            double[] array = new double[weights.size()];
            for (int i = 0; i < array.length; i++) array[i] = weights.get(i);
            owners.put(
                    e.getKey(),
                    new Owner(Rules.number(b, "chance", 0, 1), list.toArray(new Kind[0]), array));
        }
        this.terrain = terrain;
    }

    /** The widest footprint radius: a column's site lies within this many blocks of it. */
    public int reach() {
        return reach;
    }

    /** Every site whose centre lies in [minX, maxX) x [minZ, maxZ), in cell order. */
    public void sites(int minX, int minZ, int maxX, int maxZ, Consumer<Site> out) {
        for (int gz = Math.floorDiv(minZ, cell); gz * cell < maxZ; gz++)
            for (int gx = Math.floorDiv(minX, cell); gx * cell < maxX; gx++) {
                Site s = site(gx, gz);
                if (s != NONE && s.x >= minX && s.x < maxX && s.z >= minZ && s.z < maxZ)
                    out.accept(s);
            }
    }

    /** The site whose footprint holds rock in column (x, z), or null. */
    public Site at(int x, int z) {
        Site[] found = {null};
        sites(
                x - reach,
                z - reach,
                x + reach + 1,
                z + reach + 1,
                s -> {
                    if (found[0] == null && s.bottom(x, z) != Integer.MIN_VALUE) found[0] = s;
                });
        return found[0];
    }

    /** Why cell (gx, gz) holds no site, or "SITE" (diagnostics). */
    public String reason(int gx, int gz) {
        String[] out = {"SITE"};
        build(gx, gz, out);
        return out[0];
    }

    private Site site(int gx, int gz) {
        return sites.get(gx, gz, () -> build(gx, gz, null));
    }

    private static Site reject(String[] out, String reason) {
        if (out != null) out[0] = reason;
        return NONE;
    }

    private Site build(int gx, int gz, String[] out) {
        long id = SeededNoise.cell(seed, gx, gz);
        int x = gx * cell + (int) Math.floor(unit(id, 1) * cell);
        int z = gz * cell + (int) Math.floor(unit(id, 2) * cell);
        // A candidate closer than the spacing to a higher-priority neighbour stands down, whatever
        // that neighbour becomes: symmetric, so footprints never overlap.
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                long other = SeededNoise.cell(seed, gx + dx, gz + dz);
                int ox = (gx + dx) * cell + (int) Math.floor(unit(other, 1) * cell);
                int oz = (gz + dz) * cell + (int) Math.floor(unit(other, 2) * cell);
                if (Math.max(Math.abs(ox - x), Math.abs(oz - z)) < spacing
                        && unit(other, 6) > unit(id, 6)) return reject(out, "SPACING");
            }
        TerrainMap.Sample s = scratch.get();
        terrain.sample(x, z, s);
        if (dry(s) == Integer.MIN_VALUE) return reject(out, "WET");
        Owner owner = owners.get(RivenBiomeTable.select(Habitats.key(s)).id);
        if (owner == null) return reject(out, "BIOME");
        if (unit(id, 3) >= owner.chance) return reject(out, "CHANCE");
        Kind kind = owner.kinds[Math.max(0, Rules.choose(owner.weights, unit(id, 4)))];
        int ground = s.blockY() + 1;
        // Hard rock outlasts its surroundings: the hardest unit within ROCK_SEARCH blocks under the
        // soil (without the near-surface climate tint), ties to the earlier rock in HARDNESS order,
        // so one region's boulders share their rock. Loose deposits never stand as boulders.
        TerrainBlock rock = null;
        for (int y = ground - 2; y > ground - 2 - ROCK_SEARCH; y--) {
            TerrainBlock b = s.geology.rock(y);
            if (hardness(b) > hardness(rock)) rock = b;
        }
        if (rock == null) return reject(out, "ROCK");
        double radius = lerp(kind.minRadius, kind.maxRadius, unit(id, 7));
        double aspect = lerp(kind.minAspect, kind.maxAspect, unit(id, 8));
        double height = lerp(kind.minHeight, kind.maxHeight, unit(id, 9));
        double angle = unit(id, 10) * Math.PI;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        int r = (int) Math.ceil(radius + kind.jitter);
        r = Math.min(r, reach);
        int side = 2 * r + 1;
        int[] bottom = new int[side * side], top = new int[side * side];
        int[] tierHeights = new int[kind.maxTiers];
        double[] tierU = new double[kind.maxTiers], tierV = new double[kind.maxTiers];
        int tiers =
                kind.minTiers
                        + (int) Math.floor(unit(id, 11) * (kind.maxTiers - kind.minTiers + 1));
        for (int t = 0; t < tiers; t++) {
            tierHeights[t] =
                    kind.minTierHeight
                            + (int)
                                    Math.floor(
                                            unit(id, 20 + t)
                                                    * (kind.maxTierHeight
                                                            - kind.minTierHeight
                                                            + 1));
            tierU[t] = t == 0 ? 0 : kind.jitter * (2 * unit(id, 30 + t) - 1);
            tierV[t] = t == 0 ? 0 : kind.jitter * (2 * unit(id, 40 + t) - 1);
        }
        boolean any = false;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                int i = (dz + r) * side + dx + r;
                double u = dx * cos + dz * sin, v = -dx * sin + dz * cos;
                double h;
                if (kind.model.equals("tor")) {
                    h = 0;
                    for (int t = 0; t < tiers; t++) {
                        double rt = radius * Math.pow(kind.shrink, t);
                        double du = (u - tierU[t]) / rt, dv = (v - tierV[t]) / (rt * aspect);
                        if (du * du + dv * dv <= 1) h += tierHeights[t];
                    }
                } else {
                    double du = u / radius, dv = v / (radius * aspect);
                    double d = Math.sqrt(du * du + dv * dv);
                    if (d >= 1) continue;
                    h =
                            kind.model.equals("dome")
                                    ? height * Math.sqrt(1 - d * d)
                                    : height * Math.pow(1 - d, kind.exponent);
                    h += kind.roughness * (2 * unit(id ^ SeededNoise.cell(seed, dx, dz), 12) - 1);
                }
                int n = (int) Math.floor(h + .5);
                if (n <= 0) continue;
                // The column's own ground: rock fills from its ground block to the site's top.
                terrain.sample(x + dx, z + dz, s);
                int g = dry(s);
                if (g == Integer.MIN_VALUE) return reject(out, "WET");
                if (Math.abs(g - ground) > slope) return reject(out, "SLOPE");
                if (ground + n < g) continue;
                bottom[i] = g - 1;
                top[i] = ground + n;
                any = true;
            }
        if (!any) return reject(out, "EMPTY");
        return new Site(x, z, r, kind.id, rock, bottom, top);
    }

    /** How far below the soil a site looks for solid rock. */
    private static final int ROCK_SEARCH = 64;

    /**
     * Rocks that stand as boulders, hardest first (weathering resistance, not Mohs); a rock not
     * listed (sand, gravel, clay) never does.
     */
    private static final TerrainBlock[] HARDNESS = {
        TerrainBlock.QUARTZITE,
        TerrainBlock.GRANITE,
        TerrainBlock.GNEISS,
        TerrainBlock.ANORTHOSITE,
        TerrainBlock.GABBRO,
        TerrainBlock.LEUCOBASALT,
        TerrainBlock.DIORITE,
        TerrainBlock.ANDESITE,
        TerrainBlock.STONE,
        TerrainBlock.SANDSTONE,
        TerrainBlock.RED_SANDSTONE,
        TerrainBlock.LIMESTONE,
        TerrainBlock.SLATE,
        TerrainBlock.MICA_SCHIST,
        TerrainBlock.PHYLLITE,
        TerrainBlock.GRAPHITE_SCHIST,
        TerrainBlock.SHALE,
        TerrainBlock.SOAPSTONE
    };

    /** A rock's place from the softest (1) to the hardest; 0 for none or a loose deposit. */
    private static int hardness(TerrainBlock b) {
        for (int i = 0; i < HARDNESS.length; i++) if (HARDNESS[i] == b) return HARDNESS.length - i;
        return 0;
    }

    /** A dry land column's first air Y above its ground, or MIN_VALUE under water or sea. */
    private static int dry(TerrainMap.Sample s) {
        if (s.ocean.marine || (s.water.wet && s.waterY() > s.blockY())) return Integer.MIN_VALUE;
        return s.blockY() + 1;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double[] range(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (lo < min || hi > max || hi < lo) throw new IllegalStateException("Range: " + key);
        return new double[] {lo, hi};
    }

    private static double unit(long id, long salt) {
        return SeededNoise.unit(SeededNoise.mix(id ^ salt * 0x9e3779b97f4a7c15L));
    }
}
