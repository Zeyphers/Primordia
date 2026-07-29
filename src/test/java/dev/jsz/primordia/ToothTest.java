package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.ToothDef;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinBinder;
import dev.jsz.primordia.sdf.BodySdf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for dentition.
 * <p>
 * Teeth had two goes at this. As blobs in the signed distance field they were both too fine for
 * the mesher to resolve — a fifth of a sampling cell across, so simply absent — and, once made big
 * enough to survive, rounded off by the smooth union into white lumps along the lip. They are now
 * geometry emitted outside the field entirely, which is what these tests pin down: that the teeth
 * exist as real faces, that they are rigid to one bone, and that they still protrude from the gum.
 */
class ToothTest {

	private static Genome withDiet(long seed, float diet) {
		return Genome.random(new Random(seed))
				.with(Gene.DIET, diet)
				.with(Gene.HEAD_SIZE, 0.6f)
				.with(Gene.SIZE, 0.55f);
	}

	@Test
	void everyDietGrowsTeeth() {
		for (float diet : new float[]{0.1f, 0.5f, 0.9f}) {
			BodyPlan plan = BodyPlanBuilder.build(withDiet(31, diet));
			assertTrue(plan.teeth.length > 0, "diet " + diet + " produced no teeth at all");
			for (ToothDef tooth : plan.teeth) {
				assertTrue(tooth.protrusion() > 0f, "a tooth that stands clear of nothing");
				assertEquals(1f, tooth.direction().length(), 1e-4f, "tooth direction not normalised");
				assertTrue(tooth.radius() > 0f, "tooth with no width");
				assertTrue(tooth.bone() >= 0 && tooth.bone() < plan.bones.length,
						"tooth pinned to a bone that does not exist");
			}
		}
	}

	@Test
	void bothJawsAreToothed() {
		BodyPlan plan = BodyPlanBuilder.build(withDiet(35, 0.8f));
		int upper = 0;
		int lower = 0;
		for (ToothDef tooth : plan.teeth) {
			if (tooth.bone() == plan.headBone) upper++;
			if (tooth.bone() == plan.jawBone) lower++;
		}
		// Teeth on only one jaw would part in a way no mouth does.
		assertTrue(upper > 0, "no upper teeth");
		assertTrue(lower > 0, "no lower teeth");
		assertEquals(upper, lower, "the two rows should match");
	}

	@Test
	void dentitionDiffersByDiet() {
		BodyPlan herbivore = BodyPlanBuilder.build(withDiet(32, 0.10f));
		BodyPlan omnivore = BodyPlanBuilder.build(withDiet(32, 0.50f));
		BodyPlan carnivore = BodyPlanBuilder.build(withDiet(32, 0.90f));

		// A dense row of grinders against a sparse set of fangs.
		assertTrue(herbivore.teeth.length > carnivore.teeth.length,
				"a herbivore should carry more, smaller teeth than a carnivore");
		assertTrue(omnivore.teeth.length > carnivore.teeth.length,
				"an omnivore should carry more teeth than a carnivore");

		assertTrue(longest(carnivore) > longest(herbivore) * 1.5f,
				"a carnivore's fangs (" + longest(carnivore) + ") should clearly outreach a "
						+ "herbivore's grinders (" + longest(herbivore) + ")");
		// Grinders are chiselled flat; fangs come to a point.
		assertTrue(allBlunt(herbivore), "a herbivore's teeth should all be blunt");
		assertFalse(allBlunt(carnivore), "a carnivore should have pointed teeth");
	}

	/** How far the longest tooth stands clear of the gum. */
	private static float longest(BodyPlan plan) {
		float longest = 0f;
		for (ToothDef tooth : plan.teeth) longest = Math.max(longest, tooth.protrusion());
		return longest;
	}

	private static boolean allBlunt(BodyPlan plan) {
		for (ToothDef tooth : plan.teeth) {
			if (!tooth.blunt()) return false;
		}
		return true;
	}

	/**
	 * The failure that motivated moving teeth out of the field: they must actually reach the
	 * player's eye. Rooted in the gum, and standing clear of it once the mesher has walked out to
	 * the surface — an offset guessed from the bone axis instead left one whole row buried inside
	 * the lip on every creature, which is exactly half of them.
	 */
	@Test
	void teethAreRootedInsideTheGumAndProtrudeFromIt() {
		Random random = new Random(36);
		int rooted = 0;
		int roots = 0;
		int protruding = 0;
		int tipVertices = 0;

		// Sixty rather than twenty. This statistic is noisy at small samples — measured across six
		// seeds at twenty trials it ranged from 18% to 33%, and at sixty it settles into 24–28%
		// wherever it starts. A threshold set against the small sample is a threshold set against
		// one lucky draw, and it duly broke the first time an unrelated gene was appended and
		// Genome.random's whole sequence shifted under it (PITFALLS §12).
		for (int trial = 0; trial < 60; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			BodySdf sdf = new BodySdf(plan);

			for (ToothDef tooth : plan.teeth) {
				Vector3f root = tooth.root();
				if (sdf.eval(root.x, root.y, root.z) < 0f) rooted++;
				roots++;
			}

			// The mesher's own placement is what has to clear the flesh, and that is a property
			// the plan cannot check for itself — it does not know where the surface ended up.
			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));
			int toothVertices = plan.teeth.length * 10;
			for (int v = mesh.vertexCount - toothVertices; v < mesh.vertexCount; v++) {
				int p = v * 3;
				if (sdf.eval(mesh.positions[p], mesh.positions[p + 1], mesh.positions[p + 2]) > 0f) {
					protruding++;
				}
			}
			tipVertices += toothVertices;
		}

		assertEquals(roots, rooted,
				"tooth roots outside the gum: they will float free of the face");

		// Measured across the population rather than per creature. Half of every tooth's vertices
		// are its root ring and belong inside, so 50% is the ceiling and not the target; of the
		// tip ring, the vertices facing the gum sit below the point itself and legitimately stay
		// under it, because a tooth tilted outward is not a spike standing on end. What this
		// catches is the systematic failure — a whole row buried, or teeth that never clear at all
		// — rather than the spread between one skull's proportions and another's.
		double showing = protruding / (double) tipVertices;
		System.out.printf("[ToothTest] tooth vertices clear of the flesh: %.1f%%%n", showing * 100);
		// The converged value is around 26%. The bar sits well under it because what this is for is
		// the systematic failure — a whole row buried, which reads as a number near zero — and not
		// the few points of drift between one population of skulls and another.
		assertTrue(showing > 0.18,
				String.format("only %.1f%% of tooth vertices clear the flesh — the teeth are buried",
						showing * 100));
	}

	@Test
	void toothGeometryReachesTheBakedMesh() {
		Random random = new Random(37);
		for (int trial = 0; trial < 8; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			MeshData withTeeth = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));

			// Five sides per tooth, two rings of vertices.
			int expected = plan.teeth.length * 10;
			assertTrue(withTeeth.vertexCount > expected,
					"the baked mesh is too small to contain the tooth geometry");

			// Every buffer must still line up after the append, or the renderer walks off the end.
			assertEquals(withTeeth.vertexCount * 3, withTeeth.positions.length);
			assertEquals(withTeeth.vertexCount * 3, withTeeth.normals.length);
			assertEquals(withTeeth.vertexCount * 3, withTeeth.colors.length);
			assertEquals(withTeeth.vertexCount, withTeeth.emissive.length);
			assertEquals(withTeeth.vertexCount * SkinBinder.MAX_INFLUENCES, withTeeth.boneIndices.length);
			for (int index : withTeeth.quads) {
				assertTrue(index >= 0 && index < withTeeth.vertexCount,
						"a quad references a vertex outside the merged mesh");
			}

			// The roots are real vertices, exactly where the plan put them and unrounded by any
			// smoothing — the appended geometry is not passed through the field at all.
			for (ToothDef tooth : plan.teeth) {
				boolean found = false;
				for (int v = 0; v < withTeeth.vertexCount && !found; v++) {
					int p = v * 3;
					float dx = withTeeth.positions[p] - tooth.root().x;
					float dy = withTeeth.positions[p + 1] - tooth.root().y;
					float dz = withTeeth.positions[p + 2] - tooth.root().z;
					float reach = tooth.radius() * 1.05f;
					if (dx * dx + dy * dy + dz * dz < reach * reach) found = true;
				}
				assertTrue(found, "no mesh vertex at a tooth root — the geometry was not appended");
				break;
			}
		}
	}

	@Test
	void teethAreRigidToASingleBone() {
		BodyPlan plan = BodyPlanBuilder.build(withDiet(38, 0.9f));
		MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.MID));

		// Tooth vertices are the tail of the buffer, appended after the body.
		int toothVertices = plan.teeth.length * 10;
		for (int v = mesh.vertexCount - toothVertices; v < mesh.vertexCount; v++) {
			int b = v * SkinBinder.MAX_INFLUENCES;
			assertEquals(1f, mesh.boneWeights[b], 1e-5f,
					"a tooth is not rigidly bound — it will smear toward the skull as the jaw drops");
			for (int i = 1; i < SkinBinder.MAX_INFLUENCES; i++) {
				assertEquals(0f, mesh.boneWeights[b + i], 1e-5f, "a tooth picked up a second influence");
			}
		}
	}

	/** Sanity: the tooth normals must be unit length like every other vertex the renderer emits. */
	@Test
	void toothNormalsAreUnitLength() {
		BodyPlan plan = BodyPlanBuilder.build(withDiet(39, 0.7f));
		MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.MID));
		for (int v = 0; v < mesh.vertexCount; v++) {
			int p = v * 3;
			Vector3f n = new Vector3f(mesh.normals[p], mesh.normals[p + 1], mesh.normals[p + 2]);
			assertEquals(1f, n.length(), 1e-3f, "normal at vertex " + v + " is not unit length");
		}
	}
}
