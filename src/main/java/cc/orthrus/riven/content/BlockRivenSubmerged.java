package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A plant under water (riven-content.json decorations, kind submerged): a decal on the floor, such
 * as a biofilm, or an upright plant (model cross), such as a waterweed, a coral or a piece of a kelp
 * stack, in one of four forms by position. It is of water's material and carries water's level as
 * a still source, so the water around it neither flows into it nor draws a face against it, and
 * swimmers treat it as water. Broken, or once its ground goes, it leaves still water.
 */
final class BlockRivenSubmerged extends Block implements SubmergedPlant {
    private static final AxisAlignedBB DECAL = new AxisAlignedBB(0, 0, 0, 1, 1 / 16.0, 1),
            UPRIGHT = new AxisAlignedBB(0.125, 0, 0.125, 0.875, 0.875, 0.875);

    private final int ground;
    private final boolean upright;

    BlockRivenSubmerged(int ground, boolean upright) {
        super(Material.WATER);
        this.ground = ground;
        this.upright = upright;
        setSoundType(SoundType.PLANT);
        setLightOpacity(3);
        setDefaultState(blockState.getBaseState().withProperty(BlockLiquid.LEVEL, 0));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, BlockLiquid.LEVEL);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState();
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return 0;
    }

    @Override
    public boolean growsOn(IBlockState state) {
        return Grounds.on(ground, state) && state.isFullCube();
    }

    @SuppressWarnings("deprecation")
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return upright ? UPRIGHT : DECAL;
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

    /** Placed only into still water, on its own ground or on an upright piece of a stack. */
    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return world.getBlockState(pos) == Blocks.WATER.getDefaultState() && supported(world, pos);
    }

    /** Its ground below, or for an upright plant the upright piece it stacks on. */
    private boolean supported(World world, BlockPos pos) {
        BlockPos below = pos.down();
        IBlockState floor = world.getBlockState(below);
        if (upright && floor.getBlock() instanceof BlockRivenSubmerged)
            return ((BlockRivenSubmerged) floor.getBlock()).upright;
        return floor.isSideSolid(world, below, EnumFacing.UP) && growsOn(floor);
    }

    @Override
    public boolean removedByPlayer(
            IBlockState state, World world, BlockPos pos, EntityPlayer player, boolean harvest) {
        onBlockHarvested(world, pos, state, player);
        return world.setBlockState(pos, Blocks.WATER.getDefaultState(), world.isRemote ? 11 : 3);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void neighborChanged(
            IBlockState state, World world, BlockPos pos, Block block, BlockPos from) {
        if (!supported(world, pos)) {
            dropBlockAsItem(world, pos, state, 0);
            world.setBlockState(pos, Blocks.WATER.getDefaultState());
        }
    }
}
