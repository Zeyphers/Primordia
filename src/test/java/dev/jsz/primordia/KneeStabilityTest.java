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

	/**
	 * A four-legged animal gets two bones per leg, whatever {@code LEG_SEGMENTS} rolled.
	 * <p>
	 * A third joint only reads as an ankle with enough legs around it to sell an arthropod. On four
	 * it has no such context and no strong pole to commit it, so the solver folds it whichever way
	 * the step happens to favour and the ankle visibly snaps in or out mid-stride. The gene is left
	 * free to roll — it still means something on every other leg count — and the constraint is
	 * applied where the leg is built.
	 */
	@Test
	void quadrupedsNeverGrowAThirdLegSegment() {
		Random random = new Random(20260819);
		int checked = 0;

		for (int trial = 0; trial < 400 && checked < 60; trial++) {
			// Pinned to the top of the range, so a quadruped that could take a third segment does.
			Genome genome = Genome.random(random).with(Gene.LEG_SEGMENTS, 1f);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			if (plan.legs.length != 4) continue;
			checked++;
			for (LimbChain leg : plan.legs) {
				assertEquals(2, leg.bones.length,
						"a quadruped grew a " + leg.bones.length + "-bone leg — the extra ankle pops");
			}
		}
		assertTrue(checked > 0, "no quadrupeds were generated to check");
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
	 * On any creature with more than one pair of legs, every knee bends the way its own foot fans.
	 * <p>
	 * This started as a rule for hexapods, which have no forelimbs and hindlimbs to apply "elbow
	 * back, knee forward" to: the middle pair of an odd-numbered set has no fan at all, and it was
	 * measured bowing backward at -0.77 while the pair in front of it bowed forward at +0.92.
	 * <p>
	 * It now covers quadrupeds too. The opposed convention is anatomically true of the joints
	 * inside a quadruped's body wall and wrong for the joints anyone sees, and drawn that way it
	 * aimed the front and hind knees at each other until the lower legs crossed mid-stride. A leg
	 * whose foot does not fan bends straight out to the side rather than committing either way.
	 */
	@Test
	void kneesRadiateAwayFromTheMiddleOfTheBody() {
		StringBuilder wrong = new StringBuilder();
		for (dev.jsz.primordia.genome.Archetype archetype
				: dev.jsz.primordia.genome.Archetype.VALUES) {
			for (int seed = 0; seed < 12; seed++) {
				Random random = new Random(4242L + seed * 7919L + archetype.ordinal());
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
				int pairs = plan.legs.length / 2;
				if (pairs < 2) continue;
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

	/**
	 * A biped stands on its hindlimbs, and its knees face forward.
	 * <p>
	 * Its one pair used to be classified by pair index as forelimbs, which bent the knee backward
	 * and, on a three-bone leg, put the hock in front of it — the reverse of every real biped.
	 */
	@Test
	void bipedKneesFaceForward() {
		int checked = 0;
		for (int seed = 0; seed < 40; seed++) {
			BodyPlan plan = BodyPlanBuilder.build(
					dev.jsz.primordia.genome.Archetype.BIPED.create(new Random(9100L + seed)));
			if (plan.legs.length != 2) continue;
			for (LimbChain leg : plan.legs) {
				checked++;
				assertTrue(leg.poleDirection.z > 0.5f,
						"seed " + seed + ": a biped knee bends " + leg.poleDirection.z + " fore/aft");
			}
		}
		assertTrue(checked > 0, "no bipeds were generated to check");
	}

	/**
	 * Walked, not posed: a quadruped's legs on one side do not pass through each other.
	 * <p>
	 * The bind pose was never where this showed. Standing still, front and hind legs are well
	 * apart; it is the end of a stride, the front foot trailing back as the hind foot reaches
	 * forward, where they met. Measured over a flat walk before the fix, same-side legs overlapped
	 * on 60% of a saurian's frames. Two things closed it: knees that point away from each other,
	 * and a stride that cannot carry one foot into the next.
	 */
	@Test
	void quadrupedLegsDoNotCrossMidStride() {
		StringBuilder wrong = new StringBuilder();
		for (dev.jsz.primordia.genome.Archetype archetype : new dev.jsz.primordia.genome.Archetype[]{
				dev.jsz.primordia.genome.Archetype.SAURIAN, dev.jsz.primordia.genome.Archetype.GRAZER,
				dev.jsz.primordia.genome.Archetype.PACK_HUNTER, dev.jsz.primordia.genome.Archetype.SPRINTER}) {
			float sum = 0f;
			int n = 12;
			for (int k = 0; k < n; k++) {
				BodyPlan plan = BodyPlanBuilder.build(archetype.create(new Random(5150L + k * 7919L)));
				sum += dev.jsz.primordia.anim.KneeSideProbe.measure(plan, 1.6f, 3f)[3];
			}
			float mean = sum / n;
			if (mean > 0.15f) {
				wrong.append(String.format("%s: same-side legs overlap on %.0f%% of frames%n",
						archetype, mean * 100f));
			}
		}
		assertTrue(wrong.isEmpty(), "legs passing through each other mid-stride:" + nl2() + wrong);
	}

	/** And a biped's knees never fold in under its own body while it walks. */
	@Test
	void bipedKneesStayOutsideTheHips() {
		for (int k = 0; k < 12; k++) {
			BodyPlan plan = BodyPlanBuilder.build(
					dev.jsz.primordia.genome.Archetype.BIPED.create(new Random(6160L + k * 7919L)));
			float[] m = dev.jsz.primordia.anim.KneeSideProbe.measure(plan, 1.6f, 3f);
			assertTrue(m[1] < 0.02f, "specimen " + k + ": knees tucked inside the hips on "
					+ m[1] * 100f + "% of joint-frames");
			assertEquals(0f, m[2], 1e-6f, "specimen " + k + ": the left and right legs collided");
		}
	}

	private static String nl2() {
		return System.lineSeparator();
	}
}
