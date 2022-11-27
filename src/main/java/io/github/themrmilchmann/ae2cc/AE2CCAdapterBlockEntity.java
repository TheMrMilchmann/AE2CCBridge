package io.github.themrmilchmann.ae2cc;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.google.common.collect.ImmutableSet;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.StreamSupport;

public final class AE2CCAdapterBlockEntity extends AENetworkBlockEntity implements ICraftingRequester, IGridConnectedBlockEntity {

    private final ReentrantLock craftingJobLock = new ReentrantLock();
    private final List<CraftingJob> craftingJobs = new ArrayList<>();

    private record CraftingJob(AEKey key, ICraftingLink link) {}

    private final AdapterPeripheral peripheral = new AdapterPeripheral();

    public AE2CCAdapterBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(AE2CCBridge.ADAPTER_BLOCK_ENTITY, blockPos, blockState);

        this.getMainNode().addService(ICraftingRequester.class, this);
    }

    public IPeripheral asPeripheral() {
        return this.peripheral;
    }

    @Override
    protected Item getItemFromBlockEntity() {
        return AE2CCBridge.ADAPTER_BLOCK.asItem();
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return this.craftingJobs.stream().map(CraftingJob::link).collect(ImmutableSet.toImmutableSet());
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        return 0;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        this.craftingJobLock.lock();

        try {
            this.craftingJobs.removeIf(job -> {
                if (job.link() == link) {
                    this.peripheral.notify(job);
                    return true;
                }

                return false;
            });
        } finally {
            this.craftingJobLock.unlock();
        }
    }

    @Nullable
    @Override
    public IGridNode getActionableNode() {
        return this.getGridNode();
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);

        ListTag jobsTag = data.getList("jobs", Tag.TAG_COMPOUND);
        List<CraftingJob> craftingJobs = new ArrayList<>();

        for (int i = 0; i < jobsTag.size(); i++) {
            CompoundTag jobTag = jobsTag.getCompound(i);
            AEKey key = AEKey.fromTagGeneric(jobTag.getCompound("key"));
            ICraftingLink link = StorageHelper.loadCraftingLink(jobTag.getCompound("link"), this);

            craftingJobs.add(new CraftingJob(key, link));
        }

        this.craftingJobLock.lock();

        try {
            this.craftingJobs.clear();
            this.craftingJobs.addAll(craftingJobs);
        } finally {
            this.craftingJobLock.unlock();
        }
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);

        ListTag jobsTag = new ListTag();

        this.craftingJobLock.lock();

        try {
            for (CraftingJob job : this.craftingJobs) {
                CompoundTag jobTag = new CompoundTag();
                jobTag.put("key", job.key().toTagGeneric());

                CompoundTag linkTag = new CompoundTag();
                job.link().writeToNBT(linkTag);

                jobTag.put("link", linkTag);
            }
        } finally {
            this.craftingJobLock.unlock();
        }

        data.put("jobs", jobsTag);
    }

    @SuppressWarnings("FinalMethodInFinalClass")
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
        public final List<Map<String, Object>> getAvailableObjects() throws LuaException {
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
        public final List<Map<String, Object>> getCraftableObjects() throws LuaException {
            IGrid grid = blockEntity.getMainNode().getGrid();
            if (grid == null) throw new LuaException("Cannot connect to AE2 Network");

            ICraftingService craftingService = grid.getCraftingService();
            return craftingService.getCraftables((it) -> it instanceof AEFluidKey || it instanceof AEItemKey)
                .stream()
                .map(AdapterPeripheral::deriveLuaRepresentation)
                .toList();
        }

        @LuaFunction
        public final MethodResult scheduleCrafting(String type, String id, long amount, Optional<String> cpu) throws LuaException {
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

            ICraftingSubmitResult craftingSubmitResult = craftingService.trySubmitJob(craftingPlan, AE2CCAdapterBlockEntity.this, craftingCPU, false, actionSource);
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