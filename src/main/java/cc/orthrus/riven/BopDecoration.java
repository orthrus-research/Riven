package cc.orthrus.riven;

import biomesoplenty.api.biome.BOPBiomes;

/**
 * Keeps Biomes O' Plenty's own decoration out of a Riven world: BoP's event handler would run its
 * biomes' generators on every chunk, and Riven runs none of them (since terrain-v121 Riven owns
 * every stage BoP decorated). The only class that touches BoP's generation API; it is loaded only
 * when BoP is present.
 */
final class BopDecoration {
    BopDecoration(net.minecraft.world.WorldType type) {
        synchronized (BOPBiomes.excludedDecoratedWorldTypes) {
            if (!BOPBiomes.excludedDecoratedWorldTypes.contains(type))
                BOPBiomes.excludedDecoratedWorldTypes.add(type);
        }
    }
}
