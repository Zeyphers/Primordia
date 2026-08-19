package dev.jsz.primordia.splice;

/**
 * How far into a branch the player has got, and therefore how strong a trait they may carry.
 * <p>
 * See {@code MD/SPLICING.md} §5. This is the rule the whole design rests on, and it replaced a
 * per-player "genomic stability" pool that did the same job with a hidden budget:
 * <p>
 * <b>The right to carry a strong trait is earned by proving you can find strong examples of it.</b>
 * <p>
 * A depth caps the <i>potency</i> a splice may express, never the kind. It needs no currency and no
 * bookkeeping the player has to track, and it is self-balancing without tuning — a trait the ecology
 * rarely produces is automatically slow to unlock, so the rarity of the gene <i>is</i> the price of
 * the node and nobody had to set it. It also points the player at exactly the behaviour the design
 * exists to cause: not one animal studied to completion, but a survey.
 * <p>
 * {@link #DOMINANT} asks for <i>more</i> evidence rather than rarer evidence — five lineages over
 * the same bar {@link #EXPRESSED} wanted three of. A cut the ecology can barely reach would make the
 * top of a branch a lottery on world generation; a count the player can always work toward makes it
 * a survey they can finish.
 *
 * @param title    what the guide calls this depth
 * @param cap      the strongest value a splice at this depth may express
 * @param bar      how strong a lineage must be in the branch to count toward this depth
 * @param required how many distinct lineages over that bar must be characterised to Complete
 */
public enum SpliceDepth {

	TRACE("Trace", 0.45f, 0.45f, 1),
	EXPRESSED("Expressed", 0.75f, 0.75f, 3),
	DOMINANT("Dominant", 1.00f, 0.75f, 5);

	public static final SpliceDepth[] VALUES = values();

	public final String title;
	public final float cap;
	public final float bar;
	public final int required;

	SpliceDepth(String title, float cap, float bar, int required) {
		this.title = title;
		this.cap = cap;
		this.bar = bar;
		this.required = required;
	}

	/** The depth below this one, or null at the root of a branch. */
	public SpliceDepth previous() {
		return ordinal() == 0 ? null : VALUES[ordinal() - 1];
	}

	/**
	 * The strongest depth a player holding {@code unlocked} nodes may express in a branch.
	 * <p>
	 * Null when nothing in the branch is open yet, which is what a locked branch looks like.
	 */
	public static SpliceDepth highestOf(java.util.Set<SpliceDepth> unlocked) {
		SpliceDepth best = null;
		for (SpliceDepth depth : VALUES) {
			if (unlocked.contains(depth)) best = depth;
		}
		return best;
	}
}
