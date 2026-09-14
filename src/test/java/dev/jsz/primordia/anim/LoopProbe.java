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
		if (args.length > 0 && args[0].equals("sweep")) {
			sweep();
			return;
		}
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

	/**
	 * {@code gradle loopProbe --args=sweep}. The same check {@code EditorClipLoopTest} makes, over
	 * ten specimens per archetype rather than one, with the test's 40-cycle warmup and 0.01 bar.
	 * One seed passing says little about a gait change; the editor loops every creature's walk.
	 */
	private static void sweep() {
		int bad = 0, total = 0;
		float worstAll = 0f;
		for (Archetype a : Archetype.VALUES) {
			for (int k = 0; k < 10; k++) {
				BodyPlan plan = BodyPlanBuilder.build(
						a.create(new Random(4242L + a.ordinal() * 7919L + k * 104729L)));
				float cycle = new CreatureAnimator(plan).gaitCycleSeconds(SPEED, 1f);
				float[] first = poseAt(plan, cycle, 40 * FRAMES);
				float[] later = poseAt(plan, cycle, 40 * FRAMES + FRAMES);
				float worst = 0f;
				for (int i = 0; i < first.length; i++) worst = Math.max(worst, Math.abs(first[i] - later[i]));
				total++;
				worstAll = Math.max(worstAll, worst);
				if (worst > 0.01f) {
					bad++;
					System.out.printf("%-13s #%d legs=%d  differs %.4f (cycle %.3fs)%n",
							a, k, plan.legs.length, worst, cycle);
				}
			}
		}
		System.out.printf("%d of %d specimens fail to loop; worst %.4f%n", bad, total, worstAll);
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
