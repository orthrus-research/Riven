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
 * A decal on the floor under water (riven-content.json decorations, kind submerged), such as a
 * biofilm, in one of four forms by position. It is of water's material and carries water's level as
 * a still source, so the water around it neither flows into it nor draws a face against it, and
 * swimmers treat it as water. Broken, or once its ground goes, it leaves still water.
 */
final class BlockRivenSubmerged extends Block implements GroundPlant {
    private static final AxisAlignedBB BOX = new AxisAlignedBB(0, 0, 0, 1, 1 / 16.0, 1);

    private final int ground;

    BlockRivenSubmerged(int ground) {
        super(Material.WATER);
        this.ground = ground;
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

    /** Placed only into still water, on its own ground. */
    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        BlockPos below = pos.down();
        IBlockState floor = world.getBlockState(below);
        return world.getBlockState(pos) == Blocks.WATER.getDefaultState()
                && floor.isSideSolid(world, below, EnumFacing.UP)
                && growsOn(floor);
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
        BlockPos below = pos.down();
        IBlockState floor = world.getBlockState(below);
        if (!floor.isSideSolid(world, below, EnumFacing.UP) || !growsOn(floor)) {
            dropBlockAsItem(world, pos, state, 0);
            world.setBlockState(pos, Blocks.WATER.getDefaultState());
        }
    }
}
