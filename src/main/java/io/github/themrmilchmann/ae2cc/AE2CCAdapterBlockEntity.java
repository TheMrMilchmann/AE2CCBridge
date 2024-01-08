/*
 * Copyright (c) 2022-2024 Leon Linhart
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

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.google.common.collect.ImmutableSet;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class AE2CCAdapterBlockEntity extends AENetworkBlockEntity implements ICraftingRequester, IGridConnectedBlockEntity, IGridTickable {

    private static final AtomicBoolean INTERNAL_ASSUMPTION_FAILED = new AtomicBoolean(false);

    private static final Logger LOGGER = LogManager.getLogger();

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

    private final ReentrantLock pendingJobLock = new ReentrantLock();
    private final List<PendingCraftingJob> pendingJobs = new ArrayList<>();

    private final ReentrantLock craftingJobLock = new ReentrantLock();
    private final List<CraftingJob> craftingJobs = new ArrayList<>();

    private record PendingCraftingJob(UUID id, Future<ICraftingPlan> futureCraftingPlan, @Nullable String cpu) {}
    private record CraftingJob(UUID id, ICraftingLink link) {}

    private final AdapterPeripheral peripheral = new AdapterPeripheral();

    public AE2CCAdapterBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(AE2CCBridge.ADAPTER_BLOCK_ENTITY, blockPos, blockState);

        this.getMainNode().addService(ICraftingRequester.class, this);
        this.getMainNode().addService(IGridTickable.class, this);
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(10, 10, false, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        this.pendingJobLock.lock();

        try {
            ICraftingService craftingService = node.getGrid().getCraftingService();

            Iterator<PendingCraftingJob> pendingJobIterator = this.pendingJobs.iterator();

            while (pendingJobIterator.hasNext()) {
                PendingCraftingJob pendingJob = pendingJobIterator.next();
                Future<ICraftingPlan> futureCraftingPlan = pendingJob.futureCraftingPlan();

                if (futureCraftingPlan.isCancelled()) {
                    pendingJobIterator.remove();
                    this.peripheral.notify("ae2cc:crafting_cancelled", pendingJob.id().toString(), "CANCELLED");

                    continue;
                }

                if (!futureCraftingPlan.isDone()) continue;
                pendingJobIterator.remove();

                ICraftingPlan craftingPlan;

                try {
                    craftingPlan = futureCraftingPlan.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }

                ICraftingCPU craftingCPU = null;

                if (pendingJob.cpu() != null) {
                    craftingCPU = craftingService.getCpus().stream().filter(it -> {
                        Component cpuName = it.getName();
                        return cpuName != null && pendingJob.cpu().equals(cpuName.getString());
                    }).findAny().orElse(null);

                    if (craftingCPU == null) {
                        this.peripheral.notify("ae2cc:crafting_cancelled", pendingJob.id().toString(), "CPU_NOT_FOUND");
                        continue;
                    }
                }

                IActionSource actionSource = IActionSource.ofMachine(this);
                ICraftingSubmitResult craftingSubmitResult = node.getGrid().getCraftingService().submitJob(craftingPlan, this, craftingCPU, false, actionSource);
                if (!craftingSubmitResult.successful()) {
                    String reason = switch (Objects.requireNonNull(craftingSubmitResult.errorCode())) {
                        case INCOMPLETE_PLAN -> "INCOMPLETE_PLAN";
                        case NO_CPU_FOUND -> "NO_CPU_FOUND";
                        case NO_SUITABLE_CPU_FOUND -> "NO_SUITABLE_CPU_FOUND";
                        case CPU_BUSY -> "CPU_BUSY";
                        case CPU_OFFLINE -> "CPU_OFFLINE";
                        case CPU_TOO_SMALL -> "CPU_TOO_SMALL";
                        case MISSING_INGREDIENT -> "MISSING_INGREDIENT";
                    };

                    this.peripheral.notify("ae2cc:crafting_cancelled", pendingJob.id().toString(), reason);
                    continue;
                }

                ICraftingLink craftingLink = craftingSubmitResult.link();
                assert craftingLink != null;

                CraftingJob craftingJob = new CraftingJob(pendingJob.id(), craftingLink);

                craftingJobLock.lock();

                try {
                    craftingJobs.add(craftingJob);
                    this.peripheral.notify("ae2cc:crafting_started", craftingJob.id().toString());
                } finally {
                    craftingJobLock.unlock();
                }
            }

            return this.pendingJobs.isEmpty() ? TickRateModulation.IDLE : TickRateModulation.FASTER;
        } finally {
            this.pendingJobLock.unlock();
        }
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
                    this.peripheral.notify("ae2cc:crafting_done", job.id().toString());
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
            UUID id = jobTag.getUUID("id");
            ICraftingLink link = StorageHelper.loadCraftingLink(jobTag.getCompound("link"), this);

            craftingJobs.add(new CraftingJob(id, link));
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
                jobTag.putUUID("id", job.id());

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

        private final ReentrantLock attachedComputerLock = new ReentrantLock();
        private final List<IComputerAccess> attachedComputers = new ArrayList<>();

        private final AE2CCAdapterBlockEntity blockEntity = AE2CCAdapterBlockEntity.this;

        @Nonnull
        @Override
        public String getType() {
            return "ae2cc_adapter";
        }

        @Override
        public boolean equals(@Nullable IPeripheral obj) {
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

        private void notify(String event, Object... data) {
            this.attachedComputerLock.lock();

            try {
                for (IComputerAccess attachedComputer : this.attachedComputers) {
                    attachedComputer.queueEvent(event, data);
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
                .map(AE2CCAdapterBlockEntity::deriveLuaRepresentation)
                .toList();
        }

        @LuaFunction
        public final List<Map<String, Object>> getCraftingCPUs() throws LuaException {
            IGrid grid = blockEntity.getMainNode().getGrid();
            if (grid == null) throw new LuaException("Cannot connect to AE2 Network");

            Set<ICraftingCPU> cpus = grid.getCraftingService().getCpus();

            return cpus.stream()
                .map(cpu -> {
                    String selectionMode = switch (cpu.getSelectionMode()) {
                        case ANY -> "ANY";
                        case MACHINE_ONLY -> "MACHINE_ONLY";
                        case PLAYER_ONLY -> "PLAYER_ONLY";
                    };

                    HashMap<String, Object> data = new HashMap<>();
                    data.put("availableCoProcessors", cpu.getCoProcessors());
                    data.put("availableStorage", cpu.getAvailableStorage());
                    data.put("selectionMode", selectionMode);

                    Component name = cpu.getName();
                    if (name != null) {
                        data.put("name", name.getContents());
                    }

                    CraftingJobStatus jobStatus = cpu.getJobStatus();
                    if (jobStatus != null) {
                        Map<String, Object> jobData = new HashMap<>();
                        jobData.put("totalObjects", jobStatus.totalItems());
                        jobData.put("craftedObjects", jobStatus.progress());
                        jobData.put("elapsedNanos", jobStatus.elapsedTimeNanos());

                        if (cpu instanceof CraftingCPUCluster cluster) {
                            ICraftingLink link = cluster.craftingLogic.getLastLink();
                            if (link != null) {
                                jobData.put("systemID", link.getCraftingID());
                            }
                        } else {
                            if (!INTERNAL_ASSUMPTION_FAILED.getAndSet(true)) {
                                LOGGER.error(
                                    """
                                    Incorrect assumption about AE2 internals:
                                    ICraftingCPU implementation is not a CraftingCPUCluster: {}
                                    
                                    If you are using an up-to-date version of AE2CC, please make
                                    sure that this is reported.
                                    https://github.com/TheMrMilchmann/AE2CCBridge/issues
                                    """,
                                    cpu.getClass().getName()
                                );
                            }
                        }

                        GenericStack stack = jobStatus.crafting();
                        Map<String, Object> stackData = new HashMap<>();
                        stackData.put("amount", stack.amount());
                        stackData.putAll(deriveLuaRepresentation(stack.what()));

                        jobData.put("output", Map.copyOf(stackData));
                        data.put("jobStatus", Map.copyOf(jobData));
                    }

                    return Map.copyOf(data);
                })
                .toList();
        }

        @LuaFunction
        public final List<Map<String, Object>> getIssuedCraftingJobs() {
            pendingJobLock.lock();

            try {
                craftingJobLock.lock();

                try {
                    return Stream.concat(
                        pendingJobs.stream().map(pendingJob -> Map.<String, Object>of(
                            "state", "SCHEDULED",
                            "jobID", pendingJob.id().toString()
                        )),
                        craftingJobs.stream().map(craftingJob -> Map.<String, Object>of(
                            "state", "STARTED",
                            "jobID", craftingJob.id().toString(),
                            "systemID", craftingJob.link().getCraftingID()
                        ))
                    ).toList();
                } finally {
                    craftingJobLock.unlock();
                }
            } finally {
                pendingJobLock.unlock();
            }
        }

        @LuaFunction
        public final String scheduleCrafting(String type, String id, long amount) throws LuaException {
            IGrid grid = blockEntity.getMainNode().getGrid();
            if (grid == null) throw new LuaException("Cannot connect to AE2 Network");

            ResourceLocation resourceLocation = ResourceLocation.tryParse(id);
            if (resourceLocation == null) throw new LuaException("Invalid ID: '" + id + "'");

            AEKey key = switch (type) {
                case "fluid" -> {
                    Fluid fluid = BuiltInRegistries.FLUID.getOptional(resourceLocation).orElseThrow(() -> new LuaException("Fluid does not exist: " + resourceLocation));
                    yield AEFluidKey.of(fluid);
                }
                case "item" -> {
                    Item item = BuiltInRegistries.ITEM.getOptional(resourceLocation).orElseThrow(() -> new LuaException("Item does not exist: " + resourceLocation));
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

            pendingJobLock.lock();

            try {
                UUID jobID = UUID.randomUUID();
                PendingCraftingJob pendingCraftingJob = new PendingCraftingJob(
                    jobID,
                    futureCraftingPlan,
                    null
                );

                pendingJobs.add(pendingCraftingJob);
                return jobID.toString();
            } finally {
                pendingJobLock.unlock();
            }
        }

    }

}