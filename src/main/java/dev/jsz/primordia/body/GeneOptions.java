package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Gene;

import java.util.ArrayList;
import java.util.List;

/**
 * The named options a locus decodes to, and the locus value each one starts at.
 * <p>
 * Written for the editor, which draws a tick per option and labels it, but deliberately not owned
 * by the editor: the labels are read off the real enums and the boundaries off the real decoding
 * rule, so a type added to {@link HornType} or a change to {@link FootType} shows up on the slider
 * without anyone remembering to update a list. A tick that disagrees with the decode is worse than
 * no tick at all — it is a lie the player can act on — and {@code GeneDocTest} asserts every
 * boundary here decodes to the option it claims.
 * <p>
 * Three decoding rules are in play and all three live at their decoders rather than here:
 * <ul>
 *   <li>counted loci quantise onto {@link Gene#discreteLo}..{@link Gene#discreteHi};</li>
 *   <li>uniform categorical loci split [0,1] evenly across the enum;</li>
 *   <li>{@link HornType} and {@link EarType} reserve the bottom of the range for {@code NONE} and
 *       split what is left, because most animals have neither.</li>
 * </ul>
 */
public final class GeneOptions {

	/** One option, and the lowest locus value that decodes to it. */
	public record Option(String label, float start) {
	}

	/** The options for this locus, or an empty list when it is a magnitude rather than a choice. */
	public static List<Option> of(Gene gene) {
		if (gene.isDiscrete()) {
			int span = gene.discreteHi - gene.discreteLo + 1;
			List<Option> out = new ArrayList<>(span);
			for (int i = 0; i < span; i++) {
				out.add(new Option(String.valueOf(gene.discreteLo + i), (float) i / span));
			}
			return out;
		}
		return switch (gene) {
			case FOOT_TYPE -> uniform(names(FootType.VALUES));
			case EYE_STYLE -> uniform(names(EyeStyle.VALUES));
			case TAIL_SHAPE -> uniform(names(TailShape.VALUES));
			case GLOW_REGION -> uniform(names(GlowRegion.VALUES));
			case PATTERN_TYPE -> uniform(names(BodyPalette.PatternType.values()));
			case HORN_TYPE -> aboveNone(names(HornType.VALUES), HornType.THRESHOLD);
			case EAR_TYPE -> aboveNone(names(EarType.VALUES), EarType.THRESHOLD);
			default -> List.of();
		};
	}

	/** Whether this locus picks from a named list at all. */
	public static boolean has(Gene gene) {
		return !of(gene).isEmpty();
	}

	private static String[] names(Enum<?>[] values) {
		String[] out = new String[values.length];
		for (int i = 0; i < values.length; i++) out[i] = values[i].name();
		return out;
	}

	/** Mirrors {@code Genome.discrete(gene, 0, n - 1)}: n equal slices of [0,1]. */
	private static List<Option> uniform(String[] labels) {
		List<Option> out = new ArrayList<>(labels.length);
		for (int i = 0; i < labels.length; i++) {
			out.add(new Option(labels[i], (float) i / labels.length));
		}
		return out;
	}

	/** Mirrors {@code HornType.of}: {@code NONE} up to the cut, then the rest split evenly above it. */
	private static List<Option> aboveNone(String[] labels, float threshold) {
		List<Option> out = new ArrayList<>(labels.length);
		out.add(new Option(labels[0], 0f));
		int real = labels.length - 1;
		for (int i = 1; i < labels.length; i++) {
			out.add(new Option(labels[i], threshold + (1f - threshold) * (i - 1) / real));
		}
		return out;
	}

	private GeneOptions() {
	}
}
