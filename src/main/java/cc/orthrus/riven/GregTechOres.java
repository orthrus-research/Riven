package cc.orthrus.riven;

import gregtech.api.unification.ore.StoneType;
import gregtech.api.worldgen.config.OreConfigUtils;

import net.minecraft.block.state.IBlockState;

import java.util.HashMap;
import java.util.Map;

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
}
