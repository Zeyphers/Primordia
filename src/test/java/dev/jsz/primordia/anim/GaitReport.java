package dev.jsz.primordia.anim;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Walks every archetype over every terrain and prints what its legs did.
 * <p>
 * {@code gradle gaitReport} — the counterpart to {@code diversityReport}, and for the same reason:
 * a walk cycle is a property of a distribution of creatures across a distribution of ground, and
 * neither can be eyeballed one specimen at a time. A fault that shows on saurians over a ledge and
 * nowhere else is invisible in any single test.
 * <p>
 * Read the columns as: <b>demand</b> is how far the gait asked the leg to stretch as a multiple of
 * its own length — anything at or above 1.00 is a target the limb physically cannot reach, and the
 * solver's only answer is to point straight at it. <b>pinned</b> is how often it ended up doing
 * exactly that. <b>reachmiss</b> is how far short the toe finished, which is the visible gap between
 * the foot and the ground.
 */
public final class GaitReport {

	public static void main(String[] args) {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242L;
		float[] speeds = {1.5f, 3.0f, 5.0f};
		BlockTerrain.Kind[] terrains = BlockTerrain.Kind.values();

		System.out.println("Primordia gait report — seed " + seed);
		System.out.println();

		List<GaitRig.Result> all = new ArrayList<>();
		for (Archetype archetype : Archetype.VALUES) {
			Random random = new Random(seed + archetype.ordinal() * 7919L);
			Genome genome = archetype.create(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);

			System.out.println(archetype + "  hipHeight=" + fmt(plan.hipHeight)
					+ "  legs=" + plan.legs.length + "x" + plan.legs[0].bones.length
					+ "  legLength=" + fmt(plan.legs[0].totalLength)
					+ "  bodyLength=" + fmt(plan.bodyLength)
					+ "  restReach=" + fmt(restReach(plan)));
			System.out.println("  " + GaitRig.Result.header());
			for (BlockTerrain.Kind kind : terrains) {
				BlockTerrain terrain = new BlockTerrain(kind, seed);
				for (float speed : speeds) {
					GaitRig.Result straight = GaitRig.walk(plan, terrain,
							archetype + " / " + kind, speed, 0f, 6f);
					all.add(straight);
					System.out.println("  " + straight.row());
				}
				// Turning is where a planted foot is dragged out from under the body, so every
				// terrain gets one circling pass as well.
				GaitRig.Result turning = GaitRig.walk(plan, terrain,
						archetype + " / " + kind + " (turning)", 3.0f, 6f, 6f);
				all.add(turning);
				System.out.println("  " + turning.row());
			}
			System.out.println();
		}

		summarise(all);

		// The speeds a creature is actually given in the world. MOVEMENT_SPEED is clamped to
		// [0.12, 0.35], which is roughly 1.5 to 4 blocks a second, so the 5.0 pass is above
		// anything the ecology asks for and its numbers should not be read as typical.
		System.out.println();
		System.out.println("=== walking speeds only (<= 3.0 b/s) ===");
		summarise(all.stream().filter(r -> r.speed <= 3.0f).toList());
	}

	/**
	 * Worst-case hip-to-foot distance in the bind pose, as a fraction of the leg's own length. The
	 * budget every step has to fit inside: at 1.00 the creature is standing at full extension before
	 * it has taken a stride.
	 */
	private static float restReach(BodyPlan plan) {
		float worst = 0f;
		for (var leg : plan.legs) {
			worst = Math.max(worst, leg.origin.distance(leg.restEffector) / leg.totalLength);
		}
		return worst;
	}

	private static void summarise(List<GaitRig.Result> input) {
		List<GaitRig.Result> all = new ArrayList<>(input);
		all.sort((a, b) -> Float.compare(b.overreachFrac, a.overreachFrac));
		System.out.println("=== worst twenty by over-reached-leg fraction ===");
		System.out.println(GaitRig.Result.header());
		for (int i = 0; i < Math.min(20, all.size()); i++) {
			System.out.println(all.get(i).row());
		}

		double over = 0, overStance = 0, pinned = 0, demand = 0, miss = 0, air = 0, sunk = 0;
		double stepRate = 0, reversals = 0, jitter = 0, knee = 0;
		float kneeWorst = 0, perpWorst = 0;
		float pitch = 0, roll = 0, tilt = 0, demandWorst = 0, missWorst = 0;
		float stepWorst = 0, revWorst = 0, jitWorst = 0;
		for (GaitRig.Result r : all) {
			stepRate += r.stepRate;
			knee += r.kneeFlipFrac;
			kneeWorst = Math.max(kneeWorst, r.kneeFlipFrac);
			perpWorst = Math.max(perpWorst, r.perpSwingMaxDeg);
			reversals += r.riseReversals;
			jitter += r.riseJitter;
			stepWorst = Math.max(stepWorst, r.stepRate);
			revWorst = Math.max(revWorst, r.riseReversals);
			jitWorst = Math.max(jitWorst, r.riseJitter);
			over += r.overreachFrac;
			overStance += r.overreachStance;
			pinned += r.pinnedFrac;
			demand += r.demandMean;
			miss += r.reachMissMean;
			air += r.footAirMax;
			sunk += r.footSunkMax;
			demandWorst = Math.max(demandWorst, r.demandMax);
			missWorst = Math.max(missWorst, r.reachMissMax);
			pitch = Math.max(pitch, r.pitchMaxDeg);
			roll = Math.max(roll, r.rollMaxDeg);
			tilt = Math.max(tilt, r.tiltRateMaxDeg);
		}
		int n = Math.max(1, all.size());
		System.out.println();
		System.out.printf("overall: overreach %.1f%% (stance %.1f%%)  pinned %.1f%%  demand %.3f (worst %.2f)  "
						+ "reachmiss %.3f (worst %.2f)  air %.2f  sunk %.2f%n",
				over / n * 100.0, overStance / n * 100.0, pinned / n * 100.0, demand / n, demandWorst,
				miss / n, missWorst, air / n, sunk / n);
		System.out.printf("         worst pitch %.1f deg  roll %.1f deg  tilt %.0f deg/s%n",
				pitch, roll, tilt);
		System.out.printf("         knee flipped %.1f%% of frames (worst %.1f%%)  "
						+ "bend plane swung up to %.0f deg from the bind pose%n",
				knee / n * 100.0, kneeWorst * 100.0, perpWorst);
		System.out.printf("         cadence %.2f steps/leg/s (worst %.2f)  "
						+ "rise reversals %.1f/s (worst %.1f)  jitter %.1f (worst %.1f)%n",
				stepRate / n, stepWorst, reversals / n, revWorst, jitter / n, jitWorst);
	}

	private static String fmt(float v) {
		return String.format("%.2f", v);
	}

	private GaitReport() {
	}
}
