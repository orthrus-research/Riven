package cc.orthrus.riven.content;

import net.minecraft.block.BlockBush;
import net.minecraft.block.SoundType;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A Riven ground plant (riven-content.json decorations): crossed planes in one of four forms by
 * position, offset within its block like vanilla flowers, standing on its own grounds; replaceable
 * ones give way to placed blocks like tall grass.
 */
final class BlockRivenPlant extends BlockBush implements GroundPlant {
    private final int ground;
    private final boolean replaceable;

    BlockRivenPlant(int ground, boolean replaceable) {
        this.ground = ground;
        this.replaceable = replaceable;
        setSoundType(SoundType.PLANT);
    }

    @Override
    public boolean growsOn(IBlockState state) {
        return Grounds.on(ground, state);
    }

    @Override
    protected boolean canSustainBush(IBlockState state) {
        return growsOn(state);
    }

    // BlockBush asks the ground's canSustainPlant, which holds plains plants to grass and dirt.
    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock().isReplaceable(world, pos)
                && growsOn(world.getBlockState(pos.down()));
    }

    @Override
    public boolean canBlockStay(World world, BlockPos pos, IBlockState state) {
        return growsOn(world.getBlockState(pos.down()));
    }

    @Override
    public boolean isReplaceable(IBlockAccess world, BlockPos pos) {
        return replaceable;
    }

    @Override
    public EnumOffsetType getOffsetType() {
        return EnumOffsetType.XZ;
    }
}
