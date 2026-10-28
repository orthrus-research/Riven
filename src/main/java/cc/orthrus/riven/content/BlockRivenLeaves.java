package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Random;

/**
 * A Riven species' leaves (riven-content.json woods): its two foliage textures at random, decaying
 * away from logs as vanilla leaves do and dropping its sapling. They follow the vanilla leaves'
 * graphics setting (cutout when fancy, opaque when fast), which the game sets only on its own.
 */
final class BlockRivenLeaves extends BlockLeaves {
    final String species;
    private final int saplingChance;
    Block sapling;

    BlockRivenLeaves(String species, int saplingChance) {
        this.species = species;
        this.saplingChance = saplingChance;
        setDefaultState(
                blockState
                        .getBaseState()
                        .withProperty(CHECK_DECAY, true)
                        .withProperty(DECAYABLE, true));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, CHECK_DECAY, DECAYABLE);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(DECAYABLE, (meta & 4) == 0)
                .withProperty(CHECK_DECAY, (meta & 8) > 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = 0;
        if (!state.getValue(DECAYABLE)) meta |= 4;
        if (state.getValue(CHECK_DECAY)) meta |= 8;
        return meta;
    }

    /** Placed by a player, leaves never decay. */
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
        return getDefaultState().withProperty(CHECK_DECAY, false).withProperty(DECAYABLE, false);
    }

    @Override
    public Item getItemDropped(IBlockState state, Random random, int fortune) {
        return Item.getItemFromBlock(sapling);
    }

    @Override
    protected int getSaplingDropChance(IBlockState state) {
        return saplingChance;
    }

    @Override
    public BlockPlanks.EnumType getWoodType(int meta) {
        return BlockPlanks.EnumType.OAK;
    }

    @Override
    public NonNullList<ItemStack> onSheared(
            ItemStack item, IBlockAccess world, BlockPos pos, int fortune) {
        return NonNullList.withSize(1, new ItemStack(this));
    }

    /** Whether the game draws leaves fancy: it tells only its own leaves. */
    private static boolean fancy() {
        return !Blocks.LEAVES.isOpaqueCube(Blocks.LEAVES.getDefaultState());
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return !fancy();
    }

    @Override
    public BlockRenderLayer getRenderLayer() {
        return fancy() ? BlockRenderLayer.CUTOUT_MIPPED : BlockRenderLayer.SOLID;
    }

    @SuppressWarnings("deprecation")
    @SideOnly(Side.CLIENT)
    @Override
    public boolean shouldSideBeRendered(
            IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        leavesFancy = fancy();
        return super.shouldSideBeRendered(state, world, pos, side);
    }
}
