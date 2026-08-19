package dev.jsz.primordia;

import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.entity.goal.RestGoal;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the sleep schedule to the world clock.
 * <p>
 * The behaviour a player checks this with is {@code /time set night}: the sun moves, and the animals
 * that sleep at night should lie down. That has to be a function of the time of day and nothing
 * else — the moment anything per-entity has to elapse first, setting the clock appears to do
 * nothing, which is exactly how this was reported.
 */
class SleepScheduleTest {
	private static final int DAY = 24000;
	/** What {@code /time set day} and {@code /time set night} actually write. */
	private static final int NOON = 6000;
	private static final int NIGHT = 13000;

	private static final float DIURNAL = 0.2f;
	private static final float NOCTURNAL = 0.8f;

	@Test
	void settingTheWorldToNightPutsNightSleepersToSleep() {
		Random random = new Random(4);
		int resting = 0;
		int trials = 500;

		for (int i = 0; i < trials; i++) {
			long seed = random.nextLong();
			if (RestGoal.isRestingHour(NIGHT, DIURNAL, seed)) resting++;
			// And the nocturnal ones must be up, or "night" means nothing.
			assertFalse(RestGoal.isRestingHour(NIGHT, NOCTURNAL, seed),
					"a nocturnal creature was asleep at night");
		}
		// 13000 is a thousand ticks past dusk and the jitter is ±400, so every one of them should
		// already be down. A shortfall here means the stagger has grown wide enough to read as the
		// clock being ignored.
		assertEquals(trials, resting, "some day-active creatures were still awake at midnight");
	}

	@Test
	void settingTheWorldToDayPutsDaySleepersToSleep() {
		Random random = new Random(5);
		for (int i = 0; i < 500; i++) {
			long seed = random.nextLong();
			assertTrue(RestGoal.isRestingHour(NOON, NOCTURNAL, seed),
					"a nocturnal creature was awake at noon");
			assertFalse(RestGoal.isRestingHour(NOON, DIURNAL, seed),
					"a day-active creature was asleep at noon");
		}
	}

	/**
	 * The schedule must read the clock, not count ticks of its own. Feeding it a time from a
	 * hundred days later must give the same answer as the same hour today — which is what makes
	 * {@code /time set} work, and what makes a creature that just spawned agree with one that has
	 * been alive for a week.
	 */
	@Test
	void onlyThePositionWithinTheDayMatters() {
		Random random = new Random(6);
		for (int i = 0; i < 300; i++) {
			long seed = random.nextLong();
			float nocturnality = random.nextFloat();
			int hour = random.nextInt(DAY);

			boolean today = RestGoal.isRestingHour(hour, nocturnality, seed);
			assertEquals(today, RestGoal.isRestingHour(hour + DAY * 100L, nocturnality, seed),
					"the schedule drifted across days");
			// Negative times can reach this if a world's clock is wound backwards.
			assertEquals(today, RestGoal.isRestingHour(hour - DAY * 7L, nocturnality, seed),
					"the schedule broke on a wound-back clock");
		}
	}

	@Test
	void aHerdStaggersRatherThanDroppingInUnison() {
		Random random = new Random(7);
		// Right on dusk, where the jitter is doing its work: some down, some still up.
		int down = 0;
		int trials = 400;
		for (int i = 0; i < trials; i++) {
			if (RestGoal.isRestingHour(12500, DIURNAL, random.nextLong())) down++;
		}
		assertTrue(down > trials / 8 && down < trials * 7 / 8,
				"the whole herd changed state on the same tick: " + down + " of " + trials);
	}

	@Test
	void everyGenomeSpendsRoughlyHalfItsDayAsleep() {
		Random random = new Random(8);
		for (int trial = 0; trial < 50; trial++) {
			long seed = random.nextLong();
			float nocturnality = random.nextFloat();

			int resting = 0;
			for (int t = 0; t < DAY; t += 20) {
				if (RestGoal.isRestingHour(t, nocturnality, seed)) resting++;
			}
			float share = resting / (float) (DAY / 20);
			// Minecraft's night is a little under half the day, so this is not exactly 0.5. The
			// point is that nothing is awake or asleep permanently.
			assertTrue(share > 0.3f && share < 0.7f,
					"a creature rests " + share + " of the day, which is not a cycle");
		}
	}

	@Test
	void restThresholdsLeaveRoomForACreatureToActuallySleep() {
		// Sleeping is gated above REST_THRESHOLD and abandoned below WAKE_HUNGRY. If those cross,
		// or sit where energy rarely reaches, nothing ever sleeps — which is the bug this replaced.
		assertTrue(EnergyBudget.WAKE_HUNGRY < EnergyBudget.REST_THRESHOLD,
				"a creature would wake the instant it fell asleep");
		assertTrue(EnergyBudget.REST_THRESHOLD < EnergyBudget.HUNT_THRESHOLD,
				"only creatures too full to hunt could sleep");
		assertTrue(EnergyBudget.WAKE_HUNGRY > EnergyBudget.STARVING,
				"a creature would sleep until it starved to death");
	}

	/**
	 * How much of the world is awake when the player is.
	 * <p>
	 * {@link dev.jsz.primordia.genome.Gene#NOCTURNALITY} reads as a magnitude and behaves as a
	 * switch, so where its cut sits decides the balance of the whole world's activity rather than
	 * one animal's habits. At the midpoint the draw split the population in half and half of
	 * everything alive was asleep through the daylight a player spends their time in. The cut is
	 * set from the fraction wanted, so this checks the fraction rather than the cut — the number in
	 * the enum is free to be retuned, the balance it buys is not.
	 */
	@Test
	void mostOfAPopulationKeepsToTheDay() {
		Random random = new Random(31337);
		int trials = 20000;
		int nocturnal = 0;

		for (int i = 0; i < trials; i++) {
			Genome genome = Archetype.randomSurface(random).create(random);
			// Asked the way the goal asks it, rather than against a literal of this test's own.
			if (!RestGoal.isRestingHour(NIGHT, genome.raw(Gene.NOCTURNALITY), genome.seed())) nocturnal++;
		}

		float share = (float) nocturnal / trials;
		assertTrue(share > 0.13f && share < 0.28f,
				String.format("%.1f%% of a surface population is nocturnal — the world is meant to be "
						+ "roughly a fifth night shift, and mostly awake in daylight", share * 100f));
	}
}
