package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.ecology.region.LineageRecord;
import dev.jsz.primordia.ecology.region.RegionFounder;
import dev.jsz.primordia.ecology.region.RegionNeighbourhood;
import dev.jsz.primordia.ecology.region.RegionPos;
import dev.jsz.primordia.ecology.region.RegionRecord;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the cave crawler: the one archetype defined by where it lives rather than by what its body
 * is for.
 * <p>
 * Almost everything here is a band in {@link Archetype}, and a band is easy to write and easy to
 * silently lose — a later edit that reorders the switch or drops a line leaves a creature that is
 * still perfectly valid, just no longer a cave animal. These check the traits the rest of the mod
 * actually keys off: {@link Gene#SUBTERRANEAN} decides where the ledger places them, mass and leg
 * count decide whether they can climb, and the glow is the reason to go looking.
 */
class CaveCrawlerTest {
	private static final int TRIALS = 200;

	private static final class FakeWorld implements RegionNeighbourhood {
		final Map<Long, RegionRecord> records = new HashMap<>();

		@Override
		public RegionRecord existing(RegionPos pos) {
			return records.get(pos.key());
		}
	}

	@Test
	void everyCaveCrawlerIsCommittedToTheDark() {
		Random random = new Random(808);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Archetype.CAVE_CRAWLER.create(random);

			// The locus the region ledger reads to decide where to put them. Below the threshold
			// they are placed on the surface, in daylight, with none of the reasons they look the
			// way they do.
			assertTrue(Archetype.isSubterranean(genome), String.format(
					"a cave crawler rolled SUBTERRANEAN %.2f, under the %.2f threshold",
					genome.raw(Gene.SUBTERRANEAN), Archetype.SUBTERRANEAN_THRESHOLD));

			assertTrue(genome.raw(Gene.NOCTURNALITY) > 0.5f, "a cave crawler sleeps at night");
		}
	}

	@Test
	void everyCaveCrawlerGlows() {
		Random random = new Random(909);
		for (int trial = 0; trial < TRIALS; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.CAVE_CRAWLER.create(random));
			// Bioluminescence is a threshold trait — below the cut a creature does not glow at all,
			// and the whole point of these is the light moving on a cave wall.
			assertTrue(plan.palette.glowStrength > 0f,
					"a cave crawler came out dark, which is the one thing it cannot be");
		}
	}

	@Test
	void everyCaveCrawlerIsSmallAndManyLeggedEnoughToClimb() {
		Random random = new Random(1010);
		for (int trial = 0; trial < TRIALS; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.CAVE_CRAWLER.create(random));

			// The entity's fallback climbing rule is four or more legs under 0.38 mass. Cave
			// dwellers bypass that on their genome, but they should satisfy it on their own
			// proportions too — a climber that only climbs because of a special case is a climber
			// that stops the moment the special case is refactored.
			assertTrue(plan.legs.length >= 4, String.format(
					"a cave crawler grew %d legs; a climber wants contact points", plan.legs.length));
			assertTrue(plan.mass <= 0.38f, String.format(
					"a cave crawler massed %.3f, too heavy for the climbing rule", plan.mass));
		}
	}

	@Test
	void caveCrawlersAreNeverRolledAsSurfaceFauna() {
		Random random = new Random(1111);
		for (int trial = 0; trial < 2000; trial++) {
			assertNotEquals(Archetype.CAVE_CRAWLER, Archetype.randomSurface(random),
					"a cave crawler was rolled into the surface fauna");
		}

		// And the biome founder, which is what the region ledger actually calls.
		for (int trial = 0; trial < 400; trial++) {
			net.minecraft.util.math.random.Random mc =
					net.minecraft.util.math.random.Random.create(random.nextLong());
			Genome genome = Genome.createForBiome(mc, "plains");
			assertFalse(Archetype.isSubterranean(genome),
					"a biome founder came out committed to living underground");
		}
	}

	/**
	 * A lush cave carries a real population, whatever the surface above it is.
	 * <p>
	 * The cave fauna is founded from the cave biome layer, not from the weather — a lush cave under a
	 * badlands is still a lush cave. Pulling their preferences toward the desert overhead would have
	 * selection slowly drive them out of the only place they can live.
	 */
	@Test
	void lushCavesUnderAnyBiomeCarryTheirFauna() {
		int withCaves = 0;
		for (int trial = 0; trial < 40; trial++) {
			FakeWorld world = new FakeWorld();
			RegionPos pos = new RegionPos(trial * 13, trial * 7);
			RegionRecord record = new RegionRecord(pos);
			record.seed = pos.seed(2026);
			world.records.put(pos.key(), record);

			// A hostile surface over a rich cave, which is the case that has to work.
			RegionFounder.found(world, record,
					new RegionFounder.Climate(0.95f, 0.04f, 0.10f, 1.0f), "badlands", 0);

			for (LineageRecord lineage : record.lineages) {
				if (Archetype.isSubterranean(lineage.meanGenome())) {
					withCaves++;
					break;
				}
			}
		}
		// A tendency, and said so: a cave lineage can die out during pre-history like any other.
		// What would be wrong is lush caves being empty as a rule.
		assertTrue(withCaves > 30, String.format(
				"only %d of 40 lush-cave regions kept their fauna through pre-history", withCaves));
	}

	/**
	 * Plain stone caves get stragglers, not a fauna.
	 * <p>
	 * This is the difference that makes a lush cave worth finding. If every cave in the world held
	 * the same population, the biome would be decoration — and if plain caves held none at all, the
	 * animals would be a spawn table rather than a population that spreads from where it thrives.
	 */
	@Test
	void ordinaryCavesAreFarSparserThanLushOnes() {
		float lushTotal = 0f;
		float plainTotal = 0f;

		for (int trial = 0; trial < 30; trial++) {
			lushTotal += cavePopulation(trial, 1.0f);
			plainTotal += cavePopulation(trial, 0.18f);
		}

		assertTrue(lushTotal > 0f, "lush caves came out empty");
		assertTrue(plainTotal < lushTotal * 0.5f, String.format(
				"plain caves hold %.1f against lush %.1f — the biome is barely making a difference",
				plainTotal, lushTotal));
	}

	private static float cavePopulation(int trial, float caveRichness) {
		FakeWorld world = new FakeWorld();
		RegionPos pos = new RegionPos(trial * 19, trial * 23);
		RegionRecord record = new RegionRecord(pos);
		record.seed = pos.seed(4242);
		world.records.put(pos.key(), record);

		RegionFounder.found(world, record,
				new RegionFounder.Climate(0.5f, 0.5f, 0.6f, caveRichness), "plains", 0);

		float total = 0f;
		for (LineageRecord lineage : record.lineages) {
			if (Archetype.isSubterranean(lineage.meanGenome())) total += lineage.total();
		}
		return total;
	}

	/**
	 * The gradient the biome is supposed to produce: lush caves teem, plain stone gets stragglers.
	 * <p>
	 * Both ends matter and both were wrong at different times. Scaled one way, a plain cave sat
	 * below replacement and measured out at zero regions in sixty holding any cave fauna at all —
	 * "occasional" has to mean occasional and not absent. Scaled the other, every cave in the world
	 * held the same population and the biome was decoration.
	 * <p>
	 * Measured values are roughly 65 individuals per lush region, 10 for dripstone and under one for
	 * plain stone; the bounds below are wide around those.
	 */
	@Test
	void cavePopulationFollowsTheCaveBiome() {
		float lush = 0f, middling = 0f, plain = 0f;
		int lushRegions = 0, plainRegions = 0;
		int n = 40;

		for (int trial = 0; trial < n; trial++) {
			float l = cavePopulation(trial, 1.0f);
			float m = cavePopulation(trial, 0.45f);
			float p = cavePopulation(trial, 0.18f);
			lush += l;
			middling += m;
			plain += p;
			if (l > 0.5f) lushRegions++;
			if (p > 0.5f) plainRegions++;
		}

		assertEquals(n, lushRegions, "a lush cave came out empty");
		assertTrue(lush / n > 20f, String.format(
				"lush caves hold only %.1f each — that is not a population", lush / n));

		// Present sometimes, and a long way short of a colony. Both halves are the point.
		assertTrue(plainRegions > 0, "plain stone caves hold nothing anywhere — not even occasional");
		assertTrue(plainRegions < n * 3 / 4, String.format(
				"plain stone caves hold fauna in %d of %d regions — that is a fixture, not a wanderer",
				plainRegions, n));
		assertTrue(plain / n < lush / n * 0.1f, String.format(
				"plain caves hold %.1f against lush %.1f — the biome is barely making a difference",
				plain / n, lush / n));

		// And the middle sits in the middle, so this is a gradient rather than a switch.
		assertTrue(middling / n > plain / n && middling / n < lush / n,
				"dripstone caves are not between plain stone and lush");
	}

	/**
	 * A world founded before cave fauna existed must still get it.
	 * <p>
	 * Founding runs once per region and never again, so anything added afterwards reaches only
	 * ground the player has never walked on. In a save that has been played in that means the
	 * feature is missing from everywhere they actually go — which is exactly how this was reported,
	 * as "there are no creatures underground".
	 */
	@Test
	void anAlreadyFoundedRegionGainsCaveFaunaOnUpgrade() {
		FakeWorld world = new FakeWorld();
		RegionPos pos = new RegionPos(5, 5);
		RegionRecord record = new RegionRecord(pos);
		record.seed = pos.seed(31337);
		world.records.put(pos.key(), record);

		RegionFounder.found(world, record,
				new RegionFounder.Climate(0.5f, 0.6f, 0.7f, 1.0f), "plains", 0);

		// Wind it back to how a record written by the previous build looks: founded, but with no
		// cave fauna and no version stamp.
		record.lineages.removeIf(l -> Archetype.isSubterranean(l.meanGenome()));
		record.version = 0;
		record.caveRichness = 0.18f;

		RegionFounder.upgrade(record, new RegionFounder.Climate(0.5f, 0.6f, 0.7f, 1.0f));

		assertEquals(RegionFounder.VERSION, record.version, "the record was not brought forward");
		assertEquals(1.0f, record.caveRichness, 1e-4f, "cave richness was not resampled");
		assertTrue(record.lineages.stream().anyMatch(l -> Archetype.isSubterranean(l.meanGenome())),
				"an upgraded region still has no cave fauna");
	}

	@Test
	void upgradingIsIdempotentAndNeverResurrectsTheExtinct() {
		FakeWorld world = new FakeWorld();
		RegionPos pos = new RegionPos(6, 6);
		RegionRecord record = new RegionRecord(pos);
		record.seed = pos.seed(4242);
		world.records.put(pos.key(), record);

		RegionFounder.found(world, record,
				new RegionFounder.Climate(0.5f, 0.6f, 0.7f, 1.0f), "plains", 0);
		int before = record.lineages.size();

		// Already current: nothing should happen, however many times it is called.
		for (int i = 0; i < 5; i++) {
			RegionFounder.upgrade(record, new RegionFounder.Climate(0.5f, 0.6f, 0.7f, 1.0f));
		}
		assertEquals(before, record.lineages.size(),
				"upgrade added lineages to a record that was already current");

		// A region whose cave fauna genuinely died out is at the current version, so it stays dead.
		// Extinction has to be permanent or nothing that happens in the world has any weight.
		record.lineages.removeIf(l -> Archetype.isSubterranean(l.meanGenome()));
		RegionFounder.upgrade(record, new RegionFounder.Climate(0.5f, 0.6f, 0.7f, 1.0f));
		assertFalse(record.lineages.stream().anyMatch(l -> Archetype.isSubterranean(l.meanGenome())),
				"upgrade resurrected a lineage that had gone extinct");
	}

	@Test
	void lushCaveFaunaSurvivesABarrenSurface() {
		// The point of the detritus supply: a cave population must not starve because the desert
		// above it has no vegetation. Without it, every cave under a badlands empties.
		assertTrue(cavePopulationUnderDesert() > 1f,
				"a lush cave under a desert emptied — the detritus supply is not carrying them");
	}

	private static float cavePopulationUnderDesert() {
		FakeWorld world = new FakeWorld();
		RegionPos pos = new RegionPos(-40, 17);
		RegionRecord record = new RegionRecord(pos);
		record.seed = pos.seed(77);
		world.records.put(pos.key(), record);

		RegionFounder.found(world, record,
				new RegionFounder.Climate(0.9f, 0.05f, 0.08f, 1.0f), "desert", 0);

		float total = 0f;
		for (LineageRecord lineage : record.lineages) {
			if (Archetype.isSubterranean(lineage.meanGenome())) total += lineage.total();
		}
		return total;
	}
}
