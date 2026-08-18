package dev.jsz.primordia;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's walk preview plays a clip exactly one gait cycle long on repeat, so the walk has to
 * actually be periodic over that cycle or the seam shows.
 * <p>
 * The clip length used to be computed from a copy of the cadence formula living in
 * {@code EditorServer}, carrying a comment arguing the copy was safe because a wrong value would
 * "fail loudly". It did fail loudly — the stride stopped being a multiple of hip height, the copy
 * kept dividing by {@code hipHeight * 1.35}, and the preview stopped looping. Failing loudly is not
 * the same as not failing, so this asserts the property directly rather than trusting either copy.
 */
class EditorClipLoopTest {

	private static final float SPEED = 1.4f;
	private static final int WARMUP_CYCLES = 40;
	private static final int FRAMES = 48;

	/** Skin matrices after driving the animator to {@code time}, the way the recorder does. */
	private static float[] poseAt(CreatureAnimator animator, BodyPlan plan, float cycle, int steps) {
		float dt = cycle / FRAMES;
		AnimationContext ctx = new AnimationContext();
		for (int i = 0; i < steps; i++) {
			ctx.time = dt * i;
			ctx.z = ctx.time * SPEED;
			ctx.speed = SPEED;
			ctx.tier = LodTier.NEAR;
			ctx.ambient = false;
			animator.update(ctx);
		}
		float[] out = new float[plan.bones.length * 16];
		float[] scratch = new float[16];
		for (int i = 0; i < plan.bones.length; i++) {
			animator.skeleton().skinMatrix(i).get(scratch);
			System.arraycopy(scratch, 0, out, i * 16, 16);
		}
		return out;
	}

	@Test
	void theWalkRepeatsOverExactlyOneGaitCycle() {
		StringBuilder bad = new StringBuilder();
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(
					archetype.create(new Random(4242L + archetype.ordinal() * 7919L)));

			CreatureAnimator animator = new CreatureAnimator(plan);
			float cycle = animator.gaitCycleSeconds(SPEED, 1f);
			assertTrue(cycle > 0f, archetype + " reports a zero-length gait cycle");

			// Same animator, driven to the first recorded frame and then to the frame exactly one
			// cycle later. A clip that loops is one where those two poses are the same pose.
			int start = WARMUP_CYCLES * FRAMES;
			float[] first = poseAt(new CreatureAnimator(plan), plan, cycle, start);
			float[] later = poseAt(new CreatureAnimator(plan), plan, cycle, start + FRAMES);

			float worst = 0f;
			for (int i = 0; i < first.length; i++) {
				worst = Math.max(worst, Math.abs(first[i] - later[i]));
			}
			// In model units against a creature whose limbs are measured in tenths of a block, so a
			// hundredth is well under what the eye picks out as a jump at the loop point.
			if (worst > 0.01f) {
				bad.append(String.format("%s: pose differs by %.4f one cycle later (cycle %.3fs)%n",
						archetype, worst, cycle));
			}
		}
		assertTrue(bad.isEmpty(), "the walk does not repeat over its own gait cycle:"
				+ System.lineSeparator() + bad);
	}
}
