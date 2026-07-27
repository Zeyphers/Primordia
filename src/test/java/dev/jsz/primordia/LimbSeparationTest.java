package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.sdf.BodySdf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression cover for limbs fusing into webbing.
 * <p>
 * The SDF smooth-union blends anything within its radius, which is exactly what makes a leg appear
 * to grow out of a hip. But it has no notion of adjacency, so on a six- or eight-legged creature
 * neighbouring legs sit inside each other's blend radius and the union merges them into a
 * continuous sheet. The fix groups parts per limb, blending within a limb and into the trunk while
 * keeping separate limbs strictly apart — these tests check the gaps really are gaps.
 */
class LimbSeparationTest {

	private static Vector3f midpointOf(BodyPlan plan, LimbChain leg) {
		BoneDef bone = plan.bones[leg.bones[leg.bones.length - 1]];
		return new Vector3f(bone.head).lerp(bone.tail, 0.5f);
	}

	private static float radiusOf(BodyPlan plan, LimbChain leg) {
		return plan.bones[leg.bones[leg.bones.length - 1]].maxRadius();
	}

	/**
	 * Distance from a point to the nearest surface of any bone capsule, ignoring blending. A large
	 * positive value means the point is genuinely in open air, so any material the SDF reports
	 * there can only have come from parts fusing across a gap.
	 */
	private static float surfaceClearance(BodyPlan plan, Vector3f point) {
		float nearest = Float.MAX_VALUE;
		for (BoneDef bone : plan.bones) {
			if (!bone.emitsGeometry) continue;
			float t = dev.jsz.primordia.util.MathX.projectOntoSegment(point.x, point.y, point.z,
					bone.head.x, bone.head.y, bone.head.z,
					bone.tail.x, bone.tail.y, bone.tail.z);
			float ax = bone.head.x + (bone.tail.x - bone.head.x) * t;
			float ay = bone.head.y + (bone.tail.y - bone.head.y) * t;
			float az = bone.head.z + (bone.tail.z - bone.head.z) * t;
			float dx = point.x - ax, dy = point.y - ay, dz = point.z - az;
			float radius = bone.radiusHead + (bone.radiusTail - bone.radiusHead) * t;
			nearest = Math.min(nearest, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);
		}
		for (var blob : plan.blobs) {
			if (blob.subtract()) continue;
			nearest = Math.min(nearest, point.distance(blob.center()) - blob.maxRadius());
		}
		return nearest;
	}

	@Test
	void adjacentLegsDoNotFuseIntoWebbing() {
		Random random = new Random(2323);
		int checkedPairs = 0;

		for (int trial = 0; trial < 30; trial++) {
			// Insectoids are the worst case: many legs, closely spaced.
			BodyPlan plan = BodyPlanBuilder.build(Archetype.INSECTOID.create(random));
			BodySdf sdf = new BodySdf(plan);

			for (int a = 0; a < plan.legs.length; a++) {
				for (int b = a + 1; b < plan.legs.length; b++) {
					LimbChain legA = plan.legs[a];
					LimbChain legB = plan.legs[b];

					// Same side only. A left/right pair has its midpoint directly under the belly,
					// where solid material is the torso doing its job rather than webbing.
					if (legA.side != legB.side) continue;

					Vector3f pa = midpointOf(plan, legA);
					Vector3f pb = midpointOf(plan, legB);
					float gap = pa.distance(pb);
					Vector3f between = new Vector3f(pa).lerp(pb, 0.5f);

					// Establish independently that this point really is in open space, by measuring
					// it against every piece of geometry the creature has. Picking points
					// geometrically — "between two legs" — is not enough: on an eight-legged animal
					// a third leg often sits squarely in the gap, and its surface there is correct.
					float clearance = surfaceClearance(plan, between);
					if (clearance < plan.blendRadius * 2.5f) continue;

					checkedPairs++;
					if (sdf.eval(between.x, between.y, between.z) <= 0f) {
						System.out.println("FAILURE: eval=" + sdf.eval(between.x, between.y, between.z) + " clearance=" + clearance + " blendRadius=" + plan.blendRadius);
					}
					assertTrue(sdf.eval(between.x, between.y, between.z) > 0f,
							"solid material " + clearance + " away from any body part, between legs "
									+ gap + " apart — the limbs have webbed together");
				}
			}
		}
		assertTrue(checkedPairs >= 2, "not enough separated leg pairs were examined: " + checkedPairs);
	}

	@Test
	void everyLimbGetsItsOwnBlendGroup() {
		Random random = new Random(2324);
		for (int trial = 0; trial < 50; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.randomStructured(random).create(random));

			Set<Integer> limbGroups = new HashSet<>();
			for (LimbChain leg : plan.legs) {
				int group = plan.bones[leg.bones[0]].blendGroup;
				assertNotEquals(BoneDef.AXIAL, group, "a leg was left in the trunk's blend group");
				assertTrue(limbGroups.add(group), "two limbs share blend group " + group
						+ " — they will fuse to each other");
			}
			for (LimbChain arm : plan.arms) {
				int group = plan.bones[arm.bones[0]].blendGroup;
				assertNotEquals(BoneDef.AXIAL, group, "an arm was left in the trunk's blend group");
				assertTrue(limbGroups.add(group), "two limbs share blend group " + group);
			}
		}
	}

	@Test
	void limbsStillJoinTheBodyRatherThanFloating() {
		// The other failure mode: over-separating and leaving a visible seam at the hip.
		Random random = new Random(2325);
		for (int trial = 0; trial < 25; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.randomStructured(random).create(random));
			BodySdf sdf = new BodySdf(plan);

			for (LimbChain leg : plan.legs) {
				// Just inside the hip joint, where limb and torso meet.
				Vector3f hip = new Vector3f(leg.origin);
				assertTrue(sdf.eval(hip.x, hip.y, hip.z) < 0f,
						"the hip joint is hollow — the limb is not connected to the body");
			}
		}
	}

	@Test
	void bonesWithinOneLimbShareAGroupSoTheyStillBlend() {
		Random random = new Random(2326);
		for (int trial = 0; trial < 50; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.randomStructured(random).create(random));
			for (LimbChain leg : plan.legs) {
				int expected = plan.bones[leg.bones[0]].blendGroup;
				for (int bone : leg.bones) {
					assertEquals(expected, plan.bones[bone].blendGroup,
							"segments of one leg landed in different blend groups — the knee will crease");
				}
			}
		}
	}
}
