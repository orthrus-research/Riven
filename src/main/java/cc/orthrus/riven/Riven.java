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
        dependencies = "required-after:cubicchunks",
        acceptableRemoteVersions = "*")
public final class Riven {
    @Mod.EventHandler
    public void construct(net.minecraftforge.fml.common.event.FMLConstructionEvent event) {
        // GTCEu posts its material events in its own preInit, which may run before Riven's.
        if (net.minecraftforge.fml.common.Loader.isModLoaded("gregtech"))
            MinecraftForge.EVENT_BUS.register(cc.orthrus.riven.content.GregTechMaterials.class);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        net.minecraftforge.common.config.Configuration config =
                new net.minecraftforge.common.config.Configuration(
                        event.getSuggestedConfigurationFile());
        PlayerEffects.enabled =
                config.getBoolean(
                        "playerEffects",
                        "effects",
                        true,
                        "Apply the subsurface climate's effects to players underground in Riven worlds. The effects themselves are in config/riven/player-effects.json (written from Riven's defaults on first run; /riven effects reload rereads it). Server side.");
        if (config.hasChanged()) config.save();
        RivenNetwork.init();
        new RivenWorldType();
        cc.orthrus.riven.content.RivenContent.treeGrower(TreeGrowth::grow);
        MinecraftForge.EVENT_BUS.register(RivenSpawn.class);
        MinecraftForge.EVENT_BUS.register(PlayerEffects.class);
        // Every packaged definition loads here, so a malformed one fails at startup; the log names
        // each with its revision and fingerprint (Definitions).
        org.apache.logging.log4j.Logger log = event.getModLog();
        log.info(
                "Riven terrain-v{}: horizontal scale={}; seaLevel={}; highestOceanWater={};"
                        + " baseTerrainPolicy={}; skewHillHeadingPolicy={}",
                cc.orthrus.riven.worldgen.TerrainMap.VERSION,
                cc.orthrus.riven.worldgen.TerrainScale.HORIZONTAL,
                cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT.seaLevel,
                cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT.waterTopY,
                cc.orthrus.riven.worldgen.BaseTerrainMap.configuredPolicy(),
                cc.orthrus.riven.worldgen.HillTerrain.configuredHeadingPolicy());
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
        // Every block is registered by now: resolve the catalogue and prove it at startup.
        org.apache.logging.log4j.LogManager.getLogger("riven")
                .info(TerrainColumn.catalogueSummary());
        if (net.minecraftforge.fml.common.Loader.isModLoaded("gregtech"))
            org.apache.logging.log4j.LogManager.getLogger("riven")
                    .info(GregTechOres.unification(cc.orthrus.riven.content.RivenContent.blocks()));
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // At server start every mod's potions are registered, so the copy may name them.
        PlayerEffects.init(net.minecraftforge.fml.common.Loader.instance().getConfigDir());
        event.registerServerCommand(new RivenCommand());
    }
}
