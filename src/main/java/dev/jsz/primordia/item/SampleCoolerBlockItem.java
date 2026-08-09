package dev.jsz.primordia.item;

import dev.jsz.primordia.block.SampleCoolerBlockEntity;
import dev.jsz.primordia.lab.ColdStorage;
import dev.jsz.primordia.lab.SampleData;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

/**
 * The cooler in item form, still cooling.
 * <p>
 * This is not a nicety. A cooler that only worked once placed would be useless for the job it
 * exists to do: the samples that need protecting are the ones being carried back from the field,
 * and a player who has to set the box down and stand next to it has gained nothing over walking
 * faster. So the contents age slowly in a pocket exactly as they do on the ground.
 * <p>
 * The contents live in the {@code minecraft:container} component here rather than in a block
 * entity, so the work is the same arithmetic ({@link ColdStorage}) applied to a different holder.
 */
public class SampleCoolerBlockItem extends BlockItem {

	private static final String KEY_LAST_COOLED = "LastCooled";

	public SampleCoolerBlockItem(Block block, Properties settings) {
		super(block, settings);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Pointedly does not cool anything. Rewriting {@code minecraft:container} on a cooling cycle
	 * changes the stack, the server resends the slot, and the client replays the equip animation and
	 * the hotbar name — a cooler in hand jitters as though someone were re-giving it several times a
	 * second. Cooling is instead caught up in one sum when the contents are next needed, which is
	 * arithmetically identical because {@link ColdStorage} is a rate rather than a countdown.
	 * <p>
	 * The one write here is the start of that sum, and it happens once per cooler: a stack that has
	 * contents but no {@link #KEY_LAST_COOLED} has nothing to measure from, so the tick it was
	 * acquired on is stamped. Without it a cooler carried across the world and then placed would
	 * measure zero elapsed cycles and credit its samples nothing.
	 */
	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity holder, EquipmentSlot slot) {
		super.inventoryTick(stack, level, holder, slot);
		stampCoolingStart(stack, level.getGameTime());
	}

	/**
	 * Records the tick cooling should be measured from, if it is not recorded already.
	 *
	 * @return true if the stack was stamped, which happens at most once per cooler
	 */
	private static boolean stampCoolingStart(ItemStack stack, long currentTick) {
		if (stack.get(DataComponents.CONTAINER) == null) return false;

		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = customData != null ? customData.copyTag() : new CompoundTag();
		if (root.getLong(KEY_LAST_COOLED).isPresent()) return false;

		root.putLong(KEY_LAST_COOLED, currentTick);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return true;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getLevel() instanceof ServerLevel serverLevel) {
			catchUpCooling(context.getItemInHand(), serverLevel.getGameTime());
		}
		return super.useOn(context);
	}

	/**
	 * Catches up any accrued cooling cycles for samples inside this cooler stack.
	 *
	 * @return true if container contents were modified
	 */
	public static boolean catchUpCooling(ItemStack stack, long currentTick) {
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents == null) return false;

		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = customData != null ? customData.copyTag() : new CompoundTag();
		long lastCooled = root.getLongOr(KEY_LAST_COOLED, currentTick);

		long elapsedCycles = (currentTick / ColdStorage.CYCLE) - (lastCooled / ColdStorage.CYCLE);
		if (elapsedCycles <= 0) return false;

		// Only update component tag when cycles have elapsed
		root.putLong(KEY_LAST_COOLED, currentTick);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

		NonNullList<ItemStack> items =
				NonNullList.withSize(SampleCoolerBlockEntity.SLOT_COUNT, ItemStack.EMPTY);
		contents.copyInto(items);

		boolean changed = false;
		long forgivenTicks = elapsedCycles * ColdStorage.FORGIVEN_PER_CYCLE;

		for (ItemStack held : items) {
			SampleData data = SampleData.get(held);
			if (data != null && !data.isPreserved()) {
				SampleData slowed = new SampleData(
						data.genome(),
						data.collectedAtTick() + forgivenTicks,
						data.lineageHex());
				slowed.write(held);
				changed = true;
			}
		}

		if (changed) {
			stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
		}
		return changed;
	}

	@Override
	public boolean allowComponentsUpdateAnimation(
			net.minecraft.world.entity.player.Player player,
			net.minecraft.world.InteractionHand hand,
			ItemStack oldStack,
			ItemStack newStack) {
		return false;
	}
}
