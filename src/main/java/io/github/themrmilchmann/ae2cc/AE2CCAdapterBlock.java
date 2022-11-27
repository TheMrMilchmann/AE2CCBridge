package io.github.themrmilchmann.ae2cc;

import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Material;

public final class AE2CCAdapterBlock extends Block implements EntityBlock {

    public AE2CCAdapterBlock() {
        super(FabricBlockSettings.of(Material.METAL).strength(2.2F, 11F).sounds(SoundType.METAL));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AE2CCAdapterBlockEntity(pos, state);
    }

}