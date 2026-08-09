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

	/**
	 * Smallest gap between the capsule surfaces of two limbs. Negative means they physically
	 * interpenetrate. Sampled along one limb and projected onto the other, which is coarse but
	 * more than enough to catch gross intersection.
	 */
	private static float limbGap(BodyPlan plan, LimbChain a, LimbChain b) {
		float best = Float.MAX_VALUE;
		for (int ia : a.bones) {
			for (int ib : b.bones) {
				BoneDef x = plan.bones[ia];
				BoneDef y = plan.bones[ib];
				for (int i = 0; i <= 8; i++) {
					float tx = i / 8f;
					float px = x.head.x + (x.tail.x - x.head.x) * tx;
					float py = x.head.y + (x.tail.y - x.head.y) * tx;
					float pz = x.head.z + (x.tail.z - x.head.z) * tx;
					float rx = x.radiusHead + (x.radiusTail - x.radiusHead) * tx;

					float ty = dev.jsz.primordia.util.MathX.projectOntoSegment(px, py, pz,
							y.head.x, y.head.y, y.head.z, y.tail.x, y.tail.y, y.tail.z);
					float qx = y.head.x + (y.tail.x - y.head.x) * ty;
					float qy = y.head.y + (y.tail.y - y.head.y) * ty;
					float qz = y.head.z + (y.tail.z - y.head.z) * ty;
					float ry = y.radiusHead + (y.radiusTail - y.radiusHead) * ty;

					float dx = px - qx, dy = py - qy, dz = pz - qz;
					best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - rx - ry);
				}
			}
		}
		return best;
	}

	/**
	 * The failure mode underneath all of the above: limbs that physically intersect.
	 * <p>
	 * Blend groups stop two <i>nearby</i> limbs being smoothed into one another, and the sampling
	 * resolution floor stops a thin limb falling between samples — but neither can do anything
	 * about two capsules that genuinely overlap, because the union of two overlapping solids is
	 * one solid. Clustered, many-legged creatures used to be built that way by construction: an
	 * arachnid carried 55 mm-radius legs on hips 33 mm apart, and every same-side pair on every
	 * creature intersected. In game the legs looked welded into a single sheet.
	 */
	@Test
	void limbsDoNotPhysicallyIntersectEachOther() {
		Random random = new Random(9090);
		// A shared hip joint is not webbing, so allow a hair of overlap where limbs meet the
		// body. Anything past this is two legs passing through each other out in open air.
		for (Archetype archetype : Archetype.VALUES) {
			int intersecting = 0;
			int pairs = 0;
			float worst = Float.MAX_VALUE;

			for (int trial = 0; trial < 20; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				float tolerance = plan.blendRadius * 1.5f;

				for (int a = 0; a < plan.legs.length; a++) {
					for (int b = a + 1; b < plan.legs.length; b++) {
						if (plan.legs[a].side != plan.legs[b].side) continue;
						pairs++;
						float gap = limbGap(plan, plan.legs[a], plan.legs[b]);
						worst = Math.min(worst, gap);
						if (gap < -tolerance) intersecting++;
					}
				}
			}
			// A handful of near-touching hips is tolerable; a body plan where it is the norm is
			// the regression this guards.
			assertTrue(intersecting <= pairs * 0.1,
					archetype + ": " + intersecting + " of " + pairs
							+ " same-side leg pairs interpenetrate (worst gap " + worst
							+ ") — the legs will mesh as one webbed sheet");
		}
	}

	/**
	 * Only bipeds have arms.
	 * <p>
	 * An animal walking on four or more legs has no forelimbs free to be anything else, and the rule
	 * doubles as the fix for a geometry bug: arms attached at a fixed {@code u = 0.92} along the
	 * spine while the front-most hip of a multi-legged creature is pinned at
	 * {@code FOREMOST_LEG_U = 0.88}. Four hundredths of a torso apart, with nothing reconciling the
	 * two constants against the thickness of the limbs hanging off them — so on a short creature,
	 * where four hundredths of a torso is a couple of centimetres and limb radii are floored in
	 * absolute terms, the shoulder was inside the hip by construction. A second arm pair sat at
	 * {@code 0.76}, inside the hip span outright.
	 */
	@Test
	void onlyBipedsGrowArms() {
		Random random = new Random(8812);
		for (Archetype archetype : Archetype.VALUES) {
			for (int trial = 0; trial < 200; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				if (plan.legs.length > 2) {
					assertEquals(0, plan.arms.length,
							archetype + ": a creature with " + plan.legs.length
									+ " legs grew arms — they have nowhere to attach that is not a hip");
				}
			}
		}
	}

	/**
	 * And the arms bipeds do grow stay clear of their legs.
	 * <p>
	 * The sibling test above only ever compared legs against legs, which left arm-against-leg
	 * uncovered entirely. Kept as cover for the remaining case now that {@link #onlyBipedsGrowArms}
	 * has removed the impossible one.
	 */
	@Test
	void armsDoNotIntersectTheLegs() {
		Random random = new Random(4471);
		for (Archetype archetype : Archetype.VALUES) {
			int intersecting = 0;
			int pairs = 0;
			float worst = Float.MAX_VALUE;
			String culprit = "";

			// A large sample deliberately. Arms are uncommon and most archetypes rarely grow them,
			// so a few dozen trials can yield a handful of arm/leg pairs — and a 5% tolerance
			// against a denominator of twelve is not a measurement, it is a coin toss that reports
			// a different answer every time an unrelated change shifts the random sequence.
			for (int trial = 0; trial < 400; trial++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				if (plan.arms.length == 0) continue;
				float tolerance = plan.blendRadius * 1.5f;

				for (LimbChain arm : plan.arms) {
					for (LimbChain leg : plan.legs) {
						// Opposite sides of the body cannot reach each other.
						if (arm.side != leg.side) continue;
						pairs++;
						float gap = limbGap(plan, arm, leg);
						if (gap < worst) {
							worst = gap;
							culprit = String.format(
									"%d legs, hip height %.2f, torso %.2f, blend %.3f",
									plan.legs.length, plan.hipHeight, plan.bodyLength, plan.blendRadius);
						}
						if (gap < -tolerance) intersecting++;
					}
				}
			}

			// The same 10% the leg-against-leg test allows, and for the same reason: limbs meeting
			// the body at a shared joint graze by a hair, and demanding zero would be a test of the
			// random draw rather than of the body plan. What remains at this bar on a biped is a
			// hand passing near a large forward-pointing foot on a short, deep-chested animal —
			// long-standing, unrelated to the arm/leg reconciliation, and worth its own look.
			assertTrue(intersecting <= pairs * 0.1,
					archetype + ": " + intersecting + " of " + pairs
							+ " same-side arm/leg pairs interpenetrate (worst gap " + worst
							+ " on " + culprit + ") — the arms are buried in the legs");
		}
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
