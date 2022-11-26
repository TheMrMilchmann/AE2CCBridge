package io.github.themrmilchmann.ae2cc;

import appeng.block.AEBaseEntityBlock;
import net.minecraft.world.level.material.Material;

public final class AE2CCAdapterBlock extends AEBaseEntityBlock<AE2CCAdapterBlockEntity> {

    public AE2CCAdapterBlock() {
        super(defaultProps(Material.METAL));
    }

}