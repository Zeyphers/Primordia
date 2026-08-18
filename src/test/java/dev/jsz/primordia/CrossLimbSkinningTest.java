package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinBinder;
import dev.jsz.primordia.sdf.BodySdf;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression cover for one vertex being skinned to two different limbs.
 * <p>
 * Reported from play as a spider's toes sticking together with a quad stretched between the legs.
 * A vertex carrying real weight from two limbs is driven by both at once and can follow neither, so
 * as the gait swings the legs apart it is dragged out into the gap between them and the faces around
 * it stretch into a sheet. Two separate limbs are separate structures — blending across them is
 * never right, at any distance, in any pose.
 * <p>
 * The rule already existed and was only half applied: {@link BoneDef#blendGroup} is what stops the
 * SDF fusing neighbouring legs into webbing, and {@code SkinBinder} was picking influences on
 * Euclidean distance alone without consulting it. The field kept the legs apart and the skinning
 * pulled them back together.
 * <p>
 * Two reasons this is checked here rather than left to {@code LimbSeparationTest} and
 * {@code SkinBindingTopologyTest}, both of which pass on creatures that show the bug:
 * <ul>
 *   <li>{@code LimbSeparationTest} asks the <i>field</i> whether there is material in the gap.
 *       There is not — the gap is real and correct. Nothing about the field is wrong.</li>
 *   <li>{@code SkinBindingTopologyTest} checks the parent-chain hop filter, which cannot separate
 *       two legs however well it works: {@code legA_0} and {@code legB_0} are both children of a
 *       spine bone, so opposite legs are two hops apart and every hop budget admits them.</li>
 * </ul>
 * So this asserts on the shipped bone weights, which is the only place the defect is visible.
 */
class CrossLimbSkinningTest {

	/** Arachnids first: the thinnest limbs the generator makes, packed the most closely. */
	private static final Archetype[] CROWDED = {
			Archetype.ARACHNID, Archetype.INSECTOID, Archetype.CRUSTACEAN, Archetype.SAURIAN};

	@Test
	void noVertexIsDrivenByTwoDifferentLimbs() {
		Random random = new Random(4242);
		int checked = 0;

		for (Archetype archetype : CROWDED) {
			for (int trial = 0; trial < 12; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));

				for (int v = 0; v < mesh.vertexCount; v++) {
					int limb = -1;
					for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
						// Vanishing weights are the falloff's tail, not an influence anyone can
						// see move; the defect is a limb with enough weight to actually drag.
						if (mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i] <= 0.10f) continue;
						int group = plan.bones[mesh.boneIndices[v * SkinBinder.MAX_INFLUENCES + i]].blendGroup;
						// The trunk is always admissible: a limb root blending into the hip it
						// grows from is the one soft join that is genuinely wanted.
						if (group == BoneDef.AXIAL) continue;
						if (limb == -1) {
							limb = group;
							continue;
						}
						assertEquals(limb, group,
								archetype + " vertex " + v + " is weighted to limbs " + limb
										+ " and " + group + " at once — it will be pulled between "
										+ "them and stretch a face across the gap");
					}
					checked++;
				}
			}
		}

		assertTrue(checked > 50_000, "not enough vertices were examined: " + checked);
	}
	/**
	 * A limb drives limbs, and nothing else.
	 * <p>
	 * The blend-group rule above reasons about the skeleton, and the fault this catches is about the
	 * surface: a frill hangs off a spine bone but sits, in bind pose, right beside a thigh. The
	 * thigh is then the nearest capsule, so the frill vertex was "owned" by it and ownership was
	 * allowed to override the group — deliberately, because a splayed foot's outer toe reads as
	 * trunk by group and welding it to the spine looked worse.
	 * <p>
	 * Measured before the surface rule, across eleven archetypes: <b>26% of every frill vertex the
	 * generator makes was driven by a leg</b>, at up to 0.98 of its weight, along with 7% of ears
	 * and a scatter of plates, horns and light organs. It is invisible standing still and shows the
	 * moment the animal walks — the ornament stretches toward the swinging limb. Every offender was
	 * a vertex the field says belongs to the body, which is what the rule keys on.
	 */
	@Test
	void noLimbDrivesOrnamentOrTheAbdomen() {
		Random random = new Random(90125);
		int checked = 0, ornament = 0;

		// Every archetype, not just the crowded ones: this fault needs ornament rather than packed
		// limbs, and it is the grazers and saurians that grow the frills it showed on.
		for (Archetype archetype : Archetype.VALUES) {
			for (int trial = 0; trial < 6; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.NEAR));
				BodySdf sdf = new BodySdf(plan);

				for (int v = 0; v < mesh.vertexCount; v++) {
					Feature feature = sdf.featureAt(mesh.positions[v * 3],
							mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]);
					// Limb surfaces are supposed to be limb-driven; this is about everything else.
					if (feature == Feature.LIMB || feature == Feature.FOOT
							|| feature == Feature.CLAWS || feature == Feature.HAND) continue;
					ornament++;

					for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
						float w = mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
						if (w <= 0.02f) continue;
						BoneDef bone = plan.bones[mesh.boneIndices[v * SkinBinder.MAX_INFLUENCES + i]];
						if (!bone.name.startsWith("leg") && !bone.name.startsWith("arm")) continue;
						fail(String.format(
								"%s: a %s vertex is %.0f%% driven by %s — it will be dragged along "
										+ "as that limb swings", archetype, feature, w * 100f, bone.name));
					}
					checked++;
				}
			}
		}

		assertTrue(ornament > 20_000, "not enough non-limb surface was examined: " + ornament);
		assertTrue(checked > 20_000, "not enough vertices were examined: " + checked);
	}
}
