package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockGravel;
import net.minecraft.block.BlockRedSandstone;
import net.minecraft.block.BlockSand;
import net.minecraft.block.BlockSandStone;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;

/**
 * The grounds a decoration stands on, as a bit mask over named kinds: grass, dirt (any ground
 * material, so mud and the provider's dirts), sand, gravel, snow (a snow block), stone (any rock
 * material) and sandstone (either colour).
 */
public final class Grounds {
    private static final String[] KINDS = {
        "grass", "dirt", "sand", "gravel", "snow", "stone", "sandstone"
    };

    private Grounds() {}

    /** The mask of a list of kind names. */
    public static int mask(Iterable<String> kinds) {
        int mask = 0;
        for (String kind : kinds) {
            int i = java.util.Arrays.asList(KINDS).indexOf(kind);
            if (i < 0) throw new IllegalStateException("Unknown ground " + kind);
            mask |= 1 << i;
        }
        return mask;
    }

    /** Whether a ground block is of a kind in the mask. */
    public static boolean on(int mask, IBlockState state) {
        Block block = state.getBlock();
        Material m = state.getMaterial();
        return ((mask & 1) != 0 && (block instanceof BlockGrass || m == Material.GRASS))
                || ((mask & 2) != 0 && (block instanceof BlockDirt || m == Material.GROUND))
                || ((mask & 4) != 0 && block instanceof BlockSand)
                || ((mask & 8) != 0 && block instanceof BlockGravel)
                || ((mask & 16) != 0 && m == Material.CRAFTED_SNOW)
                || ((mask & 32) != 0 && m == Material.ROCK)
                || ((mask & 64) != 0
                        && (block instanceof BlockSandStone || block instanceof BlockRedSandstone));
    }
}
