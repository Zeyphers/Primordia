package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BodyPalette;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.GlowRegion;
import dev.jsz.primordia.util.MathX;
import dev.jsz.primordia.util.Noise;
import org.joml.Vector3f;

/**
 * Bakes per-vertex colour. Colour lives in the mesh rather than in a texture, which means a
 * creature needs no texture atlas, no UV unwrap and no resource-pack entry — the renderer
 * binds one flat white texture for every creature in the world and modulates it with these
 * vertex colours.
 * <p>
 * Three layers stack, in order: the base pattern (stripes, spots, marble...), then
 * countershading (dark back, pale belly — the single cheapest trick for making a procedural
 * animal read as an animal), then feature overrides for eyes, jaws and plates.
 */
public final class Pattern {
	/** Keratin: horn, tusk and claw sheath all sit near this rather than the body colour. */
	private static final Vector3f KERATIN = new Vector3f(0.86f, 0.82f, 0.71f);

	private Pattern() {
	}

	/**
	 * Writes the vertex colour into {@code dest} and returns the vertex's <b>emissive</b> weight
	 * in [0,1] — 0 for ordinary flesh, 1 for a light organ. The renderer lifts the block-light
	 * coordinate by that weight, which is how a glowing creature stays on the same single render
	 * layer as every other one.
	 */
	public static float colorAt(float x, float y, float z, float nx, float ny, float nz,
	                            Feature feature, BodyPalette palette, Noise noise, Vector3f dest) {
		// A dedicated light organ is emissive whatever the glow region says — it is the organ.
		//
		// Except on a creature that does not glow at all. Whether organs are grown is decided in
		// BodyPlanBuilder off its own threshold on the same locus, and the floor below used to
		// apply unconditionally: raise the palette's cut past the builder's and every animal in
		// the gap between them would sprout fully lit pods while reporting no bioluminescence.
		// The two constants are ordered correctly today, and this makes it not matter if they
		// ever stop being — the palette is the single authority on whether a creature emits.
		if (feature == Feature.GLOW) {
			if (palette.glowStrength <= 0f) {
				dest.set(palette.primary);
				return 0f;
			}
			dest.set(palette.glow);
			return Math.max(palette.glowStrength, 0.85f);
		}
		// Eyes bypass everything: they must stay readable against any body colour.
		if (feature == Feature.EYE) {
			dest.set(palette.eye);
			if (palette.glowStrength > 0f
					&& (palette.glowRegion == GlowRegion.EYES
					|| palette.glowRegion == GlowRegion.WHOLE_BODY)) {
				dest.lerp(palette.glow, 0.65f);
				return palette.glowStrength;
			}
			return 0f;
		}

		float s = palette.patternScale;
		float m = switch (palette.pattern) {
			case SOLID -> 0f;
			// Transverse bands around the body, like a tiger or a coral snake.
			case STRIPES -> step(0.5f + 0.5f * (float) Math.sin(z * s * (float) Math.PI * 2f)
					+ 0.25f * noise.sample(x * s * 0.5f, y * s * 0.5f, z * s * 0.5f));
			// Blotches.
			case SPOTS -> step(0.5f + 0.9f * noise.fbm(x * s, y * s, z * s, 2, 2.1f, 0.55f));
			// Horizontal bands, stacked by height.
			case BANDS -> step(0.5f + 0.5f * (float) Math.sin(y * s * (float) Math.PI * 2f));
			// Soft swirling two-tone.
			case MARBLE -> MathX.clamp01(0.5f + 0.75f * noise.fbm(x * s * 0.6f, y * s * 0.6f, z * s * 0.6f, 3, 2.0f, 0.5f));
			// A net of thin lines between cells, like a giraffe or a reticulated python.
			case RETICULATED -> 1f - step(0.35f - Math.abs(noise.fbm(x * s, y * s, z * s, 2, 2.3f, 0.5f)) * 2.2f);
		};

		dest.set(palette.primary).lerp(palette.secondary, MathX.clamp01(m) * palette.patternContrast);

		// Countershading keyed off the surface normal rather than height, so it follows the body
		// around limbs and under the jaw instead of slicing a flat horizontal line.
		float upness = MathX.smoothstep(MathX.remap(ny, -0.55f, 0.35f, 0f, 1f));
		dest.lerp(palette.belly, (1f - upness) * palette.countershading);

		// High-density micro-texture (scales / skin grain / pores) for detailed surfaces.
		float mottle = noise.fbm(x * s * 3.5f, y * s * 3.5f, z * s * 3.5f, 2, 2.7f, 0.5f);
		float microGrain = noise.fbm(x * s * 12.0f, y * s * 12.0f, z * s * 12.0f, 3, 2.2f, 0.5f);
		float grain = 1f + mottle * 0.14f + microGrain * 0.08f;
		dest.mul(grain);

		// A touch of ambient-occlusion-like darkening where the surface faces sideways or down,
		// which reads as creases and gives the silhouette some depth under flat Minecraft lighting.
		float crease = 1f - 0.12f * (1f - Math.abs(ny));
		dest.mul(crease);

		switch (feature) {
			// Extremities darken: wear, keratin, and it visually anchors the silhouette.
			case FOOT, JAW -> dest.mul(0.72f);
			case HAND -> dest.mul(0.68f);
			case CLAWS -> dest.mul(0.45f); // Sharp dark keratin talons
			case SPINE -> dest.lerp(palette.secondary, 0.80f).mul(0.85f);
			case HAIR -> dest.lerp(palette.primary, 0.5f).mul(1.1f); // Lighter fur/crest
			case EYE_STALK -> dest.mul(0.82f);
			case PLATE -> dest.lerp(palette.secondary, 0.75f).mul(0.80f);
			case TAIL -> dest.mul(0.92f);
			// Horn and tusk are dead keratin, so they read as bone rather than as skin — which
			// is exactly what makes them look grown onto the animal instead of painted on it.
			case HORN -> dest.lerp(KERATIN, 0.55f).mul(0.92f);
			case TUSK -> dest.lerp(KERATIN, 0.78f);
			case BEAK -> dest.lerp(palette.secondary, 0.45f).mul(0.72f);
			case EAR -> dest.mul(0.88f);
			// Frills and fins are thin membrane: they catch more light and show more of the
			// display colour than the hide around them.
			case FRILL -> dest.lerp(palette.secondary, 0.55f).mul(1.06f);
			case FIN -> dest.lerp(palette.secondary, 0.45f).mul(0.96f);
			case ABDOMEN -> dest.mul(0.95f);
			default -> {
			}
		}

		// Bioluminescence, applied last so it survives the feature tints above.
		float emissive = 0f;
		if (palette.glowStrength > 0f) {
			float region = switch (palette.glowRegion) {
				case EYES -> 0f;
				case MARKINGS -> MathX.smoothstep(MathX.remap(m, 0.55f, 0.90f, 0f, 1f));
				case BELLY -> 1f - upness;
				case DORSAL -> feature == Feature.SPINE || feature == Feature.PLATE
						? 1f
						: MathX.smoothstep(MathX.remap(ny, 0.25f, 0.85f, 0f, 1f));
				case EXTREMITIES -> switch (feature) {
					case FOOT, HAND, CLAWS, TAIL, FIN -> 1f;
					default -> 0f;
				};
				case WHOLE_BODY -> 0.55f;
			};
			emissive = MathX.clamp01(region * palette.glowStrength);
			if (emissive > 0f) dest.lerp(palette.glow, emissive * 0.75f);
		}

		dest.set(MathX.clamp01(dest.x), MathX.clamp01(dest.y), MathX.clamp01(dest.z));
		return emissive;
	}

	/** Soft threshold at 0.5, kept slightly gradual so patterns do not alias on a coarse mesh. */
	private static float step(float v) {
		return MathX.smoothstep(MathX.remap(v, 0.42f, 0.58f, 0f, 1f));
	}
}
