package cc.orthrus.riven;

import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.ore.StoneType;
import gregtech.api.unification.stack.UnificationEntry;
import gregtech.api.worldgen.config.OreConfigUtils;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** GTCEu glue, loaded only when GTCEu is present: a material's ore state per stone type name. */
final class GregTechOres {
    private GregTechOres() {}

    /** The material's ore by GTCEu stone type name, or null when GTCEu has no such ore. */
    static Map<String, IBlockState> byStone(String material) {
        Map<StoneType, IBlockState> ores;
        try {
            ores = OreConfigUtils.getOreStateMap("ore:" + material);
        } catch (IllegalArgumentException missing) {
            return null;
        }
        Map<String, IBlockState> result = new HashMap<>();
        for (Map.Entry<StoneType, IBlockState> e : ores.entrySet())
            result.put(e.getKey().name, e.getValue());
        return result;
    }

    /**
     * How GTCEu took Riven's ore dictionary names, for the startup log: of the Riven stacks that
     * carry a name, how many GTCEu unified with one of its materials, and the names it did not
     * resolve (no GTCEu material of that name, or a prefix GTCEu does not unify).
     */
    static String unification(List<Block> blocks) {
        int named = 0, unified = 0;
        Set<String> names = new TreeSet<>(), resolved = new TreeSet<>();
        for (Block block : blocks) {
            Item item = Item.getItemFromBlock(block);
            if (item == Items.AIR) continue;
            int variants = item.getHasSubtypes() ? 16 : 1;
            for (int meta = 0; meta < variants; meta++) {
                ItemStack stack = new ItemStack(item, 1, meta);
                int[] ids = OreDictionary.getOreIDs(stack);
                if (ids.length == 0) continue;
                named++;
                for (int id : ids) names.add(OreDictionary.getOreName(id));
                UnificationEntry entry = OreDictUnifier.getUnificationEntry(stack);
                if (entry != null) {
                    unified++;
                    resolved.add(entry.toString());
                }
            }
        }
        names.removeAll(resolved);
        return "GTCEu unifies "
                + unified
                + " of "
                + named
                + " Riven ore dictionary stacks; names it does not resolve: "
                + names;
    }
}
