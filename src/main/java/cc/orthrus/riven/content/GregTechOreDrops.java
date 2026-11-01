package cc.orthrus.riven.content;

import gregtech.api.unification.ore.StoneType;
import gregtech.api.worldgen.config.OreConfigUtils;

import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** GTCEu glue, loaded only when GTCEu is present: a material's stone ore item for GTCEu to process. */
final class GregTechOreDrops {
    private GregTechOreDrops() {}

    /** Each material's answer, found once: GTCEu's materials and ore blocks are fixed by then. */
    private static final Map<String, ItemStack> ORES = new ConcurrentHashMap<>();

    static ItemStack ore(String material) {
        return ORES.computeIfAbsent(material, GregTechOreDrops::find);
    }

    private static ItemStack find(String material) {
        Map<StoneType, IBlockState> ores;
        try {
            ores = OreConfigUtils.getOreStateMap("ore:" + material);
        } catch (IllegalArgumentException missing) {
            return ItemStack.EMPTY;
        }
        for (Map.Entry<StoneType, IBlockState> e : ores.entrySet())
            if (e.getKey().name.equals("stone")) {
                IBlockState s = e.getValue();
                return new ItemStack(s.getBlock(), 1, s.getBlock().getMetaFromState(s));
            }
        return ItemStack.EMPTY;
    }
}
