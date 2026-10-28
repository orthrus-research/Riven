package cc.orthrus.riven.content;

import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.TerrainBlock;

import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Unstable stone (riven-content.json "unstable"): a host rock gone weak over open space, drawn as
 * the host under a crack overlay and shedding dust into the space below. A player who steps or
 * lands on it without sneaking sets it off: it creaks, then the unstable stone joined to it nearby
 * falls ring by ring outward, each column from the bottom up, and every falling piece shatters
 * where it lands. Nothing else sets it off. Mined, it drops what its host drops; silk touch keeps
 * it, and placed again it behaves the same.
 */
public final class BlockUnstableStone extends BlockFalling {
    /** The generator's rock this block stands in for. */
    public final TerrainBlock terrain;

    private final String hostId;
    private final int hostMeta, dust, delay, spread, radius, maximum, fallDamageMax;
    private final float dustChance, debrisChance, fallDamagePerBlock;
    private volatile IBlockState host;

    BlockUnstableStone(JsonObject behaviour, JsonObject host) {
        super(Material.ROCK);
        terrain = TerrainBlock.valueOf(host.get("terrain").getAsString());
        hostId = host.get("block").getAsString();
        hostMeta = host.get("meta").getAsInt();
        dust = 0xff000000 | Integer.parseInt(host.get("dust").getAsString().substring(1), 16);
        delay = (int) Rules.number(behaviour, "triggerDelayTicks", 1, 200);
        spread = (int) Rules.number(behaviour, "spreadTicksPerBlock", 0, 40);
        radius = (int) Rules.number(behaviour, "spreadRadiusBlocks", 0, 16);
        maximum = (int) Rules.number(behaviour, "maximumBlocks", 1, 1024);
        dustChance = (float) Rules.number(behaviour, "dustChance", 0, 1);
        debrisChance = (float) Rules.number(behaviour, "debrisDropChance", 0, 1);
        fallDamagePerBlock = (float) Rules.number(behaviour, "fallDamagePerBlock", 0, 100);
        fallDamageMax = (int) Rules.number(behaviour, "fallDamageMax", 0, 1000);
        setSoundType(SoundType.STONE);
    }

    /** The host rock's state (resolved on first use, after every host is registered). */
    @SuppressWarnings("deprecation") // Block's state API in 1.12.
    public IBlockState host() {
        IBlockState h = host;
        if (h == null) {
            Block b = Block.REGISTRY.getObject(new ResourceLocation(hostId));
            if (b == Blocks.AIR) throw new IllegalStateException("Missing unstable host " + hostId);
            host = h = b.getStateFromMeta(hostMeta);
        }
        return h;
    }

    // Unstable stone holds until a player sets it off: no vanilla falling on placement or updates.
    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {}

    @Override
    public void neighborChanged(
            IBlockState state, World world, BlockPos pos, Block block, BlockPos from) {}

    @Override
    public void onEntityWalk(World world, BlockPos pos, Entity entity) {
        if (setsOff(world, entity)) collapse(world, pos);
    }

    @Override
    public void onFallenUpon(World world, BlockPos pos, Entity entity, float fallDistance) {
        if (setsOff(world, entity)) collapse(world, pos);
        super.onFallenUpon(world, pos, entity, fallDistance);
    }

    private static boolean setsOff(World world, Entity entity) {
        return !world.isRemote
                && entity instanceof EntityPlayer
                && !entity.isSneaking()
                && !((EntityPlayer) entity).isSpectator();
    }

    /** Creaks now and schedules each joined piece's fall, nearest ring first. */
    private void collapse(World world, BlockPos origin) {
        if (world.isBlockTickPending(origin, this)) return;
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty() && found.size() < maximum) {
            BlockPos p = queue.poll();
            found.add(p);
            for (BlockPos n :
                    new BlockPos[] {p.up(), p.down(), p.north(), p.south(), p.west(), p.east()}) {
                if (ring(n, origin) > radius
                        || Math.abs(n.getY() - origin.getY()) > radius
                        || !seen.add(n)
                        || !world.isBlockLoaded(n)
                        || !(world.getBlockState(n).getBlock() instanceof BlockUnstableStone))
                    continue;
                queue.add(n);
            }
        }
        // Same-tick updates run in the order scheduled: bottom pieces first, so a column falls
        // together instead of its top landing on the piece below.
        found.sort(
                Comparator.comparingInt((BlockPos p) -> ring(p, origin))
                        .thenComparingInt(BlockPos::getY));
        for (BlockPos p : found)
            world.scheduleUpdate(
                    p, world.getBlockState(p).getBlock(), delay + spread * ring(p, origin));
        world.playSound(
                null, origin, SoundEvents.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 1.0F, 0.5F);
        if (world instanceof WorldServer)
            ((WorldServer) world)
                    .spawnParticle(
                            EnumParticleTypes.BLOCK_DUST,
                            origin.getX() + 0.5,
                            origin.getY() + 1.0,
                            origin.getZ() + 0.5,
                            16,
                            0.4,
                            0.05,
                            0.4,
                            0.05,
                            Block.getStateId(host()));
    }

    /** Horizontal distance in rings (Chebyshev) from the piece that was set off. */
    private static int ring(BlockPos p, BlockPos origin) {
        return Math.max(Math.abs(p.getX() - origin.getX()), Math.abs(p.getZ() - origin.getZ()));
    }

    @Override
    public void updateTick(World world, BlockPos pos, IBlockState state, Random random) {
        if (world.isRemote) return;
        EntityFallingBlock falling =
                new EntityFallingBlock(
                        world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, state);
        NBTTagCompound tag = new NBTTagCompound();
        falling.writeToNBT(tag);
        tag.setBoolean("HurtEntities", fallDamagePerBlock > 0);
        tag.setFloat("FallHurtAmount", fallDamagePerBlock);
        tag.setInteger("FallHurtMax", fallDamageMax);
        falling.readFromNBT(tag);
        falling.shouldDropItem = false;
        world.spawnEntity(falling);
    }

    @Override
    public void onEndFalling(World world, BlockPos pos, IBlockState falling, IBlockState hit) {
        // A fallen piece shatters into rubble, sometimes leaving its host's drops.
        world.playEvent(2001, pos, Block.getStateId(host()));
        world.setBlockToAir(pos);
        if (world.rand.nextFloat() < debrisChance)
            host().getBlock().dropBlockAsItem(world, pos, host(), 0);
    }

    @Override
    public void getDrops(
            NonNullList<ItemStack> drops,
            IBlockAccess world,
            BlockPos pos,
            IBlockState state,
            int fortune) {
        host().getBlock().getDrops(drops, world, pos, host(), fortune);
    }

    @Override
    @SuppressWarnings("deprecation") // Block's state API in 1.12.
    public MapColor getMapColor(IBlockState state, IBlockAccess world, BlockPos pos) {
        return host().getMapColor(world, pos);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void randomDisplayTick(IBlockState state, World world, BlockPos pos, Random random) {
        if (random.nextFloat() >= dustChance || !canFallThrough(world.getBlockState(pos.down())))
            return;
        world.spawnParticle(
                EnumParticleTypes.FALLING_DUST,
                pos.getX() + random.nextDouble(),
                pos.getY() - 0.05,
                pos.getZ() + random.nextDouble(),
                0,
                0,
                0,
                Block.getStateId(state));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getDustColor(IBlockState state) {
        return dust;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT_MIPPED;
    }
}
