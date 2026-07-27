package dev.jsz.primordia.body;

/**
 * How a creature's eyes are arranged on the skull. Decoded from {@link
 * dev.jsz.primordia.genome.Gene#EYE_STYLE}.
 * <p>
 * Eye layout carries more identity per vertex than almost anything else on the body — a
 * cluster of eight small eyes reads as "spider" before the legs are even visible — so it is
 * worth spending several distinct branches on.
 */
public enum EyeStyle {
	/** One pair of spheres set into the skull. */
	SIMPLE,
	/** Horizontally elongated, set high and wide: a browser's field of view. */
	WIDE,
	/** Large faceted domes covering much of the head. */
	COMPOUND,
	/** Bulbs raised on stalks clear of the skull. */
	STALKED,
	/** Four pairs of varying size packed onto the front of the face — arachnid. */
	CLUSTER,
	/** A single pair under a heavy overhanging brow ridge. */
	HOODED;

	public static final EyeStyle[] VALUES = values();

	public static EyeStyle of(dev.jsz.primordia.genome.Genome genome) {
		return VALUES[genome.discrete(dev.jsz.primordia.genome.Gene.EYE_STYLE, 0, VALUES.length - 1)];
	}
}
