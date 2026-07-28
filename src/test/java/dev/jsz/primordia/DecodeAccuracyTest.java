package dev.jsz.primordia;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.SampleData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the progression the lab is built around: a first encounter tells you almost nothing, and
 * repeated study resolves it.
 * <p>
 * Worth testing rather than eyeballing because the failure is quiet in both directions. Too
 * generous and the first decode already reads out exact figures, which makes the sequencer a slow
 * scanner and the reference library pointless. Too stingy and no realistic amount of play ever
 * reaches exact numbers, so the player is told to keep working with no evidence that working helps.
 */
class DecodeAccuracyTest {

	@Test
	void theFirstSpecimenOfASpeciesResolvesAlmostNothing() {
		DecodeAccuracy first = DecodeAccuracy.resolve(0, 1f);
		assertEquals(DecodeAccuracy.UNKNOWN, first,
				"the first decode of a lineage should not resolve anything");
		assertEquals("???", first.describeFraction(0.73f),
				"an unreferenced decode leaked a readable value");
		assertEquals("???", first.describeMeasure(2.4f, "m"),
				"an unreferenced decode leaked a readable measurement");
	}

	@Test
	void studyingALineageSharpensItMonotonically() {
		DecodeAccuracy previous = null;
		for (int decoded = 0; decoded <= 20; decoded++) {
			DecodeAccuracy level = DecodeAccuracy.resolve(decoded, 1f);
			if (previous != null) {
				assertTrue(level.ordinal() >= previous.ordinal(),
						"accuracy went backwards between " + (decoded - 1) + " and " + decoded
								+ " decodes: " + previous + " -> " + level);
			}
			previous = level;
		}
		assertEquals(DecodeAccuracy.COMPLETE, DecodeAccuracy.resolve(20, 1f),
				"twenty specimens still did not fully characterise a species");
	}

	@Test
	void aWellStudiedSpeciesReportsExactFigures() {
		DecodeAccuracy complete = DecodeAccuracy.resolve(50, 1f);
		assertEquals("73%", complete.describeFraction(0.73f));
		// The vague forms must genuinely differ from the exact one, or the progression is cosmetic.
		assertNotEquals(complete.describeFraction(0.73f),
				DecodeAccuracy.resolve(1, 1f).describeFraction(0.73f));
		assertNotEquals(complete.describeFraction(0.73f),
				DecodeAccuracy.resolve(4, 1f).describeFraction(0.73f));
	}

	@Test
	void aDegradedSampleCostsALevelButNeverAllOfThem() {
		for (int decoded = 0; decoded <= 30; decoded++) {
			DecodeAccuracy fresh = DecodeAccuracy.resolve(decoded, 1f);
			DecodeAccuracy rotten = DecodeAccuracy.resolve(decoded, 0f);
			assertTrue(rotten.ordinal() <= fresh.ordinal(),
					"a degraded sample decoded better than a fresh one at " + decoded);
			assertTrue(fresh.ordinal() - rotten.ordinal() <= 1,
					"a degraded sample lost more than one level at " + decoded);
			assertNotNull(rotten, "a degraded sample produced no level at all");
		}
	}

	@Test
	void theCountdownToTheNextLevelReachesZeroExactlyWhenItArrives() {
		for (int decoded = 0; decoded < 30; decoded++) {
			DecodeAccuracy level = DecodeAccuracy.resolve(decoded, 1f);
			int needed = level.decodesUntilNextLevel(decoded);
			if (needed == 0) continue;

			// Claiming "n more" has to actually be true, or the hint is a lie the player can check.
			DecodeAccuracy afterEnough = DecodeAccuracy.resolve(decoded + needed, 1f);
			assertTrue(afterEnough.ordinal() > level.ordinal(),
					"at " + decoded + " decoded the report promised " + needed
							+ " more would improve it, and it did not");
			DecodeAccuracy oneShort = DecodeAccuracy.resolve(decoded + needed - 1, 1f);
			assertEquals(level, oneShort,
					"at " + decoded + " decoded the promise of " + needed + " was pessimistic");
		}
	}

	// ------------------------------------------------------------------ sample lifecycle

	@Test
	void aSampleDecaysOverItsShelfLifeAndThenStops() {
		Genome genome = Genome.random(new Random(11));
		SampleData fresh = SampleData.of(genome, 1000L);

		assertEquals(1f, fresh.freshness(1000L), 1e-4f, "a new sample was not fully fresh");
		assertEquals(0.5f, fresh.freshness(1000L + SampleData.SHELF_LIFE / 2), 0.01f,
				"half a shelf life did not read as half viable");
		assertEquals(0f, fresh.freshness(1000L + SampleData.SHELF_LIFE), 1e-4f);
		// Past its life it is spent, not negative — a negative would invert every comparison
		// downstream and make an ancient sample look pristine.
		assertEquals(0f, fresh.freshness(1000L + SampleData.SHELF_LIFE * 10), 1e-4f,
				"an ancient sample reported something other than spent");
	}

	@Test
	void preservationStopsTheClockAndThawingRestartsIt() {
		Genome genome = Genome.random(new Random(12));
		SampleData sample = SampleData.of(genome, 0L);

		SampleData cold = sample.preserved();
		assertTrue(cold.isPreserved());
		assertEquals(1f, cold.freshness(SampleData.SHELF_LIFE * 5), 1e-4f,
				"a preserved sample decayed in storage");

		// Coming back out has to restart the clock, or one trip through a case makes a sample
		// immortal and cold storage becomes a laundering step rather than a place things live.
		SampleData thawed = cold.thawed(500L);
		assertFalse(thawed.isPreserved());
		assertEquals(1f, thawed.freshness(500L), 1e-4f);
		assertEquals(0f, thawed.freshness(500L + SampleData.SHELF_LIFE), 1e-4f,
				"a thawed sample did not resume decaying");
	}

	@Test
	void aSampleKeepsTheExactGenomeItWasTakenFrom() {
		Random random = new Random(13);
		for (int i = 0; i < 100; i++) {
			Genome original = Genome.random(random);
			SampleData sample = SampleData.of(original, 0L);
			// The whole pipeline is worthless if the specimen that comes out is not the one that
			// went in — the report names an individual the player can go back and check.
			assertEquals(original, sample.genome(), "a sample altered the genome it carried");
			assertEquals(SampleData.shortLineage(original), sample.lineageHex());
		}
	}
}
