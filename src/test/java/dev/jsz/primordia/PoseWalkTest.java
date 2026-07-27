package dev.jsz.primordia;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the test rig's walk-in-place mode.
 * <p>
 * A posed specimen never moves, so the animator is fed a nominal speed instead of a measured one.
 * That is a genuinely odd input — every other creature's gait is driven by displacement it has
 * actually made — and the failure mode is silent: the legs simply stand still and nothing in the
 * log says why.
 */
class PoseWalkTest {

	/** A frame of the animation context as a posed specimen produces it: stationary, but "walking". */
	private static AnimationContext posedFrame(float time, float speed) {
		AnimationContext ctx = new AnimationContext();
		ctx.x = 0;
		ctx.y = 0;
		ctx.z = 0;
		ctx.time = time;
		ctx.speed = speed;
		ctx.tier = LodTier.NEAR;
		ctx.airborne = false;
		ctx.swimming = false;
		return ctx;
	}

	/** Total distance every foot has travelled over a second of animation. */
	private static float footTravel(BodyPlan plan, float speed) {
		CreatureAnimator animator = new CreatureAnimator(plan);
		Vector3f scratch = new Vector3f();
		float[] previous = new float[plan.legs.length * 3];
		float travel = 0f;

		// 40 frames at 20 fps: two full seconds, enough for several strides at any cadence.
		for (int frame = 0; frame < 40; frame++) {
			animator.update(posedFrame(frame / 20f, speed));
			for (int i = 0; i < plan.legs.length; i++) {
				int last = plan.legs[i].bones[plan.legs[i].bones.length - 1];
				animator.skeleton().boneTail(last, scratch);
				// Skip the first frame: the feet snap to their initial plant, which is not motion.
				if (frame > 1) {
					float dx = scratch.x - previous[i * 3];
					float dy = scratch.y - previous[i * 3 + 1];
					float dz = scratch.z - previous[i * 3 + 2];
					travel += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				}
				previous[i * 3] = scratch.x;
				previous[i * 3 + 1] = scratch.y;
				previous[i * 3 + 2] = scratch.z;
			}
		}
		return travel;
	}

	@Test
	void aStationaryCreatureFedAWalkingSpeedActuallyMovesItsFeet() {
		Random random = new Random(7788);
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));

			float walking = footTravel(plan, dev.jsz.primordia.entity.CreatureEntity.POSE_WALK_SPEED);
			float standing = footTravel(plan, 0f);

			assertTrue(walking > plan.hipHeight * 0.5f,
					archetype + ": posed walk moved the feet only " + walking
							+ " over two seconds — the gait is not running");
			// The point of the toggle is that the two states differ visibly.
			assertTrue(walking > standing * 4f,
					archetype + ": walking (" + walking + ") is barely different from standing ("
							+ standing + ")");
		}
	}
}
