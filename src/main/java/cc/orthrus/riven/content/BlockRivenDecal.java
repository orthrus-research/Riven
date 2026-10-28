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
 * A thin decal on the ground (riven-content.json): a wood's leaf litter, or a decoration decal such
 * as moss, lichen or pebbles in one of four forms by position. It has no collision, placed blocks
 * replace it, and it drops when its ground goes; a decoration decal also needs its own grounds.
 */
final class BlockRivenDecal extends Block implements GroundPlant {
    private static final AxisAlignedBB BOX = new AxisAlignedBB(0, 0, 0, 1, 1 / 16.0, 1);

    /** Any solid ground, for wood litter. */
    static final int ANY = -1;

    final String subject;
    final boolean flammable;
    private final int ground;

    BlockRivenDecal(String subject, int ground, boolean flammable) {
        super(Material.PLANTS);
        this.subject = subject;
        this.ground = ground;
        this.flammable = flammable;
        setSoundType(SoundType.PLANT);
    }

    @Override
    public boolean growsOn(IBlockState state) {
        return (ground == ANY || Grounds.on(ground, state)) && state.isFullCube();
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
        BlockPos below = pos.down();
        IBlockState ground = world.getBlockState(below);
        return ground.isSideSolid(world, below, EnumFacing.UP)
                && (this.ground == ANY || Grounds.on(this.ground, ground));
    }
}
