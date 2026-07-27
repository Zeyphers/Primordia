package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.body.ToothDef;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.ToothMesher;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.skeleton.Skeleton;
import dev.jsz.primordia.util.MathX;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teeth must not come out through the other jaw when the mouth closes.
 * <p>
 * A tooth stopping <i>inside</i> the flesh it bites into is fine and invisible — both are opaque,
 * and the surface drawn over it hides it. One that runs the whole way through is not: that is
 * fangs standing out of the top of the skull.
 * <p>
 * Only visible in the closed pose, which is why nothing else catches it. The mesh is baked with
 * the mouth wide open, and in that pose every tooth sits harmlessly in the gap.
 * <p>
 * Note what this reads: the <b>baked mesh</b>, not a reimplementation of how teeth are placed. An
 * earlier version computed the expected tip itself and reported zero failures while creatures were
 * visibly full of teeth through the skull — because the mesher had a floor under its clamp that
 * the test did not model, so the two were measuring different geometry. A test that reconstructs
 * what the code should have done cannot catch the code not doing it.
 */
class ToothClippingTest {

	/** Vertices per tooth: five sides, two rings. */
	private static final int VERTICES_PER_TOOTH = 10;

	/** Tooth vertices that end up through the jaw they close against, over a population. */
	private static int[] countThrough(Supplier<Genome> genomes, int trials) {
		int through = 0;
		int total = 0;

		for (int trial = 0; trial < trials; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(genomes.get());
			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));

			// Which teeth actually got built, and in what order. Teeth with no room are skipped,
			// so vertex position in the buffer is not a reliable index into the plan's list.
			int[] emitted = ToothMesher.build(plan, new BodySdf(plan)).emitted();
			int toothVertices = emitted.length * VERTICES_PER_TOOTH;
			if (toothVertices == 0 || mesh.vertexCount < toothVertices) continue;
			int first = mesh.vertexCount - toothVertices;

			// The resting pose: mouth shut. This is where the two rows meet.
			Skeleton skeleton = new Skeleton(plan);
			skeleton.resetPose();
			skeleton.setLocalRotation(plan.jawBone,
					new Quaternionf().rotateX(-plan.tightestJawClosure()));
			skeleton.updateWorld();
			skeleton.updateSkinMatrices();

			for (int v = first; v < mesh.vertexCount; v++) {
				ToothDef tooth = plan.teeth[emitted[(v - first) / VERTICES_PER_TOOTH]];
				boolean lower = tooth.bone() == plan.jawBone;
				BoneDef opposing = plan.bones[lower ? plan.headBone : plan.jawBone];

				int p = v * 3;
				Vector3f point = new Vector3f(
						mesh.positions[p], mesh.positions[p + 1], mesh.positions[p + 2]);
				skeleton.skinMatrix(tooth.bone()).transformPosition(point);

				float t = MathX.projectOntoSegment(point.x, point.y, point.z,
						opposing.head.x, opposing.head.y, opposing.head.z,
						opposing.tail.x, opposing.tail.y, opposing.tail.z);
				Vector3f on = new Vector3f(opposing.head).lerp(opposing.tail, t);
				float outside = point.distance(on)
						- MathX.lerp(opposing.radiusHead, opposing.radiusTail, t);

				// Both jaws are more than their capsules — a skull carries a cranium and a muzzle,
				// a mandible its ramus and chin — and a tooth buried in any of that is still
				// hidden. Checking the capsule alone condemns teeth that are perfectly covered.
				int opposingBone = lower ? plan.headBone : plan.jawBone;
				for (SdfBlob blob : plan.blobs) {
					if (blob.bone() != opposingBone) continue;
					outside = Math.min(outside, ellipsoidDistance(point, blob));
				}

				// Outside all of the opposing jaw's flesh *and* past its axis: it went through.
				boolean pastAxis = lower ? point.y > on.y : point.y < on.y;
				total++;
				if (outside > 0f && pastAxis) through++;
			}
		}
		return new int[]{through, total};
	}

	/** Signed distance to a blob's ellipsoid, matching how the field itself approximates one. */
	private static float ellipsoidDistance(Vector3f point, SdfBlob blob) {
		float dx = (point.x - blob.center().x) / blob.radii().x;
		float dy = (point.y - blob.center().y) / blob.radii().y;
		float dz = (point.z - blob.center().z) / blob.radii().z;
		float k = Math.min(blob.radii().x, Math.min(blob.radii().y, blob.radii().z));
		return ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - 1f) * k;
	}

	@Test
	void noToothEmergesThroughTheOppositeJawWhenTheMouthCloses() {
		Random random = new Random(77);
		for (float diet : new float[]{0.9f, 0.5f, 0.1f}) {
			int[] r = countThrough(() -> Genome.random(random).with(Gene.DIET, diet), 20);
			assertEquals(0, r[0], String.format(
					"diet %.1f: %d of %d tooth vertices come through the opposite jaw",
					diet, r[0], r[1]));
		}
	}

	/**
	 * And for every founder body plan, not just uniform draws.
	 * <p>
	 * Uniform genomes were the only population this covered at first, and saurians — small skulls
	 * carrying hugely elongated jaws — were riddled with teeth through the head the whole time. An
	 * archetype constrains exactly the proportions that decide whether a tooth fits, so a
	 * population of uniform draws is precisely the one that fails to exercise them.
	 */
	@Test
	void noArchetypeGrowsTeethThroughItsOwnSkull() {
		Random random = new Random(78);
		for (Archetype archetype : Archetype.VALUES) {
			int[] r = countThrough(() -> archetype.create(random), 15);
			assertEquals(0, r[0], archetype + ": " + r[0] + " of " + r[1]
					+ " tooth vertices come through the opposite jaw with the mouth shut");
		}
	}

	/**
	 * The guard shortens teeth; it must not be quietly deleting them.
	 * <p>
	 * Necessary because the two failures trade off directly: a guard that drops every tooth it
	 * cannot fit passes the clipping test perfectly and leaves the creatures toothless. An earlier
	 * version of the ceiling did exactly that to saurians — 94% of their teeth removed — while
	 * every other test stayed green.
	 */
	@Test
	void theGuardDoesNotQuietlyRemoveTheTeeth() {
		Random random = new Random(79);
		for (Archetype archetype : Archetype.VALUES) {
			int planned = 0;
			int emitted = 0;
			for (int trial = 0; trial < 15; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				planned += plan.teeth.length;
				emitted += ToothMesher.build(plan, new BodySdf(plan)).emitted().length;
			}
			// Three quarters, not all. A minority of teeth emerge from a gum that is already past
			// the opposing jaw once the mouth shuts — usually where a deep muzzle meets a slight
			// mandible — and those have nowhere to go at any length. A gap in a row reads as an
			// old animal; a tooth through the skull reads as a bug.
			assertTrue(emitted >= planned * 0.72,
					archetype + ": only " + emitted + " of " + planned + " teeth survived the "
							+ "clipping guard — it is deleting them rather than shortening them");
		}
	}
}
