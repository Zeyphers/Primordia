package dev.jsz.primordia.lab;

import net.minecraft.ChatFormatting;

/**
 * How much of a genome a decode is actually able to resolve.
 * <p>
 * Modelled on what sequencing a novel organism is really like: a read means very little on its own,
 * and means a great deal once there is something to compare it against. The first specimen of a
 * lineage produces a report full of hedges; the fifth produces numbers. Nothing about the animal
 * changed — the library did.
 * <p>
 * This is the progression hook the lab is built around. Without it the pipeline is three machines
 * that turn a creature into the readout the scanner already gave for free, and the only thing the
 * player would learn is that it now takes longer.
 */
public enum DecodeAccuracy {
	/** Nothing on file. Most loci come back unreadable. */
	UNKNOWN(0, "Unreferenced", ChatFormatting.DARK_RED),
	/** A read or two: enough to bracket a value, not to state it. */
	COARSE(1, "Fragmentary", ChatFormatting.RED),
	/** Enough of the lineage on file to give a range. */
	PARTIAL(3, "Partial", ChatFormatting.YELLOW),
	/** Well referenced: figures, with a tolerance. */
	GOOD(6, "Referenced", ChatFormatting.GREEN),
	/** The species is thoroughly characterised; the report reads like the debug output. */
	COMPLETE(12, "Complete", ChatFormatting.AQUA);

	/** Individuals of the lineage that must be on file to reach this level. */
	public final int decodesRequired;
	public final String label;
	public final ChatFormatting colour;

	DecodeAccuracy(int decodesRequired, String label, ChatFormatting colour) {
		this.decodesRequired = decodesRequired;
		this.label = label;
		this.colour = colour;
	}

	/**
	 * The level a decode reaches, given how many of this lineage are on file and how intact the
	 * sample was.
	 * <p>
	 * The prior count is what the library already held <b>before</b> this specimen, so the first
	 * ever decode of a species genuinely reports {@link #UNKNOWN} rather than immediately crediting
	 * itself. A degraded sample costs a level: rotten tissue yields a worse read, but never no read,
	 * because an unusable item is just a dead end and a vague one is a reason to go and get another.
	 */
	public static DecodeAccuracy resolve(int priorDecodes, float freshness) {
		DecodeAccuracy level = UNKNOWN;
		for (DecodeAccuracy candidate : values()) {
			if (priorDecodes >= candidate.decodesRequired) level = candidate;
		}
		return level.degraded(penaltyFor(freshness));
	}

	/**
	 * Levels of confidence lost to decay, by how much of the sample is left.
	 * <p>
	 * Graded rather than a single cliff. One threshold meant a sample was either perfect or one step
	 * worse, so decay was a coin flip the player could not see coming and could not read off the
	 * item; four bands make a wilting sample visibly worth hurrying, and make a forgotten one
	 * genuinely poor rather than mildly inconvenient.
	 * <p>
	 * Even a fully rotten sample keeps whatever three levels of penalty leaves it — usually
	 * {@link #UNKNOWN}, which is a real report saying it could not read the tissue, not a failure.
	 */
	private static int penaltyFor(float freshness) {
		if (freshness >= 0.60f) return 0;
		if (freshness >= 0.35f) return 1;
		if (freshness >= 0.15f) return 2;
		return 3;
	}

	/** {@code steps} levels worse, floored at {@link #UNKNOWN}. */
	public DecodeAccuracy degraded(int steps) {
		int index = Math.max(0, ordinal() - Math.max(0, steps));
		return values()[index];
	}

	/** One level worse, floored at {@link #UNKNOWN}. */
	public DecodeAccuracy degraded() {
		return degraded(1);
	}

	public boolean atLeast(DecodeAccuracy other) {
		return ordinal() >= other.ordinal();
	}

	/**
	 * Renders a normalised [0,1] locus at this level of confidence.
	 * <p>
	 * The bracket wording is deliberately not a number in disguise. "high" tells the player which
	 * way to lean without letting them read a percentage off it, which is the whole difference
	 * between a species they have studied and one they have merely met.
	 */
	public String describeFraction(float value) {
		return switch (this) {
			case UNKNOWN -> "???";
			case COARSE -> value < 0.34f ? "low" : value < 0.67f ? "moderate" : "high";
			case PARTIAL -> {
				int lower = (int) (Math.floor(value * 4f) * 25f);
				yield lower + "-" + (lower + 25) + "%";
			}
			case GOOD -> {
				int rounded = Math.round(value * 20f) * 5;
				yield "~" + rounded + "%";
			}
			case COMPLETE -> Math.round(value * 100f) + "%";
		};
	}

	/** Renders a physical measurement — mass, length — at this level of confidence. */
	public String describeMeasure(float value, String unit) {
		return switch (this) {
			case UNKNOWN -> "???";
			case COARSE -> value < 0.5f ? "small" : value < 1.5f ? "medium" : "large";
			case PARTIAL -> String.format("~%.0f%s", value, unit);
			case GOOD -> String.format("%.1f%s", value, unit);
			case COMPLETE -> String.format("%.2f%s", value, unit);
		};
	}

	/** Renders a categorical trait, which is either legible or it is not. */
	public String describeCategory(String value) {
		return atLeast(COARSE) ? value : "indeterminate";
	}

	/** How many more of this lineage are needed before the report improves; 0 at the top. */
	public int decodesUntilNextLevel(int priorDecodes) {
		int index = ordinal();
		if (index >= values().length - 1) return 0;
		return Math.max(0, values()[index + 1].decodesRequired - priorDecodes);
	}
}
