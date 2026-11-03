package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A plant hanging from a ceiling (riven-content.json decorations, kind hanging): crossed planes in
 * one of four forms by position under a block of its own grounds, such as roots under a root mat.
 * It has no collision, placed blocks replace it, and it drops when its ceiling goes.
 */
final class BlockRivenHanging extends Block implements GroundPlant {
    private static final AxisAlignedBB BOX = new AxisAlignedBB(0.1, 0.2, 0.1, 0.9, 1, 0.9);

    final boolean flammable;
    private final int ground;

    BlockRivenHanging(int ground, boolean flammable) {
        super(Material.VINE);
        this.ground = ground;
        this.flammable = flammable;
        setSoundType(SoundType.PLANT);
    }

    /** Whether it can hang from this ceiling block. */
    @Override
    public boolean growsOn(IBlockState state) {
        return Grounds.on(ground, state);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return BOX;
    }

    @SuppressWarnings("deprecation")
    @Override
    public AxisAlignedBB getCollisionBoundingBox(
            IBlockState state, IBlockAccess world, BlockPos pos) {
        return NULL_AABB;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isReplaceable(IBlockAccess world, BlockPos pos) {
        return true;
    }

    @SuppressWarnings("deprecation")
    @Override
    public BlockFaceShape getBlockFaceShape(
            IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }

    @Override
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return super.canPlaceBlockAt(world, pos) && supported(world, pos);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void neighborChanged(
            IBlockState state, World world, BlockPos pos, Block block, BlockPos from) {
        if (!supported(world, pos)) {
            dropBlockAsItem(world, pos, state, 0);
            world.setBlockToAir(pos);
        }
    }

    private boolean supported(World world, BlockPos pos) {
        BlockPos above = pos.up();
        IBlockState ceiling = world.getBlockState(above);
        return ceiling.isSideSolid(world, above, EnumFacing.DOWN) && growsOn(ceiling);
    }
}
