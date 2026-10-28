package cc.orthrus.riven;

import cc.orthrus.riven.content.BlockUnstableStone;
import cc.orthrus.riven.content.RivenContent;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.TerrainBlock;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;

/**
 * Minecraft states for unstable floors ({@link NativeColumns.Column#unstable}): each host rock's
 * own unstable stone (riven-content.json unstable). Unstable stone replaces only its host rock;
 * any other block in an unstable floor stays.
 */
final class UnstableBlocks {
    private static volatile IBlockState[] table;

    private UnstableBlocks() {}

    /** The state at a column block: its unstable stone where the floor is unstable, else rock. */
    static IBlockState at(
            NativeColumns.Column column, int index, int y, TerrainBlock block, IBlockState rock) {
        if (!column.unstable(index, y)) return rock;
        IBlockState[] t = table;
        if (t == null) t = build();
        IBlockState unstable = t[block.ordinal()];
        return unstable != null ? unstable : rock;
    }

    private static synchronized IBlockState[] build() {
        if (table != null) return table;
        IBlockState[] result = new IBlockState[TerrainBlock.values().length];
        for (Block b : RivenContent.blocks())
            if (b instanceof BlockUnstableStone)
                result[((BlockUnstableStone) b).terrain.ordinal()] = b.getDefaultState();
        table = result;
        return result;
    }
}
