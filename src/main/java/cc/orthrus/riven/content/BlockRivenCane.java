package cc.orthrus.riven.content;

import net.minecraft.block.BlockBush;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.Random;

/**
 * A Riven cane (riven-content.json decorations): a stack of pieces of one of four forms, the top
 * piece ending it, like sugar cane. The lowest piece stands on its own grounds where water lies
 * beside that ground; breaking a piece takes those above, and the piece below becomes the top.
 * Each piece drops a sugar cane, so worlds whose reeds are Riven's keep that source.
 */
final class BlockRivenCane extends BlockBush implements GroundPlant.Cane {
    static final PropertyBool TOP = PropertyBool.create("top");
    static final PropertyInteger FORM = PropertyInteger.create("form", 0, 3);
    private static final AxisAlignedBB BOX = new AxisAlignedBB(0.125, 0, 0.125, 0.875, 1, 0.875);

    private final int ground;
    private final boolean replaceable;

    BlockRivenCane(int ground, boolean replaceable) {
        this.ground = ground;
        this.replaceable = replaceable;
        setSoundType(SoundType.PLANT);
        setDefaultState(blockState.getBaseState().withProperty(TOP, true).withProperty(FORM, 0));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, TOP, FORM);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return piece((meta & 4) != 0, meta & 3);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return (state.getValue(TOP) ? 4 : 0) | state.getValue(FORM);
    }

    @Override
    public int forms() {
        return 4;
    }

    @Override
    public IBlockState piece(boolean top, int form) {
        return getDefaultState().withProperty(TOP, top).withProperty(FORM, form);
    }

    @Override
    public boolean growsOn(IBlockState state) {
        return Grounds.on(ground, state);
    }

    @Override
    protected boolean canSustainBush(IBlockState state) {
        return state.getBlock() == this || growsOn(state);
    }

    /** On a piece of its own, or on its ground with water beside that ground. */
    private boolean stands(World world, BlockPos pos) {
        BlockPos down = pos.down();
        IBlockState below = world.getBlockState(down);
        if (below.getBlock() == this) return true;
        if (!growsOn(below)) return false;
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL)
            if (world.getBlockState(down.offset(side)).getMaterial() == Material.WATER) return true;
        return false;
    }

    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock().isReplaceable(world, pos) && stands(world, pos);
    }

    @Override
    public boolean canBlockStay(World world, BlockPos pos, IBlockState state) {
        return stands(world, pos);
    }

    /** A placed piece tops the stack, in the form of the piece it stands on. */
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
        IBlockState below = world.getBlockState(pos.down());
        return piece(
                true,
                below.getBlock() == this
                        ? below.getValue(FORM)
                        : (int) (MathHelper.getPositionRandom(pos) >>> 16 & 3));
    }

    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        top(world, pos.down(), false);
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        top(world, pos.down(), true);
        super.breakBlock(world, pos, state);
    }

    /** Makes a piece of this cane at pos the top of its stack, or not. */
    private void top(World world, BlockPos pos, boolean top) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() == this && state.getValue(TOP) != top)
            world.setBlockState(pos, state.withProperty(TOP, top), 2);
    }

    @Override
    public Item getItemDropped(IBlockState state, Random random, int fortune) {
        return Items.REEDS;
    }

    @Override
    public int damageDropped(IBlockState state) {
        return 0;
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return BOX;
    }

    @Override
    public boolean isReplaceable(IBlockAccess world, BlockPos pos) {
        return replaceable;
    }
}
