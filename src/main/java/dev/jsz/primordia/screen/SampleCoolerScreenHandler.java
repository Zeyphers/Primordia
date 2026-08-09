package dev.jsz.primordia.screen;

import dev.jsz.primordia.block.SampleCoolerBlockEntity;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The cooler's container: two rows of eight, centred.
 * <p>
 * The panel is painted to match rather than the other way round, so these coordinates and
 * {@code design/gui/sample_cooler.png} have to agree. Sixteen is where the recesses are: eight
 * columns is one narrower than the nine a vanilla container panel carries, and left where vanilla
 * starts them the grid would sit against the left edge with the odd column's worth of space piled
 * up on the right.
 */
public class SampleCoolerScreenHandler extends AbstractContainerMenu {

	public static final int COLUMNS = 8;
	public static final int ROWS = 2;

	public static final int BACKGROUND_WIDTH = 176;
	/** Centres the grid: half of what a ninth column would have taken, on each side. */
	public static final int GRID_LEFT = (BACKGROUND_WIDTH - COLUMNS * 18) / 2;
	/**
	 * One row of slots shorter than the 166-pixel panel this borrows from.
	 * <p>
	 * It has to be the height of the drawn panel and not of the texture: the screen positions the
	 * inventory label by measuring up from this, so a value larger than the art drops the label into
	 * the middle of the player's own slots.
	 */
	public static final int BACKGROUND_HEIGHT = 148;

	/** Rows of the cooler itself, before the player's inventory begins. */
	private static final int COOLER_SLOTS = SampleCoolerBlockEntity.SLOT_COUNT;

	private final Container inventory;

	/** Client-side constructor: the screen opens before the block entity's contents arrive. */
	public SampleCoolerScreenHandler(int syncId, Inventory playerInventory) {
		this(syncId, playerInventory, new SimpleContainer(COOLER_SLOTS));
	}

	public SampleCoolerScreenHandler(int syncId, Inventory playerInventory, Container inventory) {
		super(PrimordiaScreenHandlers.SAMPLE_COOLER, syncId);
		checkContainerSize(inventory, COOLER_SLOTS);
		this.inventory = inventory;
		inventory.startOpen(playerInventory.player);

		for (int row = 0; row < ROWS; row++) {
			for (int column = 0; column < COLUMNS; column++) {
				addSlot(new SampleSlot(inventory, column + row * COLUMNS,
						GRID_LEFT + column * 18, 18 + row * 18));
			}
		}
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(playerInventory, column + row * 9 + 9,
						8 + column * 18, 66 + row * 18));
			}
		}
		for (int column = 0; column < 9; column++) {
			addSlot(new Slot(playerInventory, column, 8 + column * 18, 124));
		}
	}

	/**
	 * A slot that only takes what the cooler will hold.
	 * <p>
	 * The filter has to live here as well as on the container. {@code Container.canPlaceItem} is
	 * consulted by hoppers and by shift-clicking, but a slot the player drags an item onto asks the
	 * slot, so without this the restriction holds against automation and not against hands.
	 */
	private static class SampleSlot extends Slot {
		SampleSlot(Container inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return container.canPlaceItem(getContainerSlot(), stack);
		}
	}

	/**
	 * Shift-click routing.
	 * <p>
	 * Moving out of the cooler goes to the player. Moving in from the player is offered to the
	 * cooler first and falls through to the rest of the inventory when it is refused, which is what
	 * makes shift-clicking a stack of something that is not a sample behave like an ordinary
	 * inventory swap rather than doing nothing at all.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;

		ItemStack inSlot = slot.getItem();
		ItemStack original = inSlot.copy();

		if (index < COOLER_SLOTS) {
			if (!moveItemStackTo(inSlot, COOLER_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
		} else if (!moveItemStackTo(inSlot, 0, COOLER_SLOTS, false)) {
			// Not admissible here, so treat it as a normal inventory-to-hotbar move.
			int hotbarStart = slots.size() - 9;
			boolean fromHotbar = index >= hotbarStart;
			boolean moved = fromHotbar
					? moveItemStackTo(inSlot, COOLER_SLOTS, hotbarStart, false)
					: moveItemStackTo(inSlot, hotbarStart, slots.size(), false);
			if (!moved) return ItemStack.EMPTY;
		}

		if (inSlot.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return original;
	}

	@Override
	public boolean stillValid(Player player) {
		return inventory.stillValid(player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		inventory.stopOpen(player);
	}
}
