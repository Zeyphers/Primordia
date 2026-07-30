package dev.jsz.primordia.screen;

import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * The Basic Gene Lab's container: the sample on top, its two fuels beneath, the report to the side.
 */
public class GeneLabScreenHandler extends AbstractContainerMenu {

	// Read out of the hand-drawn background art rather than invented here, so the slots land on
	// the recesses painted for them. The four are wired in pipeline order — sample, then the fuel
	// each stage burns, then the report — regardless of where on the panel they sit.
	public static final int SAMPLE_X = 34, SAMPLE_Y = 21;
	public static final int FUEL_X = 58, FUEL_Y = 69;
	public static final int REDSTONE_X = 93, REDSTONE_Y = 69;
	public static final int OUTPUT_X = 132, OUTPUT_Y = 50;

	public static final int BACKGROUND_WIDTH = 176;
	public static final int BACKGROUND_HEIGHT = 204;

	private final Container inventory;
	private final ContainerData properties;

	/** Client-side constructor: the screen opens before the block entity's contents arrive. */
	public GeneLabScreenHandler(int syncId, Inventory playerInventory) {
		this(syncId, playerInventory,
				new SimpleContainer(GeneLabBlockEntity.SLOT_COUNT),
				new SimpleContainerData(GeneLabBlockEntity.PROPERTY_COUNT));
	}

	// Typed as Inventory rather than Container because 26.2's startOpen wants a ContainerUser, and
	// the player behind the inventory is the only thing that can supply one.
	public GeneLabScreenHandler(int syncId, Inventory playerInventory,
	                            Container inventory, ContainerData properties) {
		super(PrimordiaScreenHandlers.GENE_LAB, syncId);
		checkContainerSize(inventory, GeneLabBlockEntity.SLOT_COUNT);
		this.inventory = inventory;
		this.properties = properties;
		inventory.startOpen(playerInventory.player);

		addSlot(new FilteredSlot(inventory, GeneLabBlockEntity.SLOT_SAMPLE, SAMPLE_X, SAMPLE_Y,
				GeneLabBlockEntity::isSample));
		addSlot(new Slot(inventory, GeneLabBlockEntity.SLOT_FUEL, FUEL_X, FUEL_Y));
		addSlot(new FilteredSlot(inventory, GeneLabBlockEntity.SLOT_REDSTONE, REDSTONE_X, REDSTONE_Y,
				stack -> stack.is(Items.REDSTONE)));
		addSlot(new OutputSlot(inventory, GeneLabBlockEntity.SLOT_OUTPUT, OUTPUT_X, OUTPUT_Y));

		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 120 + row * 18));
			}
		}
		for (int column = 0; column < 9; column++) {
			addSlot(new Slot(playerInventory, column, 8 + column * 18, 180));
		}

		addDataSlots(properties);
	}

	private static class FilteredSlot extends Slot {
		private final java.util.function.Predicate<ItemStack> filter;

		FilteredSlot(Container inventory, int index, int x, int y,
		             java.util.function.Predicate<ItemStack> filter) {
			super(inventory, index, x, y);
			this.filter = filter;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return filter.test(stack);
		}
	}

	private static class OutputSlot extends Slot {
		OutputSlot(Container inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}
	}

	private int progress() {
		return properties.get(GeneLabBlockEntity.PROPERTY_PROGRESS);
	}

	/**
	 * Fill of the three lines, in order: sample to sequencer, sequencer to decoder, decoder to
	 * report.
	 * <p>
	 * Each line owns one step and fills only while that step is running, so a glance at the column
	 * says which of the three the machine is on. A single bar spanning the whole job could not:
	 * half full tells you nothing about whether it is still reading or already interpreting, and
	 * those need different things from the player.
	 */
	public float lineFill(int index) {
		return GeneLabBlockEntity.lineFill(index, stage(), progress());
	}

	/** True when this step is the one currently being worked on. */
	public boolean lineActive(int index) {
		return switch (index) {
			case 0 -> stage() == GeneLabBlockEntity.Stage.SEQUENCING;
			case 1, 2 -> stage() == GeneLabBlockEntity.Stage.DECODING;
			default -> false;
		};
	}

	/**
	 * Whether the machine is stopped waiting for something, rather than working.
	 * <p>
	 * This is the state the old status text got wrong. It read the stage alone, so a sequencing run
	 * with an empty fuel slot still announced "Sequencing" — the stage was accurate and the message
	 * was not, because the stage says what the machine is trying to do and says nothing about
	 * whether it can. Both halves are checked here.
	 */
	public boolean isStalled() {
		return switch (stage()) {
			case IDLE -> false;
			case SEQUENCING -> properties.get(GeneLabBlockEntity.PROPERTY_BURN) <= 0;
			case DECODING -> redstoneUsed() < GeneLabBlockEntity.REDSTONE_PER_DECODE
					&& getSlot(GeneLabBlockEntity.SLOT_REDSTONE).getItem().isEmpty();
		};
	}

	public GeneLabBlockEntity.Stage stage() {
		int ordinal = properties.get(GeneLabBlockEntity.PROPERTY_STAGE);
		return GeneLabBlockEntity.Stage.VALUES[
				Math.floorMod(ordinal, GeneLabBlockEntity.Stage.VALUES.length)];
	}

	/** Fuel remaining, 0 to 1. Drives the flame. */
	public float burnFraction() {
		int total = properties.get(GeneLabBlockEntity.PROPERTY_BURN_TOTAL);
		if (total <= 0) return 0f;
		return Math.min(1f, (float) properties.get(GeneLabBlockEntity.PROPERTY_BURN) / total);
	}

	/** Redstone spent so far on the decode in progress. */
	public int redstoneUsed() {
		return properties.get(GeneLabBlockEntity.PROPERTY_REDSTONE_USED);
	}

	@Override
	public boolean stillValid(Player player) {
		return inventory.stillValid(player);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Routes each shift-clicked item to the slot that actually wants it: samples to the top,
	 * redstone to its own slot, anything burnable to the fuel slot. Guessing wrong here is
	 * immediately annoying, because loading the lab is the one repeated action it has.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		ItemStack moved = ItemStack.EMPTY;
		Slot slot = slots.get(slotIndex);
		if (slot == null || !slot.hasItem()) return moved;

		ItemStack stack = slot.getItem();
		moved = stack.copy();
		int playerStart = GeneLabBlockEntity.SLOT_COUNT;
		int playerEnd = playerStart + 36;

		if (slotIndex < playerStart) {
			if (!moveItemStackTo(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
			slot.onQuickCraft(stack, moved);
		} else {
			int target;
			if (GeneLabBlockEntity.isSample(stack)) {
				target = GeneLabBlockEntity.SLOT_SAMPLE;
			} else if (stack.is(Items.REDSTONE)) {
				target = GeneLabBlockEntity.SLOT_REDSTONE;
			} else if (player.level().fuelValues().burnDuration(stack) > 0) {
				target = GeneLabBlockEntity.SLOT_FUEL;
			} else {
				return ItemStack.EMPTY;
			}
			if (!moveItemStackTo(stack, target, target + 1, false)) return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return moved;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		inventory.stopOpen(player);
	}
}
