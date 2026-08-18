package dev.jsz.primordia;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.BlockTerrain;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.anim.GaitRig;
import dev.jsz.primordia.anim.GroundProbe;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Walking, over ground made of whole blocks.
 * <p>
 * Every animation test before this one drove the animator over a flat plane or a smooth stub, and
 * all of them passed while creatures were walking with their legs locked out. The reason is in
 * {@link GaitRig}: an over-reached limb still solves with a bend in it, because the solver stretches
 * a working copy of the bone lengths to absorb the excess. Measured as straightness the pose looks
 * fine. What is actually wrong is that the foot never arrives — and to see that you have to compare
 * where the toe ended up against where the gait put the foot, over ground that is not flat.
 * <p>
 * Numbers, measured across all eleven archetypes over seven terrains at walking and running speed,
 * before this suite existed and after the gait was rebuilt around the legs' reach envelope:
 * <pre>
 *                          before   after
 *   demanded reach          1.221   0.800   (multiples of the limb's own length)
 *   worst single traverse    3.11    1.07
 *   foot short of target    0.343   0.050   (leg lengths)
 *   worst body roll         65.7째   18.0째
 * </pre>
 * The thresholds below sit between the two columns on purpose. They are loose enough that a
 * creature moving faster than its legs can physically stride still passes — that case is real and
 * the gait handles it by letting the feet skate — and tight enough that the old behaviour fails
 * every one of them.
 */
class TerrainGaitTest {

	/** Speeds a creature actually reaches in play. The report sweeps wider; a test should not. */
	private static final float[] SPEEDS = {1.5f, 3.0f};
	private static final long SEED = 4242L;

	private static BodyPlan specimen(Archetype archetype) {
		return BodyPlanBuilder.build(archetype.create(new Random(SEED + archetype.ordinal() * 7919L)));
	}

	/** Runs every archetype over every terrain, straight and circling. */
	private static java.util.List<GaitRig.Result> sweep() {
		java.util.List<GaitRig.Result> all = new java.util.ArrayList<>();
		for (Archetype archetype : Archetype.VALUES) {
			BodyPlan plan = specimen(archetype);
			for (BlockTerrain.Kind kind : BlockTerrain.Kind.values()) {
				BlockTerrain terrain = new BlockTerrain(kind, SEED);
				for (float speed : SPEEDS) {
					all.add(GaitRig.walk(plan, terrain, archetype + " / " + kind, speed, 0f, 5f));
				}
				all.add(GaitRig.walk(plan, terrain,
						archetype + " / " + kind + " (turning)", 3f, 6f, 5f));
			}
		}
		return all;
	}

	/** Runs the sweep and applies a per-traverse check to each result. */
	private static void sweep(java.util.function.Consumer<GaitRig.Result> check) {
		sweep().forEach(check);
	}

	/**
	 * Asserts a measurement both on average across the sweep and as a ceiling on any one traverse.
	 * <p>
	 * Two thresholds because the two say different things and the tight one cannot be applied to a
	 * single run. Gait quality is a distribution: a creature moving faster than its legs can stride
	 * is a legitimate outlier, so the mean is where the tight bound belongs, and the per-run
	 * ceiling is there to catch one specimen going badly wrong in a way an average would absorb.
	 */
	private static void assertSweep(String what, java.util.function.Function<GaitRig.Result, Float> read,
	                                float meanLimit, float worstLimit) {
		java.util.List<GaitRig.Result> all = sweep();
		double sum = 0;
		GaitRig.Result worst = all.get(0);
		for (GaitRig.Result r : all) {
			sum += read.apply(r);
			if (read.apply(r) > read.apply(worst)) worst = r;
		}
		float mean = (float) (sum / all.size());
		assertTrue(read.apply(worst) < worstLimit, String.format(
				"%s: %s at %.1f b/s measured %.2f, over the %.2f a single traverse may reach",
				what, worst.label, worst.speed, read.apply(worst), worstLimit));
		assertTrue(mean < meanLimit, String.format(
				"%s: averaged %.3f across %d traverses, over the %.2f limit (worst was %s at %.2f)",
				what, mean, all.size(), meanLimit, worst.label, read.apply(worst)));
	}

	/**
	 * The core one: no gait may routinely ask a leg for a target outside its own reach.
	 * <p>
	 * A target beyond the limb's length has no pose that satisfies it. The solver answers with the
	 * chain pointing at it and the foot stopping short, which on screen is a rigid leg held out with
	 * its foot off the ground — the fault this whole suite exists for. The stride is sized from the
	 * reach envelope precisely so that this cannot happen, and the mean demanded reach is the direct
	 * readout of whether it is still being sized that way.
	 */
	@Test
	void noLegIsRoutinelyAskedToReachBeyondItself() {
		// Before: 1.221 average, 3.11 on the worst traverse. After: 0.800 and 1.07.
		assertSweep("demanded reach, in multiples of the limb's own length",
				r -> r.demandMean, 1.00f, 1.30f);
	}

	/**
	 * A leg has to look like it is walking, not shivering.
	 * <p>
	 * This is not a comfort measure. The stride is derived from the legs' reach envelope, so if that
	 * derivation goes wrong the stride collapses and cadence rises to compensate — which is exactly
	 * what happened when the envelope was measured about each foot's bind position rather than about
	 * the middle of its travel. Every other number in this suite stayed green while it did: a
	 * creature taking paces a tenth of its hip height never over-reaches, never misses its target
	 * and never tips over. It just vibrates. Cadence is the only reading that catches it.
	 */
	@Test
	void legsWalkRatherThanVibrate() {
		// Before: 7.07 average, 11.85 on the worst traverse. After: 5.55 and 11.36 — the ceiling is
		// still high because a creature outrunning its own legs really does have to scurry, and
		// three archetypes are given speeds their stride cannot cover (see MD/PITFALLS.md).
		assertSweep("steps per leg per second",
				r -> r.stepRate, 7.0f, 12.5f);
	}

	/**
	 * And the body has to ride over the legs rather than judder on top of them.
	 * <p>
	 * Counted as direction changes in the torso's height, which a walk cannot avoid — one dip per
	 * footfall is the point of a gait. What it can avoid is doing it at eight or ten hertz, which is
	 * what a bob driven at twice a too-high cadence produces, and what a height target taken as a
	 * minimum over a changing set of feet produces on its own.
	 */
	@Test
	void theTorsoDoesNotJudderVertically() {
		// Before: 19.1 average, 27.8 on the worst traverse. After: 5.8 and 16.0.
		assertSweep("torso direction changes per second",
				r -> r.riseReversals, 9.0f, 18.0f);
	}

	/** And the feet must actually get where they were sent. */
	@Test
	void feetArriveWhereTheGaitPutsThem() {
		// Before: 0.343 average, 2.43 on the worst traverse. After: 0.050 and 0.67.
		assertSweep("distance from toe to its target, in leg lengths",
				r -> r.reachMissMean, 0.15f, 1.00f);
	}

	/**
	 * The body follows the ground; it does not lie down on it.
	 * <p>
	 * Attitude used to come from averaging the front feet against the rear over a span guessed from
	 * the bounding box, with roll not clamped at all. One foot up a single block on a narrow animal
	 * asked for sixty degrees of lean and was given it, which is the creature turning forty-five
	 * degrees on a block edge that this was reported as.
	 */
	@Test
	void theBodyNeverTipsOntoItsSide() {
		sweep(r -> {
			assertTrue(r.rollMaxDeg < 22f, String.format(
					"%s at %.1f b/s rolled to %.1f degrees", r.label, r.speed, r.rollMaxDeg));
			assertTrue(r.pitchMaxDeg < 22f, String.format(
					"%s at %.1f b/s pitched to %.1f degrees", r.label, r.speed, r.pitchMaxDeg));
		});
	}

	/** However sharp the ground under it, the body eases onto the new angle rather than snapping. */
	@Test
	void attitudeChangesAtAHumanePace() {
		sweep(r -> assertTrue(r.tiltRateMaxDeg < 190f, String.format(
				"%s at %.1f b/s changed attitude at %.0f degrees a second", r.label, r.speed,
				r.tiltRateMaxDeg)));
	}

	/**
	 * Weight-bearing feet stay on the block they are standing on.
	 * <p>
	 * Generous on purpose. A foot at the lip of a drop it cannot follow is allowed to stop where the
	 * leg runs out, and a creature outrunning its own stride is allowed to skate; what is not
	 * allowed is a foot planted a leg's length clear of the ground beneath it.
	 */
	@Test
	void plantedFeetTouchTheGround() {
		sweep(r -> {
			assertTrue(r.footAirMax < 3.0f, String.format(
					"%s at %.1f b/s planted a foot %.2f leg lengths above the block under it",
					r.label, r.speed, r.footAirMax));
			assertTrue(r.footSunkMax < 3.0f, String.format(
					"%s at %.1f b/s planted a foot %.2f leg lengths inside the block under it",
					r.label, r.speed, r.footSunkMax));
		});
	}

	/**
	 * Every limb is grown with room left over to take a step with.
	 * <p>
	 * A leg's stride is what remains of its reach after standing still is paid for, so a limb grown
	 * at 0.99 of its own length can stand and nothing else. That was not a rare outlier: an
	 * insectoid's legs measured 0.99 across the board, because the digitigrade S-curve puts its
	 * second control point on the axis at mid-range and the two halves of the bow cancel.
	 */
	@Test
	void everyLimbIsGrownWithRoomToStride() {
		Random random = new Random(31415);
		float worst = 0f;
		String worstWhere = "";
		int checked = 0;

		for (int trial = 0; trial < 220; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			for (LimbChain leg : plan.legs) {
				if (leg.totalLength < 1e-4f) continue;
				checked++;
				float extension = leg.origin.distance(leg.restEffector) / leg.totalLength;
				if (extension > worst) {
					worst = extension;
					worstWhere = "a " + leg.bones.length + "-segment leg on a "
							+ plan.legs.length + "-legged creature";
				}
			}
		}

		assertTrue(checked > 400, "too few legs generated to be meaningful: " + checked);
		assertTrue(worst < 0.94f, String.format(
				"%s stands at %.3f of its own length in the bind pose — it has no stride left",
				worstWhere, worst));
	}

	/**
	 * A juvenile's feet reach the ground it is standing on.
	 * <p>
	 * The animator works in unscaled model space while foot plants are world positions, and the
	 * renderer scales the whole model by how far grown the creature is. Without dividing that back
	 * out the two frames disagree by exactly the growth factor, and a half-grown animal on a slope
	 * reaches its feet only part of the way down — a silent error, because the foot <i>target</i> is
	 * a world position and is perfectly correct; it is the drawn leg that falls short.
	 * <p>
	 * What it checks is that the drawn toe and the target agree once the model is scaled the way the
	 * renderer scales it. The obvious test — put the creature on a slope and see whether its toes
	 * land on the ground — does not work, and failing to notice cost a round here: the bug scales
	 * <i>both</i> axes about the creature's own position, and a straight ramp through that point maps
	 * onto itself under exactly that scaling. Every toe sat perfectly on a slope that was wrong by
	 * 58%. The two frames themselves have to be compared, because any test conducted inside one of
	 * them agrees with itself.
	 */
	@Test
	void aJuvenileReachesTheGroundBeneathIt() {
		float growth = dev.jsz.primordia.entity.CreatureEntity.BABY_SCALE;
		// A ramp in X, and a real one. The error being measured is proportional to how far a foot's
		// ground differs in height from the body's own level, so on ground that is flat under the
		// creature it vanishes entirely and the test would pass against the bug. Steep enough that
		// feet on opposite sides stand at visibly different heights, which is the whole point.
		GroundProbe ramp = (x, z, referenceY) -> (float) (x * 0.5);

		Random random = new Random(24680);
		int checked = 0;
		float worst = 0f;

		for (int trial = 0; trial < 60 && checked < 120; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			if (plan.legs.length == 0) continue;

			CreatureAnimator animator = new CreatureAnimator(plan);
			AnimationContext ctx = new AnimationContext();
			ctx.tier = LodTier.NEAR;
			ctx.ground = ramp;
			ctx.scale = growth;
			ctx.y = 0.0;
			for (int frame = 0; frame < 40; frame++) {
				ctx.time = frame / 20f;
				animator.update(ctx);
			}

			Vector3f toe = new Vector3f();
			org.joml.Vector3d target = new org.joml.Vector3d();
			for (int i = 0; i < plan.legs.length; i++) {
				LimbChain leg = plan.legs[i];
				if (!animator.getFootWorldPosition(i, target)) continue;
				animator.skeleton().boneTail(leg.bones[leg.bones.length - 1], toe);
				// Model space to world exactly as the submit pass does it: scale, then translate.
				double dx = ctx.x + toe.x * growth - target.x;
				double dy = ctx.y + toe.y * growth - target.y;
				double dz = ctx.z + toe.z * growth - target.z;
				float error = (float) Math.sqrt(dx * dx + dy * dy + dz * dz)
						/ (leg.totalLength * growth);
				worst = Math.max(worst, error);
				checked++;
			}
		}

		assertTrue(checked > 40, "too few juvenile legs measured: " + checked);
		assertTrue(worst < 0.35f, String.format(
				"a juvenile's drawn toe finished %.2f leg lengths from the world position its foot "
						+ "was planted at — the world-to-model conversion is not dividing out the "
						+ "growth scale", worst));
	}
	/**
	 * A walking animal carries its legs under itself, not behind it.
	 * <p>
	 * The plant is chosen when a foot lifts and reached when it lands, and the body travels the
	 * whole swing in between. Leading the target by half a stance alone aimed at where the hip was
	 * rather than where it would be, so at a duty factor of 0.62 the foot touched down already
	 * behind the hip and then fell a full stance further back. Nothing else in this suite noticed:
	 * reach, cadence, contact and attitude were all green while every leg on every archetype
	 * trailed by 0.29 of its own length.
	 * <p>
	 * Measured against where the body plan grew the foot, so a leg built to stand ahead of its own
	 * shoulder is not counted as trailing for standing where it was made. Read as a magnitude —
	 * a gait that threw its feet too far forward would be just as wrong, and the ceiling is
	 * two-sided for that reason.
	 */
	@Test
	void feetDoNotTrailBehindTheBody() {
		// Before: -0.286 average, and worse the faster the creature went. After: -0.134, and
		// -0.056 at walking speed. What is left is the outrunning case — a creature given more
		// speed than its stride covers has its plants dragged back by the envelope clamp, which is
		// a stride problem and not a lead one (see MD/PITFALLS.md and `gradle strideProbe`).
		java.util.List<GaitRig.Result> all = sweep();
		double sum = 0;
		GaitRig.Result worst = all.get(0);
		for (GaitRig.Result r : all) {
			sum += r.footBiasMean;
			if (Math.abs(r.footBiasMean) > Math.abs(worst.footBiasMean)) worst = r;
		}
		float mean = (float) (sum / all.size());
		assertTrue(Math.abs(mean) < 0.20f, String.format(
				"weight-bearing feet sat %.3f leg lengths from where they were grown, averaged over "
						+ "%d traverses — the gait is dragging its legs rather than stepping onto "
						+ "them (worst %s at %.2f)", mean, all.size(), worst.label, worst.footBiasMean));
		assertTrue(Math.abs(worst.footBiasMean) < 0.45f, String.format(
				"%s at %.1f b/s kept its feet %.2f leg lengths from where they were grown",
				worst.label, worst.speed, worst.footBiasMean));
	}
}
