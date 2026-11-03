package cc.orthrus.riven.content;

import net.minecraft.block.BlockFalling;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;

/** A loose Riven sediment (riven-content.json kind sediment): sand material, falls like sand. */
final class BlockRivenSediment extends BlockFalling {
    BlockRivenSediment() {
        super(Material.SAND);
        setSoundType(SoundType.SAND);
    }
}
