package io.github.themrmilchmann.ae2cc;

import dan200.computercraft.api.ComputerCraftAPI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class AE2CCBridge implements ModInitializer {

    public static final Block ADAPTER_BLOCK = new AE2CCAdapterBlock();

    public static final BlockEntityType<AE2CCAdapterBlockEntity> ADAPTER_BLOCK_ENTITY = Registry.register(
        Registry.BLOCK_ENTITY_TYPE,
        "ae2cc:adapter_block_entity",
        FabricBlockEntityTypeBuilder.create(AE2CCAdapterBlockEntity::new, ADAPTER_BLOCK).build()
    );

    @Override
    public void onInitialize() {
        Registry.register(Registry.BLOCK, "ae2cc:adapter", ADAPTER_BLOCK);
        Registry.register(Registry.ITEM, "ae2cc:adapter", new BlockItem(ADAPTER_BLOCK, new FabricItemSettings()));

        ComputerCraftAPI.registerPeripheralProvider((level, pos, dir) -> {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            return (blockEntity instanceof AE2CCAdapterBlockEntity ae2CCAdapterBlockEntity) ? ae2CCAdapterBlockEntity.asPeripheral() : null;
        });
    }

}