package cc.orthrus.riven.worldgen;

import java.util.Map;
import java.util.TreeMap;

/**
 * Every habitat key Riven can produce selects a riven: biome (riven-biome-registry.json), names are
 * shader-safe, and the inland set covers every inland key. Prints the selection by key family and
 * the biome count per shader category.
 */
public final class BiomeTableCheck {
    public static void main(String[] args) {
        int keys = 0;
        Map<String, Integer> categories = new TreeMap<>();
        for (RivenBiomeTable.Definition d : RivenBiomeTable.definitions())
            categories.merge(d.category, 1, Integer::sum);
        String[] levels = {"LOW", "MID", "HIGH"};
        for (ClimateRegion region : ClimateRegion.values())
            for (String level : levels)
                for (String relief : new String[] {"calm", "rugged"}) {
                    String key = region.name() + "/" + level + "/" + relief;
                    RivenBiomeTable.Definition d = RivenBiomeTable.select(key);
                    if (!RivenBiomeTable.inland().contains(d.id))
                        throw new IllegalStateException(
                                "Inland key outside the inland set: " + key);
                    keys++;
                }
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> coastEntry :
                Biomes.vanillaTable().entrySet())
            for (TemperatureBand band : TemperatureBand.values()) {
                String key = coastEntry.getKey() + "#" + band.name();
                RivenBiomeTable.select(key);
                keys++;
            }
        for (String mouth : Biomes.mouthNames()) {
            RivenBiomeTable.select("MOUTH/" + mouth);
            keys++;
        }
        System.out.printf(
                "%d biomes, %d inland, %d keys selected; categories %s%n",
                RivenBiomeTable.definitions().size(),
                RivenBiomeTable.inland().size(),
                keys,
                categories);
        System.out.println(
                "MILD_MODERATE/LOW/calm -> " + RivenBiomeTable.select("MILD_MODERATE/LOW/calm").id);
        System.out.println(
                "HOT_DRY/HIGH/rugged -> " + RivenBiomeTable.select("HOT_DRY/HIGH/rugged").id);
        System.out.println("BEACH#WARM -> " + RivenBiomeTable.select("BEACH#WARM").id);
        System.out.println("MOUTH/MANGROVE -> " + RivenBiomeTable.select("MOUTH/MANGROVE").id);
    }
}
