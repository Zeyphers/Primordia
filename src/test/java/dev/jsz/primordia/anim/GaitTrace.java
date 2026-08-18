package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Frame-by-frame trace of one leg on one specimen over one terrain.
 * <p>
 * The companion to {@link GaitReport}: that says which combination is wrong, this says why. Every
 * time a number in the report has failed to make sense, the answer has been in a column the report
 * does not print — which foot was grounded, where its plant was, how high the body was riding — and
 * reconstructing those by reasoning about the code has been reliably slower and occasionally wrong.
 * <p>
 * {@code gradle gaitTrace --args="INSECTOID FLAT 5.0 0"} — archetype, terrain, speed, leg index.
 */
public final class GaitTrace {

	public static void main(String[] args) {
		Archetype archetype = Archetype.valueOf(args.length > 0 ? args[0] : "INSECTOID");
		BlockTerrain.Kind kind = BlockTerrain.Kind.valueOf(args.length > 1 ? args[1] : "FLAT");
		float speed = args.length > 2 ? Float.parseFloat(args[2]) : 5f;
		int legIndex = args.length > 3 ? Integer.parseInt(args[3]) : 0;
		long seed = 4242L;

		Random random = new Random(seed + archetype.ordinal() * 7919L);
		BodyPlan plan = BodyPlanBuilder.build(archetype.create(random));
		CreatureAnimator animator = new CreatureAnimator(plan);
		BlockTerrain terrain = new BlockTerrain(kind, seed);
		LimbChain leg = plan.legs[legIndex];

		System.out.printf("%s over %s at %.1f b/s, leg %d of %d%n",
				archetype, kind, speed, legIndex, plan.legs.length);
		System.out.printf("legLength %.4f  reach %.4f  stride %.4f  stanceDrop %.4f  hipHeight %.4f%n",
				leg.totalLength, animator.legReach(legIndex), animator.strideLength(),
				animator.stanceDrop(), plan.hipHeight);
		System.out.println("frame  gnd  plantY   curY    toeY   hipY  demand  air   rise  pitch");

		AnimationContext ctx = new AnimationContext();
		ctx.tier = LodTier.NEAR;
		ctx.ground = terrain;
		ctx.speed = speed;

		double x = 0.5, z = -12.5;
		double y = terrain.surfaceAt(x, z);
		Vector3f hip = new Vector3f(), toe = new Vector3f();
		org.joml.Vector3d target = new org.joml.Vector3d();

		int fps = 60;
		for (int step = 0; step < 240; step++) {
			z += speed / fps;
			y = terrain.supportUnder(x, z, Math.max(0.3, plan.width() * 0.4));
			ctx.x = x;
			ctx.y = y;
			ctx.z = z;
			ctx.time = step / (float) fps;
			animator.update(ctx);

			if (step < 30 || step % 3 != 0) continue;
			animator.skeleton().boneHead(leg.bones[0], hip);
			animator.skeleton().boneTail(leg.bones[leg.bones.length - 1], toe);
			animator.getFootWorldPosition(legIndex, target);
			FootState foot = null;
			boolean grounded = animator.isFootGrounded(legIndex);
			float demand = (float) Math.sqrt(
					sq(target.x - x - hip.x) + sq(target.y - y - hip.y) + sq(target.z - z - hip.z))
					/ leg.totalLength;
			System.out.printf("%5d  %3s  %6.3f %6.3f %6.3f %6.3f  %5.2f %6.3f %6.3f %5.1f%n",
					step, grounded ? "yes" : "no",
					target.y - y, target.y - y, toe.y, hip.y, demand,
					toe.y - (terrain.surfaceAt(x + toe.x, z + toe.z) - y),
					0f, Math.toDegrees(animator.bodyPitch()));
		}
	}

	private static double sq(double v) {
		return v * v;
	}

	private GaitTrace() {
	}
}
