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
        event.getModLog()
                .info(
                        "Riven terrain-v"
                                + cc.orthrus.riven.worldgen.TerrainMap.VERSION
                                + " / climate-forcing-v2 / climate-structure-v1 / continental-outline-v1 / regional-fabric-v1 / base-elevation-v1 / base-terrain-v2 / terrain-regions-v1"
                                + " / continents-v10 / rocky-coasts-v1 / sedimentary-coasts-v1 / estuaries-v1 / rivers-v1 / general-surface-cuts-v1 / volcanic-v4 / cold-regions-v2 / drylands-v2 / substrate-v1 / plateaus-v1 / surface-rules-v8 / mountains-v3 / hills-v3 / lowlands-v1 / lakes-v2 / ocean-floors-v3 / hydro-v6 / shores-v4 / terrain-types-v3 / habitats-v9 / biomes-v4 / erosion-v1 / climate-v3 / temperature-v3: horizontal scale=0.5; seaLevel="
                                + cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT.seaLevel
                                + "; highestOceanWater="
                                + cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT.waterTopY
                                + "; continents="
                                + cc.orthrus.riven.worldgen.ContinentalMap.FINGERPRINT
                                + "; reference="
                                + cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT
                                        .fingerprint
                                + "; terrain="
                                + cc.orthrus.riven.worldgen.Terrain.FINGERPRINT
                                + "; shores="
                                + cc.orthrus.riven.worldgen.Shore.FINGERPRINT
                                + "; habitats="
                                + cc.orthrus.riven.worldgen.Habitats.FINGERPRINT
                                + "; decorationProvider="
                                + BiomeProvision.PROVIDER
                                + (BiomeProvision.active() ? " (loaded) " : " (absent) ")
                                + BiomeProvision.FINGERPRINT
                                + "; hydro="
                                + cc.orthrus.riven.worldgen.Hydro.FINGERPRINT
                                + "; oceanFloors="
                                + cc.orthrus.riven.worldgen.OceanBed.FINGERPRINT
                                + "; lakes="
                                + cc.orthrus.riven.worldgen.LakeShape.FINGERPRINT
                                + "; rivers="
                                + cc.orthrus.riven.worldgen.RiverShape.FINGERPRINT
                                + "; estuaries="
                                + cc.orthrus.riven.worldgen.CoastShape.FINGERPRINT
                                + "; sedimentaryCoasts="
                                + cc.orthrus.riven.worldgen.SedimentTerrain.FINGERPRINT
                                + "; rockyCoasts="
                                + cc.orthrus.riven.worldgen.RockyTerrain.FINGERPRINT
                                + "; lowlands="
                                + cc.orthrus.riven.worldgen.LowlandTerrain.FINGERPRINT
                                + "; hills="
                                + cc.orthrus.riven.worldgen.HillTerrain.FINGERPRINT
                                + "; skewHillHeadingPolicy="
                                + cc.orthrus.riven.worldgen.HillTerrain.configuredHeadingPolicy()
                                + "; regionalFabric="
                                + cc.orthrus.riven.worldgen.RegionalFabricMap.FINGERPRINT
                                + "; mountains="
                                + cc.orthrus.riven.worldgen.MountainTerrain.FINGERPRINT
                                + "; plateaus="
                                + cc.orthrus.riven.worldgen.PlateauTerrain.FINGERPRINT
                                + "; drylands="
                                + cc.orthrus.riven.worldgen.DrylandTerrain.FINGERPRINT
                                + "; substrate="
                                + cc.orthrus.riven.worldgen.SubstrateMap.FINGERPRINT
                                + "; baseTerrain="
                                + cc.orthrus.riven.worldgen.BaseTerrainMap.FINGERPRINT
                                + "; baseElevation="
                                + cc.orthrus.riven.worldgen.BaseElevationMap.FINGERPRINT
                                + "; climateForcing="
                                + cc.orthrus.riven.worldgen.ClimateForcingMap.FINGERPRINT
                                + "; baseTerrainPolicy="
                                + cc.orthrus.riven.worldgen.BaseTerrainMap.configuredPolicy()
                                + "; terrainRegions="
                                + cc.orthrus.riven.worldgen.RegionalProfileMap.FINGERPRINT
                                + "; coldRegions="
                                + cc.orthrus.riven.worldgen.ColdTerrain.FINGERPRINT
                                + "; volcanic="
                                + cc.orthrus.riven.worldgen.VolcanicTerrain.FINGERPRINT
                                + "; generalSurfaceCuts="
                                + cc.orthrus.riven.worldgen.GeneralSurfaceCuts.FINGERPRINT
                                + "; wonders="
                                + cc.orthrus.riven.worldgen.WonderTerrain.FINGERPRINT
                                + "; geology="
                                + cc.orthrus.riven.worldgen.GeologyMap.FINGERPRINT
                                + "; caves="
                                + cc.orthrus.riven.worldgen.CaveMap.FINGERPRINT
                                + "; ores="
                                + cc.orthrus.riven.worldgen.OreMap.FINGERPRINT
                                + "; caveDecor="
                                + cc.orthrus.riven.worldgen.CaveMap.DECOR_FINGERPRINT
                                + "; boulders="
                                + cc.orthrus.riven.worldgen.Boulders.FINGERPRINT
                                + "; trees="
                                + TreeSites.FINGERPRINT
                                + "; groundCover="
                                + GroundCover.FINGERPRINT
                                + "; biomeRegistry="
                                + cc.orthrus.riven.worldgen.RivenBiomeTable.FINGERPRINT
                                + "; mappings="
                                + Mappings.FINGERPRINT
                                + "; content="
                                + cc.orthrus.riven.content.RivenContent.FINGERPRINT
                                + "; surfaceRules="
                                + cc.orthrus.riven.worldgen.SurfaceRules.FINGERPRINT
                                + "; passes="
                                + cc.orthrus.riven.worldgen.RegionalTerrainPasses.FINGERPRINT);
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
