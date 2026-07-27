package dev.jsz.primordia.body;

/**
 * Which parts of a bioluminescent creature actually emit, decoded from {@link
 * dev.jsz.primordia.genome.Gene#GLOW_REGION}.
 * <p>
 * Glow is a per-vertex property rather than geometry: {@link dev.jsz.primordia.mesh.Pattern}
 * writes an emissive scalar alongside the vertex colour and the renderer lifts the block-light
 * coordinate for those vertices. That keeps glowing creatures on the same single render layer as
 * everything else — no second pass, no extra draw call.
 */
public enum GlowRegion {
	/** Only the eyes catch the light. */
	EYES,
	/** The pattern's secondary colour glows, so stripes and spots light up. */
	MARKINGS,
	/** Underside glow — counter-illumination, the way deep-sea animals hide their silhouette. */
	BELLY,
	/** Dorsal ridge, spines and plates. */
	DORSAL,
	/** Feet, hands, claws and the tail tip. */
	EXTREMITIES,
	/** A dim wash over the whole animal. */
	WHOLE_BODY;

	public static final GlowRegion[] VALUES = values();
}
