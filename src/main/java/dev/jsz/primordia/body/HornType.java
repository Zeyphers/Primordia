package dev.jsz.primordia.body;

/**
 * Cranial weaponry and display structures, decoded from {@link
 * dev.jsz.primordia.genome.Gene#HORN_TYPE}.
 * <p>
 * Horns are built as short chains of tapering ellipsoids attached to the head bone, so they
 * follow head-tracking for free and cost nothing in the skeleton. {@link #NONE} occupies the
 * bottom of the gene's range because a world where every animal is horned reads as noise.
 */
public enum HornType {
	NONE,
	/** A straight pair angled up and back off the rear of the skull. */
	SPIKE,
	/** A pair curving back and down around the cheek, like a ram. */
	CURVED,
	/** Short, thick, forward-pointing horns sat directly over the eyes. */
	BROW,
	/** One horn on the midline of the snout. */
	NASAL,
	/** A branching pair: main beam plus two tines. */
	ANTLER,
	/** A single flat blade running along the skull midline — a casque rather than a horn. */
	CREST;

	public static final HornType[] VALUES = values();
	/** The types that are actually horns, i.e. everything except {@link #NONE}. */
	public static final int REAL_COUNT = VALUES.length - 1;

	/** Locus value below which a creature grows no horns at all. */
	public static final float THRESHOLD = 0.40f;

	public static HornType of(dev.jsz.primordia.genome.Genome genome) {
		float roll = genome.raw(dev.jsz.primordia.genome.Gene.HORN_TYPE);
		if (roll < THRESHOLD) return NONE;
		return VALUES[1 + dev.jsz.primordia.util.MathX.clamp(
				(int) ((roll - THRESHOLD) / (1f - THRESHOLD) * REAL_COUNT), 0, REAL_COUNT - 1)];
	}
}
