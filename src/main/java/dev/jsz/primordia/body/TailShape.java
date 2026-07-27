package dev.jsz.primordia.body;

/**
 * Cross-section and tip treatment of the tail, decoded from {@link
 * dev.jsz.primordia.genome.Gene#TAIL_SHAPE}.
 * <p>
 * The tail bones are always round capsules; shape comes from ellipsoid blobs laid along them
 * with anisotropic radii. Shapes that read as flat also <i>thin the capsule</i> first, because a
 * wide blob unioned with a full-thickness round capsule gives a round tail with side flanges
 * rather than a genuinely flat one.
 */
public enum TailShape {
	/** Round taper — the default reptile/mammal tail. */
	ROUND,
	/** Flattened top-to-bottom and widened: a beaver or crocodile paddle. */
	FLAT,
	/** Flattened side-to-side and deepened into a vertical swimming fin. */
	FIN,
	/** Round, with a heavy bony mass at the tip. */
	CLUB,
	/** Round along its length, opening into a wide flat fan at the tip. */
	FAN;

	public static final TailShape[] VALUES = values();

	public static TailShape of(dev.jsz.primordia.genome.Genome genome) {
		return VALUES[genome.discrete(dev.jsz.primordia.genome.Gene.TAIL_SHAPE, 0, VALUES.length - 1)];
	}

	/** How much to scale the tail capsule radius so the blobs define the silhouette. */
	public float capsuleScale() {
		return switch (this) {
			case FLAT -> 0.72f;
			case FIN -> 0.78f;
			default -> 1f;
		};
	}
}
