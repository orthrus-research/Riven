package cc.orthrus.riven;

import biomesoplenty.api.biome.BOPBiomes;
import biomesoplenty.api.biome.IExtendedBiome;
import biomesoplenty.api.generation.GeneratorStage;
import biomesoplenty.api.generation.IGenerator;
import biomesoplenty.common.world.generator.GeneratorWeighted;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a Biomes O' Plenty biome's own generators on Riven terrain. The only class that touches BoP;
 * it is loaded only when BoP is present. Only allowed generator types run, so BoP adds vegetation
 * and surface patches but never lakes, boulders, spikes, columns, crystals, hives or ores.
 */
final class BopDecoration {
    /** Vanilla decoration order, as BoP's own event handler would run its stages. */
    private static final GeneratorStage[] ORDER = order();

    /** Stages Riven owns: tree sites place trees and huge mushrooms, ground cover the plants. */
    private static final java.util.EnumSet<GeneratorStage> OWNED = owned();

    private static final Field CHILDREN = children();

    private final Set<String> allowed;

    BopDecoration(Collection<String> allowed, net.minecraft.world.WorldType type) {
        this.allowed = new HashSet<>(allowed);
        // Riven runs BoP's generators itself, filtered; BoP's own event handler stands aside.
        synchronized (BOPBiomes.excludedDecoratedWorldTypes) {
            if (!BOPBiomes.excludedDecoratedWorldTypes.contains(type))
                BOPBiomes.excludedDecoratedWorldTypes.add(type);
        }
    }

    private static GeneratorStage[] order() {
        String[] names = {
            "PRE",
            "SAND",
            "CLAY",
            "SAND_PASS2",
            "TREE",
            "BIG_SHROOM",
            "FLOWERS",
            "GRASS",
            "DEAD_BUSH",
            "LILYPAD",
            "SHROOM",
            "REED",
            "PUMPKIN",
            "CACTUS",
            "LAKE_WATER",
            "LAKE_LAVA",
            "ROCK",
            "FOSSIL",
            "ICE",
            "DESERT_WELL",
            "POST"
        };
        List<GeneratorStage> stages = new ArrayList<>();
        for (String name : names)
            for (GeneratorStage stage : GeneratorStage.values())
                if (stage.name().equals(name)) stages.add(stage);
        return stages.toArray(new GeneratorStage[0]);
    }

    private static java.util.EnumSet<GeneratorStage> owned() {
        java.util.EnumSet<GeneratorStage> owned = java.util.EnumSet.noneOf(GeneratorStage.class);
        for (String name :
                new String[] {
                    "TREE", "BIG_SHROOM", "GRASS", "FLOWERS", "DEAD_BUSH", "SHROOM", "REED"
                })
            for (GeneratorStage stage : GeneratorStage.values())
                if (stage.name().equals(name)) owned.add(stage);
        return owned;
    }

    private static Field children() {
        try {
            Field f = GeneratorWeighted.class.getDeclaredField("weights");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Biomes O' Plenty generator layout", e);
        }
    }

    /**
     * Decorates one chunk's offset area (vanilla convention: corner at chunk origin). The stages
     * Riven owns stand aside.
     */
    void decorate(World world, java.util.Random random, BlockPos corner, Biome biome) {
        // Riven's own unregistered look carries BoP's generators; vanilla looks have none.
        IExtendedBiome extended = BopLooks.extended(biome);
        if (extended == null) return;
        for (GeneratorStage stage : ORDER) {
            if (OWNED.contains(stage)) continue;
            for (IGenerator generator :
                    extended.getGenerationManager().getGeneratorsForStage(stage))
                if (allowed(generator)) generator.scatter(world, random, corner);
        }
    }

    /** An allowed type; a weighted generator only if every child is allowed too. */
    private boolean allowed(IGenerator generator) {
        String id = generator.getIdentifier();
        if (id == null) return false;
        if (!allowed.contains(id.substring(id.indexOf(':') + 1))) return false;
        if (!(generator instanceof GeneratorWeighted)) return true;
        try {
            for (Object child : ((Map<?, ?>) CHILDREN.get(generator)).keySet())
                if (!(child instanceof IGenerator) || !allowed((IGenerator) child)) return false;
        } catch (IllegalAccessException e) {
            return false;
        }
        return true;
    }
}
