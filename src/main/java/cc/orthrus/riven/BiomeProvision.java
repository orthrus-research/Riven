package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.Loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Legacy looks from the decoration provider (riven-bop.json). With the provider loaded, a column's
 * look comes from an explicit override for its habitat key, else from the provider's own climate
 * table at the column's climate grid cell, weighted as the provider weights it, one pick per variety
 * cell; a look bordering a different pick may give way to its edge look. Otherwise the vanilla
 * habitat stands. A look only lends decoration, surface routines and colours-of-record; every
 * column reports a riven: biome (RivenBiomes).
 */
final class BiomeProvision {
    private static final Rules RULES = Rules.load("riven-bop.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;
    static final String PROVIDER = RULES.json.get("provider").getAsString();

    /** A weighted biome list, picked by an unsigned hash. */
    static final class Weighted {
        final Biome[] biomes;
        final int[] cumulative;

        Weighted(List<Biome> biomes, List<Integer> weights) {
            this.biomes = biomes.toArray(new Biome[0]);
            cumulative = new int[weights.size()];
            int total = 0;
            for (int i = 0; i < cumulative.length; i++) cumulative[i] = total += weights.get(i);
        }

        Biome pick(long hash) {
            long at = Long.remainderUnsigned(hash, cumulative[cumulative.length - 1]);
            for (int i = 0; i < cumulative.length; i++) if (at < cumulative[i]) return biomes[i];
            return biomes[biomes.length - 1];
        }
    }

    private final Map<String, Weighted> overrides = new HashMap<>();
    private final Map<Biome, Biome> edges = new HashMap<>();
    private final List<String> allowedGenerators = new ArrayList<>();
    private final Weighted[] climates;
    private final long seed;
    private final double cell, edgeWidth, warp;

    BiomeProvision(long worldSeed) {
        JsonObject j = RULES.json;
        seed = mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        cell = Rules.number(j, "varietyCellBlocks", 64, 16384);
        edgeWidth = Rules.number(j, "edgeBlocks", 0, 1024) / cell;
        warp = Rules.number(j, "borderWarpInCells", 0, .5);
        for (JsonElement id : j.getAsJsonArray("allowedGenerators"))
            allowedGenerators.add(id.getAsString());
        if (!active()) {
            climates = null;
            return;
        }
        Set<String> missing = new java.util.TreeSet<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("biomes").entrySet()) {
            List<Biome> found = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            JsonArray names = e.getValue().getAsJsonArray();
            for (JsonElement name : names) {
                Biome biome = look(name.getAsString(), missing);
                if (biome != null) {
                    found.add(biome);
                    weights.add(1);
                }
            }
            if (!found.isEmpty()) overrides.put(e.getKey(), new Weighted(found, weights));
        }
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("edges").entrySet()) {
            Biome from = look(e.getKey(), missing), to = look(e.getValue().getAsString(), missing);
            if (from != null && to != null) edges.put(from, to);
        }
        Set<String> excluded = new HashSet<>();
        for (JsonElement name : j.getAsJsonArray("excludedFromClimates"))
            excluded.add(qualified(name.getAsString()));
        climates = BopLooks.climates(excluded);
        if (!missing.isEmpty())
            org.apache.logging.log4j.LogManager.getLogger("riven")
                    .warn("Decoration provider looks unknown (vanilla kept): " + missing);
    }

    /** A provider name, or a namespaced id such as {@code minecraft:plains}. */
    private static String qualified(String name) {
        return name.contains(":") ? name : PROVIDER + ":" + name;
    }

    /** A provider look (built by Riven, unregistered) or a registered vanilla biome. */
    private static Biome look(String name, Set<String> missing) {
        ResourceLocation id = new ResourceLocation(qualified(name));
        Biome biome =
                id.getNamespace().equals(PROVIDER)
                        ? BopLooks.looks().get(id.getPath())
                        : Biome.REGISTRY.getObject(id);
        if (biome == null) missing.add(id.toString());
        return biome;
    }

    /**
     * A look's name: {@code biomesoplenty:<name>} for a provider look Riven built (they carry no
     * registry name), else the registry name of a vanilla biome.
     */
    static String lookName(Biome look) {
        String provided = active() ? BopLooks.name(look) : null;
        if (provided != null) return PROVIDER + ":" + provided;
        return look.getRegistryName() == null ? "" : look.getRegistryName().toString();
    }

    /** Whether a look is one of the provider's own biomes. */
    static boolean isProviderLook(Biome look) {
        return active() && BopLooks.name(look) != null;
    }

    /** Whether the provider mod is loaded; without it nothing here changes the world. */
    static boolean active() {
        return Loader.isModLoaded(PROVIDER);
    }

    /**
     * The provider look at a column, or null to keep the vanilla habitat: an explicit override for
     * the habitat key (an inland key falls back from its rugged override to its level), else for an
     * inland key the provider's climate table at the column's climate cell.
     */
    Biome biome(String key, int climate, int x, int z) {
        Weighted list = overrides.get(key);
        if (list == null && key.endsWith("/rugged"))
            list = overrides.get(key.substring(0, key.length() - "/rugged".length()));
        if (list == null) list = overrides.get(key.replace("/calm", ""));
        if (list == null && key.indexOf('#') > 0)
            list = overrides.get(key.substring(0, key.indexOf('#')));
        if (list == null && climates != null && key.contains("/") && !key.startsWith("MOUTH/"))
            list = climates[climate];
        if (list == null) return null;
        long[] v = variety(x, z);
        Biome chosen = list.pick(v[0]);
        Biome edge = edges.get(chosen);
        // An edge biome lines a border with a different pick, as BoP's own edge layers do.
        if (edge != null && Double.longBitsToDouble(v[2]) < edgeWidth && list.pick(v[1]) != chosen)
            return edge;
        return chosen;
    }

    List<String> allowedGenerators() {
        return Collections.unmodifiableList(allowedGenerators);
    }

    /**
     * The nearest and second-nearest jittered points on a grid of variety cells: their hashes and
     * the border distance between them (difference of distances, in cells). Regional patches with
     * organic borders, the same for every query of a column.
     */
    private long[] variety(int x, int z) {
        // Warped lookups give organic borders; straight Voronoi edges read as seams.
        double u0 = x / cell, v0 = z / cell;
        double u =
                u0
                        + warp
                                * (value(seed + 11, u0 * 3, v0 * 3)
                                        + .5 * value(seed + 13, u0 * 7, v0 * 7));
        double v =
                v0
                        + warp
                                * (value(seed + 17, u0 * 3, v0 * 3)
                                        + .5 * value(seed + 19, u0 * 7, v0 * 7));
        long cx = (long) Math.floor(u), cz = (long) Math.floor(v);
        double best = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
        long chosen = 0, runner = 0;
        for (long dz = -1; dz <= 1; dz++)
            for (long dx = -1; dx <= 1; dx++) {
                long h =
                        mix(
                                seed
                                        ^ mix((cx + dx) * 0x9e3779b97f4a7c15L)
                                        ^ mix((cz + dz) * 0xc2b2ae3d27d4eb4fL));
                double px = cx + dx + unit(h), pz = cz + dz + unit(mix(h + 1));
                double d = (px - u) * (px - u) + (pz - v) * (pz - v);
                if (d < best) {
                    second = best;
                    runner = chosen;
                    best = d;
                    chosen = mix(h + 2);
                } else if (d < second) {
                    second = d;
                    runner = mix(h + 2);
                }
            }
        return new long[] {
            chosen, runner, Double.doubleToRawLongBits(Math.sqrt(second) - Math.sqrt(best))
        };
    }

    /** Smooth value noise in [-1, 1]. */
    private static double value(long salt, double x, double z) {
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        double fx = x - ix, fz = z - iz, sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double a = corner(salt, ix, iz), b = corner(salt, ix + 1, iz);
        double c = corner(salt, ix, iz + 1), d = corner(salt, ix + 1, iz + 1);
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sz;
    }

    private static double corner(long salt, long x, long z) {
        return unit(mix(salt ^ mix(x * 0x9e3779b97f4a7c15L) ^ mix(z * 0xc2b2ae3d27d4eb4fL))) * 2
                - 1;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
