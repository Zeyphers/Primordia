package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.genome.Genome;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzzes the energy economy over the gene space.
 * <p>
 * These invariants are the ones with no visual tell. An ecology that is subtly wrong still looks
 * like an ecology — animals still walk about and eat things — so the failure mode is not a crash or
 * an obviously broken creature but a population that quietly empties over an hour of play, which is
 * exactly the bug this system was written to fix. Reasoning about the numbers is not enough,
 * because a rate that is wrong by a factor of three reads as plausible in the source.
 * <p>
 * Every test here fuzzes rather than checking one hand-picked genome, for the same reason
 * {@code BodyPlanTest} does: there is no authoring pass, so the invariant has to hold at every
 * point in the space, including the corners where metabolism and mass are both extreme.
 */
class EnergyBudgetTest {
	private static final int TRIALS = 400;
	/** Ticks in a Minecraft day. */
	private static final float DAY = 24000f;

	@Test
	void thresholdsAreCoherentlyOrdered() {
		// A creature must want to scavenge or graze before it is hungry enough to hunt: hunting is
		// the expensive, risky option and should be the later resort. If these ever cross, a
		// predator hunts while still too full to bother eating what it kills.
		assertTrue(EnergyBudget.HUNT_THRESHOLD < EnergyBudget.FORAGE_THRESHOLD,
				"hunting should begin at a lower energy than foraging");
		// Breeding must require more than mere survival, or animals reproduce while starving.
		assertTrue(EnergyBudget.BREED_THRESHOLD > EnergyBudget.HUNT_THRESHOLD,
				"breeding should require more energy than hunting");
		// Paying the cost of a brood must not immediately drop a parent into starvation.
		assertTrue(EnergyBudget.BREED_THRESHOLD - EnergyBudget.BREED_COST > EnergyBudget.STARVING,
				"breeding should not leave the parent empty");
	}

	@Test
	void everyGenomeStaysFedForAPlausibleTime() {
		Random random = new Random(4242);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);

			float idle = EnergyBudget.drainPerTick(genome, plan, EnergyBudget.Activity.IDLE);
			float daysToEmpty = 1f / (idle * DAY);

			// Fast enough that hunger is a real pressure within a session, slow enough that no
			// genome starves before it can plausibly find anything to eat. The upper bound is the
			// one that matters: a creature that takes a week to get hungry never participates in
			// the food web at all, and its lineage would drift with no selection acting on it.
			assertTrue(daysToEmpty > 0.15f,
					"genome empties in " + daysToEmpty + " days — too fast to survive");
			assertTrue(daysToEmpty < 6f,
					"genome empties in " + daysToEmpty + " days — hunger never bites");
		}
	}

	@Test
	void restingIsCheaperThanMovingWhichIsCheaperThanSprinting() {
		Random random = new Random(99);
		for (int trial = 0; trial < 50; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);

			float rest = EnergyBudget.drainPerTick(genome, plan, EnergyBudget.Activity.RESTING);
			float idle = EnergyBudget.drainPerTick(genome, plan, EnergyBudget.Activity.IDLE);
			float move = EnergyBudget.drainPerTick(genome, plan, EnergyBudget.Activity.MOVING);
			float sprint = EnergyBudget.drainPerTick(genome, plan, EnergyBudget.Activity.SPRINTING);

			// Sleeping through the unproductive half of the day has to actually save energy, or
			// RestGoal is a behaviour with no ecological function.
			assertTrue(rest < idle, "resting should cost less than standing");
			assertTrue(idle < move, "standing should cost less than walking");
			assertTrue(move < sprint, "walking should cost less than running");
		}
	}

	/**
	 * The invariant the whole predation loop rests on.
	 * <p>
	 * If eating an entire prey animal cannot lift a predator from hunting-hungry to satisfied, then
	 * {@code wantsToHunt()} never goes false and the predator kills continuously — which is the
	 * original bug, reintroduced through the numbers rather than through the logic. It would look
	 * exactly like the fix not working, with no error anywhere to find.
	 */
	@Test
	void oneWholePreyAnimalSatisfiesItsPredator() {
		Random random = new Random(7);
		int pairs = 0;
		for (int trial = 0; trial < TRIALS; trial++) {
			BodyPlan predator = BodyPlanBuilder.build(Genome.random(random));
			BodyPlan prey = BodyPlanBuilder.build(Genome.random(random));

			// Only pairings a predator would actually take on. The lower bound is the load-bearing
			// one: without it this assertion fails for a large hunter against very small prey,
			// which is precisely the case where a predator kills forever and stays hungry.
			if (!EnergyBudget.isWorthHunting(predator.mass, prey.mass)) continue;
			pairs++;

			float gain = EnergyBudget.carcassNutrition(prey)
					* EnergyBudget.energyPerNutrition(predator);
			float after = EnergyBudget.HUNT_THRESHOLD + gain;

			assertTrue(after > EnergyBudget.FORAGE_THRESHOLD,
					String.format("predator mass %.3f eating prey mass %.3f reaches only %.2f — "
									+ "it would still be hungry, and would keep killing",
							predator.mass, prey.mass, after));
		}
		assertTrue(pairs > 50, "not enough valid predator/prey pairings to be meaningful: " + pairs);
	}

	/**
	 * The herbivore equivalent. A plant-eater that burns energy faster than it can crop has no
	 * viable strategy at all, and every herbivore lineage starves regardless of predation.
	 */
	@Test
	void grazingOutpacesTheDrainItCosts() {
		Random random = new Random(31337);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);

			// GrazeGoal takes one mouthful per 20-tick chew cycle while standing still.
			float gainPerTick = EnergyBudget.mouthfulValue(plan) / 20f;
			float costPerTick = EnergyBudget.drainPerTick(genome, plan, EnergyBudget.Activity.IDLE);

			assertTrue(gainPerTick > costPerTick * 3f,
					String.format("mass %.3f gains %.6f/tick grazing but burns %.6f/tick — "
							+ "it cannot eat fast enough to live", plan.mass, gainPerTick, costPerTick));
		}
	}

	@Test
	void largerAnimalsGetLessFromTheSameFood() {
		Random random = new Random(2718);
		BodyPlan small = null, large = null;
		for (int trial = 0; trial < TRIALS && (small == null || large == null); trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			if (plan.mass < 0.08f) small = plan;
			if (plan.mass > 0.5f) large = plan;
		}
		assertNotNull(small, "no small-bodied genome found to compare");
		assertNotNull(large, "no large-bodied genome found to compare");

		// This asymmetry is what stops a large predator living indefinitely on tiny prey, and what
		// makes a big herbivore have to clear far more ground. Without it, mass is free.
		assertTrue(EnergyBudget.energyPerNutrition(large) < EnergyBudget.energyPerNutrition(small),
				"a carcass should be worth less to a larger animal");
		assertTrue(EnergyBudget.mouthfulValue(large) < EnergyBudget.mouthfulValue(small),
				"a mouthful should be worth less to a larger animal");
	}

	@Test
	void pursuitIsAlwaysBoundedAndAlwaysWorthAttempting() {
		Random random = new Random(555);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);

			int chase = EnergyBudget.chaseBudgetTicks(genome);
			// Long enough to catch something, short enough that prey can outlast it. An unbounded
			// chase is a guaranteed kill with a delay, which is how a valley got stripped.
			assertTrue(chase >= 20, "chase budget " + chase + " ticks is too short to catch anything");
			assertTrue(chase <= 400, "chase budget " + chase + " ticks is effectively unbounded");

			int cooldown = EnergyBudget.failedHuntCooldown(genome);
			// The cooldown is what stops the targeting goal re-acquiring the animal that just
			// outran it on the following tick, turning one bounded chase into an endless series.
			assertTrue(cooldown > 0, "a failed hunt must stand the predator down for some time");
		}
	}

	@Test
	void generationsAreShortEnoughToObserve() {
		Random random = new Random(8080);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);

			float maturityDays = EnergyBudget.maturityTicks(genome) / DAY;
			float broodDays = EnergyBudget.breedingInterval(genome) / DAY;

			// ROADMAP targets a visible trait shift within one to two in-game days of a sustained
			// pressure change. Generation length is the term that decides whether that is possible
			// at all — a species that takes a week to mature cannot demonstrate selection to
			// anybody who is watching.
			assertTrue(maturityDays > 0.2f, "maturity in " + maturityDays + " days is instant");
			assertTrue(maturityDays < 2.5f, "maturity in " + maturityDays + " days is too slow to watch");
			assertTrue(broodDays > 0.2f, "broods every " + broodDays + " days is a population explosion");
			assertTrue(broodDays < 2.5f, "broods every " + broodDays + " days cannot replace losses");
		}
	}
}
