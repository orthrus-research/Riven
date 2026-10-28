package cc.orthrus.riven;

import net.minecraft.world.World;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import net.minecraftforge.event.terraingen.OreGenEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Keeps biome decorators off Riven's land shape (riven-bop.json contract). In Riven worlds the
 * vanilla decorator may place cactus, reeds, pumpkins, lily pads and the clay, sand and gravel
 * patches that swap surface blocks, but not trees or huge mushrooms (Riven's tree sites own them),
 * grass, flowers, dead bushes or small mushrooms (Riven's ground cover owns them), springs, wells,
 * fossils, ice spikes or boulders, and no ore or stone blob. Other world types are untouched. Registered on the terrain, ore and main buses:
 * Cleanroom delivers these events to main-bus handlers, Forge to its own buses; a repeat changes
 * nothing.
 */
public final class DecorationFilter {
    private static final Set<DecorateBiomeEvent.Decorate.EventType> DENIED =
            EnumSet.of(
                    DecorateBiomeEvent.Decorate.EventType.LAKE_WATER,
                    DecorateBiomeEvent.Decorate.EventType.LAKE_LAVA,
                    DecorateBiomeEvent.Decorate.EventType.DESERT_WELL,
                    DecorateBiomeEvent.Decorate.EventType.FOSSIL,
                    DecorateBiomeEvent.Decorate.EventType.ICE,
                    DecorateBiomeEvent.Decorate.EventType.ROCK,
                    DecorateBiomeEvent.Decorate.EventType.TREE,
                    DecorateBiomeEvent.Decorate.EventType.BIG_SHROOM,
                    DecorateBiomeEvent.Decorate.EventType.GRASS,
                    DecorateBiomeEvent.Decorate.EventType.FLOWERS,
                    DecorateBiomeEvent.Decorate.EventType.DEAD_BUSH,
                    DecorateBiomeEvent.Decorate.EventType.SHROOM);

    private DecorationFilter() {}

    private static boolean riven(World world) {
        return world != null && world.getWorldType() instanceof RivenWorldType;
    }

    @SubscribeEvent
    public static void decorate(DecorateBiomeEvent.Decorate event) {
        if (riven(event.getWorld()) && DENIED.contains(event.getType()))
            event.setResult(Event.Result.DENY);
    }

    @SubscribeEvent
    public static void ore(OreGenEvent.GenerateMinable event) {
        if (riven(event.getWorld())) event.setResult(Event.Result.DENY);
    }
}
