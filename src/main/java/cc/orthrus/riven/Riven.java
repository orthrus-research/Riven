package cc.orthrus.riven;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/** Registers Riven at the earliest generation entry point. */
@Mod(
        modid = "riven",
        name = "Riven",
        version = "0.0.1",
        // Before Biomes O' Plenty, so Riven withholds its overworld biomes before BoP registers
        // them.
        dependencies = "required-after:cubicchunks;before:biomesoplenty",
        acceptableRemoteVersions = "*")
public final class Riven {
    /** Whether Riven withholds the decoration provider's overworld biomes (config/riven.cfg). */
    static boolean withholdProviderBiomes = true;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        net.minecraftforge.common.config.Configuration config =
                new net.minecraftforge.common.config.Configuration(
                        event.getSuggestedConfigurationFile());
        withholdProviderBiomes =
                config.getBoolean(
                        "withholdProviderBiomes",
                        "biomes",
                        true,
                        "Withhold Biomes O' Plenty's overworld biomes from the biome registry (BoP's own biome_ids.json switch). Riven reports its own riven: biomes; BoP keeps its blocks, saplings and Nether biomes. Applies to the whole installation: client and server must match.");
        PlayerEffects.enabled =
                config.getBoolean(
                        "playerEffects",
                        "effects",
                        true,
                        "Apply the subsurface climate's effects to players underground in Riven worlds. The effects themselves are in config/riven/player-effects.json (written from Riven's defaults on first run; /riven effects reload rereads it). Server side.");
        if (config.hasChanged()) config.save();
        if (withholdProviderBiomes && BiomeProvision.active())
            BopLooks.withhold(event.getModConfigurationDirectory(), event.getModLog());
        RivenNetwork.init();
        new RivenWorldType();
        cc.orthrus.riven.content.RivenContent.treeGrower(TreeGrowth::grow);
        MinecraftForge.EVENT_BUS.register(RivenSpawn.class);
        MinecraftForge.EVENT_BUS.register(PlayerEffects.class);
        MinecraftForge.TERRAIN_GEN_BUS.register(DecorationFilter.class);
        MinecraftForge.ORE_GEN_BUS.register(DecorationFilter.class);
        // Cleanroom posts terrain-generation events on the main bus; Forge on its own buses.
        MinecraftForge.EVENT_BUS.register(DecorationFilter.class);
        // Every packaged definition loads here, so a malformed one fails at startup; the log names
        // each with its revision and fingerprint (Definitions).
        org.apache.logging.log4j.Logger log = event.getModLog();
        log.info(
                "Riven terrain-v{}: horizontal scale={}; seaLevel={}; highestOceanWater={};"
                        + " baseTerrainPolicy={}",
                cc.orthrus.riven.worldgen.TerrainMap.VERSION,
                cc.orthrus.riven.worldgen.TerrainScale.HORIZONTAL,
                cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT.seaLevel,
                cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT.waterTopY,
                cc.orthrus.riven.worldgen.BaseTerrainMap.configuredPolicy());
        for (cc.orthrus.riven.worldgen.Rules definition :
                cc.orthrus.riven.worldgen.Definitions.loadAll())
            log.info(
                    "Riven definition {} revision {} sha256 {}",
                    definition.resource,
                    definition.revision() == null ? "-" : definition.revision(),
                    definition.fingerprint);
    }

    @Mod.EventHandler
    public void postInit(net.minecraftforge.fml.common.event.FMLPostInitializationEvent event) {
        if (withholdProviderBiomes && BiomeProvision.active())
            BopLooks.hideWorldType(org.apache.logging.log4j.LogManager.getLogger("riven"));
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // At server start every mod's potions are registered, so the copy may name them.
        PlayerEffects.init(net.minecraftforge.fml.common.Loader.instance().getConfigDir());
        event.registerServerCommand(new RivenCommand());
    }
}
