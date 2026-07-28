package dev.jsz.primordia.item;

import dev.jsz.primordia.lab.SampleData;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

	public TissueSampleItem(Settings settings) {
		super(settings);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		SampleData data = SampleData.get(stack);
		if (data == null) {
			tooltip.add(Text.literal("Empty swab — no specimen recorded")
					.formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
			return;
		}

		tooltip.add(Text.literal("Specimen " + data.lineageHex()).formatted(Formatting.AQUA));
		tooltip.add(Text.literal("Generation " + data.genome().generation())
				.formatted(Formatting.DARK_GRAY));
		if (data.isPreserved()) {
			tooltip.add(Text.literal("Preserved — degradation halted").formatted(Formatting.BLUE));
		}
	}

	/**
	 * The freshness line, given the world time to measure against. Called from the client tooltip
	 * callback; kept here so the wording and thresholds sit next to the item they describe.
	 */
	public static Text freshnessLine(SampleData data, long worldTime) {
		float freshness = data.freshness(worldTime);
		Formatting colour = freshness > 0.6f ? Formatting.GREEN
				: freshness > 0.3f ? Formatting.YELLOW : Formatting.RED;
		String state = freshness <= 0f ? "Degraded"
				: freshness > 0.6f ? "Fresh"
				: freshness > 0.3f ? "Ageing" : "Deteriorating";
		return Text.literal(state + " · " + Math.round(freshness * 100f) + "% viable")
				.formatted(colour);
	}
}
