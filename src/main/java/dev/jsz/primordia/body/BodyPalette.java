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
		// Secondary hue sits near the primary rather than opposite it. A wide spread here was
		// putting violet spots on a green animal — complementary schemes are a graphic-design
		// idea, not a biological one. Real markings are almost always a darker or lighter tone
		// of the same pigment, so the pattern reads as the same animal rather than two.
		float hue2 = (hue + genome.range(Gene.HUE_SECONDARY, -0.16f, 0.16f) + 1f) % 1f;
		float sat = pigmentSaturation(hue, genome.range(Gene.SATURATION, 0.06f, 0.78f));
		float val = genome.range(Gene.BRIGHTNESS, 0.22f, 0.92f);

		this.primary = hsvToRgb(hue, sat, val, new Vector3f());
		this.secondary = hsvToRgb(hue2, pigmentSaturation(hue2, sat * 1.15f),
				MathX.clamp01(val * 0.62f), new Vector3f());
		this.belly = hsvToRgb(hue, sat * 0.45f, MathX.clamp01(val * 1.35f + 0.12f), new Vector3f());
		// Eyes deliberately break from the body scheme so they read as eyes at distance. They are
		// exempt from the pigment cap for the same reason: an iris is a few pixels, and dulling it
		// to a naturalistic hide colour loses the one feature that tells you where the animal is
		// facing. The offset is short of a true complement so it stops short of neon.
		this.eye = hsvToRgb((hue + 0.42f) % 1f, 0.75f, genome.raw(Gene.EYE_SIZE) > 0.5f ? 0.95f : 0.15f, new Vector3f());

		PatternType[] types = PatternType.values();
		this.pattern = types[genome.discrete(Gene.PATTERN_TYPE, 0, types.length - 1)];
		this.patternScale = genome.biased(Gene.PATTERN_SCALE, 0.8f, 9f, 1.6f);
		this.patternContrast = genome.range(Gene.PATTERN_CONTRAST, 0f, 0.95f);
		this.countershading = genome.range(Gene.COUNTERSHADING, 0f, 0.85f);

		// Bioluminescence is a threshold trait: below the cut a creature does not glow at all,
		// and above it the strength ramps from faint to full rather than switching on at maximum.
		//
		// The cut used to sit at 0.55, which measured out at forty percent of every creature in
		// every biome — BIOLUMINESCENCE is one of the loci Genome.createForBiome leaves alone, so
		// nothing anywhere was pulling it down. Glowing animals were the norm rather than the
		// exception, which is the opposite of what makes one worth finding. See GLOW_RATE.
		float lumen = genome.raw(Gene.BIOLUMINESCENCE);
		this.glowStrength = lumen < GLOW_THRESHOLD
				? 0f
				: MathX.remap(lumen, GLOW_THRESHOLD, 1f, 0.25f, 0.85f);
		this.glowRegion = GlowRegion.VALUES[
				genome.discrete(Gene.GLOW_REGION, 0, GlowRegion.VALUES.length - 1)];
		this.glow = hsvToRgb(glowHue(genome.raw(Gene.GLOW_HUE)), 0.7f, 1f, new Vector3f());
	}

	/**
	 * Locus value at or above which a creature is bioluminescent at all.
	 * <p>
	 * Founder genomes draw this locus from {@link Genome#randomModerate} — the mean of three
	 * uniform samples — so the tail above the cut is cubic rather than linear, and the rate falls
	 * far faster than moving the threshold suggests. Measured across the biome founders this yields
	 * roughly one glowing creature in twenty-five.
	 * <p>
	 * {@link dev.jsz.primordia.body.BodyPlanBuilder} grows discrete light organs off the same
	 * locus and its threshold <b>must stay above this one</b> — an organ is emissive because of
	 * what it is, so a creature that grew pods while reporting no glow would light up regardless
	 * of every other check. {@link dev.jsz.primordia.mesh.Pattern} now refuses that combination
	 * outright rather than trusting the two constants to stay in order.
	 */
	public static final float GLOW_THRESHOLD = 0.82f;

	/**
	 * Maps the glow locus onto hues that bioluminescence actually occurs at.
	 * <p>
	 * The full colour wheel was reachable here, so glowing magenta and glowing scarlet were as
	 * common as anything else and read as neon signage rather than as an animal. Real light
	 * organs are overwhelmingly blue-green — that is where the chemistry lands and where water
	 * transmits — with a warm minority in the firefly range, so that is the distribution.
	 */
	private static float glowHue(float locus) {
		if (locus < 0.85f) {
			// Green through to blue-cyan.
			return MathX.remap(locus, 0f, 0.85f, 0.40f, 0.58f);
		}
		// The firefly tail: yellow-green to amber.
		return MathX.remap(locus, 0.85f, 1f, 0.13f, 0.19f);
	}

	/**
	 * Caps saturation by hue, so how vivid a colour may be depends on which colour it is.
	 * <p>
	 * Hue and saturation are independent loci, and mutation walks both, so nothing stopped a
	 * lineage arriving at fully saturated violet. Animal colour does not work that way: the
	 * pigments that produce it — melanins and carotenoids — cover black through brown, red,
	 * orange and yellow, and that arc is where a hide can be genuinely strong in colour. Greens
	 * are common but muted. Blues and purples are structural rather than pigmented, rare, and
	 * almost never vivid across a whole body.
	 * <p>
	 * Capping rather than remapping the hue keeps the whole wheel reachable — a violet creature
	 * is still possible, it just comes out slate rather than electric — and leaves the biome hue
	 * bands in {@link Genome#createForBiome} meaning exactly what they say.
	 */
	private static float pigmentSaturation(float hue, float requested) {
		return MathX.clamp01(requested) * pigmentAllowance(hue);
	}

	/**
	 * Fraction of the requested saturation a given hue is allowed to keep.
	 * <p>
	 * Deliberately asymmetric. A first attempt measured distance from the middle of the warm arc
	 * and fell away evenly in both directions, which sounds reasonable and is wrong at the wrap:
	 * magenta sits a short way round the wheel from red, so it scored as nearly a pigment and
	 * kept two thirds of its saturation. Hot pink is not an earth tone by virtue of being
	 * adjacent to one. The arc is rotated so it runs contiguously from red through the warm
	 * colours and the taper is written out along it.
	 */
	private static float pigmentAllowance(float hue) {
		float h = (hue % 1f + 1f) % 1f;
		// Rotate the wheel so the pigment arc stops straddling the wrap: t = 0 is deep red,
		// rising through orange and yellow into green, cyan, blue, violet, and back to magenta.
		float t = (h + 0.05f) % 1f;
		if (t < 0.20f) {
			// Red, rust, orange, tan, ochre, yellow. Melanin and carotenoid territory, and the
			// only place a hide is allowed to be genuinely strong in colour.
			return 1f;
		}
		if (t < 0.38f) {
			// Yellow-green into green: common, but as moss and olive rather than as parrot.
			return MathX.lerp(1f, 0.62f, MathX.smoothstep(MathX.remap(t, 0.20f, 0.38f, 0f, 1f)));
		}
		if (t < 0.52f) {
			return MathX.lerp(0.62f, 0.30f, MathX.smoothstep(MathX.remap(t, 0.38f, 0.52f, 0f, 1f)));
		}
		if (t < 0.90f) {
			// Cyan, blue, violet. Structural colours: real, but never across a whole animal at
			// strength. Held at a slate version of whatever was asked for.
			return 0.30f;
		}
		// Magenta climbing back toward red, where pigment resumes and a dusty rose is plausible.
		return MathX.lerp(0.30f, 1f, MathX.smoothstep(MathX.remap(t, 0.90f, 1f, 0f, 1f)));
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
