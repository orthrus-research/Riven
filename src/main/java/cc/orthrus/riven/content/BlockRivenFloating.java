package cc.orthrus.riven.content;

import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * A plant on still water (riven-content.json decorations, kind floating), in one of four forms by
 * position: a pad lying on the surface, which can be stood on as a lily pad can, or reeds standing
 * up from the shallows. It needs still water below (a water source or a submerged decoration);
 * boats break it as they do lily pads.
 */
final class BlockRivenFloating extends BlockBush implements GroundPlant {
    private static final AxisAlignedBB PAD = new AxisAlignedBB(0, 0, 0, 1, 1.5 / 16, 1);

    final boolean pad;

    BlockRivenFloating(boolean pad) {
        super(Material.PLANTS);
        this.pad = pad;
        setSoundType(SoundType.PLANT);
    }

    /** Still water: a source block, or a decoration of water's material. */
    @Override
    public boolean growsOn(IBlockState state) {
        if (state.getMaterial() != Material.WATER) return false;
        return !(state.getBlock() instanceof BlockLiquid) || state.getValue(BlockLiquid.LEVEL) == 0;
    }

    @Override
    protected boolean canSustainBush(IBlockState state) {
        return growsOn(state);
    }

    @Override
    public boolean canBlockStay(World world, BlockPos pos, IBlockState state) {
        return growsOn(world.getBlockState(pos.down()));
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return pad ? PAD : super.getBoundingBox(state, world, pos);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void addCollisionBoxToList(
            IBlockState state,
            World world,
            BlockPos pos,
            AxisAlignedBB box,
            List<AxisAlignedBB> boxes,
            Entity entity,
            boolean actual) {
        if (pad && !(entity instanceof EntityBoat)) addCollisionBoxToList(pos, box, boxes, PAD);
    }

    @Override
    public void onEntityCollision(World world, BlockPos pos, IBlockState state, Entity entity) {
        if (entity instanceof EntityBoat) world.destroyBlock(new BlockPos(pos), true);
    }
}
