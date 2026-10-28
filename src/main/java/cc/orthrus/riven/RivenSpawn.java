package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.ContinentalOutlineMap;
import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.TerrainMap;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Chooses the initial spawn on dry land from Riven's own maps. Vanilla searches 256 blocks for a
 * spawn biome; an ocean origin then leaves CC to place the player on the seabed.
 */
final class RivenSpawn {
    private static final Logger LOG = LogManager.getLogger("riven");

    /** Rings grow by this step; CC's spawn fuzz stays inside the checked margin. */
    private static final int STEP = 64, MARGIN = 16, MAX_RADIUS = 16384;

    @SubscribeEvent
    public static void createSpawnPosition(WorldEvent.CreateSpawnPosition event) {
        World world = event.getWorld();
        if (world.isRemote
                || world.provider.getDimension() != 0
                || !(world.getBiomeProvider() instanceof ClimateBiomeProvider)) return;
        ClimateBiomeProvider biomes = (ClimateBiomeProvider) world.getBiomeProvider();
        BlockPos spawn = find(biomes);
        if (spawn == null) {
            LOG.warn("No dry Riven spawn within {} blocks; using the vanilla search", MAX_RADIUS);
            return;
        }
        world.getWorldInfo().setSpawn(spawn);
        event.setCanceled(true);
        LOG.info("Riven spawn on dry land at {}", spawn);
    }

    /** Deterministic outward square rings from the origin; the first dry candidate wins. */
    static BlockPos find(ClimateBiomeProvider biomes) {
        Probe probe = new Probe(biomes);
        if (probe.dry(0, 0)) return probe.spawn(0, 0);
        for (int r = STEP; r <= MAX_RADIUS; r += STEP)
            for (int i = -r; i < r; i += STEP) {
                if (probe.dry(i, -r)) return probe.spawn(i, -r);
                if (probe.dry(r, i)) return probe.spawn(r, i);
                if (probe.dry(-i, r)) return probe.spawn(-i, r);
                if (probe.dry(-r, -i)) return probe.spawn(-r, -i);
            }
        return null;
    }

    private static final class Probe {
        private final ClimateBiomeProvider biomes;
        private final GenerationMaps maps;
        private final int waterTopY;
        private final ContinentalOutlineMap.Sample outline = new ContinentalOutlineMap.Sample();
        private final TerrainMap.Sample terrain = new TerrainMap.Sample();

        Probe(ClimateBiomeProvider biomes) {
            this.biomes = biomes;
            maps = biomes.maps;
            waterTopY = biomes.context.terrainHeights.waterTopY;
        }

        /** The column and its fuzz margin are land above ocean water without surface water. */
        boolean dry(int x, int z) {
            maps.outline.sample(x, z, outline);
            if (outline.marine()) return false;
            if (!biomes.getBiomesToSpawnIn().contains(biomes.at(x, z))) return false;
            return column(x, z)
                    && column(x - MARGIN, z - MARGIN)
                    && column(x + MARGIN, z - MARGIN)
                    && column(x - MARGIN, z + MARGIN)
                    && column(x + MARGIN, z + MARGIN);
        }

        private boolean column(int x, int z) {
            maps.terrain.sample(x, z, terrain);
            return !terrain.ocean.marine
                    && terrain.blockY() > waterTopY
                    && terrain.waterY() <= terrain.blockY();
        }

        BlockPos spawn(int x, int z) {
            maps.terrain.sample(x, z, terrain);
            return new BlockPos(x, terrain.blockY() + 1, z);
        }
    }
}
