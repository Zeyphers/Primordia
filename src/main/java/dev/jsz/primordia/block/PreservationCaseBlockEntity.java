package dev.jsz.primordia.block;

import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.registry.PrimordiaBlockEntities;
import dev.jsz.primordia.registry.PrimordiaItems;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Cold storage. Stops the clock on every sample inside it.
 * <p>
 * The answer to the tempo {@link SampleData#SHELF_LIFE} imposes: a player who wants to collect
 * before they process has to build somewhere to keep the collection. Preservation works by clearing
 * the sample's collection timestamp rather than by continuously topping up a freshness number,
 * which means a case that is broken, unloaded, or left alone for a year behaves identically — there
 * is no upkeep to miss and nothing to go wrong while the chunk is unloaded.
 */
public class PreservationCaseBlockEntity extends SimpleContainerBlockEntity {

	public static final int SIZE = 27;
	/** How often the contents are swept. A sample is not going to spoil inside a second. */
	private static final int SWEEP_INTERVAL = 20;

	public PreservationCaseBlockEntity(BlockPos pos, BlockState state) {
		super(PrimordiaBlockEntities.PRESERVATION_CASE, pos, state, SIZE);
	}

	public static void tick(World world, BlockPos pos, BlockState state,
	                        PreservationCaseBlockEntity be) {
		if (world.isClient() || world.getTime() % SWEEP_INTERVAL != 0) return;

		boolean changed = false;
		for (int slot = 0; slot < be.size(); slot++) {
			ItemStack stack = be.getStack(slot);
			if (!stack.isOf(PrimordiaItems.TISSUE_SAMPLE)) continue;
			SampleData data = SampleData.get(stack);
			if (data == null || data.isPreserved()) continue;
			data.preserved().write(stack);
			changed = true;
		}
		if (changed) be.markDirty();
	}

	/**
	 * Restarts the clock on a sample taken back out.
	 * <p>
	 * Without this a single trip through a case would make a sample immortal, and cold storage
	 * would be a one-time laundering step rather than a place things have to stay.
	 */
	@Override
	public ItemStack removeStack(int slot, int amount) {
		return thaw(super.removeStack(slot, amount));
	}

	@Override
	public ItemStack removeStack(int slot) {
		return thaw(super.removeStack(slot));
	}

	private ItemStack thaw(ItemStack stack) {
		if (world == null || !stack.isOf(PrimordiaItems.TISSUE_SAMPLE)) return stack;
		SampleData data = SampleData.get(stack);
		if (data != null && data.isPreserved()) {
			data.thawed(world.getTime()).write(stack);
		}
		return stack;
	}

	@Override
	public Text getDisplayName() {
		return Text.translatable("container.primordia.preservation_case");
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, this);
	}
}
