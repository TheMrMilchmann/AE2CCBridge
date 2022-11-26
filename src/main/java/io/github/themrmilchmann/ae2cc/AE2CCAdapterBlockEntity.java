package io.github.themrmilchmann.ae2cc;

import appeng.api.networking.IGrid;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.StreamSupport;

public final class AE2CCAdapterBlockEntity extends AENetworkBlockEntity implements IGridConnectedBlockEntity {

    private final ReentrantLock craftingJobLock = new ReentrantLock();
    private final List<CraftingJob> craftingJobs = new ArrayList<>();

    private record CraftingJob(AEKey key, ICraftingLink link) {}

    private final AdapterPeripheral peripheral = new AdapterPeripheral();

    private IStackWatcher craftingWatcher;

    public AE2CCAdapterBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(AE2CCBridge.ADAPTER_BLOCK_ENTITY, blockPos, blockState);

        this.getMainNode().addService(ICraftingWatcherNode.class, new ICraftingWatcherNode() {

            @Override
            public void updateWatcher(IStackWatcher value) {
                AE2CCAdapterBlockEntity.this.craftingWatcher = value;
                AE2CCAdapterBlockEntity.this.configureWatcher();
            }

            @Override
            public void onRequestChange(ICraftingService service, AEKey key) {
                AE2CCAdapterBlockEntity.this.refreshState();
            }

        });
    }

    public IPeripheral asPeripheral() {
        return this.peripheral;
    }

    private void configureWatcher() {
        if (this.craftingWatcher == null) return;

        this.craftingWatcher.reset();
        ICraftingProvider.requestUpdate(this.getMainNode());

        // TODO It might be better (for performance) to watch only for a subset of keys instead.
        this.craftingWatcher.setWatchAll(true);

        this.refreshState();
    }

    @Override
    protected Item getItemFromBlockEntity() {
        return AE2CCBridge.ADAPTER_BLOCK.asItem();
    }

    private void refreshState() {
        this.craftingJobLock.lock();

        try {
            this.craftingJobs.removeIf(job -> {
                if (job.link().isCanceled() || job.link().isDone()) {
                    this.peripheral.notify(job);
                    return true;
                }

                return false;
            });
        } finally {
            this.craftingJobLock.unlock();
        }
    }

    public final class AdapterPeripheral implements IPeripheral {

        private static Map<String, Object> deriveLuaRepresentation(AEKey key) {
            String displayName, id, type;

            if (key instanceof AEFluidKey) {
                type = "fluid";
            } else if (key instanceof AEItemKey) {
                type = "item";
            } else {
                throw new IllegalArgumentException();
            }

            id = key.getId().toString();
            displayName = key.getDisplayName().getString();

            return Map.of(
                "type", type,
                "id", id,
                "displayName", displayName
            );
        }

        private final ReentrantLock attachedComputerLock = new ReentrantLock();
        private final List<IComputerAccess> attachedComputers = new ArrayList<>();

        private final AE2CCAdapterBlockEntity blockEntity = AE2CCAdapterBlockEntity.this;

        @Override
        public String getType() {
            return "ae2cc_adapter";
        }

        @Override
        public boolean equals(IPeripheral obj) {
            if (!(obj instanceof AdapterPeripheral other)) return false;
            return this.blockEntity == other.blockEntity;
        }

        @Override
        public void attach(IComputerAccess computer) {
            this.attachedComputerLock.lock();

            try {
                attachedComputers.add(computer);
            } finally {
                this.attachedComputerLock.unlock();
            }
        }

        @Override
        public void detach(IComputerAccess computer) {
            this.attachedComputerLock.lock();

            try {
                attachedComputers.remove(computer);
            } finally {
                this.attachedComputerLock.unlock();
            }
        }

        private void notify(CraftingJob job) {
            this.attachedComputerLock.lock();

            try {
                for (IComputerAccess attachedComputer : this.attachedComputers) {
                    attachedComputer.queueEvent("ae2cc:crafting_done", deriveLuaRepresentation(job.key()));
                }
            } finally {
                this.attachedComputerLock.unlock();
            }
        }

        @LuaFunction(mainThread = true)
        public List<Map<String, Object>> getAvailableObjects() throws LuaException {
            IGrid grid = blockEntity.getMainNode().getGrid();
            if (grid == null) throw new LuaException("Cannot connect to AE2 Network");

            MEStorage inventory = grid.getStorageService().getInventory();
            KeyCounter keyCounter = inventory.getAvailableStacks();

            return StreamSupport.stream(keyCounter.spliterator(), false)
                .map(it -> {
                    AEKey key = it.getKey();
                    long value = it.getLongValue();

                    Map<String, Object> data = new HashMap<>();
                    data.put("id", key.getId().toString());
                    data.put("displayName", key.getDisplayName().getString());

                    if (key instanceof AEFluidKey) {
                        data.put("type", "fluid");
                        data.put("amount", value / 81);
                    } else if (key instanceof AEItemKey) {
                        data.put("type", "item");
                        data.put("amount", value);
                    } else {
                        return null;
                    }

                    return Map.copyOf(data);
                })
                .filter(Objects::nonNull)
                .toList();
        }

        @LuaFunction(mainThread = true)
        public List<Map<String, Object>> getCraftableObjects() throws LuaException {
            IGrid grid = blockEntity.getMainNode().getGrid();
            if (grid == null) throw new LuaException("Cannot connect to AE2 Network");

            ICraftingService craftingService = grid.getCraftingService();
            return craftingService.getCraftables((it) -> it instanceof AEFluidKey || it instanceof AEItemKey)
                .stream()
                .map(AdapterPeripheral::deriveLuaRepresentation)
                .toList();
        }

        @LuaFunction
        public MethodResult scheduleCrafting(String type, String id, long amount, Optional<String> cpu) throws LuaException {
            IGrid grid = blockEntity.getMainNode().getGrid();
            if (grid == null) throw new LuaException("Cannot connect to AE2 Network");

            ResourceLocation resourceLocation = ResourceLocation.tryParse(id);
            if (resourceLocation == null) throw new LuaException("Invalid ID: '" + id + "'");

            AEKey key = switch (type) {
                case "fluid" -> {
                    Fluid fluid = Registry.FLUID.get(resourceLocation); // TODO check if this exists
                    yield AEFluidKey.of(fluid);
                }
                case "item" -> {
                    Item item = Registry.ITEM.get(resourceLocation); // TODO check if this exists
                    yield AEItemKey.of(item);
                }
                default -> throw new LuaException("Invalid type: '" + type + "' (Valid types are 'fluid' and 'item')");
            };

            ICraftingService craftingService = grid.getCraftingService();
            IActionSource actionSource = IActionSource.ofMachine(this.blockEntity);

            Future<ICraftingPlan> futureCraftingPlan = craftingService.beginCraftingCalculation(
                blockEntity.level,
                () -> actionSource,
                key,
                amount,
                CalculationStrategy.CRAFT_LESS
            );

            ICraftingPlan craftingPlan;

            try {
                // TODO The docs warn not to do this. There must be a better way.
                craftingPlan = futureCraftingPlan.get();
            } catch (Exception e) {
                throw new LuaException("Cannot calculate crafting plan");
            }

            ICraftingCPU craftingCPU = null;

            if (cpu.isPresent()) {
                craftingCPU = cpu.flatMap(targetName -> craftingService.getCpus().stream().filter(it -> {
                        Component cpuName = it.getName();
                        return cpuName != null && targetName.equals(cpuName.getString());
                    }).findAny())
                    .orElseThrow(() -> new LuaException("Cannot find crafting CPU with name '" + cpu.get() + "'"));
            }

            ICraftingSubmitResult craftingSubmitResult = craftingService.trySubmitJob(craftingPlan, null, craftingCPU, false, actionSource);
            if (!craftingSubmitResult.successful()) {
                // TODO provide a better error message
                throw new LuaException("Cannot submit crafting plan");
            }

            ICraftingLink craftingLink = craftingSubmitResult.link();
            CraftingJob craftingJob = new CraftingJob(key, craftingLink);

            craftingJobLock.lock();

            try {
                craftingJobs.add(craftingJob);
            } finally {
                craftingJobLock.unlock();
            }

            return MethodResult.of(true);
        }

    }

}