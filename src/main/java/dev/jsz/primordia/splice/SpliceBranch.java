package dev.jsz.primordia.splice;

import dev.jsz.primordia.genome.Gene;

import java.util.ArrayList;
import java.util.List;

/**
 * One branch of the splice tree, and the contiguous run of loci it carries.
 * <p>
 * See {@code MD/SPLICING.md} §4. The unit of splicing is the <b>linkage block</b>, not the gene:
 * {@code Mutation.crossover} already cuts the genome at a few points so linked traits travel
 * together, and splicing uses the same unit. You cannot cherry-pick a locus — you adopt a
 * contiguous span of the donor's genome and you get all of it, including whatever else that
 * particular animal happens to be.
 * <p>
 * The spans are defined by their first and last {@link Gene} rather than by listing members, so a
 * locus inserted into the enum inside a span joins that block automatically instead of silently
 * falling out of every one of them.
 *
 * @param title     what the guide calls this branch
 * @param first     first locus of the span, inclusive
 * @param last      last locus of the span, inclusive
 * @param headline  the locus whose value decides the branch's potency and its unlock progress, or
 *                  null for a branch that is purely cosmetic and has no strength to rank by
 * @param blurb     one line naming the trade, shown under the branch in the guide
 */
public enum SpliceBranch {

	PHYSIOLOGY("Physiology", Gene.DIET, Gene.STAMINA, Gene.SPEED,
			"Speed and wind — and that animal's appetite along with them."),

	DISPOSITION("Disposition", Gene.AGGRESSION, Gene.TERRITORIALITY, Gene.AGGRESSION,
			"Wild creatures read you differently. Not always better."),

	/**
	 * Climate starts at {@code TEMP_PREFERENCE} rather than at {@code NOCTURNALITY}, which sits
	 * immediately before it. Sleep schedule is not a climate trait, and a block is only honest if
	 * everything inside it is something the donor's environment actually selected together.
	 */
	CLIMATE("Climate", Gene.TEMP_PREFERENCE, Gene.ARMOR, Gene.ARMOR,
			"Tolerance for where it lived, and the hide it needed to live there."),

	COLOUR("Colour", Gene.HUE, Gene.COUNTERSHADING, null,
			"Its colour and its markings. Costs you nothing but a slot."),

	LIGHT("Light", Gene.BIOLUMINESCENCE, Gene.GLOW_HUE, Gene.BIOLUMINESCENCE,
			"You emit light, in its colour, from the parts it lit."),

	/**
	 * Habit stops at {@code GRAZING_IMPACT}.
	 * <p>
	 * {@code MD/SPLICING.md} lists {@code SUBTERRANEAN} in this block, and it belongs there by
	 * meaning — a preference for the dark is exactly the drawback the design wants attached to a
	 * digging animal. It cannot be here. {@code SUBTERRANEAN} was appended at the end of the enum
	 * long after the habit loci, and {@link Gene} ordinals are the wire format that
	 * {@code Genome.decode} reads by index, so moving it would silently reinterpret every genome
	 * ever saved. A block that is contiguous in meaning but not in memory is not a linkage block,
	 * it is a list — and the honesty of Rule 2 is the whole reason the rule earns its drawbacks.
	 */
	HABIT("Habit", Gene.BURROWING, Gene.GRAZING_IMPACT, Gene.BURROWING,
			"Digging, nesting, and an appetite for the ground cover.");

	public static final SpliceBranch[] VALUES = values();

	public final String title;
	public final Gene first;
	public final Gene last;
	/** Null on a purely cosmetic branch; see {@link #COLOUR}. */
	public final Gene headline;
	public final String blurb;

	SpliceBranch(String title, Gene first, Gene last, Gene headline, String blurb) {
		this.title = title;
		this.first = first;
		this.last = last;
		this.headline = headline;
		this.blurb = blurb;
	}

	/** Every locus in this block, in enum order. */
	public List<Gene> genes() {
		List<Gene> out = new ArrayList<>();
		for (int i = first.ordinal(); i <= last.ordinal(); i++) out.add(Gene.VALUES[i]);
		return out;
	}

	public boolean carries(Gene gene) {
		return gene.ordinal() >= first.ordinal() && gene.ordinal() <= last.ordinal();
	}

	/** True for a branch with no strength to rank donors by, which is the tutorial branch. */
	public boolean cosmetic() {
		return headline == null;
	}

	/**
	 * How strong this donor is in this branch, in [0,1].
	 * <p>
	 * A cosmetic branch has no such thing, and answers 1 so that every donor is equally valid and
	 * the branch unlocks on the first specimen filed. That is deliberate: {@link #COLOUR} exists so
	 * the player learns the mechanic on something that cannot hurt them.
	 */
	public float potency(dev.jsz.primordia.genome.Genome donor) {
		return cosmetic() ? 1f : donor.raw(headline);
	}
}
