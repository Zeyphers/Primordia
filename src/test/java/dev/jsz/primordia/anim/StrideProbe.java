package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;

import java.util.Random;

/**
 * Prints, per archetype, the fastest each creature's legs can actually carry it.
 * <p>
 * {@code gradle strideProbe}. Stride is derived from the legs' reach envelope, so a body plan can
 * be handed a movement speed its geometry cannot deliver — and when that happens there is no gait
 * that looks right, only a choice between legs that blur and legs held out straight. The last
 * column compares against {@code CreatureEntity.POSE_WALK_SPEED}, the speed
 * {@code /primordia test walk} drives every specimen at regardless of its size.
 */
public final class StrideProbe {
	public static void main(String[] args) {
		long seed = 4242L;
		System.out.printf("%-12s %6s %6s %8s %8s %10s %9s%n",
				"archetype", "hipH", "legLen", "stride", "str/hipH", "maxWalk", "@2.2b/s");
		for (Archetype a : Archetype.VALUES) {
			CreatureAnimator an = rig(a, seed);
			BodyPlan plan = plan(a, seed);
			float stride = an.strideLength();
			// The fastest the legs can carry the body: one stride per step at the cadence ceiling.
			float maxWalk = stride * 6f;
			System.out.printf("%-12s %6.3f %6.3f %8.3f %8.2f %10.2f %9s%n",
					a, plan.hipHeight, plan.legs[0].totalLength, stride,
					stride / Math.max(1e-4f, plan.hipHeight), maxWalk,
					maxWalk >= 2.2f ? "ok" : String.format("%.1fx over", 2.2f / maxWalk));
		}
	}

	private static BodyPlan plan(Archetype a, long seed) {
		Random r = new Random(seed + a.ordinal() * 7919L);
		Genome g = a.create(r);
		return BodyPlanBuilder.build(g);
	}

	private static CreatureAnimator rig(Archetype a, long seed) {
		BodyPlan plan = plan(a, seed);
		CreatureAnimator an = new CreatureAnimator(plan);
		AnimationContext ctx = new AnimationContext();
		ctx.tier = LodTier.NEAR;
		BlockTerrain terrain = new BlockTerrain(BlockTerrain.Kind.FLAT, seed);
		ctx.ground = terrain;
		ctx.x = 0.5;
		ctx.z = 0.5;
		ctx.y = terrain.surfaceAt(0.5, 0.5);
		ctx.speed = 0f;
		an.update(ctx);
		return an;
	}
}
