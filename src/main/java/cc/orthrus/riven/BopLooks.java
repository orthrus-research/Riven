package cc.orthrus.riven;

import biomesoplenty.api.biome.BOPBiomes;
import biomesoplenty.api.biome.IExtendedBiome;
import biomesoplenty.api.enums.BOPClimates;

import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.block.state.IBlockState;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Biomes O' Plenty's overworld biomes as Riven uses them (riven-compat-biomesoplenty.json): withheld
 * from the biome registry, and built by Riven, unregistered, as legacy looks that still lend their
 * decoration, surface routines and climate weights until Riven's feature families replace them.
 * Loaded only when BoP is present.
 */
final class BopLooks {
    private static final Rules RULES = Rules.load("riven-compat-biomesoplenty.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;
    private static final String PACKAGE = "biomesoplenty.common.biome.overworld.";

    private static Map<String, Biome> looks;
    private static Map<Biome, String> names;

    /**
     * Each look's top and filler blocks as built, before any surface routine runs: some routines
     * (chaparral, bog, overgrown cliffs and others) rewrite their biome's fields per column.
     */
    private static Map<Biome, IBlockState[]> soils;

    private BopLooks() {}

    /** BoP's overworld biomes by name, in its registration order (built once, on first use). */
    static synchronized Map<String, Biome> looks() {
        if (looks == null) {
            Map<String, Biome> built = new LinkedHashMap<>();
            Map<Biome, String> reverse = new IdentityHashMap<>();
            Map<Biome, IBlockState[]> soil = new IdentityHashMap<>();
            for (JsonElement e : RULES.json.getAsJsonArray("looks")) {
                JsonObject look = e.getAsJsonObject();
                Biome biome = construct(look);
                String name = look.get("name").getAsString();
                built.put(name, biome);
                reverse.put(biome, name);
                soil.put(biome, new IBlockState[] {biome.topBlock, biome.fillerBlock});
            }
            looks = Collections.unmodifiableMap(built);
            names = reverse;
            soils = soil;
        }
        return looks;
    }

    /** A look's top (0) and filler (1) blocks as built, or null for any other biome. */
    static IBlockState[] soil(Biome biome) {
        looks();
        return soils.get(biome);
    }

    /** The BoP name of a look, or null for any other biome. */
    static String name(Biome biome) {
        looks();
        return names.get(biome);
    }

    private static Biome construct(JsonObject look) {
        String type = PACKAGE + look.get("class").getAsString();
        try {
            Class<?> c = Class.forName(type);
            if (!look.has("variant")) return (Biome) c.getConstructor().newInstance();
            String[] variant = look.get("variant").getAsString().split("\\.");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> kind =
                    (Class<? extends Enum>) Class.forName(type + "$" + variant[0]);
            @SuppressWarnings("unchecked")
            Object value = Enum.valueOf(kind, variant[1]);
            Constructor<?> k = c.getConstructor(kind);
            return (Biome) k.newInstance(value);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            throw new IllegalStateException("Unsupported Biomes O' Plenty biome " + type, ex);
        }
    }

    /** The look's BoP generators, or null for a vanilla biome. */
    static IExtendedBiome extended(Biome biome) {
        return name(biome) != null ? (IExtendedBiome) biome : null;
    }

    /**
     * BoP's climate table rebuilt from Riven's looks: for each cell of its temperature x rainfall
     * grid, the vanilla biomes BoP adds statically, then each look's own weights in BoP's
     * registration order, as BoP's registration would have appended them.
     */
    static BiomeProvision.Weighted[] climates(Set<String> excluded) {
        BiomeProvision.Weighted[] byClimate =
                new BiomeProvision.Weighted[BOPClimates.values().length];
        try {
            Field entries = BOPClimates.class.getDeclaredField("landBiomes");
            entries.setAccessible(true);
            for (BOPClimates climate : BOPClimates.values()) {
                List<Biome> biomes = new ArrayList<>();
                List<Integer> weights = new ArrayList<>();
                for (Object o : (List<?>) entries.get(climate)) {
                    BOPClimates.WeightedBiomeEntry e = (BOPClimates.WeightedBiomeEntry) o;
                    // Only BoP's static vanilla entries: registered BoP biomes come from the looks.
                    if (e.biome == null || e.weight <= 0 || e.biome.getRegistryName() == null)
                        continue;
                    if (!"minecraft".equals(e.biome.getRegistryName().getNamespace())) continue;
                    if (excluded.contains(e.biome.getRegistryName().toString())) continue;
                    biomes.add(e.biome);
                    weights.add(e.weight);
                }
                for (Map.Entry<String, Biome> look : looks().entrySet()) {
                    if (excluded.contains("biomesoplenty:" + look.getKey())) continue;
                    Integer weight = ((IExtendedBiome) look.getValue()).getWeightMap().get(climate);
                    if (weight == null || weight <= 0) continue;
                    biomes.add(look.getValue());
                    weights.add(weight);
                }
                if (!biomes.isEmpty())
                    byClimate[climate.ordinal()] = new BiomeProvision.Weighted(biomes, weights);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Biomes O' Plenty climate layout", e);
        }
        int[] mapping = BOPClimates.getClimateMappingInts();
        BiomeProvision.Weighted[] table = new BiomeProvision.Weighted[mapping.length];
        for (int i = 0; i < mapping.length; i++) table[i] = byClimate[mapping[i]];
        return table;
    }

    /**
     * Gives every BoP overworld biome ID -1 in BoP's biome_ids.json, before BoP's preInit reads it,
     * so BoP registers none of them. The names come from BoP's API at runtime; the Nether biomes
     * listed as keepRegistered stay. Other entries of the file are kept.
     */
    @SuppressWarnings(
            "deprecation") // Keep the Gson API shared with the Forge fallback, as Rules does.
    static List<String> withhold(File configDirectory, Logger log) {
        Set<String> keep = new HashSet<>();
        for (JsonElement e : RULES.json.getAsJsonArray("keepRegistered")) keep.add(e.getAsString());
        List<String> withheld = new ArrayList<>();
        for (Field f : BOPBiomes.class.getFields())
            if (f.getType() == com.google.common.base.Optional.class && !keep.contains(f.getName()))
                withheld.add(f.getName());
        File file = new File(new File(configDirectory, "biomesoplenty"), "biome_ids.json");
        try {
            JsonObject ids = new JsonObject();
            if (file.isFile())
                ids =
                        new JsonParser()
                                .parse(
                                        new String(
                                                Files.readAllBytes(file.toPath()),
                                                StandardCharsets.UTF_8))
                                .getAsJsonObject();
            for (String name : withheld) ids.addProperty(name, -1);
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(
                    file.toPath(),
                    new GsonBuilder()
                            .setPrettyPrinting()
                            .create()
                            .toJson(ids)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot withhold Biomes O' Plenty biomes in " + file, e);
        }
        log.info(
                "Withheld {} Biomes O' Plenty overworld biomes from the biome registry ({}); Riven owns the overworld",
                withheld.size(),
                file);
        return withheld;
    }

    /** Hides BoP's world type, which cannot generate without its overworld biomes. */
    static void hideWorldType(Logger log) {
        WorldType type = biomesoplenty.common.init.ModBiomes.worldTypeBOP;
        if (type == null || type.getId() < 0 || WorldType.WORLD_TYPES[type.getId()] != type) return;
        WorldType.WORLD_TYPES[type.getId()] = null;
        log.info("Hid the Biomes O' Plenty world type; its overworld biomes are withheld");
    }
}
