package cc.orthrus.riven;

import cc.orthrus.riven.content.BlockRivenOre;
import cc.orthrus.riven.content.RivenContent;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.OreMap;
import cc.orthrus.riven.worldgen.TerrainBlock;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minecraft states for Riven's ore minerals ({@link OreMap}). With GTCEu each mineral is its GTCEu
 * ore in the host rock's stone type; without it, the vanilla ore riven-ores.json names, or the
 * rock itself. Ore replaces only rock blocks.
 */
final class OreBlocks {
    private static final Logger LOG = LogManager.getLogger("Riven");
    private static volatile IBlockState[][] table;

    private OreBlocks() {}

    /** The state at a column block: its ore where the rock holds one, else {@code rock}. */
    static IBlockState at(
            NativeColumns.Column column,
            int index,
            int x,
            int y,
            int z,
            TerrainBlock block,
            IBlockState rock) {
        if (!host(block)) return rock;
        int mineral = column.ore(index, x, y, z);
        if (mineral < 0) return rock;
        IBlockState[][] t = table;
        if (t == null) t = build();
        IBlockState ore = t[mineral][block.ordinal()];
        return ore != null ? ore : rock;
    }

    private static boolean host(TerrainBlock block) {
        if (block.stoneId() != null) return true;
        switch (block) {
            case STONE:
            case GRANITE:
            case DIORITE:
            case ANDESITE:
            case SANDSTONE:
            case RED_SANDSTONE:
            case SAND:
            case GRAVEL:
                return true;
            default:
                return false;
        }
    }

    private static synchronized IBlockState[][] build() {
        if (table != null) return table;
        boolean gregtech = Loader.isModLoaded("gregtech");
        TerrainBlock[] blocks = TerrainBlock.values();
        IBlockState[][] result = new IBlockState[OreMap.MINERALS.length][blocks.length];
        List<String> fallbacks = new ArrayList<>();
        for (int m = 0; m < OreMap.MINERALS.length; m++) {
            // A seam or rock body is its Riven block in any host.
            if (OreMap.BLOCK[m] != null) {
                Block body = Block.REGISTRY.getObject(new ResourceLocation(OreMap.BLOCK[m]));
                if (body == Blocks.AIR)
                    throw new IllegalStateException("Missing " + OreMap.BLOCK[m]);
                for (TerrainBlock b : blocks)
                    if (host(b)) result[m][b.ordinal()] = body.getDefaultState();
                continue;
            }
            Map<String, IBlockState> byStone =
                    gregtech ? GregTechOres.byStone(OreMap.MINERALS[m]) : null;
            if (gregtech && byStone == null) fallbacks.add(OreMap.MINERALS[m]);
            IBlockState vanilla = vanilla(OreMap.VANILLA[m]);
            BlockRivenOre riven = RivenContent.ore(OreMap.MINERALS[m]);
            for (TerrainBlock b : blocks) {
                if (!host(b)) continue;
                IBlockState ore = null;
                // Riven's host stones carry Riven's own ore blocks.
                int hostIndex = b.stoneId() == null ? -1 : RivenContent.hostIndex(b.stoneId());
                if (riven != null && hostIndex >= 0)
                    ore = riven.getDefaultState().withProperty(BlockRivenOre.HOST, hostIndex);
                if (ore == null && byStone != null) {
                    ore = byStone.get(stoneType(b));
                    if (ore == null) ore = byStone.get("stone");
                }
                result[m][b.ordinal()] = ore != null ? ore : vanilla;
            }
        }
        LOG.info(
                "Riven ores: {} minerals from {}{}",
                OreMap.MINERALS.length,
                gregtech ? "GTCEu" : "vanilla",
                fallbacks.isEmpty() ? "" : "; vanilla fallback for " + fallbacks);
        table = result;
        return result;
    }

    /** GTCEu's stone type name for a host block; stone where GTCEu has none. */
    private static String stoneType(TerrainBlock block) {
        switch (block) {
            case GRANITE:
                return "granite";
            case DIORITE:
                return "diorite";
            case ANDESITE:
                return "andesite";
            case SAND:
                return "sand";
            case GRAVEL:
                return "gravel";
            default:
                return "stone";
        }
    }

    private static IBlockState vanilla(String name) {
        switch (name) {
            case "COAL":
                return Blocks.COAL_ORE.getDefaultState();
            case "IRON":
                return Blocks.IRON_ORE.getDefaultState();
            case "GOLD":
                return Blocks.GOLD_ORE.getDefaultState();
            case "REDSTONE":
                return Blocks.REDSTONE_ORE.getDefaultState();
            case "LAPIS":
                return Blocks.LAPIS_ORE.getDefaultState();
            case "DIAMOND":
                return Blocks.DIAMOND_ORE.getDefaultState();
            case "EMERALD":
                return Blocks.EMERALD_ORE.getDefaultState();
            case "NONE":
                return null;
            default:
                throw new IllegalStateException("Vanilla ore: " + name);
        }
    }
}
