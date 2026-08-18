package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;

import java.util.Random;

/** How close the walk comes to repeating over one gait cycle, against how long it has been running. */
public final class LoopProbe {
	private static final float SPEED = 1.4f;
	private static final int FRAMES = 48;

	public static void main(String[] args) {
		int[] warmups = {2, 8, 16, 32, 64, 128};
		System.out.printf("%-13s %8s |", "archetype", "cycle");
		for (int w : warmups) System.out.printf(" %8d", w);
		System.out.println("   (warmup cycles -> pose difference one cycle later)");
		for (Archetype a : Archetype.VALUES) {
			BodyPlan plan = BodyPlanBuilder.build(
					a.create(new Random(4242L + a.ordinal() * 7919L)));
			float cycle = new CreatureAnimator(plan).gaitCycleSeconds(SPEED, 1f);
			System.out.printf("%-13s %8.3f |", a, cycle);
			for (int w : warmups) {
				float[] first = poseAt(plan, cycle, w * FRAMES);
				float[] later = poseAt(plan, cycle, w * FRAMES + FRAMES);
				float worst = 0f;
				for (int i = 0; i < first.length; i++) {
					worst = Math.max(worst, Math.abs(first[i] - later[i]));
				}
				System.out.printf(" %8.4f", worst);
			}
			System.out.println();
		}
	}

	private static float[] poseAt(BodyPlan plan, float cycle, int steps) {
		CreatureAnimator animator = new CreatureAnimator(plan);
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
}
