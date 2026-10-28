package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RivenBiomeTable;

import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registers Riven's biomes (riven-biome-registry.json) and answers which one a habitat key
 * reports. They are never added to vanilla world types; only Riven worlds report them.
 */
@Mod.EventBusSubscriber(modid = "riven")
final class RivenBiomes {
    private RivenBiomes() {}

    /** Riven's biomes in definition order, once registered. */
    private static volatile Biome[] biomes;

    @SubscribeEvent
    public static void register(RegistryEvent.Register<Biome> event) {
        List<RivenBiomeTable.Definition> definitions = RivenBiomeTable.definitions();
        Biome[] created = new Biome[definitions.size()];
        for (RivenBiomeTable.Definition d : definitions) {
            Biome biome = RivenBiomeTypes.create(d);
            biome.setRegistryName("riven", d.id);
            event.getRegistry().register(biome);
            for (String type : d.types) BiomeDictionary.addTypes(biome, type(type));
            created[d.index] = biome;
        }
        biomes = created;
    }

    private static BiomeDictionary.Type type(String name) {
        // Unknown names create a type, as BiomeDictionary.Type.getType does for mods.
        return BiomeDictionary.Type.getType(name);
    }

    private static Biome[] all() {
        Biome[] b = biomes;
        if (b == null) throw new IllegalStateException("Riven biomes are not registered yet");
        return b;
    }

    /** The biome a habitat key reports. */
    static Biome select(String key) {
        return all()[RivenBiomeTable.select(key).index];
    }

    static Biome of(RivenBiomeTable.Definition d) {
        return all()[d.index];
    }

    static List<Biome> list() {
        return Collections.unmodifiableList(java.util.Arrays.asList(all()));
    }

    /** The biomes inland keys report, for spawn search. */
    static List<Biome> inland() {
        List<Biome> result = new ArrayList<>();
        for (String id : RivenBiomeTable.inland()) result.add(of(RivenBiomeTable.byId(id)));
        return Collections.unmodifiableList(result);
    }

    /** Applies a snapshot's colour overrides by biome id; absent ids keep their defaults. */
    static void applyColours(Map<String, int[]> colours) {
        for (Biome biome : all()) {
            RivenBiomeTypes.RivenBiome riven = (RivenBiomeTypes.RivenBiome) biome;
            int[] c = colours.get(riven.definition().id);
            riven.colours().grass = c == null ? -1 : c[0];
            riven.colours().foliage = c == null ? -1 : c[1];
        }
    }
}
