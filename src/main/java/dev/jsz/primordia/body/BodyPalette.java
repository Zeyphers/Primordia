package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.util.MathX;
import org.joml.Vector3f;

/**
 * Colour genotype decoded into the handful of values the mesher needs. Vertex colours are
 * baked directly into the mesh, which lets every creature look different while sharing a
 * single flat white texture — no per-creature texture atlas, no UV unwrapping.
 */
public final class BodyPalette {
	public enum PatternType {
		SOLID, STRIPES, SPOTS, BANDS, MARBLE, RETICULATED
	}

	public final Vector3f primary;
	public final Vector3f secondary;
	public final Vector3f belly;
	public final Vector3f eye;
	public final PatternType pattern;
	/** Noise frequency of the pattern, in cycles per metre. */
	public final float patternScale;
	/** 0 = pattern invisible, 1 = full swap to the secondary colour. */
	public final float patternContrast;
	/** Strength of the dark-back / pale-belly gradient. */
	public final float countershading;

	/** Emitted colour of a bioluminescent creature. Meaningless when {@link #glowStrength} is 0. */
	public final Vector3f glow;
	/** 0 for a creature that does not glow at all; otherwise how brightly it does. */
	public final float glowStrength;
	/** Which parts light up. Only consulted when {@link #glowStrength} is above zero. */
	public final GlowRegion glowRegion;

	public BodyPalette(Genome genome) {
		float hue = genome.raw(Gene.HUE);
		// Secondary hue sits somewhere on the wheel relative to the primary, so
		// complementary and analogous schemes both occur naturally.
		float hue2 = (hue + genome.range(Gene.HUE_SECONDARY, -0.45f, 0.45f) + 1f) % 1f;
		float sat = genome.range(Gene.SATURATION, 0.08f, 0.85f);
		float val = genome.range(Gene.BRIGHTNESS, 0.22f, 0.92f);

		this.primary = hsvToRgb(hue, sat, val, new Vector3f());
		this.secondary = hsvToRgb(hue2, MathX.clamp01(sat * 1.15f), MathX.clamp01(val * 0.62f), new Vector3f());
		this.belly = hsvToRgb(hue, sat * 0.45f, MathX.clamp01(val * 1.35f + 0.12f), new Vector3f());
		// Eyes deliberately break from the body scheme so they read as eyes at distance.
		this.eye = hsvToRgb((hue + 0.5f) % 1f, 0.9f, genome.raw(Gene.EYE_SIZE) > 0.5f ? 0.95f : 0.15f, new Vector3f());

		PatternType[] types = PatternType.values();
		this.pattern = types[genome.discrete(Gene.PATTERN_TYPE, 0, types.length - 1)];
		this.patternScale = genome.biased(Gene.PATTERN_SCALE, 0.8f, 9f, 1.6f);
		this.patternContrast = genome.range(Gene.PATTERN_CONTRAST, 0f, 0.95f);
		this.countershading = genome.range(Gene.COUNTERSHADING, 0f, 0.85f);

		// Bioluminescence is a threshold trait: below the cut a creature does not glow at all,
		// and above it the strength ramps from faint to full rather than switching on at maximum.
		float lumen = genome.raw(Gene.BIOLUMINESCENCE);
		this.glowStrength = lumen < 0.55f ? 0f : MathX.remap(lumen, 0.55f, 1f, 0.35f, 1f);
		this.glowRegion = GlowRegion.VALUES[
				genome.discrete(Gene.GLOW_REGION, 0, GlowRegion.VALUES.length - 1)];
		// Always saturated and bright: a dim, desaturated "glow" just reads as a stain.
		this.glow = hsvToRgb(genome.raw(Gene.GLOW_HUE), 0.78f, 1f, new Vector3f());
	}

	public static Vector3f hsvToRgb(float h, float s, float v, Vector3f dest) {
		h = (h % 1f + 1f) % 1f;
		s = MathX.clamp01(s);
		v = MathX.clamp01(v);
		float i = (float) Math.floor(h * 6f);
		float f = h * 6f - i;
		float p = v * (1f - s);
		float q = v * (1f - f * s);
		float t = v * (1f - (1f - f) * s);
		return switch ((int) i % 6) {
			case 0 -> dest.set(v, t, p);
			case 1 -> dest.set(q, v, p);
			case 2 -> dest.set(p, v, t);
			case 3 -> dest.set(p, q, v);
			case 4 -> dest.set(t, p, v);
			default -> dest.set(v, p, q);
		};
	}
}
