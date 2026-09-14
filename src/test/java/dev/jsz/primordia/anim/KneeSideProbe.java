package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Which way the knees point while the animal walks, and whether its legs pass through each other.
 * <p>
 * {@code gradle kneeSideProbe}. {@link KneeProbe} reads the bind pose, where every pole points
 * outward and nothing looks wrong; this reads the solved skeleton mid-stride, which is what the
 * player sees. Per archetype, over many specimens walking flat ground:
 * <ul>
 *   <li><b>inward</b> — interior joints sitting toward the body's midline of the straight line from
 *   hip to toe, by more than 5% of the leg. A knee folded in under the belly.</li>
 *   <li><b>tucked</b> — the same joints closer to the midline than the hip itself.</li>
 *   <li><b>cross</b> — frames where a left leg and a right leg physically overlap.</li>
 *   <li><b>same</b> — frames where two legs on one side overlap.</li>
 * </ul>
 * Specimens are counted as "bad" when any of these happens on more than 5% of their frames.
 */
public final class KneeSideProbe {

	private static final int FPS = 30;

	/** Bone-pair histogram of same-side collisions: "thigh/shin" rather than just "they touched". */
	private static java.util.Map<String, Integer> collisionPairs;
	/** Bone indices, within each limb, of the closest approach {@link #gap} last found. */
	private static int lastI, lastJ;

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("diag")) {
			collisionPairs = new java.util.TreeMap<>();
			Archetype a = Archetype.valueOf(args[1]);
			for (int k = 0; k < 20; k++) {
				BodyPlan plan = BodyPlanBuilder.build(a.create(new Random(4242L + a.ordinal() * 7919L + k * 104729L)));
				measure(plan, 1.6f, 4f);
			}
			System.out.println(a + " same-side collisions by bone pair (front leg bone / rear leg bone, 0 = thigh):");
			collisionPairs.forEach((key, n) -> System.out.printf("  %-12s %6d%n", key, n));
			return;
		}
		int specimens = args.length > 0 ? Integer.parseInt(args[0]) : 40;
		long seed = args.length > 1 ? Long.parseLong(args[1]) : 4242L;

		System.out.printf("%-13s %4s | %8s %8s %8s %8s | %s%n",
				"archetype", "n", "inward", "tucked", "cross", "same", "bad specimens");
		double[] totals = new double[4];
		int totalN = 0, totalBad = 0;
		for (Archetype a : Archetype.VALUES) {
			double[] sum = new double[4];
			int bad = 0;
			StringBuilder worst = new StringBuilder();
			for (int k = 0; k < specimens; k++) {
				long s = seed + a.ordinal() * 7919L + k * 104729L;
				BodyPlan plan = BodyPlanBuilder.build(a.create(new Random(s)));
				float[] m = measure(plan, 1.6f, 4f);
				for (int i = 0; i < 4; i++) sum[i] += m[i];
				if (m[0] > 0.05f || m[2] > 0.05f || m[3] > 0.05f) {
					bad++;
					if (worst.length() < 60) worst.append(s).append(' ');
				}
			}
			for (int i = 0; i < 4; i++) totals[i] += sum[i];
			totalN += specimens;
			totalBad += bad;
			System.out.printf("%-13s %4d | %7.1f%% %7.1f%% %7.1f%% %7.1f%% | %d  %s%n", a, specimens,
					sum[0] / specimens * 100, sum[1] / specimens * 100,
					sum[2] / specimens * 100, sum[3] / specimens * 100, bad, worst);
		}
		System.out.printf("%-13s %4d | %7.1f%% %7.1f%% %7.1f%% %7.1f%% | %d%n", "ALL", totalN,
				totals[0] / totalN * 100, totals[1] / totalN * 100,
				totals[2] / totalN * 100, totals[3] / totalN * 100, totalBad);
	}

	/** {inward, tucked, cross, same} as fractions of the frames or joint-frames sampled. */
	public static float[] measure(BodyPlan plan, float speed, float seconds) {
		CreatureAnimator animator = new CreatureAnimator(plan);
		AnimationContext ctx = new AnimationContext();
		ctx.tier = LodTier.NEAR;
		BlockTerrain terrain = new BlockTerrain(BlockTerrain.Kind.FLAT, 1L);
		ctx.ground = terrain;

		int legs = plan.legs.length;
		Vector3f hip = new Vector3f(), toe = new Vector3f(), joint = new Vector3f();
		Vector3f[][] heads = new Vector3f[legs][];
		Vector3f[][] tails = new Vector3f[legs][];
		for (int i = 0; i < legs; i++) {
			int n = plan.legs[i].bones.length;
			heads[i] = new Vector3f[n];
			tails[i] = new Vector3f[n];
			for (int b = 0; b < n; b++) {
				heads[i][b] = new Vector3f();
				tails[i][b] = new Vector3f();
			}
		}

		int jointFrames = 0, inward = 0, tucked = 0, frames = 0, crossFrames = 0, sameFrames = 0;
		float tolerance = plan.blendRadius * 1.5f;
		double z = 0;
		int steps = (int) (seconds * FPS);
		for (int step = 0; step < steps; step++) {
			z += speed / FPS;
			ctx.x = 0.5;
			ctx.y = terrain.surfaceAt(0.5, z);
			ctx.z = z;
			ctx.bodyYaw = 0f;
			ctx.speed = speed;
			ctx.time = step / (float) FPS;
			animator.update(ctx);
			if (step < FPS / 2) continue;
			frames++;

			for (int i = 0; i < legs; i++) {
				LimbChain leg = plan.legs[i];
				int n = leg.bones.length;
				for (int b = 0; b < n; b++) {
					animator.skeleton().boneHead(leg.bones[b], heads[i][b]);
					animator.skeleton().boneTail(leg.bones[b], tails[i][b]);
				}
				hip.set(heads[i][0]);
				toe.set(tails[i][n - 1]);
				float side = leg.side > 0 ? 1f : -1f;
				for (int b = 1; b < n; b++) {
					joint.set(heads[i][b]);
					float t = projectT(joint, hip, toe);
					float lineX = hip.x + (toe.x - hip.x) * t;
					jointFrames++;
					if (side * (joint.x - lineX) < -0.05f * leg.totalLength) inward++;
					if (side * (joint.x - hip.x) < -0.02f * leg.totalLength) tucked++;
				}
			}

			boolean cross = false, same = false;
			for (int a = 0; a < legs && !(cross && same); a++) {
				for (int c = a + 1; c < legs; c++) {
					boolean sameSide = plan.legs[a].side == plan.legs[c].side;
					if (sameSide ? same : cross) continue;
					if (gap(plan, plan.legs[a], plan.legs[c], heads[a], tails[a], heads[c], tails[c]) < -tolerance) {
						if (sameSide && collisionPairs != null) {
							collisionPairs.merge(lastI + "/" + lastJ, 1, Integer::sum);
						}
						if (sameSide) same = true;
						else cross = true;
					}
				}
			}
			if (cross) crossFrames++;
			if (same) sameFrames++;
		}
		return new float[]{
				jointFrames == 0 ? 0f : inward / (float) jointFrames,
				jointFrames == 0 ? 0f : tucked / (float) jointFrames,
				frames == 0 ? 0f : crossFrames / (float) frames,
				frames == 0 ? 0f : sameFrames / (float) frames};
	}

	private static float projectT(Vector3f p, Vector3f a, Vector3f b) {
		float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
		float len2 = dx * dx + dy * dy + dz * dz;
		if (len2 < 1e-10f) return 0f;
		float t = ((p.x - a.x) * dx + (p.y - a.y) * dy + (p.z - a.z) * dz) / len2;
		return Math.max(0f, Math.min(1f, t));
	}

	/**
	 * Smallest surface gap between two posed limbs, skipping the first bone of each: the thighs
	 * meet at the body and graze there by construction, which is the hip rather than a collision.
	 */
	private static float gap(BodyPlan plan, LimbChain la, LimbChain lb,
	                         Vector3f[] ha, Vector3f[] ta, Vector3f[] hb, Vector3f[] tb) {
		float best = Float.MAX_VALUE;
		Vector3f p = new Vector3f(), q = new Vector3f();
		for (int i = 0; i < la.bones.length; i++) {
			BoneDef x = plan.bones[la.bones[i]];
			for (int j = 0; j < lb.bones.length; j++) {
				if (i == 0 && j == 0) continue;
				BoneDef y = plan.bones[lb.bones[j]];
				for (int k = 0; k <= 8; k++) {
					float tx = k / 8f;
					p.set(ha[i]).lerp(ta[i], tx);
					float rx = x.radiusHead + (x.radiusTail - x.radiusHead) * tx;
					float ty = projectT(p, hb[j], tb[j]);
					q.set(hb[j]).lerp(tb[j], ty);
					float ry = y.radiusHead + (y.radiusTail - y.radiusHead) * ty;
					float d = p.distance(q) - rx - ry;
					if (d < best) {
						best = d;
						lastI = i;
						lastJ = j;
					}
				}
			}
		}
		return best;
	}

	private KneeSideProbe() {
	}
}
