package dev.jsz.primordia;

import dev.jsz.primordia.anim.Fabrik;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression cover for knees flipping on three-segment limbs.
 * <p>
 * A pole vector pins exactly one joint. That fully determines a two-bone limb, which is why those
 * always animated cleanly, but a three-bone limb has a second interior joint left free to rotate
 * around the hip-to-foot axis — and it would swap sides between frames as the target moved, which
 * reads as the knee popping inside out. A pole also cannot describe a digitigrade leg at all,
 * whose knee and hock bend in deliberately opposite directions.
 * <p>
 * These tests sweep a target the way a walk cycle does and assert the limb keeps the configuration
 * it was grown with, every frame.
 */
class KneeStabilityTest {

	private static float[] lengths(int n, float length) {
		float[] out = new float[n];
		java.util.Arrays.fill(out, length);
		return out;
	}

	private static Vector3f[] chain(int n, float length) {
		Vector3f[] joints = new Vector3f[n + 1];
		for (int i = 0; i <= n; i++) joints[i] = new Vector3f(0f, -length * i, 0f);
		return joints;
	}

	/** Which side of the hip-to-target axis a joint sits on, measured along the pole. */
	private static float sideOf(Vector3f[] joints, int index, Vector3f target, Vector3f pole) {
		Vector3f axis = new Vector3f(target).sub(joints[0]);
		if (axis.lengthSquared() < 1e-10f) return 0f;
		axis.normalize();
		Vector3f perp = new Vector3f(pole).fma(-pole.dot(axis), axis);
		if (perp.lengthSquared() < 1e-8f) return 0f;
		perp.normalize();
		return new Vector3f(joints[index]).sub(joints[0]).dot(perp);
	}

	@Test
	void threeSegmentLimbKeepsItsBendDirectionsThroughAStride() {
		Fabrik solver = new Fabrik();
		float bone = 0.4f;
		Vector3f pole = new Vector3f(0f, 0f, 1f);
		// Digitigrade: knee forward, hock back. A single pole cannot express this.
		float[] bendSigns = {1f, -1f};

		Vector3f[] joints = chain(3, bone);

		// Sweep the foot fore-and-aft the way a stride does, at fine steps.
		for (int step = 0; step <= 120; step++) {
			float t = step / 120f;
			Vector3f target = new Vector3f(
					0.05f * (float) Math.sin(t * Math.PI * 4),
					-0.95f - 0.1f * (float) Math.sin(t * Math.PI * 2),
					-0.35f + 0.7f * t);

			solver.solve(joints, lengths(3, bone), 3, target, pole, bendSigns, 12);

			assertTrue(sideOf(joints, 1, target, pole) >= -1e-3f,
					"knee flipped behind the axis at step " + step);
			assertTrue(sideOf(joints, 2, target, pole) <= 1e-3f,
					"hock flipped in front of the axis at step " + step);
		}
	}

	@Test
	void jointsNeverJumpBetweenAdjacentFrames() {
		Fabrik solver = new Fabrik();
		float bone = 0.4f;
		Vector3f pole = new Vector3f(0f, 0f, 1f);
		float[] bendSigns = {1f, -1f};
		Vector3f[] joints = chain(3, bone);

		Vector3f[] previous = null;
		float worst = 0f;
		int worstStep = -1;

		for (int step = 0; step <= 200; step++) {
			float t = step / 200f;
			Vector3f target = new Vector3f(
					0.1f * (float) Math.sin(t * Math.PI * 6),
					-0.9f - 0.15f * (float) Math.sin(t * Math.PI * 3),
					-0.4f + 0.8f * t);

			solver.solve(joints, lengths(3, bone), 3, target, pole, bendSigns, 12);

			if (previous != null && step > 1) {
				for (int j = 1; j < joints.length; j++) {
					float moved = joints[j].distance(previous[j]);
					if (moved > worst) {
						worst = moved;
						worstStep = step;
					}
				}
			}
			previous = new Vector3f[joints.length];
			for (int j = 0; j < joints.length; j++) previous[j] = new Vector3f(joints[j]);
		}

		// The target moves ~0.02 per step; a joint moving far more than that is a pop, not motion.
		assertTrue(worst < 0.12f,
				"a joint jumped " + worst + " in one frame at step " + worstStep + " — that is a visible pop");
	}

	@Test
	void generatedThreeSegmentLegsAllCarryBendSigns() {
		Random random = new Random(7777);
		int checked = 0;
		for (int trial = 0; trial < 300 && checked < 40; trial++) {
			Genome genome = Genome.random(random).with(Gene.LEG_SEGMENTS, 1f);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			for (LimbChain leg : plan.legs) {
				if (leg.bones.length < 3) continue;
				checked++;
				assertEquals(leg.bones.length - 1, leg.bendSigns.length,
						"a three-bone leg must record a bend sign per interior joint");
				for (float sign : leg.bendSigns) {
					assertTrue(sign == 1f || sign == -1f,
							"bend sign must be decisive, was " + sign);
				}
			}
		}
		assertTrue(checked > 0, "no three-segment legs were generated to check");
	}

	@Test
	void twoSegmentLimbsStillHonourTheirPole() {
		// The previously-working case must not regress now that the constraint path has changed.
		Fabrik solver = new Fabrik();
		float bone = 0.5f;
		// Comfortably inside the chain's 1.0 total reach: an out-of-range target is straightened
		// deliberately, and a straight chain has no bend for a pole to have an opinion about.
		Vector3f target = new Vector3f(0f, -0.8f, 0f);

		Vector3f[] forward = chain(2, bone);
		solver.solve(forward, lengths(2, bone), 2, target, new Vector3f(0f, 0f, 1f), new float[]{1f}, 16);
		assertTrue(forward[1].z > 0f, "two-bone knee ignored a +Z pole");

		Vector3f[] backward = chain(2, bone);
		solver.solve(backward, lengths(2, bone), 2, target, new Vector3f(0f, 0f, -1f), new float[]{1f}, 16);
		assertTrue(backward[1].z < 0f, "two-bone knee ignored a -Z pole");
	}

	/**
	 * On a creature with more than two pairs of legs, every knee bends the way its own foot fans.
	 * <p>
	 * "Elbow forward, knee back" is a fact about quadrupeds. A hexapod has no forelimbs and
	 * hindlimbs, just legs, and applying the opposed convention to it bows the middle pairs toward
	 * each other. Worse, the middle pair of an odd-numbered set has no fan at all, so whatever
	 * fraction of the quadruped rule survives becomes the <i>only</i> term deciding its bend: it was
	 * measured bowing backward at -0.77 while the pair in front of it bowed forward at +0.92, with
	 * its own foot planted square out to the side. Neighbouring legs bending opposite ways, which is
	 * what "some legs look different from the others" turns out to mean.
	 * <p>
	 * The rule is that the knee radiates with the foot, and a leg whose foot does not fan bends
	 * straight out to the side rather than picking a direction from a convention that does not
	 * apply to it.
	 */
	@Test
	void manyLeggedCreaturesRadiateTheirKnees() {
		StringBuilder wrong = new StringBuilder();
		for (dev.jsz.primordia.genome.Archetype archetype
				: dev.jsz.primordia.genome.Archetype.VALUES) {
			for (int seed = 0; seed < 12; seed++) {
				Random random = new Random(4242L + seed * 7919L + archetype.ordinal());
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				int pairs = plan.legs.length / 2;
				if (pairs <= 2) continue;
				for (LimbChain leg : plan.legs) {
					float fan = leg.restEffector.z - leg.origin.z;
					float poleZ = new Vector3f(leg.poleDirection).normalize().z;
					// A foot barely fanned has no direction to radiate along, and the knee should be
					// out to the side rather than committed either way.
					if (Math.abs(fan) < leg.totalLength * 0.05f) {
						if (Math.abs(poleZ) > 0.5f) {
							wrong.append(String.format(
									"%s seed %d: a leg with no fan bends %.2f fore/aft%n",
									archetype, seed, poleZ));
						}
					} else if (fan * poleZ < 0f) {
						wrong.append(String.format(
								"%s seed %d: foot fans %.3f but the knee bends %.2f, the other way%n",
								archetype, seed, fan, poleZ));
					}
				}
			}
		}
		assertTrue(wrong.isEmpty(), "knees bending against their own foot fan:" + nl2() + wrong);
	}

	private static String nl2() {
		return System.lineSeparator();
	}
}
