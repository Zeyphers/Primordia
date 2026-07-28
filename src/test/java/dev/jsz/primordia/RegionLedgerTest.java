package dev.jsz.primordia;

import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.ecology.region.LineageRecord;
import dev.jsz.primordia.ecology.region.RegionFounder;
import dev.jsz.primordia.ecology.region.RegionMaterialiser;
import dev.jsz.primordia.ecology.region.RegionNeighbourhood;
import dev.jsz.primordia.ecology.region.RegionPos;
import dev.jsz.primordia.ecology.region.RegionRecord;
import dev.jsz.primordia.ecology.region.RegionSimulation;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzzes the region ledger and the population model that runs on it.
 * <p>
 * These are the invariants with no visual tell at all, and they are worse than the geometry ones for
 * it: a wrong ecology still <i>looks</i> like an ecology. Animals walk about and eat things, and the
 * failure only shows up as a world that is subtly emptier every hour, by which point there is
 * nothing left to debug against.
 */
class RegionLedgerTest {

	/** A ledger with no server behind it. Migration and inherited founders only ever read. */
	private static final class FakeWorld implements RegionNeighbourhood {
		final Map<Long, RegionRecord> records = new HashMap<>();

		@Override
		public RegionRecord existing(RegionPos pos) {
			return records.get(pos.key());
		}

		RegionRecord create(RegionPos pos, long seed) {
			RegionRecord record = new RegionRecord(pos);
			record.seed = seed;
			records.put(pos.key(), record);
			return record;
		}
	}

	// ------------------------------------------------------------------ the leak

	/**
	 * The single most important test in the ecology, and the one to write first when touching any
	 * of this.
	 * <p>
	 * A population that loses a fraction of a percent every time it is materialised and absorbed is
	 * completely invisible in play — the numbers all look plausible at every individual moment — and
	 * it empties the world over an afternoon of walking around. Nothing else here would catch it.
	 */
	@Test
	void populationSurvivesAThousandLoadAndUnloadCycles() {
		LineageRecord lineage = new LineageRecord();
		lineage.count = 37.4f;
		float original = lineage.total();

		for (int cycle = 0; cycle < 1000; cycle++) {
			// Materialise: take whole animals out of the record, up to the entity budget.
			int taken = 0;
			while (taken < 24 && lineage.take()) taken++;

			// The fraction must survive being left behind. Rounding it away here is exactly how
			// small populations used to evaporate: 1.4 becomes 1, then 1 becomes 0.
			assertTrue(lineage.count >= 0f, "count went negative on cycle " + cycle);

			// Absorb: every one of them despawns and comes home.
			for (int i = 0; i < taken; i++) lineage.give();

			assertEquals(original, lineage.total(), 1e-3f,
					"population drifted after " + (cycle + 1) + " load/unload cycles");
		}
	}

	/**
	 * Every lineage a region holds must actually get animals on the ground.
	 * <p>
	 * This one was found in play rather than by testing, which is the whole reason it is here now.
	 * The materialiser walked the lineage list spawning as many of each as it could, so the first
	 * lineage took the entire entity budget and a region recorded as holding four species showed ten
	 * individuals of one. The record was right the whole time — {@code /primordia region} would have
	 * listed all four — and every other test passed, because nothing was lost or double-counted.
	 * <p>
	 * The tell in game was the favourite food. {@code TamingPreference} keys bait off the lineage id,
	 * so every animal for a hundred blocks wanting sugar cane meant every animal for a hundred blocks
	 * was one lineage.
	 */
	@Test
	void everyLineagePresentGetsAShareOfTheEntityBudget() {
		int budget = RegionMaterialiser.ENTITY_BUDGET;
		// A typical trophic pyramid: plenty of grazers, a middle, and a couple of predators.
		float[] populations = {40f, 18.4f, 9f, 2f};

		int[] quota = RegionMaterialiser.allocate(populations, budget);

		int total = 0;
		for (int i = 0; i < quota.length; i++) {
			assertTrue(quota[i] >= 1,
					"lineage " + i + " with population " + populations[i] + " got no representation");
			total += quota[i];
		}
		assertTrue(total <= budget, "allocation overran the entity budget: " + total);

		// Still weighted, not flat — a region of mostly grazers should look like mostly grazers.
		assertTrue(quota[0] > quota[3], "the most abundant lineage was not the most represented");
	}

	@Test
	void allocationNeverPlacesMoreAnimalsThanTheRecordHolds() {
		Random random = new Random(88);
		for (int trial = 0; trial < 500; trial++) {
			int n = 1 + random.nextInt(8);
			float[] populations = new float[n];
			for (int i = 0; i < n; i++) {
				populations[i] = random.nextFloat() * 30f;
			}
			int room = random.nextInt(RegionMaterialiser.ENTITY_BUDGET + 1);

			int[] quota = RegionMaterialiser.allocate(populations, room);

			int total = 0;
			for (int i = 0; i < n; i++) {
				assertTrue(quota[i] >= 0, "negative quota");
				// Placing more than the record holds would conjure animals out of nothing, and
				// take() would silently refuse — leaving live entities the ledger never debited.
				assertTrue(quota[i] <= Math.floor(populations[i]),
						"allocated " + quota[i] + " from a population of " + populations[i]);
				total += quota[i];
			}
			assertTrue(total <= room, "allocation overran the budget: " + total + " > " + room);
		}
	}

	@Test
	void moreLineagesThanSlotsShowsTheMostAbundant() {
		// Six lineages, three slots. The three biggest should be the ones that appear.
		float[] populations = {1f, 30f, 2f, 25f, 1f, 20f};
		int[] quota = RegionMaterialiser.allocate(populations, 3);

		assertEquals(1, quota[1]);
		assertEquals(1, quota[3]);
		assertEquals(1, quota[5]);
		assertEquals(0, quota[0]);
		assertEquals(0, quota[2]);
		assertEquals(0, quota[4]);
	}

	@Test
	void takeRefusesToSplitAnAnimalInHalf() {
		LineageRecord lineage = new LineageRecord();
		lineage.count = 2.9f;

		assertTrue(lineage.take());
		assertTrue(lineage.take());
		// 0.9 of an animal is not an animal. It stays in the record until something is born.
		assertFalse(lineage.take(), "took an individual out of a fractional remainder");
		assertEquals(0.9f, lineage.count, 1e-4f);
	}

	// ------------------------------------------------------------- serialisation

	@Test
	void regionCoordinatesRoundTripThroughTheirPackedKey() {
		Random random = new Random(11);
		for (int trial = 0; trial < 500; trial++) {
			RegionPos pos = new RegionPos(random.nextInt() >> 8, random.nextInt() >> 8);
			assertEquals(pos, RegionPos.fromKey(pos.key()));
		}
	}

	@Test
	void neighbouringRegionsGetUncorrelatedSeeds() {
		long worldSeed = 987654321L;
		RegionPos origin = new RegionPos(40, -17);
		long here = origin.seed(worldSeed);
		for (RegionPos neighbour : origin.neighbours()) {
			assertNotEquals(here, neighbour.seed(worldSeed));
		}
		// Reproducible from the world seed alone — the whole off-screen simulation rests on it.
		assertEquals(here, new RegionPos(40, -17).seed(worldSeed));
	}

	// -------------------------------------------------------------- the food web

	/**
	 * A founded region must be able to feed itself.
	 * <p>
	 * This is the complaint that started the whole design, checked at its source. The old spawner
	 * rolled a random archetype per individual, so a valley was as likely to come out as four
	 * predators with nothing to eat as anything viable — and no amount of restraint in the
	 * creatures' behaviour rescues a composition that was never survivable.
	 */
	@Test
	void everyFoundedRegionHasAWorkingFoodChain() {
		Random random = new Random(1234);
		int checked = 0;

		for (int trial = 0; trial < 120; trial++) {
			FakeWorld world = new FakeWorld();
			RegionPos pos = new RegionPos(trial * 37, trial * 11);
			RegionRecord record = world.create(pos, pos.seed(4242));

			RegionFounder.Climate climate = new RegionFounder.Climate(
					random.nextFloat(), random.nextFloat(), 0.15f + random.nextFloat() * 0.85f);
			RegionFounder.found(world, record, climate, "plains", 0);

			// A region can legitimately end up empty if its pre-history went badly, but it must not
			// end up as predators alone.
			if (record.lineages.isEmpty()) continue;
			checked++;

			float herbivoreBiomass = 0f;
			float carnivoreBiomass = 0f;
			for (LineageRecord l : record.lineages) {
				if (l.meanOf(Gene.DIET) > 0.65f) carnivoreBiomass += l.biomass();
				else herbivoreBiomass += l.biomass();
			}

			if (carnivoreBiomass > 0f) {
				assertTrue(herbivoreBiomass > 0f,
						"region " + pos + " founded with hunters and nothing to hunt");
				// The pyramid is emergent rather than enforced after founding — predators starve
				// when prey run short — so this is a generous band, not the founding cap.
				assertTrue(carnivoreBiomass < herbivoreBiomass,
						String.format("region %s carries more predator than prey biomass (%.2f vs %.2f)",
								pos, carnivoreBiomass, herbivoreBiomass));
			}
		}
		assertTrue(checked > 60, "too few regions survived founding to be meaningful: " + checked);
	}

	@Test
	void aFoundedRegionIsAlreadyAdaptedToItsClimate() {
		FakeWorld world = new FakeWorld();
		RegionPos pos = new RegionPos(3, 3);
		RegionRecord record = world.create(pos, pos.seed(7));
		// A hot, dry region.
		RegionFounder.found(world, record, new RegionFounder.Climate(0.92f, 0.06f, 0.12f), "desert", 0);

		assertFalse(record.lineages.isEmpty(), "desert region founded empty");
		for (LineageRecord l : record.lineages) {
			assertTrue(l.meanOf(Gene.TEMP_PREFERENCE) > 0.5f,
					"a lineage that has lived in a desert for centuries prefers the cold");
			assertTrue(l.meanOf(Gene.HUMIDITY_PREFERENCE) < 0.5f,
					"a lineage that has lived in a desert for centuries prefers the wet");
		}
	}

	// --------------------------------------------------------------- the model

	/**
	 * Selection must not be a ratchet.
	 * <p>
	 * Every locus the model pushes on is pushed from both sides, and this checks the one where a
	 * one-way push was actually shipped: {@code SIZE} was driven by hunger alone, so across the few
	 * hundred steps of a region's pre-history it arrived pinned at minimum in every region in the
	 * world. The result was a world containing no large animals whatsoever — and nothing looked
	 * wrong, because a world of small animals is a perfectly plausible world.
	 * <p>
	 * The general failure is worth guarding as a class: a trait under monotone selection over a
	 * few hundred steps reaches an extreme and stays there, which destroys variety silently.
	 */
	@Test
	void selectionDoesNotPinTraitsAtTheirExtremes() {
		Gene[] pushed = {Gene.SIZE, Gene.METABOLISM, Gene.SPEED, Gene.STAMINA,
				Gene.ARMOR, Gene.FEAR, Gene.FECUNDITY};

		float[] lowest = new float[pushed.length];
		float[] highest = new float[pushed.length];
		java.util.Arrays.fill(lowest, 1f);
		java.util.Arrays.fill(highest, 0f);

		int sampled = 0;
		for (int trial = 0; trial < 60; trial++) {
			RegionRecord record = seededRegion(trial + 500);
			for (LineageRecord l : record.lineages) {
				sampled++;
				for (int g = 0; g < pushed.length; g++) {
					float v = l.meanOf(pushed[g]);
					lowest[g] = Math.min(lowest[g], v);
					highest[g] = Math.max(highest[g], v);
				}
			}
		}
		assertTrue(sampled > 40, "not enough lineages survived founding: " + sampled);

		for (int g = 0; g < pushed.length; g++) {
			assertTrue(highest[g] > 0.12f,
					pushed[g] + " is pinned at the bottom across every region (max " + highest[g] + ")");
			assertTrue(lowest[g] < 0.94f,
					pushed[g] + " is pinned at the top across every region (min " + lowest[g] + ")");
			assertTrue(highest[g] - lowest[g] > 0.10f,
					pushed[g] + " has collapsed to a single value across the world"
							+ " (" + lowest[g] + " to " + highest[g] + ")");
		}
	}

	/**
	 * A productive region should end up carrying heavier animals than a barren one.
	 * <p>
	 * This is the biogeography claim the README makes, checked rather than asserted. It is also the
	 * thing that broke when size selection was one-way: with every lineage driven to minimum, a
	 * jungle and a scree slope produced the same fauna.
	 */
	@Test
	void richRegionsCarryHeavierFaunaThanBarrenOnes() {
		float richTotal = 0f, barrenTotal = 0f;
		int richCount = 0, barrenCount = 0;

		for (int trial = 0; trial < 30; trial++) {
			FakeWorld world = new FakeWorld();
			RegionPos richPos = new RegionPos(trial, 0);
			RegionRecord rich = world.create(richPos, richPos.seed(31));
			RegionFounder.found(world, rich, new RegionFounder.Climate(0.6f, 0.9f, 0.95f), "jungle", 0);

			FakeWorld other = new FakeWorld();
			RegionPos barrenPos = new RegionPos(trial, 900);
			RegionRecord barren = other.create(barrenPos, barrenPos.seed(31));
			RegionFounder.found(other, barren, new RegionFounder.Climate(0.5f, 0.1f, 0.12f), "badlands", 0);

			for (LineageRecord l : rich.lineages) {
				richTotal += l.meanMass();
				richCount++;
			}
			for (LineageRecord l : barren.lineages) {
				barrenTotal += l.meanMass();
				barrenCount++;
			}
		}
		assertTrue(richCount > 20 && barrenCount > 20, "too few lineages to compare");

		float richMean = richTotal / richCount;
		float barrenMean = barrenTotal / barrenCount;
		assertTrue(richMean > barrenMean * 1.15f, String.format(
				"jungle fauna (mean mass %.3f) is no heavier than badlands fauna (%.3f)",
				richMean, barrenMean));
	}

	/**
	 * Determinism is a correctness requirement, not a nicety. Without it the simulation cannot be
	 * reproduced from a seed, cannot be tested, and two catch-ups over the same span disagree.
	 */
	@Test
	void integratingTheSameRegionTwiceGivesTheSameAnswer() {
		for (int trial = 0; trial < 20; trial++) {
			RegionRecord a = seededRegion(trial);
			RegionRecord b = seededRegion(trial);
			FakeWorld worldA = new FakeWorld();
			FakeWorld worldB = new FakeWorld();
			worldA.records.put(a.pos.key(), a);
			worldB.records.put(b.pos.key(), b);

			RegionSimulation.integrate(worldA, a, 60);
			RegionSimulation.integrate(worldB, b, 60);

			assertEquals(a.lineages.size(), b.lineages.size(), "lineage count diverged");
			assertEquals(a.vegetation, b.vegetation, 0f, "vegetation diverged");
			for (int i = 0; i < a.lineages.size(); i++) {
				LineageRecord la = a.lineages.get(i);
				LineageRecord lb = b.lineages.get(i);
				assertEquals(la.id, lb.id, "lineage identity diverged");
				assertEquals(la.count, lb.count, 0f, "population diverged");
				for (int g = 0; g < la.mean.length; g++) {
					assertEquals(la.mean[g], lb.mean[g], 0f,
							"gene " + Gene.VALUES[g] + " diverged");
				}
			}
		}
	}

	/**
	 * A region left alone for a long time should settle, not spiral. Both failure directions are
	 * silent: a region that empties gives the player a dead world, and one that explodes gives them
	 * a server that cannot tick.
	 */
	@Test
	void regionsNeitherEmptyNorExplodeOverFiveHundredDays() {
		for (int trial = 0; trial < 40; trial++) {
			FakeWorld world = new FakeWorld();
			RegionRecord record = seededRegion(trial);
			world.records.put(record.pos.key(), record);

			Random random = new Random(record.seed);
			for (int day = 0; day < 500; day++) {
				RegionSimulation.step(world, record, random);
			}

			float population = record.totalPopulation();
			assertTrue(population < 900f,
					"region " + record.pos + " ran away to " + population + " individuals");
			assertTrue(record.vegetation >= 0f && record.vegetation <= 1f,
					"vegetation left [0,1]: " + record.vegetation);
			for (LineageRecord l : record.lineages) {
				assertTrue(l.count >= 0f, "a lineage went to a negative population");
				for (int g = 0; g < l.mean.length; g++) {
					assertTrue(l.mean[g] >= 0f && l.mean[g] <= 1f,
							"gene " + Gene.VALUES[g] + " escaped [0,1] under selection");
				}
			}
		}
	}

	/**
	 * Migration is the line that makes the world one place rather than a set of independent
	 * samples. Without it every region is an island, and walking a long way shows you unrelated
	 * random draws instead of a cline.
	 */
	@Test
	void migrationReachesNeighboursAndDivergesWithDistance() {
		FakeWorld world = new FakeWorld();

		// A row of regions, all founded, all the same climate so suitability is not the variable
		// under test.
		RegionRecord[] row = new RegionRecord[5];
		for (int i = 0; i < row.length; i++) {
			RegionPos pos = new RegionPos(i, 0);
			row[i] = world.create(pos, pos.seed(99));
			row[i].founded = true;
			row[i].productivity = 0.7f;
			row[i].vegetation = 0.7f;
			row[i].temperature = 0.5f;
			row[i].humidity = 0.5f;
		}

		// Seed a lineage into one end only.
		Genome founder = Genome.random(new Random(5));
		LineageRecord seed = LineageRecord.of(
				new Genome(founder.copyValues(), founder.seed(), 777L, 0), 40f);
		seed.mean[Gene.DIET.ordinal()] = 0.1f;
		seed.mean[Gene.TEMP_PREFERENCE.ordinal()] = 0.5f;
		seed.mean[Gene.HUMIDITY_PREFERENCE.ordinal()] = 0.5f;
		row[0].add(seed);

		Random random = new Random(31);
		for (int day = 0; day < 220; day++) {
			for (RegionRecord record : row) {
				RegionSimulation.step(world, record, random);
			}
		}

		assertNotNull(row[1].lineage(777L), "the lineage never reached the adjacent region");
		assertNotNull(row[2].lineage(777L), "the lineage never spread two regions out");

		// And having spread, the far end should not be genetically identical to the near end.
		LineageRecord near = row[0].lineage(777L);
		LineageRecord far = row[row.length - 1].lineage(777L);
		if (near != null && far != null) {
			float divergence = 0f;
			for (int g = 0; g < near.mean.length; g++) {
				divergence += Math.abs(near.mean[g] - far.mean[g]);
			}
			assertTrue(divergence > 0.05f,
					"populations at opposite ends of the range are genetically identical");
		}
	}

	/**
	 * Predators must not be able to eat a population that is outside the size window the entity
	 * layer would let them hunt, or the two simulations disagree about what is food — and a region
	 * would collapse off-screen in a way it never does while the player watches.
	 */
	@Test
	void theRegionalModelUsesTheSamePreyWindowAsTheEntities() {
		// Small prey a large hunter would ignore in the world must be ignored here too.
		assertFalse(EnergyBudget.isWorthHunting(1.0f, 0.05f));
		assertFalse(EnergyBudget.isWorthHunting(1.0f, 0.95f));
		assertTrue(EnergyBudget.isWorthHunting(1.0f, 0.5f));
	}

	// ------------------------------------------------------------------ helpers

	private static RegionRecord seededRegion(int trial) {
		RegionPos pos = new RegionPos(trial, trial * 3);
		RegionRecord record = new RegionRecord(pos);
		record.seed = pos.seed(20260727L);
		FakeWorld isolated = new FakeWorld();
		isolated.records.put(pos.key(), record);
		RegionFounder.found(isolated, record,
				new RegionFounder.Climate(0.5f, 0.5f, 0.7f), "plains", 0);
		return record;
	}
}
