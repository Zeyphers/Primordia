package dev.jsz.primordia;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the graze pose to the direction the creature is actually looking.
 * <p>
 * The behavioural layer used to hold the head at a flat 1.05 radians of downward pitch for the
 * whole of GRAZE. That is correct for cropping grass underfoot and wrong for every other thing the
 * goal can target: a creature browsing leaves off a branch overhead drove its head at the ground
 * regardless, because the constant swamped the look direction and the clamp absorbed the remainder.
 * <p>
 * These tests read the posed skeleton rather than recomputing what the animator should have done —
 * {@code .agents/AGENTS.md} §2, and the reason three earlier tests in this project passed while the
 * bug they existed to catch was live.
 */
class GrazeAimTest {

	/** A frame of a stationary creature grazing, looking at {@code lookPitch} radians (+ is down). */
	private static AnimationContext grazingFrame(float time, float lookPitch) {
		AnimationContext ctx = new AnimationContext();
		ctx.time = time;
		ctx.speed = 0f;
		ctx.tier = LodTier.NEAR;
		ctx.airborne = false;
		ctx.swimming = false;
		ctx.activity = CreatureActivity.GRAZE;
		ctx.lookPitch = lookPitch;
		return ctx;
	}

	/**
	 * Vertical component of the head bone's direction after the pose settles, normalised.
	 * Positive is nose-up, negative is nose-down.
	 */
	private static float settledHeadRise(BodyPlan plan, float lookPitch) {
		CreatureAnimator animator = new CreatureAnimator(plan);
		// The behavioural offsets are damped, so a single frame reads the approach rather than the
		// pose. Two seconds is well past settling at the rate this layer uses.
		for (int frame = 0; frame < 40; frame++) {
			animator.update(grazingFrame(frame / 20f, lookPitch));
		}
		Vector3f head = new Vector3f();
		Vector3f tail = new Vector3f();
		animator.skeleton().boneHead(plan.headBone, head);
		animator.skeleton().boneTail(plan.headBone, tail);
		Vector3f dir = tail.sub(head);
		float length = dir.length();
		assertTrue(length > 1.0e-4f, "head bone has no length to take a direction from");
		return dir.y / length;
	}

	@Test
	void aCreatureBrowsingAboveItselfLiftsItsHeadInsteadOfLoweringIt() {
		Random random = new Random(4242);
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
			if (plan.headBone < 0) continue;

			// Looking well up, as at leaves on a branch overhead.
			float up = settledHeadRise(plan, -0.85f);
			// Looking well down, as at grass underfoot.
			float down = settledHeadRise(plan, 1.15f);
			System.out.printf("%-12s up %+.3f  down %+.3f%n", archetype, up, down);

			// Absolute, not relative. A relative assertion — "up differs from down" — passes
			// against the original constant, because a flat +1.05 offset still responds to the
			// look, it just responds around the wrong centre: the head ends up at -0.20 rise,
			// which is nose-down at food that is overhead. Only requiring the nose to actually
			// come up catches that, and this assertion was checked against the old code to
			// confirm it fails there.
			assertTrue(up > 0.25f,
					archetype + ": browsing at food overhead left the head pointing " + up
							+ " (negative is nose-down) — the pose is not following the look");
			assertTrue(up > down + 0.25f,
					archetype + ": grazing up (" + up + ") and grazing down (" + down
							+ ") barely differ — the pose is ignoring the look direction");
		}
	}

	@Test
	void grazingAtTheGroundStillPutsTheHeadDown() {
		Random random = new Random(9191);
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
			if (plan.headBone < 0) continue;

			// Cropping underfoot is the common case and must not have regressed while making the
			// uncommon one work.
			assertTrue(settledHeadRise(plan, 1.15f) < -0.4f,
					archetype + ": a creature cropping the ground did not get its head down");
		}
	}

	@Test
	void anIdleCreatureDoesNotHoldAGrazingPose() {
		Random random = new Random(313);
		BodyPlan plan = BodyPlanBuilder.build(Archetype.GRAZER.create(random));

		CreatureAnimator animator = new CreatureAnimator(plan);
		for (int frame = 0; frame < 40; frame++) {
			AnimationContext ctx = grazingFrame(frame / 20f, 0f);
			ctx.activity = CreatureActivity.IDLE;
			animator.update(ctx);
		}
		Vector3f head = new Vector3f();
		Vector3f tail = new Vector3f();
		animator.skeleton().boneHead(plan.headBone, head);
		animator.skeleton().boneTail(plan.headBone, tail);
		Vector3f dir = tail.sub(head);

		// Level, give or take. An idle animal looking at nothing should not be nosing the dirt.
		assertTrue(Math.abs(dir.y / dir.length()) < 0.45f,
				"an idle creature is holding a pitched head: rise " + dir.y / dir.length());
	}
}
