package cc.orthrus.riven.content;

import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A soft Riven soil (riven-content.json kind soil): ground material, so decorations take it as dirt.
 * Its behaviour lets walkers sink into it (sinkBlocks off the collision top) and slows them as
 * soul sand does (slowdown, the share of motion kept).
 */
final class BlockRivenSoil extends Block {
    private final AxisAlignedBB collision;
    private final double slowdown;

    BlockRivenSoil(JsonObject behaviour) {
        super(Material.GROUND);
        setSoundType(SoundType.GROUND);
        double sink = behaviour.get("sinkBlocks").getAsDouble();
        slowdown = behaviour.get("slowdown").getAsDouble();
        if (sink < 0 || sink >= 1 || slowdown <= 0 || slowdown > 1)
            throw new IllegalStateException("Soil behaviour out of range: " + behaviour);
        collision = new AxisAlignedBB(0, 0, 0, 1, 1 - sink, 1);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public AxisAlignedBB getCollisionBoundingBox(
            IBlockState state, IBlockAccess world, BlockPos pos) {
        return collision;
    }

    @Override
    public void onEntityCollision(World world, BlockPos pos, IBlockState state, Entity entity) {
        entity.motionX *= slowdown;
        entity.motionZ *= slowdown;
    }
}
