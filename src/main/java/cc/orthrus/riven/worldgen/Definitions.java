package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every packaged definition, src/main/resources/riven-*.json. {@link Rules#load} reads only these;
 * Riven loads all of them at startup and logs each revision and fingerprint, the atlas records the
 * same fingerprints, and tools/checks/DefinitionsCheck keeps this list equal to the packaged files.
 */
public final class Definitions {
    private static final List<String> RESOURCES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "riven-base-elevation.json",
                            "riven-base-terrain.json",
                            "riven-biome-registry.json",
                            "riven-biomes.json",
                            "riven-bop.json",
                            "riven-boulders.json",
                            "riven-cave-decor.json",
                            "riven-caves.json",
                            "riven-climate-forcing.json",
                            "riven-climate.json",
                            "riven-cold-regions.json",
                            "riven-compat-biomesoplenty.json",
                            "riven-content.json",
                            "riven-continents.json",
                            "riven-drylands.json",
                            "riven-estuaries.json",
                            "riven-general-surface-cuts.json",
                            "riven-geology.json",
                            "riven-ground-cover.json",
                            "riven-habitats.json",
                            "riven-hills.json",
                            "riven-hydrology.json",
                            "riven-lakes.json",
                            "riven-landmarks.json",
                            "riven-lowlands.json",
                            "riven-mappings.json",
                            "riven-materials.json",
                            "riven-mountains.json",
                            "riven-ocean-floors.json",
                            "riven-ores.json",
                            "riven-plateaus.json",
                            "riven-player-effects.json",
                            "riven-realms.json",
                            "riven-regional-fabric.json",
                            "riven-rivers.json",
                            "riven-rocky-coasts.json",
                            "riven-sedimentary-coasts.json",
                            "riven-shaderprops.json",
                            "riven-shores.json",
                            "riven-substrate.json",
                            "riven-subsurface-climate.json",
                            "riven-surface-carvers.json",
                            "riven-surface-rules.json",
                            "riven-terrain-height-reference.json",
                            "riven-terrain-passes.json",
                            "riven-terrain-regions.json",
                            "riven-terrain.json",
                            "riven-trees.json",
                            "riven-volcanic.json",
                            "riven-wonders.json"));

    private Definitions() {}

    /** The packaged definitions' resource names, sorted. */
    public static List<String> resources() {
        return RESOURCES;
    }

    static boolean contains(String resource) {
        return RESOURCES.contains(resource);
    }

    /** Every definition loaded once, in catalogue order: parsed, schema-checked, fingerprinted. */
    public static List<Rules> loadAll() {
        List<Rules> all = new ArrayList<>();
        for (String resource : RESOURCES) all.add(Rules.load(resource, 1));
        return all;
    }
}
