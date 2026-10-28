package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.function.Function;

/**
 * A mineral's ore in Riven's host stones (riven-content.json "ores"): the host property indexes
 * the host list. With GTCEu a mineral naming a GTCEu material drops that material's ore, so GTCEu
 * processing applies; otherwise the block drops itself.
 */
public final class BlockRivenOre extends Block {
    public static final PropertyInteger HOST = PropertyInteger.create("host", 0, 15);

    /** Set once at registration when GTCEu is loaded: a material name to its ore stack, or null. */
    static Function<String, ItemStack> gregtechOre;

    public final String mineral;
    public final int color, hosts;
    private final String gregtech;

    BlockRivenOre(String mineral, int color, String gregtech, int hosts) {
        super(Material.ROCK);
        this.mineral = mineral;
        this.color = color;
        this.gregtech = gregtech;
        this.hosts = hosts;
        setDefaultState(blockState.getBaseState().withProperty(HOST, 0));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, HOST);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(HOST);
    }

    @Override
    @SuppressWarnings("deprecation") // Block's state API in 1.12.
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(HOST, meta & 15);
    }

    @Override
    public int damageDropped(IBlockState state) {
        return state.getValue(HOST);
    }

    @Override
    public void getDrops(
            NonNullList<ItemStack> drops,
            IBlockAccess world,
            BlockPos pos,
            IBlockState state,
            int fortune) {
        ItemStack ore =
                gregtech == null || gregtechOre == null ? null : gregtechOre.apply(gregtech);
        if (ore != null && !ore.isEmpty()) drops.add(ore.copy());
        else super.getDrops(drops, world, pos, state, fortune);
    }

    @Override
    public void getSubBlocks(CreativeTabs tab, NonNullList<ItemStack> items) {
        for (int i = 0; i < hosts; i++) items.add(new ItemStack(this, 1, i));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT_MIPPED;
    }
}
