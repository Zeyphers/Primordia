package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.entity.Temperament;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that founder archetypes actually produce structurally distinct animals, and that the
 * widened size range has not broken any of the invariants the mesher and animator rely on.
 */
class ArchetypeTest {

	private static BodyPlan build(Archetype archetype, Random random) {
		return BodyPlanBuilder.build(archetype.create(random));
	}

	@Test
	void archetypesProduceTheirDefiningStructure() {
		Random random = new Random(4141);
		for (int trial = 0; trial < 25; trial++) {
			assertEquals(2, build(Archetype.BIPED, random).legs.length,
					"a biped must have exactly one pair of legs");
			assertTrue(build(Archetype.APEX, random).legs.length == 2,
					"an apex predator is bipedal");
			assertTrue(build(Archetype.INSECTOID, random).legs.length >= 6,
					"an insectoid needs at least three pairs of legs");
			assertEquals(4, build(Archetype.SAURIAN, random).legs.length,
					"a saurian is a quadruped");
			assertEquals(4, build(Archetype.GRAZER, random).legs.length,
					"a grazer is a quadruped");
		}
	}

	@Test
	void archetypesOccupyDistinctSizeBands() {
		Random random = new Random(4142);
		Map<Archetype, Double> meanLength = new EnumMap<>(Archetype.class);

		for (Archetype archetype : Archetype.VALUES) {
			double sum = 0;
			int n = 30;
			for (int i = 0; i < n; i++) sum += build(archetype, random).bodyLength;
			meanLength.put(archetype, sum / n);
		}

		// The whole point of archetypes is that they are not all the same animal.
		assertTrue(meanLength.get(Archetype.SAURIAN) > meanLength.get(Archetype.INSECTOID) * 3,
				"saurians should dwarf insectoids: " + meanLength.get(Archetype.SAURIAN)
						+ " vs " + meanLength.get(Archetype.INSECTOID));
		assertTrue(meanLength.get(Archetype.APEX) > meanLength.get(Archetype.PACK_HUNTER),
				"an apex predator should outsize a pack hunter");
	}

	@Test
	void creaturesReachDinosaurScale() {
		Random random = new Random(4143);
		float tallest = 0f;
		for (int i = 0; i < 200; i++) {
			tallest = Math.max(tallest, build(Archetype.SAURIAN, random).height());
		}
		assertTrue(tallest <= BodyPlanBuilder.MAX_HEIGHT + 1e-3f,
				"creatures should not exceed " + BodyPlanBuilder.MAX_HEIGHT + "m tall but was " + tallest);
		// Read from the constant rather than repeated as a literal, so raising the ceiling cannot
		// leave this test quietly asserting the old one.
		//
		// The lower bound is past where the ceiling used to sit, which is the point of the change:
		// at 2.5 m roughly a creature in six was being scaled down to fit, so saurian, apex and the
		// giant arachnid all arrived at exactly the same height and the top of the size range was
		// flat. If this drops back under 2.5 the tall archetypes have stopped being tall.
		assertTrue(tallest >= 3.0f, "the largest saurian was only " + tallest + " m tall");
	}

	@Test
	void everyArchetypeStillMeshesAndStandsUp() {
		Random random = new Random(4144);
		for (Archetype archetype : Archetype.VALUES) {
			for (int trial = 0; trial < 8; trial++) {
				BodyPlan plan = build(archetype, random);

				assertTrue(plan.minLimbRadius > 0f, archetype + ": no limb radius");
				assertTrue(plan.blendRadius <= plan.minLimbRadius * 1.6f,
						archetype + ": blend radius would swallow the thinnest feature");
				for (var leg : plan.legs) {
					assertEquals(0f, leg.restEffector.y, 1e-3f, archetype + ": foot is off the ground");
					assertTrue(leg.totalLength > leg.origin.distance(leg.restEffector),
							archetype + ": leg cannot reach its own rest position");
				}
				var mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.MID));
				assertTrue(mesh.quadCount > 0, archetype + ": produced no geometry");
			}
		}
	}

	@Test
	void tailsNeverTaperToAThread() {
		Random random = new Random(4145);
		for (int trial = 0; trial < 400; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			for (BoneDef bone : plan.bones) {
				if (bone.feature != Feature.TAIL) continue;
				// A tail tip narrower than the smooth-union radius is simply absorbed by the blend.
				assertTrue(Math.min(bone.radiusHead, bone.radiusTail) >= plan.blendRadius,
						"tail segment " + bone.name + " (radius "
								+ Math.min(bone.radiusHead, bone.radiusTail)
								+ ") is thinner than the blend radius " + plan.blendRadius);
			}
		}
	}

	@Test
	void temperamentCoversAllThreeResponses() {
		Random random = new Random(4146);
		Map<Temperament, Integer> counts = new EnumMap<>(Temperament.class);
		for (Temperament t : Temperament.values()) counts.put(t, 0);

		for (int i = 0; i < 800; i++) {
			counts.merge(Temperament.of(Genome.random(random)), 1, Integer::sum);
		}
		// A disposition nothing ever expresses is dead code dressed as a feature.
		for (Temperament t : Temperament.values()) {
			assertTrue(counts.get(t) > 20, t + " almost never occurs: " + counts.get(t) + "/800");
		}
	}

	@Test
	void committedPredatorsAreTheOnesThatHuntUnprovoked() {
		Random random = new Random(4147);
		for (int i = 0; i < 500; i++) {
			Genome genome = Genome.random(random);
			if (Temperament.of(genome).huntsUnprovoked()) {
				assertTrue(genome.raw(Gene.DIET) >= 0.65f,
						"a herbivore should never hunt the player unprovoked");
			}
		}
	}
}
