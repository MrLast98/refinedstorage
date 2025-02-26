package com.refinedmods.refinedstorage.apiimpl.network;

import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.INetworkNodeGraph;
import com.refinedmods.refinedstorage.api.network.INetworkNodeGraphEntry;
import com.refinedmods.refinedstorage.api.network.NetworkType;
import com.refinedmods.refinedstorage.api.network.grid.handler.IFluidGridHandler;
import com.refinedmods.refinedstorage.api.network.grid.handler.IItemGridHandler;
import com.refinedmods.refinedstorage.api.network.item.INetworkItemManager;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.StorageType;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.externalstorage.IExternalStorage;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.CraftingManager;
import com.refinedmods.refinedstorage.apiimpl.network.grid.handler.FluidGridHandler;
import com.refinedmods.refinedstorage.apiimpl.network.grid.handler.ItemGridHandler;
import com.refinedmods.refinedstorage.apiimpl.network.item.NetworkItemManager;
import com.refinedmods.refinedstorage.apiimpl.network.node.RootNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.security.SecurityManager;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.FluidStorageCache;
import com.refinedmods.refinedstorage.apiimpl.storage.cache.ItemStorageCache;
import com.refinedmods.refinedstorage.apiimpl.storage.tracker.FluidStorageTracker;
import com.refinedmods.refinedstorage.apiimpl.storage.tracker.ItemStorageTracker;
import com.refinedmods.refinedstorage.block.AIControllerBlock;
import com.refinedmods.refinedstorage.block.ControllerBlock;
import com.refinedmods.refinedstorage.blockentity.AIControllerBlockEntity;
import com.refinedmods.refinedstorage.energy.BaseEnergyStorage;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.refinedmods.refinedstorage.blockentity.config.IRedstoneConfigurable;
import com.refinedmods.refinedstorage.blockentity.config.RedstoneMode;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemHandlerHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Predicate;

public class Network implements INetwork, IRedstoneConfigurable {
    private static final int THROTTLE_INACTIVE_TO_ACTIVE = 20;
    private static final int THROTTLE_ACTIVE_TO_INACTIVE = 4;

    private static final String NBT_ENERGY = "Energy";
    private static final String NBT_ITEM_STORAGE_TRACKER_ID = "ItemStorageTrackerId";
    private static final String NBT_FLUID_STORAGE_TRACKER_ID = "FluidStorageTrackerId";

    private static final Logger LOGGER = LogManager.getLogger(Network.class);

    private final IItemGridHandler itemGridHandler = new ItemGridHandler(this);
    private final IFluidGridHandler fluidGridHandler = new FluidGridHandler(this);
    private final INetworkItemManager networkItemManager = new NetworkItemManager(this);
    private final INetworkNodeGraph nodeGraph = new NetworkNodeGraph(this);
    private final ICraftingManager craftingManager = new CraftingManager(this);
    private final ISecurityManager securityManager = new SecurityManager(this);
    private final IStorageCache<ItemStack> itemStorage = new ItemStorageCache(this);
    private final IStorageCache<FluidStack> fluidStorage = new FluidStorageCache(this);
    private final BaseEnergyStorage energy = new BaseEnergyStorage(RS.SERVER_CONFIG.getController().getCapacity(), RS.SERVER_CONFIG.getController().getMaxTransfer(), 0);
    private final RootNetworkNode root;
    private final BlockPos pos;
    private final Level level;
    private final NetworkType type;
    private ItemStorageTracker itemStorageTracker;
    private UUID itemStorageTrackerId;
    private FluidStorageTracker fluidStorageTracker;
    private UUID fluidStorageTrackerId;
    private ControllerBlock.EnergyType lastEnergyType = ControllerBlock.EnergyType.OFF;
    private int lastEnergyUsage;
    private RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private boolean redstonePowered = false;

    private boolean amILoaded = false;
    private boolean throttlingDisabled = true; // Will be enabled after first update
    private boolean couldRun;
    private int ticksSinceUpdateChanged;
    private int ticks;
    private final long[] tickTimes = new long[100];
    private int tickCounter = 0;

    public Network(Level level, BlockPos pos, NetworkType type) {
        this.pos = pos;
        this.level = level;
        this.type = type;
        this.root = new RootNetworkNode(this, level, pos);
        this.nodeGraph.addListener(() -> {
            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof ControllerBlockEntity) {
                ((ControllerBlockEntity) blockEntity).getDataManager().sendParameterToWatchers(ControllerBlockEntity.NODES);
            } else if (blockEntity instanceof AIControllerBlockEntity) {
                ((AIControllerBlockEntity) blockEntity).getDataManager().sendParameterToWatchers(AIControllerBlockEntity.NODES);
            }
        });
    }

    public static int getEnergyScaled(int stored, int capacity, int scale) {
        return (int) ((float) stored / (float) capacity * (float) scale);
    }

    public static ControllerBlock.EnergyType getEnergyType(int stored, int capacity) {
        int energy = getEnergyScaled(stored, capacity, 100);

        if (energy <= 0) {
            return ControllerBlock.EnergyType.OFF;
        } else if (energy <= 10) {
            return ControllerBlock.EnergyType.NEARLY_OFF;
        } else if (energy <= 20) {
            return ControllerBlock.EnergyType.NEARLY_ON;
        }

        return ControllerBlock.EnergyType.ON;
    }

    public RootNetworkNode getRoot() {
        return root;
    }

    @Override
    public BlockPos getPosition() {
        return pos;
    }

    @Override
    public boolean canRun() {
        return amILoaded && energy.getEnergyStored() >= getEnergyUsage() && redstoneMode.isEnabled(redstonePowered);
    }

    public void setRedstonePowered(boolean redstonePowered) {
        this.redstonePowered = redstonePowered;
    }

    @Override
    public INetworkNodeGraph getNodeGraph() {
        return nodeGraph;
    }

    @Override
    public ISecurityManager getSecurityManager() {
        return securityManager;
    }

    @Override
    public ICraftingManager getCraftingManager() {
        return craftingManager;
    }

    @Override
    public void update() {
        if (!level.isClientSide) {
            long tickStart = Util.getNanos();

            if (ticks == 0) {
                redstonePowered = level.hasNeighborSignal(pos);
            }

            ++ticks;

            amILoaded = level.isLoaded(pos);

            updateEnergyUsage();

            if (canRun()) {
                craftingManager.update();

                if (!craftingManager.getTasks().isEmpty()) {
                    markDirty();
                }
            }

            if (type == NetworkType.NORMAL) {
                if (!RS.SERVER_CONFIG.getController().getUseEnergy()) {
                    energy.setStored(this.energy.getMaxEnergyStored());
                } else if (!RS.SERVER_CONFIG.getAIController().getUseEnergy()){
                    energy.setStored(this.energy.getMaxEnergyStored());
                } else {
                    energy.extractEnergyBypassCanExtract(getEnergyUsage(), false);
                }
            } else if (type == NetworkType.CREATIVE) {
                energy.setStored(energy.getMaxEnergyStored());
            }

            boolean canRun = canRun();

            if (couldRun != canRun) {
                ++ticksSinceUpdateChanged;

                if ((canRun ? (ticksSinceUpdateChanged > THROTTLE_INACTIVE_TO_ACTIVE) : (ticksSinceUpdateChanged > THROTTLE_ACTIVE_TO_INACTIVE)) || throttlingDisabled) {
                    ticksSinceUpdateChanged = 0;
                    couldRun = canRun;
                    throttlingDisabled = false;

                    LOGGER.debug("Network at position {} changed running state to {}, causing an invalidation of the node graph", pos, couldRun);

                    nodeGraph.invalidate(Action.PERFORM, level, pos);
                    securityManager.invalidate();
                }
            } else {
                ticksSinceUpdateChanged = 0;
            }

            ControllerBlock.EnergyType energyType = getEnergyType();
            if (lastEnergyType != energyType) {
                lastEnergyType = energyType;

                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof ControllerBlock) {
                    level.setBlockAndUpdate(pos, state.setValue(ControllerBlock.ENERGY_TYPE, energyType));
                } else if (state.getBlock() instanceof AIControllerBlock) {
                    level.setBlockAndUpdate(pos, state.setValue(AIControllerBlock.ENERGY_TYPE, energyType));
                }
            }

            tickTimes[tickCounter % tickTimes.length] = Util.getNanos() - tickStart;
            tickCounter++;
        }
    }

    @Override
    public IItemGridHandler getItemGridHandler() {
        return itemGridHandler;
    }

    @Override
    public IFluidGridHandler getFluidGridHandler() {
        return fluidGridHandler;
    }

    @Override
    public INetworkItemManager getNetworkItemManager() {
        return networkItemManager;
    }

    @Override
    public void onRemoved() {
        for (ICraftingTask task : craftingManager.getTasks()) {
            task.onCancelled();
        }

        nodeGraph.disconnectAll();
        API.instance().getStorageTrackerManager((ServerLevel) getLevel()).remove(itemStorageTrackerId);
        API.instance().getStorageTrackerManager((ServerLevel) getLevel()).remove(fluidStorageTrackerId);
    }

    @Override
    public IStorageCache<ItemStack> getItemStorageCache() {
        return itemStorage;
    }

    @Override
    public IStorageCache<FluidStack> getFluidStorageCache() {
        return fluidStorage;
    }

    @Override
    @Nonnull
    public ItemStack insertItem(@Nonnull ItemStack stack, int size, Action action) {
        if (stack.isEmpty()) {
            return stack;
        }

        if (itemStorage.getStorages().isEmpty()) {
            return ItemHandlerHelper.copyStackWithSize(stack, size);
        }

        ItemStack remainder = stack;

        int inserted = 0;
        int insertedExternally = 0;

        for (IStorage<ItemStack> storage : this.itemStorage.getStorages()) {
            if (storage.getAccessType() == AccessType.EXTRACT) {
                continue;
            }

            int storedPre = storage.getStored();

            remainder = storage.insert(remainder, size, action);

            if (action == Action.PERFORM) {
                inserted += storage.getCacheDelta(storedPre, size, remainder);
            }

            if (remainder.isEmpty()) {
                // The external storage is responsible for sending changes, we don't need to anymore
                if (storage instanceof IExternalStorage && action == Action.PERFORM) {
                    ((IExternalStorage) storage).update(this);

                    insertedExternally += size;
                }

                break;
            } else {
                // The external storage is responsible for sending changes, we don't need to anymore
                if (size != remainder.getCount() && storage instanceof IExternalStorage && action == Action.PERFORM) {
                    ((IExternalStorage) storage).update(this);

                    insertedExternally += size - remainder.getCount();
                }

                size = remainder.getCount();
            }
        }

        if (action == Action.PERFORM && inserted - insertedExternally > 0) {
            itemStorage.add(stack, inserted - insertedExternally, false, false);
        }

        return remainder;
    }

    @Override
    @Nonnull
    public ItemStack extractItem(@Nonnull ItemStack stack, int size, int flags, Action action, Predicate<IStorage<ItemStack>> filter) {
        if (stack.isEmpty()) {
            return stack;
        }

        int requested = size;
        int received = 0;

        int extractedExternally = 0;

        ItemStack newStack = ItemStack.EMPTY;

        for (IStorage<ItemStack> storage : this.itemStorage.getStorages()) {
            ItemStack took = ItemStack.EMPTY;

            if (filter.test(storage) && storage.getAccessType() != AccessType.INSERT) {
                took = storage.extract(stack, requested - received, flags, action);
            }

            if (!took.isEmpty()) {
                // The external storage is responsible for sending changes, we don't need to anymore
                if (storage instanceof IExternalStorage && action == Action.PERFORM) {
                    ((IExternalStorage) storage).update(this);

                    extractedExternally += took.getCount();
                }

                if (newStack.isEmpty()) {
                    newStack = took;
                } else {
                    newStack.grow(took.getCount());
                }

                received += took.getCount();
            }

            if (requested == received) {
                break;
            }
        }

        if (newStack.getCount() - extractedExternally > 0 && action == Action.PERFORM) {
            itemStorage.remove(newStack, newStack.getCount() - extractedExternally, false);
        }

        return newStack;
    }

    @Override
    @Nonnull
    public FluidStack insertFluid(@Nonnull FluidStack stack, int size, Action action) {
        if (stack.isEmpty()) {
            return stack;
        }

        if (fluidStorage.getStorages().isEmpty()) {
            return StackUtils.copy(stack, size);
        }

        FluidStack remainder = stack;

        int inserted = 0;
        int insertedExternally = 0;

        for (IStorage<FluidStack> storage : this.fluidStorage.getStorages()) {
            if (storage.getAccessType() == AccessType.EXTRACT) {
                continue;
            }

            int storedPre = storage.getStored();

            remainder = storage.insert(remainder, size, action);

            if (action == Action.PERFORM) {
                inserted += storage.getCacheDelta(storedPre, size, remainder);
            }

            if (remainder.isEmpty()) {
                // The external storage is responsible for sending changes, we don't need to anymore
                if (storage instanceof IExternalStorage && action == Action.PERFORM) {
                    ((IExternalStorage) storage).update(this);

                    insertedExternally += size;
                }

                break;
            } else {
                // The external storage is responsible for sending changes, we don't need to anymore
                if (size != remainder.getAmount() && storage instanceof IExternalStorage && action == Action.PERFORM) {
                    ((IExternalStorage) storage).update(this);

                    insertedExternally += size - remainder.getAmount();
                }

                size = remainder.getAmount();
            }
        }

        if (action == Action.PERFORM && inserted - insertedExternally > 0) {
            fluidStorage.add(stack, inserted - insertedExternally, false, false);
        }

        return remainder;
    }

    @Override
    @Nonnull
    public FluidStack extractFluid(@Nonnull FluidStack stack, int size, int flags, Action action, Predicate<IStorage<FluidStack>> filter) {
        if (stack.isEmpty()) {
            return stack;
        }

        int requested = size;
        int received = 0;

        int extractedExternally = 0;

        FluidStack newStack = FluidStack.EMPTY;

        for (IStorage<FluidStack> storage : this.fluidStorage.getStorages()) {
            FluidStack took = FluidStack.EMPTY;

            if (filter.test(storage) && storage.getAccessType() != AccessType.INSERT) {
                took = storage.extract(stack, requested - received, flags, action);
            }

            if (!took.isEmpty()) {
                // The external storage is responsible for sending changes, we don't need to anymore
                if (storage instanceof IExternalStorage && action == Action.PERFORM) {
                    ((IExternalStorage) storage).update(this);

                    extractedExternally += took.getAmount();
                }

                if (newStack.isEmpty()) {
                    newStack = took;
                } else {
                    newStack.grow(took.getAmount());
                }

                received += took.getAmount();
            }

            if (requested == received) {
                break;
            }
        }

        if (newStack.getAmount() - extractedExternally > 0 && action == Action.PERFORM) {
            fluidStorage.remove(newStack, newStack.getAmount() - extractedExternally, false);
        }

        return newStack;
    }

    @Override
    public IStorageTracker<ItemStack> getItemStorageTracker() {
        if (itemStorageTracker == null) {
            if (itemStorageTrackerId == null) {
                this.itemStorageTrackerId = UUID.randomUUID();
            }

            this.itemStorageTracker = (ItemStorageTracker) API.instance().getStorageTrackerManager((ServerLevel) level).getOrCreate(itemStorageTrackerId, StorageType.ITEM);
        }

        return itemStorageTracker;
    }

    @Override
    public IStorageTracker<FluidStack> getFluidStorageTracker() {
        if (fluidStorageTracker == null) {
            if (fluidStorageTrackerId == null) {
                this.fluidStorageTrackerId = UUID.randomUUID();
            }

            this.fluidStorageTracker = (FluidStorageTracker) API.instance().getStorageTrackerManager((ServerLevel) level).getOrCreate(fluidStorageTrackerId, StorageType.FLUID);
        }

        return fluidStorageTracker;
    }

    @Override
    public Level getLevel() {
        return level;
    }

    @Override
    public INetwork readFromNbt(CompoundTag tag) {
        if (tag.contains(NBT_ENERGY)) {
            this.energy.setStored(tag.getInt(NBT_ENERGY));
        }

        redstoneMode = RedstoneMode.read(tag);

        craftingManager.readFromNbt(tag);

        if (tag.contains(NBT_ITEM_STORAGE_TRACKER_ID)) {
            this.itemStorageTrackerId = tag.getUUID(NBT_ITEM_STORAGE_TRACKER_ID);
        }

        if (tag.contains(NBT_FLUID_STORAGE_TRACKER_ID)) {
            this.fluidStorageTrackerId = tag.getUUID(NBT_FLUID_STORAGE_TRACKER_ID);
        }

        return this;
    }

    @Override
    public CompoundTag writeToNbt(CompoundTag tag) {
        tag.putInt(NBT_ENERGY, this.energy.getEnergyStored());

        redstoneMode.write(tag);

        craftingManager.writeToNbt(tag);
        if (itemStorageTrackerId != null) {
            tag.putUUID(NBT_ITEM_STORAGE_TRACKER_ID, itemStorageTrackerId);
        }

        if (fluidStorageTrackerId != null) {
            tag.putUUID(NBT_FLUID_STORAGE_TRACKER_ID, fluidStorageTrackerId);
        }

        return tag;
    }

    @Override
    public long[] getTickTimes() {
        return tickTimes;
    }

    @Override
    public void markDirty() {
        API.instance().getNetworkManager((ServerLevel) level).markForSaving();
    }

    public ControllerBlock.EnergyType getEnergyType() {
        if (!redstoneMode.isEnabled(redstonePowered)) {
            return ControllerBlock.EnergyType.OFF;
        }

        return getEnergyType(this.energy.getEnergyStored(), this.energy.getMaxEnergyStored());
    }

    

    @Override
    public RedstoneMode getRedstoneMode() {
        return redstoneMode;
    }

    @Override
    public void setRedstoneMode(RedstoneMode mode) {
        this.redstoneMode = mode;

        markDirty();
    }

    private void updateEnergyUsage() {
        if (!redstoneMode.isEnabled(redstonePowered)) {
            this.lastEnergyUsage = 0;
            return;
        }

        int usage = RS.SERVER_CONFIG.getController().getBaseUsage();

        for (INetworkNodeGraphEntry entry : nodeGraph.all()) {
            if (entry.getNode().isActive()) {
                usage += entry.getNode().getEnergyUsage();
            }
        }

        this.lastEnergyUsage = usage;
    }

    @Override
    public int getEnergyUsage() {
        return lastEnergyUsage;
    }

    @Override
    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    @Override
    public NetworkType getType() {
        return type;
    }
}
