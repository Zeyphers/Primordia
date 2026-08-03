package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins large creatures to a narrower stance.
 * <p>
 * A SAURIAN's rest foot position used to be as wide as {@code LEG_SPLAY} and {@code LEG_ARCH}
 * happened to roll, with nothing narrowing it for the creature's size — "pillar legs" was already
 * the intent behind the thickness band, but nothing had ever actually pulled the stance in to match
 * it. On the biggest creatures the rest pose sat close enough to the IK solver's stretch limit that
 * ordinary gait sway pushed it over, which read as legs snapping. This checks the geometric claim
 * directly: how far a rest foot sits from the plane under the spine, relative to how tall the
 * creature stands, and that the ratio comes down as size goes up.
 */
class GraviportalStanceTest {

	/** Lateral offset of a leg's bind-pose foot from the body's own midline, relative to hip height. */
	private static float relativeStanceWidth(BodyPlan plan) {
		float widest = 0f;
		for (LimbChain leg : plan.legs) {
			widest = Math.max(widest, Math.abs(leg.restEffector.x));
		}
		return plan.hipHeight <= 1e-4f ? 0f : widest / plan.hipHeight;
	}

	@Test
	void largeSauriansStandNarrowerThanTheirOwnHeight() {
		Random random = new Random(9001);
		int checked = 0;
		float total = 0f;

		for (int trial = 0; trial < 60; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.SAURIAN.create(random));
			if (plan.legs.length == 0) continue;
			checked++;
			total += relativeStanceWidth(plan);
		}
		assertTrue(checked > 40, "too few saurians built to be meaningful: " + checked);

		// A relative stance width of 1.0 means the foot sits as far out to the side as the hip
		// stands tall — already a wide-looking animal. The old code had no ceiling on this at all;
		// this is a generous one, comfortably above a real graviportal stance, but well short of
		// "sprawled".
		float mean = total / checked;
		assertTrue(mean < 0.55f, String.format(
				"saurian mean relative stance width is %.2f — still reads as sprawled", mean));
	}

	/**
	 * The general mechanism, not just the archetype band: a creature that ends up large through
	 * mutation rather than through a SAURIAN founder must inherit the same narrowing. Checked by
	 * driving the same body plan through two hand-set sizes rather than relying on the gene roll to
	 * land somewhere large, since {@code SIZE} is stochastic even within one archetype's band.
	 */
	@Test
	void narrowingScalesWithSizeRegardlessOfArchetype() {
		Random random = new Random(1234);
		int comparisons = 0;

		for (int trial = 0; trial < 40 && comparisons < 15; trial++) {
			Genome genome = Archetype.GRAZER.create(random);
			Genome small = genome.with(dev.jsz.primordia.genome.Gene.SIZE, 0.05f);
			Genome large = genome.with(dev.jsz.primordia.genome.Gene.SIZE, 0.98f);

			BodyPlan smallPlan = BodyPlanBuilder.build(small);
			BodyPlan largePlan = BodyPlanBuilder.build(large);
			if (smallPlan.legs.length == 0 || largePlan.legs.length == 0) continue;
			comparisons++;

			float smallStance = relativeStanceWidth(smallPlan);
			float largeStance = relativeStanceWidth(largePlan);
			assertTrue(largeStance <= smallStance + 1e-3f, String.format(
					"the same genome stood relatively wider large (%.3f) than small (%.3f)",
					largeStance, smallStance));
		}
		assertTrue(comparisons >= 10, "too few valid comparisons: " + comparisons);
	}

	@Test
	void narrowingNeverReachesZero() {
		// The floor keeps LEG_SPLAY doing something at every size. A creature standing with its
		// feet directly under its hips at every size would read as stilts, not as a large animal.
		Random random = new Random(555);
		for (int trial = 0; trial < 30; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.SAURIAN.create(random));
			if (plan.legs.length == 0) continue;
			assertTrue(relativeStanceWidth(plan) > 0.02f,
					"a saurian's stance narrowed all the way to directly under the hip");
		}
	}
}
