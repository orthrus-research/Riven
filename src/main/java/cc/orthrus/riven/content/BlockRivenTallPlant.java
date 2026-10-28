package cc.orthrus.riven.content;

import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.SoundType;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * A Riven two-block plant (riven-content.json decorations): a bottom and a top piece of one of four
 * forms that join, standing on its own grounds. Breaking either half takes the plant, which drops
 * once; a placed plant takes its form from its position.
 */
final class BlockRivenTallPlant extends BlockBush implements GroundPlant.Tall {
    static final PropertyEnum<BlockDoublePlant.EnumBlockHalf> HALF = BlockDoublePlant.HALF;
    static final PropertyInteger FORM = PropertyInteger.create("form", 0, 3);

    private final int ground;
    private final boolean replaceable;

    BlockRivenTallPlant(int ground, boolean replaceable) {
        this.ground = ground;
        this.replaceable = replaceable;
        setSoundType(SoundType.PLANT);
        setDefaultState(
                blockState
                        .getBaseState()
                        .withProperty(HALF, BlockDoublePlant.EnumBlockHalf.LOWER)
                        .withProperty(FORM, 0));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, HALF, FORM);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(
                        HALF,
                        (meta & 4) != 0
                                ? BlockDoublePlant.EnumBlockHalf.UPPER
                                : BlockDoublePlant.EnumBlockHalf.LOWER)
                .withProperty(FORM, meta & 3);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return (upper(state) ? 4 : 0) | state.getValue(FORM);
    }

    @Override
    public int forms() {
        return 4;
    }

    @Override
    public IBlockState part(boolean upper, int form) {
        return getDefaultState()
                .withProperty(
                        HALF,
                        upper
                                ? BlockDoublePlant.EnumBlockHalf.UPPER
                                : BlockDoublePlant.EnumBlockHalf.LOWER)
                .withProperty(FORM, form);
    }

    private static boolean upper(IBlockState state) {
        return state.getValue(HALF) == BlockDoublePlant.EnumBlockHalf.UPPER;
    }

    @Override
    public boolean growsOn(IBlockState state) {
        return Grounds.on(ground, state);
    }

    @Override
    protected boolean canSustainBush(IBlockState state) {
        return growsOn(state);
    }

    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock().isReplaceable(world, pos)
                && world.getBlockState(pos.up()).getBlock().isReplaceable(world, pos.up())
                && growsOn(world.getBlockState(pos.down()));
    }

    @Override
    public boolean canBlockStay(World world, BlockPos pos, IBlockState state) {
        if (state.getBlock() != this) return growsOn(world.getBlockState(pos.down()));
        if (upper(state)) return world.getBlockState(pos.down()).getBlock() == this;
        return world.getBlockState(pos.up()).getBlock() == this
                && growsOn(world.getBlockState(pos.down()));
    }

    /** Only the lower half drops, so a plant whose halves both fail drops once. */
    @Override
    protected void checkAndDropBlock(World world, BlockPos pos, IBlockState state) {
        if (canBlockStay(world, pos, state)) return;
        if (!upper(state)) dropBlockAsItem(world, pos, state, 0);
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
    }

    @Override
    public IBlockState getStateForPlacement(
            World world,
            BlockPos pos,
            EnumFacing facing,
            float hitX,
            float hitY,
            float hitZ,
            int meta,
            EntityLivingBase placer,
            EnumHand hand) {
        return part(false, (int) (MathHelper.getPositionRandom(pos) >>> 16 & 3));
    }

    @Override
    public void onBlockPlacedBy(
            World world,
            BlockPos pos,
            IBlockState state,
            EntityLivingBase placer,
            ItemStack stack) {
        world.setBlockState(pos.up(), part(true, state.getValue(FORM)), 2);
    }

    /** Breaking either half takes its partner without a second drop. */
    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        BlockPos partner = upper(state) ? pos.down() : pos.up();
        if (world.getBlockState(partner).getBlock() == this)
            world.setBlockState(partner, Blocks.AIR.getDefaultState(), 2);
        super.breakBlock(world, pos, state);
    }

    @Override
    public int damageDropped(IBlockState state) {
        return 0;
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
