package cc.orthrus.riven.content;

import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.JsonObject;

import net.minecraft.block.BlockCauldron;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Locale;
import java.util.Random;

/**
 * Pointed dripstone, reimplemented for 1.12 from modern Minecraft's gameplay: a stalactite
 * (direction down) or stalagmite (direction up). Only the direction is stored; the thickness is
 * worked out from the neighbours along the direction, so columns reshape themselves as pieces are
 * added or removed. Values come from riven-content.json.
 */
public final class BlockPointedDripstone extends BlockFalling {
    /** From the pointed end to the attached end; tip_merge is a tip touching an opposing tip. */
    public enum Thickness implements IStringSerializable {
        TIP_MERGE,
        TIP,
        FRUSTUM,
        MIDDLE,
        BASE;

        @Override
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final PropertyDirection DIRECTION =
            PropertyDirection.create(
                    "direction", f -> f != null && f.getAxis() == EnumFacing.Axis.Y);
    public static final PropertyEnum<Thickness> THICKNESS =
            PropertyEnum.create("thickness", Thickness.class);

    private final float fallDamagePerBlock, extraFall, damageMultiplier, dripChance, cauldronChance;
    private final int fallDamageMax, cauldronReach;

    BlockPointedDripstone(JsonObject j) {
        super(Material.ROCK);
        fallDamagePerBlock = (float) Rules.number(j, "fallDamagePerBlock", 0, 100);
        fallDamageMax = (int) Rules.number(j, "fallDamageMax", 0, 1000);
        extraFall = (float) Rules.number(j, "stalagmiteExtraFallBlocks", 0, 64);
        damageMultiplier = (float) Rules.number(j, "stalagmiteDamageMultiplier", 0, 16);
        dripChance = (float) Rules.number(j, "dripChance", 0, 1);
        cauldronChance = (float) Rules.number(j, "cauldronFillChance", 0, 1);
        cauldronReach = (int) Rules.number(j, "cauldronReachBlocks", 1, 64);
        setDefaultState(
                blockState
                        .getBaseState()
                        .withProperty(DIRECTION, EnumFacing.UP)
                        .withProperty(THICKNESS, Thickness.TIP));
        setTickRandomly(true);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, DIRECTION, THICKNESS);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(DIRECTION) == EnumFacing.DOWN ? 1 : 0;
    }

    @Override
    @SuppressWarnings("deprecation") // Block's state API in 1.12.
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(DIRECTION, (meta & 1) == 1 ? EnumFacing.DOWN : EnumFacing.UP);
    }

    @Override
    @SuppressWarnings("deprecation")
    public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state.withProperty(THICKNESS, thickness(world, pos, state.getValue(DIRECTION)));
    }

    /**
     * Whether a piece of this block points {@code direction} at pos: each formation material
     * (dripstone, ice, salt, basalt) forms its own columns, never merging with another.
     */
    private boolean pointing(IBlockAccess world, BlockPos pos, EnumFacing direction) {
        IBlockState s = world.getBlockState(pos);
        return s.getBlock() == this && s.getValue(DIRECTION) == direction;
    }

    /** The piece's thickness from its neighbours along its direction (towards its point). */
    Thickness thickness(IBlockAccess world, BlockPos pos, EnumFacing direction) {
        BlockPos next = pos.offset(direction);
        if (pointing(world, next, direction.getOpposite())) return Thickness.TIP_MERGE;
        if (!pointing(world, next, direction)) return Thickness.TIP;
        // The next piece is a tip (or a merged tip) when nothing continues the column past it.
        if (!pointing(world, next.offset(direction), direction)) return Thickness.FRUSTUM;
        if (!pointing(world, pos.offset(direction.getOpposite()), direction)) return Thickness.BASE;
        return Thickness.MIDDLE;
    }

    /** Whether the piece pointing {@code direction} at pos has something to hang from or stand on. */
    private boolean supported(IBlockAccess world, BlockPos pos, EnumFacing direction) {
        BlockPos anchor = pos.offset(direction.getOpposite());
        IBlockState s = world.getBlockState(anchor);
        return pointing(world, anchor, direction) || s.isSideSolid(world, anchor, direction);
    }

    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        return super.canPlaceBlockAt(world, pos)
                && (supported(world, pos, EnumFacing.DOWN) || supported(world, pos, EnumFacing.UP));
    }

    @Override
    @SuppressWarnings("deprecation") // The 1.12 placement hook ItemBlock still calls.
    public IBlockState getStateForPlacement(
            World world,
            BlockPos pos,
            EnumFacing facing,
            float hitX,
            float hitY,
            float hitZ,
            int meta,
            EntityLivingBase placer) {
        // Placed against a ceiling it hangs; against a floor it stands; from the side it hangs
        // when it can.
        EnumFacing direction =
                facing == EnumFacing.DOWN
                        ? EnumFacing.DOWN
                        : facing == EnumFacing.UP
                                ? EnumFacing.UP
                                : supported(world, pos, EnumFacing.DOWN)
                                        ? EnumFacing.DOWN
                                        : EnumFacing.UP;
        if (!supported(world, pos, direction)) direction = direction.getOpposite();
        return getDefaultState().withProperty(DIRECTION, direction);
    }

    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        if (!supported(world, pos, state.getValue(DIRECTION))) world.scheduleUpdate(pos, this, 1);
    }

    @Override
    public void neighborChanged(
            IBlockState state,
            World world,
            BlockPos pos,
            net.minecraft.block.Block block,
            BlockPos from) {
        if (!supported(world, pos, state.getValue(DIRECTION))) world.scheduleUpdate(pos, this, 1);
    }

    @Override
    public void updateTick(World world, BlockPos pos, IBlockState state, Random random) {
        if (world.isRemote || supported(world, pos, state.getValue(DIRECTION))) return;
        if (state.getValue(DIRECTION) == EnumFacing.UP) {
            world.destroyBlock(pos, true);
            return;
        }
        // A stalactite that lost its anchor falls; the pieces below follow as each loses its own.
        EntityFallingBlock falling =
                new EntityFallingBlock(
                        world,
                        pos.getX() + 0.5,
                        pos.getY(),
                        pos.getZ() + 0.5,
                        getDefaultState().withProperty(DIRECTION, EnumFacing.DOWN));
        NBTTagCompound tag = new NBTTagCompound();
        falling.writeToNBT(tag);
        tag.setBoolean("HurtEntities", true);
        tag.setFloat("FallHurtAmount", fallDamagePerBlock);
        tag.setInteger("FallHurtMax", fallDamageMax);
        falling.readFromNBT(tag);
        falling.shouldDropItem = true;
        world.spawnEntity(falling);
    }

    @Override
    public void onEndFalling(World world, BlockPos pos, IBlockState falling, IBlockState hit) {
        // A fallen stalactite shatters instead of setting.
        world.destroyBlock(pos, true);
    }

    @Override
    public void onFallenUpon(World world, BlockPos pos, Entity entity, float fallDistance) {
        IBlockState s = world.getBlockState(pos);
        if (s.getBlock() == this
                && s.getValue(DIRECTION) == EnumFacing.UP
                && thickness(world, pos, EnumFacing.UP) == Thickness.TIP)
            entity.fall(fallDistance + extraFall, damageMultiplier);
        else super.onFallenUpon(world, pos, entity, fallDistance);
    }

    /** The fluid on top of the block a stalactite column hangs from, or null. */
    private Material dripFluid(World world, BlockPos tip) {
        BlockPos p = tip;
        while (pointing(world, p.up(), EnumFacing.DOWN)) p = p.up();
        Material m = world.getBlockState(p.up(2)).getMaterial();
        return m == Material.WATER || m == Material.LAVA ? m : null;
    }

    private boolean isDrippingTip(World world, BlockPos pos, IBlockState state) {
        return state.getValue(DIRECTION) == EnumFacing.DOWN
                && thickness(world, pos, EnumFacing.DOWN) == Thickness.TIP;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void randomDisplayTick(IBlockState state, World world, BlockPos pos, Random random) {
        if (random.nextFloat() >= dripChance || !isDrippingTip(world, pos, state)) return;
        Material fluid = dripFluid(world, pos);
        if (fluid == null) return;
        world.spawnParticle(
                fluid == Material.WATER
                        ? EnumParticleTypes.DRIP_WATER
                        : EnumParticleTypes.DRIP_LAVA,
                pos.getX() + 0.5,
                pos.getY() + 0.05,
                pos.getZ() + 0.5,
                0,
                0,
                0);
    }

    @Override
    public void randomTick(World world, BlockPos pos, IBlockState state, Random random) {
        // Water dripping from a tip slowly fills a cauldron below it (1.12 has no lava cauldron).
        if (random.nextFloat() >= cauldronChance || !isDrippingTip(world, pos, state)) return;
        if (dripFluid(world, pos) != Material.WATER) return;
        for (int i = 1; i <= cauldronReach; i++) {
            BlockPos below = pos.down(i);
            IBlockState s = world.getBlockState(below);
            if (s.getMaterial() == Material.AIR) continue;
            if (s.getBlock() == Blocks.CAULDRON && s.getValue(BlockCauldron.LEVEL) < 3)
                Blocks.CAULDRON.setWaterLevel(world, below, s, s.getValue(BlockCauldron.LEVEL) + 1);
            return;
        }
    }

    private static final AxisAlignedBB[] UP = boxes(false), DOWN = boxes(true);

    private static AxisAlignedBB[] boxes(boolean hanging) {
        double[][] r = {{5, 0, 16}, {5, 0, 11}, {4, 0, 16}, {3, 0, 16}, {2, 0, 16}};
        AxisAlignedBB[] out = new AxisAlignedBB[r.length];
        for (int i = 0; i < r.length; i++) {
            double inset = r[i][0] / 16, lo = r[i][1] / 16, hi = r[i][2] / 16;
            out[i] =
                    hanging
                            ? new AxisAlignedBB(inset, 1 - hi, inset, 1 - inset, 1 - lo, 1 - inset)
                            : new AxisAlignedBB(inset, lo, inset, 1 - inset, hi, 1 - inset);
        }
        return out;
    }

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        EnumFacing direction = state.getValue(DIRECTION);
        return (direction == EnumFacing.DOWN ? DOWN : UP)
                [thickness(world, pos, direction).ordinal()];
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockFaceShape getBlockFaceShape(
            IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT;
    }
}
