package dev.jsz.primordia;

import dev.jsz.primordia.anim.AnimationContext;
import dev.jsz.primordia.anim.CreatureAnimator;
import dev.jsz.primordia.anim.GroundProbe;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what a foot does at the edge of open water.
 * <p>
 * Creatures standing beside a lake put their outer feet on the surface of it. The cause was not that
 * water was mistaken for a solid block — it is not, its collision shape is empty and the probe walks
 * straight through it. It was that the probe answered {@link Float#NaN}, meaning "nothing standable
 * here", and the animator's response to that is to search the four neighbouring columns for
 * something to rescue the foot onto. That rescue exists so a foot over a cliff edge finds the ledge
 * instead of dangling; at a shoreline the neighbour it finds is the bank the creature is standing on,
 * so the foot snapped up to the land's height while sitting out over the water.
 * <p>
 * The fix is for a water column to give a real answer — just under the surface — rather than no
 * answer. This checks the outcome through the animator, because the rescue is the part that has to
 * not happen and it lives there rather than in the probe.
 */
class ShorelineFootingTest {
	/** Land, and the water beside it, both at this height. */
	private static final float SHORE_Y = 64f;
	/** Everything at or past this X is open water. */
	private static final double WATERLINE = 0.0;

	/**
	 * A shoreline. Land to the west at {@link #SHORE_Y}; deep water to the east, reporting a surface
	 * just below it, exactly as {@code WorldGroundProbe} now does for a column with no reachable
	 * bottom.
	 */
	private static final float WADE_DEPTH = 0.35f;

	private static GroundProbe shoreline() {
		return (x, z, referenceY) -> x < WATERLINE ? SHORE_Y : SHORE_Y - WADE_DEPTH;
	}

	/** The old behaviour: water reported nothing at all, and the rescue took over. */
	private static GroundProbe shorelineReportingNothing() {
		return (x, z, referenceY) -> x < WATERLINE ? SHORE_Y : Float.NaN;
	}

	private static AnimationContext context(GroundProbe probe, double x, float time) {
		AnimationContext ctx = new AnimationContext();
		ctx.x = x;
		ctx.y = SHORE_Y;
		ctx.z = 0.0;
		ctx.speed = 0f;
		ctx.time = time;
		ctx.tier = LodTier.NEAR;
		ctx.ground = probe;
		return ctx;
	}

	/**
	 * Straddling the waterline, no foot may rest at the water's surface height.
	 * <p>
	 * Feet on the land side stay on the land; feet over the water sit below it. The failure this
	 * guards is specifically the second group being lifted to the first group's height.
	 */
	@Test
	void feetOverWaterDoNotRestOnItsSurface() {
		Random random = new Random(1608);
		int straddled = 0;

		for (int trial = 0; trial < 120 && straddled < 20; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			if (plan.legs.length == 0) continue;

			CreatureAnimator animator = new CreatureAnimator(plan);
			// Settled over several frames: feet ease onto their plants rather than snapping.
			for (int frame = 0; frame < 30; frame++) {
				animator.update(context(shoreline(), WATERLINE, frame / 20f));
			}

			Vector3d foot = new Vector3d();
			boolean anyOverWater = false;
			for (int i = 0; i < plan.legs.length; i++) {
				if (!animator.getFootWorldPosition(i, foot)) continue;
				if (foot.x < WATERLINE) continue;
				anyOverWater = true;

				assertTrue(foot.y < SHORE_Y - WADE_DEPTH * 0.5, String.format(
						"a foot at x=%.2f (over water) rests at y=%.3f, the shore is at %.1f — "
								+ "it is standing on the surface", foot.x, foot.y, SHORE_Y));
			}
			if (anyOverWater) straddled++;
		}
		assertTrue(straddled >= 10,
				"too few creatures straddled the waterline to be meaningful: " + straddled);
	}

	/**
	 * The complement, and the one that shows the test is measuring the right thing: with the probe
	 * reporting {@code NaN} over water — the old behaviour — feet <i>are</i> lifted to the bank.
	 * <p>
	 * Without this, the test above would pass just as happily against a stub that never put a foot
	 * over the water at all.
	 */
	@Test
	void reportingNothingOverWaterIsWhatLiftedTheFoot() {
		Random random = new Random(1608);
		int lifted = 0;
		int overWater = 0;

		for (int trial = 0; trial < 120; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			if (plan.legs.length == 0) continue;

			CreatureAnimator animator = new CreatureAnimator(plan);
			for (int frame = 0; frame < 30; frame++) {
				animator.update(context(shorelineReportingNothing(), WATERLINE, frame / 20f));
			}

			Vector3d foot = new Vector3d();
			for (int i = 0; i < plan.legs.length; i++) {
				if (!animator.getFootWorldPosition(i, foot)) continue;
				if (foot.x < WATERLINE) continue;
				overWater++;
				if (foot.y > SHORE_Y - WADE_DEPTH * 0.5) lifted++;
			}
		}
		assertTrue(overWater > 20, "no feet landed over the water: " + overWater);
		assertTrue(lifted > overWater / 2, String.format(
				"only %d of %d feet were lifted to the bank under the old behaviour — the rescue "
						+ "this test exists to catch may no longer be reachable", lifted, overWater));
	}

	@Test
	void landSideFeetAreUnaffected() {
		Random random = new Random(99);
		for (int trial = 0; trial < 40; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			if (plan.legs.length == 0) continue;

			CreatureAnimator animator = new CreatureAnimator(plan);
			// Well inland: every column is land, so nothing should have moved.
			for (int frame = 0; frame < 30; frame++) {
				animator.update(context(shoreline(), WATERLINE - 8.0, frame / 20f));
			}

			Vector3d foot = new Vector3d();
			for (int i = 0; i < plan.legs.length; i++) {
				if (!animator.getFootWorldPosition(i, foot)) continue;
				assertEquals(SHORE_Y, foot.y, 0.35,
						"a foot on open ground left the ground it was standing on");
			}
		}
	}
}
