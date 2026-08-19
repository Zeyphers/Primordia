package dev.jsz.primordia.screen;

import dev.jsz.primordia.block.SplicerBlockEntity;
import dev.jsz.primordia.registry.PrimordiaScreenHandlers;
import dev.jsz.primordia.splice.SpliceBranch;
import dev.jsz.primordia.splice.Splicing;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The splicing bench's menu.
 * <p>
 * Almost nothing travels over the wire, and that is the point. Which branches are open, which
 * bloodline is the best donor for each and how much of it can be carried are all derived from the
 * player's guide — which is already synchronised — by {@code SpliceTree}, so the screen works those
 * out for itself. What the menu carries is the one output slot and a progress bar, and what it sends
 * back is a button press.
 * <p>
 * Buttons go through {@link #clickMenuButton}, vanilla's own mechanism, rather than a custom payload.
 * The button id <i>is</i> the branch ordinal, so there is no protocol to keep in step: a branch that
 * does not exist cannot be pressed, and the server re-derives everything about the request anyway.
 */
public class SplicerMenu extends AbstractContainerMenu {

	private static final int DATA_SIZE = 3;
	private static final int DATA_TICKS = 0;
	private static final int DATA_RUNNING = 1;
	/**
	 * Which branch is running, as an ordinal, or -1.
	 * <p>
	 * Synced rather than remembered by the screen. It used to be inferred from whichever row the
	 * player had last clicked, which held up exactly as long as the screen stayed open — walk out of
	 * range and the menu closes, and reopening it left the client with no idea what the machine was
	 * doing, so the progress line simply stopped being drawn. The machine knows; it should say.
	 */
	private static final int DATA_BRANCH = 2;

	private final Container output;
	private final ContainerData data;
	private final SplicerBlockEntity bench;

	/** Client constructor: no bench, and a stand-in container the server will fill. */
	public SplicerMenu(int syncId, Inventory playerInventory) {
		this(syncId, playerInventory, new SimpleContainer(1), new SimpleContainerData(DATA_SIZE), null);
	}

	public SplicerMenu(int syncId, Inventory playerInventory, Container output, ContainerData data,
	                   SplicerBlockEntity bench) {
		super(PrimordiaScreenHandlers.SPLICER, syncId);
		this.output = output;
		this.data = data;
		this.bench = bench;
		checkContainerSize(output, 1);
		addDataSlots(data);

		// Output only. Nothing is inserted into a splicer: the ingredients are research, and those
		// live in the player's guide rather than in a hopper.
		addSlot(new Slot(output, 0, SplicerLayout.OUTPUT_X, SplicerLayout.OUTPUT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		});

		// Positions come from the layout file, so the wells painted into the art and the slots the
		// menu creates cannot drift apart. See SplicerLayout.
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInventory, col + row * 9 + 9,
						SplicerLayout.INV_X + col * 18, SplicerLayout.INV_Y + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(playerInventory, col,
					SplicerLayout.HOTBAR_X + col * 18, SplicerLayout.HOTBAR_Y));
		}
	}

	/** Ticks into the current run, for the screen's progress bar. */
	public int ticks() {
		return data.get(DATA_TICKS);
	}

	public boolean running() {
		return data.get(DATA_RUNNING) != 0;
	}

	public float progress() {
		return Math.min(1f, ticks() / (float) SplicerBlockEntity.RUN_TICKS);
	}

	/** The branch currently being isolated, or null when the bench is idle. */
	public SpliceBranch runningBranch() {
		int ordinal = data.get(DATA_BRANCH);
		return ordinal < 0 || ordinal >= SpliceBranch.VALUES.length
				? null : SpliceBranch.VALUES[ordinal];
	}

	/**
	 * A branch was asked for. The id is its ordinal.
	 * <p>
	 * Everything about the request is worked out here rather than trusted from the screen: which
	 * donor is best, how deep the player has got, and whether the bench is free. A client that lied
	 * about any of it would only be asking the server a question it answers for itself.
	 */
	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (bench == null || !(player instanceof ServerPlayer serverPlayer)) return false;
		if (id < 0 || id >= SpliceBranch.VALUES.length) return false;

		SpliceBranch branch = SpliceBranch.VALUES[id];
		Splicing.Result result = Splicing.beginAt(serverPlayer, bench, branch);
		if (result != Splicing.Result.OK) {
			serverPlayer.sendSystemMessage(Component.literal(result.message));
			return false;
		}
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;

		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		if (index == 0) {
			// Out of the machine and into the player, never the other way.
			if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
			slot.onQuickCraft(stack, copy);
		} else if (!moveItemStackTo(stack, 0, 1, false)) {
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		return output.stillValid(player);
	}

	/** The two numbers the screen needs from the bench, wired the vanilla way. */
	public static ContainerData dataOf(SplicerBlockEntity bench) {
		return new ContainerData() {
			@Override
			public int get(int index) {
				return switch (index) {
					case DATA_TICKS -> bench.ticks();
					case DATA_RUNNING -> bench.isRunning() ? 1 : 0;
					case DATA_BRANCH -> bench.runningBranch() == null
							? -1 : bench.runningBranch().ordinal();
					default -> 0;
				};
			}

			@Override
			public void set(int index, int value) {
			}

			@Override
			public int getCount() {
				return DATA_SIZE;
			}
		};
	}
}
