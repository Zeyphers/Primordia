package dev.jsz.primordia.screen;

import dev.jsz.primordia.block.GeneLabBlockEntity;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/**
 * The Basic Gene Lab's container: the sample on top, its two fuels beneath, the report to the side.
 */
public class GeneLabScreenHandler extends ScreenHandler {

	// Read out of the hand-drawn background art rather than invented here, so the slots land on
	// the recesses painted for them. The four are wired in pipeline order — sample, then the fuel
	// each stage burns, then the report — regardless of where on the panel they sit.
	public static final int SAMPLE_X = 34, SAMPLE_Y = 21;
	public static final int FUEL_X = 58, FUEL_Y = 69;
	public static final int REDSTONE_X = 93, REDSTONE_Y = 69;
	public static final int OUTPUT_X = 132, OUTPUT_Y = 50;

	public static final int BACKGROUND_WIDTH = 176;
	public static final int BACKGROUND_HEIGHT = 204;

	private final Inventory inventory;
	private final PropertyDelegate properties;

	/** Client-side constructor: the screen opens before the block entity's contents arrive. */
	public GeneLabScreenHandler(int syncId, PlayerInventory playerInventory) {
		this(syncId, playerInventory,
				new SimpleInventory(GeneLabBlockEntity.SLOT_COUNT),
				new ArrayPropertyDelegate(GeneLabBlockEntity.PROPERTY_COUNT));
	}

	public GeneLabScreenHandler(int syncId, PlayerInventory playerInventory,
	                            Inventory inventory, PropertyDelegate properties) {
		super(PrimordiaScreenHandlers.GENE_LAB, syncId);
		checkSize(inventory, GeneLabBlockEntity.SLOT_COUNT);
		this.inventory = inventory;
		this.properties = properties;
		inventory.onOpen(playerInventory.player);

		addSlot(new FilteredSlot(inventory, GeneLabBlockEntity.SLOT_SAMPLE, SAMPLE_X, SAMPLE_Y,
				GeneLabBlockEntity::isSample));
		addSlot(new Slot(inventory, GeneLabBlockEntity.SLOT_FUEL, FUEL_X, FUEL_Y));
		addSlot(new FilteredSlot(inventory, GeneLabBlockEntity.SLOT_REDSTONE, REDSTONE_X, REDSTONE_Y,
				stack -> stack.isOf(Items.REDSTONE)));
		addSlot(new OutputSlot(inventory, GeneLabBlockEntity.SLOT_OUTPUT, OUTPUT_X, OUTPUT_Y));

		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 120 + row * 18));
			}
		}
		for (int column = 0; column < 9; column++) {
			addSlot(new Slot(playerInventory, column, 8 + column * 18, 180));
		}

		addProperties(properties);
	}

	private static class FilteredSlot extends Slot {
		private final java.util.function.Predicate<ItemStack> filter;

		FilteredSlot(Inventory inventory, int index, int x, int y,
		             java.util.function.Predicate<ItemStack> filter) {
			super(inventory, index, x, y);
			this.filter = filter;
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return filter.test(stack);
		}
	}

	private static class OutputSlot extends Slot {
		OutputSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
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
					&& getSlot(GeneLabBlockEntity.SLOT_REDSTONE).getStack().isEmpty();
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
	public boolean canUse(PlayerEntity player) {
		return inventory.canPlayerUse(player);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Routes each shift-clicked item to the slot that actually wants it: samples to the top,
	 * redstone to its own slot, anything burnable to the fuel slot. Guessing wrong here is
	 * immediately annoying, because loading the lab is the one repeated action it has.
	 */
	@Override
	public ItemStack quickMove(PlayerEntity player, int slotIndex) {
		ItemStack moved = ItemStack.EMPTY;
		Slot slot = slots.get(slotIndex);
		if (slot == null || !slot.hasStack()) return moved;

		ItemStack stack = slot.getStack();
		moved = stack.copy();
		int playerStart = GeneLabBlockEntity.SLOT_COUNT;
		int playerEnd = playerStart + 36;

		if (slotIndex < playerStart) {
			if (!insertItem(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
			slot.onQuickTransfer(stack, moved);
		} else {
			int target;
			if (GeneLabBlockEntity.isSample(stack)) {
				target = GeneLabBlockEntity.SLOT_SAMPLE;
			} else if (stack.isOf(Items.REDSTONE)) {
				target = GeneLabBlockEntity.SLOT_REDSTONE;
			} else if (net.minecraft.block.entity.AbstractFurnaceBlockEntity.canUseAsFuel(stack)) {
				target = GeneLabBlockEntity.SLOT_FUEL;
			} else {
				return ItemStack.EMPTY;
			}
			if (!insertItem(stack, target, target + 1, false)) return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.setStack(ItemStack.EMPTY);
		} else {
			slot.markDirty();
		}
		return moved;
	}

	@Override
	public void onClosed(PlayerEntity player) {
		super.onClosed(player);
		inventory.onClose(player);
	}
}
