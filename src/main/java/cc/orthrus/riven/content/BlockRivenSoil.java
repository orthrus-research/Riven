package cc.orthrus.riven.content;

import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.EnumPlantType;
import net.minecraftforge.common.IPlantable;

/**
 * A Riven soil (riven-content.json kind soil): ground material, so decorations take it as dirt, and
 * it holds what dirt holds (saplings, flowers, crops' plains plants). A behaviour, where given, lets
 * walkers sink into it (sinkBlocks off the collision top) and slows them as soul sand does
 * (slowdown, the share of motion kept), as mud does.
 */
final class BlockRivenSoil extends Block {
    private final AxisAlignedBB collision;
    private final double slowdown;

    BlockRivenSoil(JsonObject behaviour) {
        super(Material.GROUND);
        setSoundType(SoundType.GROUND);
        double sink = behaviour == null ? 0 : behaviour.get("sinkBlocks").getAsDouble();
        slowdown = behaviour == null ? 1 : behaviour.get("slowdown").getAsDouble();
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
        if (slowdown == 1) return;
        entity.motionX *= slowdown;
        entity.motionZ *= slowdown;
    }

    @Override
    public boolean canSustainPlant(
            IBlockState state,
            IBlockAccess world,
            BlockPos pos,
            EnumFacing direction,
            IPlantable plantable) {
        EnumPlantType type = plantable.getPlantType(world, pos.offset(direction));
        return type == EnumPlantType.Plains
                || super.canSustainPlant(state, world, pos, direction, plantable);
    }
}
