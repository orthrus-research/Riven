package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

/**
 * The item of a floating plant: used on water, it sets the plant on the surface the player looks
 * at, as a lily pad is placed (a plain block item cannot target water).
 */
final class ItemRivenFloating extends ItemBlock {
    ItemRivenFloating(Block block) {
        super(block);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(
            World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        RayTraceResult hit = rayTrace(world, player, true);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK)
            return new ActionResult<>(EnumActionResult.PASS, stack);
        BlockPos water = hit.getBlockPos(), above = water.up();
        if (!world.isBlockModifiable(player, water)
                || !player.canPlayerEdit(above, hit.sideHit, stack)
                || !world.isAirBlock(above)
                || !((BlockRivenFloating) block).growsOn(world.getBlockState(water)))
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        world.setBlockState(above, block.getDefaultState(), 11);
        world.playSound(
                player,
                above,
                block.getSoundType(block.getDefaultState(), world, above, player).getPlaceSound(),
                SoundCategory.BLOCKS,
                1,
                1);
        if (!player.capabilities.isCreativeMode) stack.shrink(1);
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
