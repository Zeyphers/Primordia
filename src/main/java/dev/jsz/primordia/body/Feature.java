package dev.jsz.primordia.body;

/**
 * Semantic tag on a piece of geometry. The mesher uses it to paint vertex colours
 * (eyes are not the same colour as flanks) and later milestones use it for hit
 * regions — a bite lands on {@link #JAW}, armour applies to {@link #PLATE}.
 */
public enum Feature {
	BODY,
	HEAD,
	JAW,
	EYE,
	LIMB,
	FOOT,
	TAIL,
	PLATE,
	CLAWS,
	HAND,
	SPINE,
	HAIR,
	EYE_STALK,
	/** Keratin horn, antler or casque grown off the skull. */
	HORN,
	/** Enlarged tooth projecting past the lip. */
	TUSK,
	/** Hard keratin sheath over the front of the jaw. */
	BEAK,
	/** External ear. */
	EAR,
	/** Neck frill or dorsal sail — thin membrane over a bony frame. */
	FRILL,
	/** Tail or dorsal fin, and the flat blades of paddle tails. */
	FIN,
	/** Rear body segment of a two-part (arachnid) body plan. */
	ABDOMEN,
	/** A dedicated light organ. Always emissive regardless of the glow region. */
	GLOW;

	public static final Feature[] VALUES = values();

	/**
	 * True for features that sit <b>on</b> the surface rather than being part of the flesh.
	 * <p>
	 * The SDF unions these with a hard minimum instead of a smooth one, so a horn stays a horn
	 * with a crisp base rather than melting into the skull it grows from. Structural mass — the
	 * cranium, the jaw, an abdomen — is deliberately not in this set, because those <i>should</i>
	 * fair into the body.
	 */
	public boolean isSurfaceDetail() {
		return switch (this) {
			// Hard keratin and sensory organs: these want a crisp base where they leave the hide.
			case EYE, EYE_STALK, CLAWS, SPINE, HAND, HORN, TUSK, BEAK, EAR, FRILL, FIN -> true;
			// Armour plating and light organs are part of the flesh, not mounted on it. Hard
			// unioning them left a row of discs and balls stuck to the body with a visible rim
			// around each; they belong in the smooth union with the cranium and the jaw.
			default -> false;
		};
	}
}
