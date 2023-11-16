/*
 * Copyright (c) 2022-2023 Leon Linhart
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.themrmilchmann.ae2cc;

import dan200.computercraft.api.peripheral.PeripheralLookup;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class AE2CCBridge implements ModInitializer {

    public static final Block ADAPTER_BLOCK = new AE2CCAdapterBlock();

    public static final BlockEntityType<AE2CCAdapterBlockEntity> ADAPTER_BLOCK_ENTITY = Registry.register(
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        "ae2cc:adapter_block_entity",
        FabricBlockEntityTypeBuilder.create(AE2CCAdapterBlockEntity::new, ADAPTER_BLOCK).build()
    );

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, "ae2cc:adapter", ADAPTER_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, "ae2cc:adapter", new BlockItem(ADAPTER_BLOCK, new FabricItemSettings()));

        PeripheralLookup.get().registerForBlockEntity((blockEntity, ignored) -> blockEntity.asPeripheral(), ADAPTER_BLOCK_ENTITY);
    }

}