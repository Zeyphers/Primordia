package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPalette;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.genome.Mutation;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins how often a creature is allowed to be striking.
 * <p>
 * These are distribution tests, not correctness tests, and they exist because the failure they
 * guard against is invisible from any single creature. Every animal here was individually
 * plausible; it was only the population that was wrong — two in five glowed, and one in seven was
 * scarlet, because {@link Gene#BIOLUMINESCENCE} is one of the loci
 * {@link Genome#createForBiome} never touches and the warning-colour branch fired far too often.
 * Nothing short of counting them says so.
 */
class PaletteRarityTest {

	private static final String[] BIOMES = {
			"plains", "forest", "jungle", "desert", "swamp", "snowy_taiga", "lush_caves"
	};
	private static final int SAMPLES = 1500;

	@Test
	void bioluminescenceIsRareButNeverExtinct() {
		for (String biome : BIOMES) {
			var random = net.minecraft.util.math.random.Random.create(4242L);
			int glowing = 0;
			for (int i = 0; i < SAMPLES; i++) {
				if (new BodyPalette(Genome.createForBiome(random, biome)).glowStrength > 0f) glowing++;
			}
			float rate = (float) glowing / SAMPLES;
			assertTrue(rate < 0.10f, biome + ": " + Math.round(rate * 100)
					+ "% of creatures glow — bioluminescence is supposed to be a find, not the norm");
			// Two-sided on purpose: raising the threshold far enough to switch the trait off
			// entirely would satisfy a one-sided assertion perfectly.
			assertTrue(rate > 0.004f, biome + ": bioluminescence has effectively died out at "
					+ rate + " — the threshold has gone too far the other way");
		}
	}

	@Test
	void bluesAndPurplesAreNeverVivid() {
		var random = net.minecraft.util.math.random.Random.create(99L);
		var drift = new java.util.Random(99L);
		for (int i = 0; i < SAMPLES; i++) {
			// Drifted, not freshly founded: founding runs a long pre-history, and mutation walks
			// hue and saturation independently. The cap has to hold for genomes nothing curated.
			Genome g = Genome.createForBiome(random, BIOMES[i % BIOMES.length]);
			for (int gen = 0; gen < 25; gen++) g = Mutation.mutate(g, drift);

			BodyPalette p = new BodyPalette(g);
			assertPigmentPlausible(g, p.primary.x, p.primary.y, p.primary.z, "body");
			assertPigmentPlausible(g, p.secondary.x, p.secondary.y, p.secondary.z, "pattern");
		}
	}

	private static void assertPigmentPlausible(Genome g, float r, float gr, float b, String what) {
		float[] hsv = rgbToHsv(r, gr, b);
		float hue = hsv[0], sat = hsv[1];
		// Cyan through violet: the structural colours, which no animal wears at strength across
		// its whole body. Stops short of magenta at both ends, where pigment resumes and a rust
		// or a dusty rose is perfectly plausible — the cap is about electric blue, not about
		// forbidding a colour wheel.
		boolean structural = hue > 0.48f && hue < 0.84f;
		if (!structural) return;
		assertTrue(sat <= 0.30f, what + " colour came out at hue " + hue + " saturation " + sat
				+ " — a vivid blue or purple hide, which the pigment cap exists to prevent"
				+ " (genome " + g + ")");
	}

	@Test
	void glowStaysInTheColoursBioluminescenceActuallyOccursAt() {
		var random = net.minecraft.util.math.random.Random.create(7L);
		int checked = 0;
		for (int i = 0; i < SAMPLES * 4 && checked < 200; i++) {
			BodyPalette p = new BodyPalette(Genome.createForBiome(random, BIOMES[i % BIOMES.length]));
			if (p.glowStrength <= 0f) continue;
			checked++;
			float hue = rgbToHsv(p.glow.x, p.glow.y, p.glow.z)[0];
			// Blue-green, or the warm firefly tail. Anything between is the neon the full wheel
			// used to produce: glowing magenta and glowing scarlet were as likely as anything.
			boolean blueGreen = hue >= 0.38f && hue <= 0.60f;
			boolean firefly = hue >= 0.11f && hue <= 0.21f;
			assertTrue(blueGreen || firefly,
					"a creature glows at hue " + hue + ", which is not a colour anything glows at");
		}
		assertTrue(checked > 20, "not enough glowing creatures sampled to say anything: " + checked);
	}

	@Test
	void aCreatureBelowTheGlowThresholdEmitsNothingAtAll() {
		// The gap between the palette's cut and the threshold BodyPlanBuilder grows light organs
		// at is the dangerous band: a creature in it would carry Feature.GLOW blobs while
		// reporting no glow, and Pattern used to light those unconditionally. Sampled just under
		// the cut, where organs must not appear and nothing may emit.
		Genome base = Genome.random(new java.util.Random(2024));
		for (float lumen : new float[]{0f, 0.4f, BodyPalette.GLOW_THRESHOLD - 0.01f}) {
			Genome g = base.with(Gene.BIOLUMINESCENCE, lumen);
			BodyPlan plan = BodyPlanBuilder.build(g);
			assertEquals(0f, plan.palette.glowStrength, 0f,
					"lumen " + lumen + " reported a glow strength below the threshold");

			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.MID));
			for (float e : mesh.emissive) {
				assertEquals(0f, e, 0f,
						"a non-bioluminescent creature (lumen " + lumen + ") baked emissive geometry");
			}
		}
	}

	private static float[] rgbToHsv(float r, float g, float b) {
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h;
		if (d < 1e-6f) h = 0f;
		else if (max == r) h = ((g - b) / d % 6f) / 6f;
		else if (max == g) h = ((b - r) / d + 2f) / 6f;
		else h = ((r - g) / d + 4f) / 6f;
		if (h < 0f) h += 1f;
		return new float[]{h, max < 1e-6f ? 0f : d / max, max};
	}
}
