package dev.jsz.primordia;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.sdf.BodySdf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the hinged jaw.
 * <p>
 * Two things here are easy to get silently backwards. The rotation <b>sign</b>: a jaw that swings
 * up into the skull is a one-character mistake and looks, from most angles, merely odd rather
 * than obviously wrong. And the bind-pose <b>gap</b>: the mesh is polygonised once and skinned
 * forever after, so if the mandible is baked fused to the cranium the mouth can never open, and
 * nothing about the body plan would look wrong when it happens.
 */
class JawTest {

	private static AnimationContext frame(float time, CreatureActivity activity, float progress) {
		AnimationContext ctx = new AnimationContext();
		ctx.time = time;
		ctx.speed = 0f;
		ctx.tier = LodTier.NEAR;
		ctx.activity = activity;
		ctx.activityProgress = progress;
		return ctx;
	}

	@Test
	void everyCreatureHasAJawHingedToItsSkull() {
		Random random = new Random(1212);
		for (int trial = 0; trial < 200; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));

			assertTrue(plan.jawBone > 0 && plan.jawBone < plan.bones.length,
					"jaw bone index out of range");
			BoneDef jaw = plan.bones[plan.jawBone];
			assertEquals(plan.headBone, jaw.parent, "the jaw must hinge from the skull");
			assertTrue(jaw.emitsGeometry, "a jaw that emits nothing is an invisible mouth");
			assertTrue(jaw.length() > 0f, "degenerate jaw bone");
			// Shared with the cranium, the smooth union fairs them into one mass and the mouth
			// line vanishes.
			assertNotEquals(plan.bones[plan.headBone].blendGroup, jaw.blendGroup,
					"jaw shares the skull's blend group — it will be welded shut");
		}
	}

	@Test
	void theJawIsBakedAjarRatherThanFused() {
		Random random = new Random(1213);
		for (int trial = 0; trial < 40; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			BoneDef jaw = plan.bones[plan.jawBone];
			BodySdf sdf = new BodySdf(plan);

			// Midway between the jaw's own axis and the muzzle above it there has to be open air,
			// or the two were baked as one solid and the mouth cannot part.
			Vector3f chin = new Vector3f(jaw.head).lerp(jaw.tail, 0.75f);
			Vector3f above = new Vector3f(chin).add(0f, jaw.radiusTail * 2.2f, 0f);
			assertTrue(sdf.eval(above.x, above.y, above.z) > 0f,
					"no gap above the jaw at trial " + trial + " — mouth is fused shut");
		}
	}

	/**
	 * How far the jaw is swung off the skull's axis, and which way.
	 * <p>
	 * In the head bone's local frame +Y runs along the skull from base to snout, so the component
	 * of the jaw's direction <i>perpendicular</i> to it is the whole of the hinge angle: its
	 * length is how far open the mouth is, and its direction is which way the jaw swung. Both
	 * matter, and the second is what catches a reversed rotation — a jaw closing up into the
	 * braincase is off by one character and, from most camera angles, merely looks odd.
	 */
	private static Vector3f hingeOffAxis(BodyPlan plan, CreatureActivity activity, float progress) {
		CreatureAnimator animator = new CreatureAnimator(plan);
		for (int i = 0; i < 60; i++) {
			animator.update(frame(i / 20f, activity, progress));
		}
		Vector3f head = animator.skeleton().boneHead(plan.jawBone, new Vector3f());
		Vector3f tail = animator.skeleton().boneTail(plan.jawBone, new Vector3f());
		Vector3f direction = tail.sub(head).normalize();

		new org.joml.Matrix4f(animator.skeleton().worldMatrix(plan.headBone))
				.invert()
				.transformDirection(direction);
		// Strip the along-the-skull component; what is left is the swing.
		return new Vector3f(direction.x, 0f, direction.z);
	}

	@Test
	void openingTheJawSwingsTheChinAwayFromTheSkull() {
		Random random = new Random(1214);
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));

			Vector3f rest = hingeOffAxis(plan, CreatureActivity.RAM, 1.0f);
			Vector3f open = hingeOffAxis(plan, CreatureActivity.BITE, 0.40f);

			// Same side as the bind pose already leans: the jaw is baked slightly ajar downward,
			// and opening has to continue in that direction rather than reverse through the skull.
			assertTrue(open.dot(rest) > 0f,
					archetype + ": the jaw swings back through the skull when it opens — "
							+ "rest " + rest + " against open " + open);
			assertTrue(open.length() > rest.length() * 1.5f,
					archetype + ": the jaw barely moved — off-axis swing went from "
							+ rest.length() + " to " + open.length());
		}
	}

	@Test
	void aBitingCreatureClosesItsMouthOnTheStrike() {
		BodyPlan plan = BodyPlanBuilder.build(Archetype.APEX.create(new Random(1215)));
		// The strike lands around halfway through; the jaw must be shutting by then, not opening.
		float gape = hingeOffAxis(plan, CreatureActivity.BITE, 0.40f).length();
		float impact = hingeOffAxis(plan, CreatureActivity.BITE, 0.90f).length();
		assertTrue(impact < gape,
				"the jaw is still open at the end of the bite: " + impact + " against a gape of " + gape);
	}
}
