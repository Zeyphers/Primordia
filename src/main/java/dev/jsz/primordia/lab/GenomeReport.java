package dev.jsz.primordia.lab;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanCache;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.entity.Temperament;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a genome plus a confidence level into the lines a player reads.
 * <p>
 * Written once and used from three places — the decoder's output item, the Genome Bank listing, and
 * the field scanner — so that a species described two ways is never described inconsistently. The
 * only thing that differs between callers is the {@link DecodeAccuracy} they pass in.
 */
public final class GenomeReport {

	private GenomeReport() {
	}

	/**
	 * The full report for one specimen.
	 *
	 * @param priorDecodes individuals of this lineage already on file, used to tell the player how
	 *                     much more work would sharpen the picture
	 */
	public static List<Component> lines(Genome genome, DecodeAccuracy accuracy, int priorDecodes) {
		List<Component> out = new ArrayList<>();
		BodyPlan plan = BodyPlanCache.get(genome);
		DietGroup diet = DietGroup.of(genome);
		Temperament temperament = Temperament.of(genome);

		out.add(Component.literal("── Specimen [" + SampleData.shortLineage(genome) + "] ──")
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		out.add(Component.literal("  Reference: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(accuracy.label).withStyle(accuracy.colour))
				.append(Component.literal(" · " + priorDecodes + " on file").withStyle(ChatFormatting.DARK_GRAY)));

		out.add(Component.literal(String.format("  Classification: Gen %d · %s · %s",
				genome.generation(),
				accuracy.describeCategory(diet.name().toLowerCase()),
				accuracy.describeCategory(temperament.name().toLowerCase())))
				.withStyle(ChatFormatting.WHITE));

		if (plan != null) {
			out.add(Component.literal("  Dimensions: "
					+ accuracy.describeMeasure(plan.bodyLength, "m") + " long · "
					+ accuracy.describeMeasure(plan.height(), "m") + " tall · mass "
					+ accuracy.describeMeasure(plan.mass, ""))
					.withStyle(ChatFormatting.GRAY));
			// Anatomy is countable, so it is either legible or it is not — there is no honest way
			// to report "about four legs".
			out.add(Component.literal(accuracy.atLeast(DecodeAccuracy.PARTIAL)
					? String.format("  Anatomy: %d legs (%d segments) · %d arms · %d bones",
					plan.legs.length,
					plan.legs.length == 0 ? 0 : plan.legs[0].bones.length,
					plan.arms.length, plan.bones.length)
					: "  Anatomy: structure not resolved")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		out.add(Component.literal("  Speed " + accuracy.describeFraction(genome.raw(Gene.SPEED))
				+ " · Aggression " + accuracy.describeFraction(genome.raw(Gene.AGGRESSION))
				+ " · Fear " + accuracy.describeFraction(genome.raw(Gene.FEAR))
				+ " · Social " + accuracy.describeFraction(genome.raw(Gene.SOCIABILITY)))
				.withStyle(ChatFormatting.LIGHT_PURPLE));

		if (accuracy.atLeast(DecodeAccuracy.GOOD)) {
			out.add(Component.literal("  Diet " + accuracy.describeFraction(genome.raw(Gene.DIET))
					+ " · Stamina " + accuracy.describeFraction(genome.raw(Gene.STAMINA))
					+ " · Mutability " + accuracy.describeFraction(genome.raw(Gene.MUTABILITY)))
					.withStyle(ChatFormatting.DARK_AQUA));
		}

		int needed = accuracy.decodesUntilNextLevel(priorDecodes);
		if (needed > 0) {
			out.add(Component.literal("  Sequence " + needed + " more of this lineage to sharpen the read.")
					.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		}
		return out;
	}

	/** The condensed form that fits an item tooltip. */
	public static List<Component> tooltip(Genome genome, DecodeAccuracy accuracy) {
		List<Component> out = new ArrayList<>();
		out.add(Component.literal("Lineage " + SampleData.shortLineage(genome))
				.withStyle(ChatFormatting.AQUA));
		out.add(Component.literal(accuracy.label + " · generation " + genome.generation())
				.withStyle(accuracy.colour));
		out.add(Component.literal("Speed " + accuracy.describeFraction(genome.raw(Gene.SPEED))
				+ " · Aggr " + accuracy.describeFraction(genome.raw(Gene.AGGRESSION)))
				.withStyle(ChatFormatting.GRAY));
		return out;
	}
}
