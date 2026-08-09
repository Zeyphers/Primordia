package dev.jsz.primordia.block;

import dev.jsz.primordia.lab.ColdStorage;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaBlockEntities;
import dev.jsz.primordia.registry.PrimordiaSounds;
import dev.jsz.primordia.screen.SampleCoolerScreenHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Cold storage for tissue samples: a small shulker box that only takes specimens and keeps them.
 * <p>
 * Samples age against the world clock rather than on a countdown, so nothing here pauses. Cooling
 * is done by walking the collection tick forward, which is all {@link ColdStorage} is.
 * <p>
 * Extends {@link BaseContainerBlockEntity} for one specific inherited behaviour: it implements
 * {@code collectImplicitComponents} and {@code applyImplicitComponents} for
 * {@code minecraft:container}, which is the whole mechanism behind a shulker box keeping its
 * contents through being broken. Paired with the loot table copying that component onto the drop,
 * and with {@link #preRemoveSideEffects} declining to scatter anything, a broken cooler arrives in
 * the player's hand still full, and every sample inside still remembers which animal it came from.
 */
public class SampleCoolerBlockEntity extends BaseContainerBlockEntity {

	/**
	 * Eight across, two down.
	 * <p>
	 * Short of shulker parity on purpose. A cooler is a trip's worth of specimens, not a chest: the
	 * shelf life only means anything while the box can fill up, and twenty-seven slots is more than
	 * a player can gather before the first samples in are spoiling anyway.
	 */
	public static final int SLOT_COUNT = 16;

	private NonNullList<ItemStack> inventory = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

	public SampleCoolerBlockEntity(BlockPos pos, BlockState state) {
		super(PrimordiaBlockEntities.SAMPLE_COOLER, pos, state);
	}

	/**
	 * Ages the contents more slowly. Runs on the cooling cycle rather than every tick, because that
	 * is what keeps the arithmetic in whole ticks.
	 */
	public static void serverTick(Level level, BlockPos pos, BlockState state,
	                              SampleCoolerBlockEntity cooler) {
		if (!ColdStorage.isCoolingTick(level.getGameTime())) return;
		boolean changed = false;
		for (ItemStack stack : cooler.inventory) {
			changed |= ColdStorage.cool(stack);
		}
		if (changed) cooler.setChanged();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The lid, heard rather than seen. Hung on the container's own open and close rather than on the
	 * block's use handler so that every way in is covered by one pair of calls — the sound follows
	 * the menu, not the click that happened to open it.
	 */
	@Override
	public void startOpen(ContainerUser user) {
		if (level != null && !level.isClientSide()) {
			level.playSound(null, worldPosition, PrimordiaSounds.SAMPLE_COOLER_OPEN,
					SoundSource.BLOCKS, 0.5f, 1.0f);
		}
	}

	@Override
	public void stopOpen(ContainerUser user) {
		if (level != null && !level.isClientSide()) {
			level.playSound(null, worldPosition, PrimordiaSounds.SAMPLE_COOLER_CLOSE,
					SoundSource.BLOCKS, 0.5f, 1.0f);
		}
	}

	/**
	 * Only tissue. Anything carrying no {@link SampleData} has no clock to slow and would just be
	 * using the box as a chest, which is not what this is for.
	 * <p>
	 * Tested against the payload rather than against the item id, so a sample stays admissible
	 * however it was produced and nothing else sneaks in by wearing the right item.
	 */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return SampleData.get(stack) != null;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Deliberately does not drop the contents, which is the opposite of what a container normally
	 * does on removal and the entire point here. The samples stay in the block entity long enough
	 * for the loot table to copy them onto the dropped item.
	 */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
	}

	@Override
	public int getContainerSize() {
		return SLOT_COUNT;
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return inventory;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.inventory = items;
	}

	@Override
	protected Component getDefaultName() {
		return Component.translatable("container.primordia.sample_cooler");
	}

	@Override
	protected AbstractContainerMenu createMenu(int syncId, Inventory playerInventory) {
		return new SampleCoolerScreenHandler(syncId, playerInventory, this);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, inventory);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		inventory = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, inventory);
	}
}
