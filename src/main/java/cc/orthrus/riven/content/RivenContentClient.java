package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/** Binds each Riven item to its generated item model. */
@Mod.EventBusSubscriber(modid = "riven", value = Side.CLIENT)
public final class RivenContentClient {
    private RivenContentClient() {}

    @SubscribeEvent
    public static void models(ModelRegistryEvent event) {
        for (Block block : RivenContent.blocks()) {
            if (block instanceof BlockRivenOre) {
                for (int i = 0; i < ((BlockRivenOre) block).hosts; i++)
                    ModelLoader.setCustomModelResourceLocation(
                            Item.getItemFromBlock(block),
                            i,
                            new ModelResourceLocation(block.getRegistryName(), "host=" + i));
                continue;
            }
            ModelLoader.setCustomModelResourceLocation(
                    Item.getItemFromBlock(block),
                    0,
                    new ModelResourceLocation(block.getRegistryName(), "inventory"));
        }
    }

    /** Ore overlays take their mineral's color, in the world and in hand. */
    @SubscribeEvent
    public static void blockColors(net.minecraftforge.client.event.ColorHandlerEvent.Block event) {
        for (Block block : RivenContent.blocks())
            if (block instanceof BlockRivenOre) {
                int color = ((BlockRivenOre) block).color;
                event.getBlockColors()
                        .registerBlockColorHandler(
                                (state, world, pos, tint) -> tint == 0 ? color : -1, block);
            }
    }

    @SubscribeEvent
    public static void itemColors(net.minecraftforge.client.event.ColorHandlerEvent.Item event) {
        for (Block block : RivenContent.blocks())
            if (block instanceof BlockRivenOre) {
                int color = ((BlockRivenOre) block).color;
                event.getItemColors()
                        .registerItemColorHandler((stack, tint) -> tint == 0 ? color : -1, block);
            }
    }
}
