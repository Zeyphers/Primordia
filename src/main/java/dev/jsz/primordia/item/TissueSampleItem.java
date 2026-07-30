package dev.jsz.primordia.item;

import dev.jsz.primordia.lab.SampleData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

/**
 * Raw tissue drawn off a live creature, carrying that individual's genome and a clock.
 * <p>
 * The clock is the point. A sample is perishable, so the pipeline has a tempo: gather, get it to a
 * sequencer, or spend the resources on cold storage. Left in a chest it quietly turns into a worse
 * read rather than a useless one — {@link dev.jsz.primordia.lab.DecodeAccuracy} costs a level for a
 * degraded sample — because an item that becomes worthless is just a punishment, while one that
 * becomes vague is a reason to go back out.
 * <p>
 * Only the world-independent half of the tooltip lives here. Freshness needs the current world
 * time, and reaching for the client world from a class the dedicated server also loads is how a mod
 * ends up crashing on servers, so that line is added by a client-side tooltip callback registered
 * in {@code PrimordiaClient} instead.
 */
public class TissueSampleItem extends Item {

	public TissueSampleItem(Properties settings) {
		super(settings);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tooltipAdder, TooltipFlag flag) {
		SampleData data = SampleData.get(stack);
		if (data == null) {
			tooltipAdder.accept(Component.literal("Empty swab — no specimen recorded")
					.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
			return;
		}

		tooltipAdder.accept(Component.literal("Specimen " + data.lineageHex()).withStyle(ChatFormatting.AQUA));
		tooltipAdder.accept(Component.literal("Generation " + data.genome().generation())
				.withStyle(ChatFormatting.DARK_GRAY));
		if (data.isPreserved()) {
			tooltipAdder.accept(Component.literal("Preserved — degradation halted").withStyle(ChatFormatting.BLUE));
		}
	}

	/**
	 * The freshness line, given the world time to measure against. Called from the client tooltip
	 * callback; kept here so the wording and thresholds sit next to the item they describe.
	 */
	public static Component freshnessLine(SampleData data, long worldTime) {
		float freshness = data.freshness(worldTime);
		ChatFormatting colour = freshness > 0.6f ? ChatFormatting.GREEN
				: freshness > 0.3f ? ChatFormatting.YELLOW : ChatFormatting.RED;
		String state = freshness <= 0f ? "Degraded"
				: freshness > 0.6f ? "Fresh"
				: freshness > 0.3f ? "Ageing" : "Deteriorating";
		return Component.literal(state + " · " + Math.round(freshness * 100f) + "% viable")
				.withStyle(colour);
	}
}
