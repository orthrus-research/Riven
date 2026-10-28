package cc.orthrus.riven.content;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Random;

/** Grows a wood's tree from its sapling at a position, replacing it; false when none fits. */
public interface TreeGrower {
    boolean grow(World world, BlockPos pos, String wood, Random random);
}
