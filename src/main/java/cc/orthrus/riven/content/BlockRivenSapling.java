package cc.orthrus.riven.content;

import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockSand;
import net.minecraft.block.IGrowable;
import net.minecraft.block.SoundType;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.Random;

/**
 * A Riven species' sapling (riven-content.json woods). It grows in two stages as vanilla saplings
 * do, and its tree comes from {@link #grower} (Riven's tree shapes); palm saplings also take sand.
 */
final class BlockRivenSapling extends BlockBush implements IGrowable {
    static final PropertyInteger STAGE = PropertyInteger.create("stage", 0, 1);
    private static final AxisAlignedBB BOX = new AxisAlignedBB(.1, 0, .1, .9, .8, .9);

    static volatile TreeGrower grower;

    final String species;
    private final boolean sand;

    BlockRivenSapling(String species, boolean sand) {
        this.species = species;
        this.sand = sand;
        setSoundType(SoundType.PLANT);
        setDefaultState(blockState.getBaseState().withProperty(STAGE, 0));
    }

    /** A palm sapling on sand, which Forge's plains plants refuse. */
    private boolean onSand(World world, BlockPos pos) {
        return sand && world.getBlockState(pos.down()).getBlock() instanceof BlockSand;
    }

    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return super.canPlaceBlockAt(world, pos)
                || (onSand(world, pos)
                        && world.getBlockState(pos).getBlock().isReplaceable(world, pos));
    }

    @Override
    public boolean canBlockStay(World world, BlockPos pos, IBlockState state) {
        return super.canBlockStay(world, pos, state) || onSand(world, pos);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, STAGE);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(STAGE, meta & 1);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(STAGE);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return BOX;
    }

    @Override
    public void updateTick(World world, BlockPos pos, IBlockState state, Random random) {
        if (world.isRemote) return;
        super.updateTick(world, pos, state, random);
        if (world.getLightFromNeighbors(pos.up()) >= 9 && random.nextInt(7) == 0)
            grow(world, random, pos, state);
    }

    @Override
    public boolean canGrow(World world, BlockPos pos, IBlockState state, boolean client) {
        return grower != null;
    }

    @Override
    public boolean canUseBonemeal(World world, Random random, BlockPos pos, IBlockState state) {
        return grower != null && world.rand.nextFloat() < .45f;
    }

    @Override
    public void grow(World world, Random random, BlockPos pos, IBlockState state) {
        TreeGrower g = grower;
        if (g == null) return;
        if (state.getValue(STAGE) == 0) world.setBlockState(pos, state.withProperty(STAGE, 1), 4);
        else g.grow(world, pos, species, random);
    }
}
