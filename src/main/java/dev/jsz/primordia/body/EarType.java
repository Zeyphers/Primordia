package dev.jsz.primordia.body;

/**
 * External ear shape, decoded from {@link dev.jsz.primordia.genome.Gene#EAR_TYPE}. Ears are
 * flattened ellipsoids on the sides of the cranium, attached to the head bone.
 */
public enum EarType {
	NONE,
	/** Small discs pressed against the skull. */
	ROUND,
	/** Tall and pricked, angled up and slightly back. */
	UPRIGHT,
	/** Long and hanging down past the jaw. */
	DROOPING,
	/** A broad membranous fan, wider than it is tall. */
	FANNED;

	public static final EarType[] VALUES = values();
	public static final int REAL_COUNT = VALUES.length - 1;

	/** Locus value below which a creature has no external ears. */
	public static final float THRESHOLD = 0.45f;

	public static EarType of(dev.jsz.primordia.genome.Genome genome) {
		float roll = genome.raw(dev.jsz.primordia.genome.Gene.EAR_TYPE);
		if (roll < THRESHOLD) return NONE;
		return VALUES[1 + dev.jsz.primordia.util.MathX.clamp(
				(int) ((roll - THRESHOLD) / (1f - THRESHOLD) * REAL_COUNT), 0, REAL_COUNT - 1)];
	}
}
