package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for dentition.
 * <p>
 * Teeth are the smallest thing the generator emits and the easiest to lose. Surface Nets only
 * produces geometry where the sampled field changes sign, so a tooth narrower than one sampling
 * cell is not rendered coarsely — it is not rendered at all, and nothing about the body plan looks
 * wrong when that happens. The first version of these came out at a fifth of a cell across and was
 * completely invisible in game while every existing test passed.
 */
class ToothTest {

	private static Genome withDiet(long seed, float diet) {
		return Genome.random(new Random(seed))
				.with(Gene.DIET, diet)
				.with(Gene.HEAD_SIZE, 0.6f)
				.with(Gene.SIZE, 0.55f);
	}

	private static int countTeeth(BodyPlan plan) {
		int n = 0;
		for (SdfBlob blob : plan.blobs) {
			if (blob.feature() == Feature.TOOTH) n++;
		}
		return n;
	}

	@Test
	void everyDietGrowsTeeth() {
		for (float diet : new float[]{0.1f, 0.5f, 0.9f}) {
			BodyPlan plan = BodyPlanBuilder.build(withDiet(31, diet));
			assertTrue(countTeeth(plan) > 0, "diet " + diet + " produced no teeth at all");
		}
	}

	@Test
	void dentitionDiffersByDiet() {
		BodyPlan herbivore = BodyPlanBuilder.build(withDiet(32, 0.10f));
		BodyPlan omnivore = BodyPlanBuilder.build(withDiet(32, 0.50f));
		BodyPlan carnivore = BodyPlanBuilder.build(withDiet(32, 0.90f));

		// A grinding ridge is one piece per side; a mouth full of fangs is many.
		assertTrue(countTeeth(herbivore) < countTeeth(omnivore),
				"a herbivore's grinding ridge should be fewer pieces than an omnivore's dentition");
		assertTrue(countTeeth(omnivore) > countTeeth(carnivore),
				"an omnivore carries more teeth than a carnivore's sparse fangs");

		// And the carnivore's should be the longest of the three.
		assertTrue(longestTooth(carnivore) > longestTooth(herbivore) * 1.5f,
				"a carnivore's fangs (" + longestTooth(carnivore) + ") should clearly outreach a "
						+ "herbivore's ridge (" + longestTooth(herbivore) + ")");
	}

	private static float longestTooth(BodyPlan plan) {
		float longest = 0f;
		for (SdfBlob blob : plan.blobs) {
			if (blob.feature() == Feature.TOOTH) longest = Math.max(longest, blob.radii().y);
		}
		return longest;
	}

	/**
	 * The one that matters: teeth have to be big enough for the mesher to see, and the mesher has
	 * to have been told they exist when it chose its resolution.
	 */
	@Test
	void teethSurviveMeshingRatherThanFallingBetweenSamples() {
		Random random = new Random(33);
		for (int trial = 0; trial < 12; trial++) {
			float diet = trial % 3 == 0 ? 0.15f : (trial % 3 == 1 ? 0.5f : 0.9f);
			BodyPlan plan = BodyPlanBuilder.build(
					Genome.random(random).with(Gene.DIET, diet));
			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));

			// The mesher must have sized its cells against the teeth, not just the legs.
			assertTrue(plan.minFeatureRadius <= plan.minLimbRadius + 1e-6f,
					"minFeatureRadius should account for teeth being finer than limbs");

			for (SdfBlob tooth : plan.blobs) {
				if (tooth.feature() != Feature.TOOTH) continue;

				float reach = tooth.maxRadius() * 3f + plan.blendRadius * 2f;
				boolean nearby = false;
				for (int v = 0; v < mesh.vertexCount && !nearby; v++) {
					int p = v * 3;
					float dx = mesh.positions[p] - tooth.center().x;
					float dy = mesh.positions[p + 1] - tooth.center().y;
					float dz = mesh.positions[p + 2] - tooth.center().z;
					if (dx * dx + dy * dy + dz * dz <= reach * reach) nearby = true;
				}
				assertTrue(nearby, "a tooth of radius " + tooth.maxRadius()
						+ " produced no mesh anywhere near it — it fell between sample points");
				break; // One per creature is enough; they are all the same size band.
			}
		}
	}
}
