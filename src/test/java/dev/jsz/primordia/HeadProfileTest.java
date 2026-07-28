package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.genome.Genome;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the shape of the face across the gene space.
 * <p>
 * The generator used to place the muzzle at a fixed two thirds along the skull with a fixed forward
 * extent, so every creature — whatever its genome — came out with a tapering snout projecting from a
 * rounded braincase. Jaw size and jaw width scaled that muzzle and made it broader or narrower, but
 * never changed the silhouette. The whole world looked like dogs.
 * <p>
 * That is a failure with no invariant of its own to break, which is why nothing caught it: every
 * head was a perfectly valid head. The only thing to test is that the generator's <i>range</i> is
 * actually reachable — the same reason {@code OrnamentTest} exists.
 */
class HeadProfileTest {
	private static final int TRIALS = 600;

	/**
	 * Measures how far the face carries in front of the braincase, as a ratio of the head's forward
	 * extent to its width. Low is a blunt face, high is a long snout.
	 * <p>
	 * Read off the baked blobs rather than recomputed from the genome, for the reason
	 * {@code PITFALLS.md} §6 gives: a test that reimplements the code cannot catch the code. The
	 * previous version of this generator would have passed a recomputed check happily.
	 */
	private static float snoutRatio(BodyPlan plan) {
		// The whole head's forward extent, not any single blob's. Taking the largest radius instead
		// measures the braincase — which barely varies — and reports a constant no matter what the
		// face is doing. Model space is built facing +Z, so this is genuinely the fore-aft axis.
		float zMin = Float.MAX_VALUE;
		float zMax = -Float.MAX_VALUE;
		float maxX = 0f;
		for (SdfBlob blob : plan.blobs) {
			if (blob.feature() != Feature.HEAD) continue;
			zMin = Math.min(zMin, blob.center().z - blob.radii().z);
			zMax = Math.max(zMax, blob.center().z + blob.radii().z);
			maxX = Math.max(maxX, blob.radii().x);
		}
		if (maxX <= 0f || zMax < zMin) return 0f;
		return (zMax - zMin) / (maxX * 2f);
	}

	@Test
	void bothFlatFacedAndLongSnoutedAnimalsAreReachable() {
		Random random = new Random(606);
		float lowest = Float.MAX_VALUE;
		float highest = 0f;
		int blunt = 0;
		int elongated = 0;

		for (int trial = 0; trial < TRIALS; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			float ratio = snoutRatio(plan);
			if (ratio <= 0f) continue;

			lowest = Math.min(lowest, ratio);
			highest = Math.max(highest, ratio);
			if (ratio < 1.00f) blunt++;
			if (ratio > 1.60f) elongated++;
		}

		// Both ends of the axis have to actually occur. A generator that can only make one face
		// shape is not a generator, and this is the only thing about it that can be asserted —
		// a blunt-faced creature and a long-snouted one are both perfectly valid animals.
		//
		// Asserted as a tendency, with a wide margin, and said so plainly (PITFALLS §12). Measured
		// rates over this seed are around 8% blunt and 12% elongated; the bar is 5%, which leaves
		// room for the whole distribution to shift when a gene is added and Genome.random's
		// sequence moves under it.
		assertTrue(blunt > TRIALS / 20,
				"almost no flat-faced creatures are reachable: " + blunt + " of " + TRIALS);
		assertTrue(elongated > TRIALS / 20,
				"almost no long-snouted creatures are reachable: " + elongated + " of " + TRIALS);
		assertTrue(highest / lowest > 2.0f, String.format(
				"the face profile barely varies: %.2f to %.2f", lowest, highest));
	}

	@Test
	void everyGenomeStillGrowsAHead() {
		Random random = new Random(77);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);

			boolean hasHead = false;
			for (SdfBlob blob : plan.blobs) {
				if (blob.feature() == Feature.HEAD) {
					hasHead = true;
					// Drawing the muzzle back into the skull must not shrink it out of existence —
					// a blunt face is a broad face set back, not a head with the front missing.
					assertTrue(blob.radii().x > 0f && blob.radii().y > 0f && blob.radii().z > 0f,
							"a head blob collapsed to zero size");
				}
			}
			assertTrue(hasHead, "genome produced no head at all");
		}
	}
}
