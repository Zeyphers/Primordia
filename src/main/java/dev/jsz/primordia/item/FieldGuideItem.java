package dev.jsz.primordia.item;

import dev.jsz.primordia.lab.GuideData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * The player's own record of what they have learned, and the mod's manual.
 * <p>
 * Exists mostly to solve a storage problem. Raising a species to full confidence takes twelve
 * decoded specimens, and twelve report items saying nearly the same thing is not knowledge, it is
 * clutter. Reports file themselves into a guide carried in the inventory and are consumed doing it,
 * so studying a lineage thoroughly costs one line in a book rather than most of a backpack.
 */
public class FieldGuideItem extends Item {

	public FieldGuideItem(Settings settings) {
		super(settings);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The screen is opened by a client-side handler registered in {@code PrimordiaClient}, not from
	 * here. Naming a screen class in an item — which the dedicated server also loads — is how a mod
	 * crashes on servers.
	 */
	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		return TypedActionResult.success(player.getStackInHand(hand), true);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		GuideData data = GuideData.get(stack);
		if (data.speciesCount() == 0) {
			tooltip.add(Text.literal("No species on file yet").formatted(Formatting.DARK_GRAY));
		} else {
			tooltip.add(Text.literal(data.speciesCount() + " species · "
					+ data.specimensFiled() + " specimens filed").formatted(Formatting.AQUA));
		}
		tooltip.add(Text.literal("Reports file themselves while this is carried.")
				.formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
		tooltip.add(Text.literal("Right-click to read.")
				.formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
	}
}
