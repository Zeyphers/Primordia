package dev.jsz.primordia.ecology.region;

import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.genome.Mutation;
import dev.jsz.primordia.util.MathX;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Gives a region a fauna the first time the world looks at it, and then runs it forward a few
 * hundred days before anybody sees it.
 * <p>
 * This is what answers "the world was here before I arrived". A region is not populated with a
 * random draw of animals at the moment a player walks in — it is founded from the stock of whatever
 * region is nearest, drifted by the distance between them, and then simulated through a century of
 * its own history. What the player crests the ridge and sees is a set of animals that are visibly
 * related to each other, fitted to that biome, already balanced against each other, and already
 * different from the ones two thousand blocks behind them.
 * <p>
 * The emergent results are the reason to do it this way rather than tuning a spawn table:
 * <ul>
 *   <li><b>Clines.</b> Fauna change gradually with distance, because founders come from next door.</li>
 *   <li><b>Barriers.</b> A climate a lineage cannot cross damps migration, so the far side diverges.</li>
 *   <li><b>Endemics.</b> A region reached from only one direction evolves largely alone.</li>
 *   <li><b>Recolonisation.</b> A region that crashed is refilled by whatever migrates in next, which
 *       will not be what was there before.</li>
 * </ul>
 * None of that is authored anywhere. It falls out of inherited founders plus drift plus migration.
 */
public final class RegionFounder {
	/** How far to look for an already-founded region to inherit founders from, in regions. */
	private static final int ANCESTRY_SEARCH_RADIUS = 6;
	/** Days of history simulated before a region is ever seen. */
	private static final int MIN_PRE_AGE_DAYS = 100;
	private static final int PRE_AGE_SPREAD = 200;

	/** Founding population sizes by trophic level. */
	private static final float HERBIVORE_FOUNDERS = 24f;
	private static final float OMNIVORE_FOUNDERS = 9f;
	private static final float CARNIVORE_FOUNDERS = 3f;
	/**
	 * Cave dwellers founded per region. Higher than the surface predators because they are tiny,
	 * and a cave system holding three of anything is a cave system you will never meet one in.
	 */
	private static final float CAVE_FOUNDERS = 18f;

	/**
	 * Ceiling on carnivore biomass as a fraction of the herbivore biomass beneath it.
	 * <p>
	 * A food chain loses most of its energy at every step, so predators are always rarer and
	 * smaller than what they eat. Seeding without this constraint is how a valley ends up with four
	 * hunters and nothing to hunt — which no amount of restraint in the predators' behaviour can
	 * rescue, because the food simply is not there.
	 */
	public static final float CARNIVORE_BIOMASS_CAP = 0.15f;

	private RegionFounder() {
	}

	/**
	 * Founds a region and runs its pre-history. Does nothing if it is already founded.
	 *
	 * @param climate   normalised temperature and humidity, and productivity, sampled from the biome
	 * @param biomeName the biome path name, used only for colouring the founding stock
	 */
	/**
	 * Generation of the ecology this class writes. Bump when adding something that founding does
	 * once and that an already-founded region would therefore never receive.
	 */
	public static final int VERSION = 1;

	/**
	 * Brings an already-founded record up to the current {@link #VERSION}.
	 * <p>
	 * Founding happens once per region and never again, so anything added to it afterwards reaches
	 * only ground the player has never walked on. In a save that has been played in, that means the
	 * feature is missing everywhere the player actually goes — which is indistinguishable from it
	 * not working. Cave fauna arrived after the first regions were founded and this is how it
	 * reaches them.
	 * <p>
	 * Deliberately additive. It never removes a lineage and never touches one that is already
	 * there, so a region that genuinely lost its cave dwellers to extinction does not have them
	 * conjured back — only a region that never had the chance gets one.
	 */
	public static void upgrade(RegionRecord record, Climate climate) {
		if (!record.founded || record.version >= VERSION) return;

		record.caveRichness = MathX.clamp01(climate.caveRichness());
		boolean hasCaveFauna = false;
		for (LineageRecord lineage : record.lineages) {
			if (Archetype.isSubterranean(lineage.meanGenome())) {
				hasCaveFauna = true;
				break;
			}
		}
		if (!hasCaveFauna) {
			Random random = new Random(record.seed ^ 0x5EEDCAFEL);
			caveFounder(record, new Random(random.nextLong()), random);
		}
		record.version = VERSION;
		record.dirty = true;
	}

	public static void found(RegionNeighbourhood ledger, RegionRecord record, Climate climate,
	                         String biomeName, long currentDay) {
		if (record.founded) return;

		record.productivity = MathX.clamp01(climate.productivity());
		record.temperature = MathX.clamp01(climate.temperature());
		record.humidity = MathX.clamp01(climate.humidity());
		record.vegetation = record.productivity * 0.9f;
		record.caveRichness = MathX.clamp01(climate.caveRichness());

		Random random = new Random(record.seed);

		// A record can already hold lineages before it is founded: creatures that wandered in from
		// a neighbouring region and then despawned were absorbed into it. Those animals are really
		// here, so seeding on top of them would double the fauna rather than establish it.
		if (record.lineages.isEmpty()) {
			LineageRecord[] inherited = inheritFrom(ledger, record, random);
			if (inherited != null) {
				for (LineageRecord founder : inherited) record.add(founder);
			} else {
				seedFreshFauna(record, biomeName, random);
			}
		}

		record.founded = true;
		record.version = VERSION;
		record.lastStep = currentDay;
		record.dirty = true;

		// The pre-history. One loop over the population model buys the entire premise.
		int days = MIN_PRE_AGE_DAYS + random.nextInt(PRE_AGE_SPREAD);
		preAge(ledger, record, days, random);
	}

	/** Runs a founded region forward without advancing its clock — used for pre-history only. */
	public static void preAge(RegionNeighbourhood ledger, RegionRecord record, int days,
	                          RandomGenerator random) {
		for (int i = 0; i < days; i++) {
			RegionSimulation.step(ledger, record, random);
			// A region whose entire fauna died during its pre-history is not a story, it is an
			// empty valley. Reseed once and let it try again.
			if (record.lineages.isEmpty() && i < days - 1) {
				seedFreshFauna(record, "", random);
			}
		}
	}

	/**
	 * Copies the fauna of the nearest already-founded region, drifted by how far away it is.
	 * <p>
	 * Returns null when the world has nothing nearby to inherit from, which is the genuinely new
	 * frontier case.
	 */
	private static LineageRecord[] inheritFrom(RegionNeighbourhood ledger, RegionRecord record,
	                                           RandomGenerator random) {
		RegionRecord source = null;
		int sourceDistance = Integer.MAX_VALUE;

		for (int radius = 1; radius <= ANCESTRY_SEARCH_RADIUS && source == null; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					// Only the ring at this radius; the interior was covered by earlier passes.
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					RegionRecord candidate = ledger.existing(record.pos.offset(dx, dz));
					if (candidate == null || !candidate.founded || candidate.lineages.isEmpty()) continue;
					int distance = Math.abs(dx) + Math.abs(dz);
					if (distance < sourceDistance) {
						source = candidate;
						sourceDistance = distance;
					}
				}
			}
		}
		if (source == null) return null;

		// Drift scales with distance and with how different the climate is. A neighbouring region
		// in the same biome gets recognisable relatives; one six regions away across a climate
		// gradient gets something that has clearly been apart for a long time.
		float climateGap = Math.abs(source.temperature - record.temperature)
				+ Math.abs(source.humidity - record.humidity);
		float drift = MathX.clamp(0.06f * sourceDistance + 0.30f * climateGap, 0.03f, 0.45f);

		// A founder effect, not a copy. Only some of the source's lineages make the crossing, which
		// is both what really happens and the thing that keeps neighbouring regions from being the
		// same place twice. Copying every lineage at low drift produced a continent of clones —
		// every animal for hundreds of blocks sharing a lineage id, and therefore a favourite food.
		List<LineageRecord> pool = new ArrayList<>(source.lineages);
		java.util.Collections.shuffle(pool, new Random(random.nextLong()));
		int taken = 1 + random.nextInt(Math.max(1, Math.min(3, pool.size())));

		List<LineageRecord> founders = new ArrayList<>();
		for (int i = 0; i < taken; i++) {
			LineageRecord founder = pool.get(i).copy();
			// A founding party, not the whole population.
			founder.count = Math.max(2f, founder.total() * 0.3f);
			founder.held = 0f;
			float moved = 0f;
			for (int g = 0; g < founder.mean.length; g++) {
				float delta = (float) random.nextGaussian() * drift * Gene.VALUES[g].plasticity;
				founder.mean[g] = MathX.clamp01(founder.mean[g] + delta);
				moved += Math.abs(delta);
			}
			founder.variance = MathX.clamp(founder.variance + drift * 0.4f, 0.02f, 0.4f);
			// Far enough from the parent stock to be its own thing. Uses the same threshold as
			// everything else that decides what a species is, so the clade tree stays coherent.
			if (moved / founder.mean.length > Mutation.SPECIATION_DISTANCE * 0.15f) {
				founder.id = random.nextLong();
				founder.seed = random.nextLong();
			}
			founders.add(founder);
		}

		// And some of the time, something genuinely new turns up alongside the immigrants —
		// otherwise the whole world descends from whichever region the player happened to spawn in
		// and no novelty ever enters it.
		if (random.nextFloat() < 0.45f) {
			seedFreshFauna(record, "", random);
		}
		return founders.toArray(new LineageRecord[0]);
	}

	/**
	 * Builds a food chain from nothing: a plant-eating base, a middle, and at most one hunter.
	 * <p>
	 * The old behaviour — which is what the player was complaining about — rolled a uniformly random
	 * archetype per individual, so a valley was as likely to be seeded with four carnivores and
	 * nothing to eat as with something that could sustain itself. Composition is decided here, once,
	 * for the region as a whole.
	 */
	private static void seedFreshFauna(RegionRecord record, String biomeName, RandomGenerator random) {
		Random mcRandomSource = new Random(random.nextLong());

		int herbivores = 2 + (random.nextInt(2));
		float herbivoreBiomass = 0f;
		for (int i = 0; i < herbivores; i++) {
			LineageRecord l = founder(record, biomeName, mcRandomSource, random,
					0.05f + random.nextFloat() * 0.22f, HERBIVORE_FOUNDERS);
			if (l != null) herbivoreBiomass += l.biomass();
		}

		if (random.nextFloat() < 0.75f) {
			founder(record, biomeName, mcRandomSource, random,
					0.40f + random.nextFloat() * 0.18f, OMNIVORE_FOUNDERS);
		}

		// Every region gets its cave fauna, whatever the biome above it. Caves are under the desert
		// as much as under the jungle, and what lives in them answers to the cave rather than to the
		// weather — which is why this is seeded unconditionally and its climate preferences are not
		// taken from the surface.
		caveFounder(record, mcRandomSource, random);

		// One hunter, and only if the base below it can actually carry one.
		if (herbivoreBiomass > 0f && random.nextFloat() < 0.7f) {
			LineageRecord predator = founder(record, biomeName, mcRandomSource, random,
					0.70f + random.nextFloat() * 0.25f, CARNIVORE_FOUNDERS);
			if (predator != null) {
				float allowed = herbivoreBiomass * CARNIVORE_BIOMASS_CAP;
				if (predator.biomass() > allowed) {
					predator.count = Math.max(1f, allowed / Math.max(0.02f, predator.meanMass()));
				}
			}
		}
	}

	/**
	 * Seeds the region's cave dwellers.
	 * <p>
	 * Founded from {@link Archetype#CAVE_CRAWLER} rather than from the biome, and deliberately not
	 * passed through the surface climate: the temperature twenty blocks down is not the temperature
	 * of the meadow above it, and pulling a cave animal's preferences toward a desert's would have
	 * selection slowly drive it out of the only place it can live.
	 */
	private static void caveFounder(RegionRecord record, Random mcSource, RandomGenerator random) {
		net.minecraft.util.RandomSource mcRandom =
				net.minecraft.util.RandomSource.create(mcSource.nextLong());
		Genome genome = Archetype.CAVE_CRAWLER.create(new Random(mcRandom.nextLong()));

		// Scaled by what the caves under this region can actually support. A lush cave gets a
		// founding population; a plain stone one gets a handful, which the pre-history will either
		// carry or quietly lose — and either outcome is the right one.
		float founders = CAVE_FOUNDERS * record.caveRichness;
		if (founders < 1f) return;

		LineageRecord lineage = LineageRecord.of(
				new Genome(genome.copyValues(), genome.seed(), random.nextLong(), 0),
				founders);
		lineage.variance = 0.04f + random.nextFloat() * 0.04f;
		record.add(lineage);
	}

	/** One founding lineage with its diet forced to a trophic level. */
	private static LineageRecord founder(RegionRecord record, String biomeName,
	                                     Random mcSource, RandomGenerator random,
	                                     float diet, float population) {
		net.minecraft.util.RandomSource mcRandom =
				net.minecraft.util.RandomSource.create(mcSource.nextLong());
		Genome base = Genome.createForBiome(mcRandom, biomeName);

		float[] values = base.copyValues();
		values[Gene.DIET.ordinal()] = MathX.clamp01(diet);
		// Climate preferences start matched to where they are being put. They would converge there
		// anyway under selection, but starting matched means the founding fauna is not immediately
		// dying of a mismatch nobody chose.
		values[Gene.TEMP_PREFERENCE.ordinal()] = MathX.clamp01(
				record.temperature + (float) random.nextGaussian() * 0.08f);
		values[Gene.HUMIDITY_PREFERENCE.ordinal()] = MathX.clamp01(
				record.humidity + (float) random.nextGaussian() * 0.08f);
		// Founding body size scales with how much the region can feed.
		//
		// This is where "a jungle carries something a scree slope cannot" actually starts — the
		// selection term in RegionSimulation will push it further either way during the region's
		// pre-history, but starting a desert's fauna at the size a rainforest could support just
		// means watching them all starve back down through a century of simulation nobody sees.
		//
		// Hunters start above the grazers they will be eating, because EnergyBudget's prey window
		// requires prey between a quarter and 85% of the hunter's mass: found them at the same size
		// and there is nothing on the menu.
		float productivity = record.productivity;
		if (diet > 0.65f) {
			values[Gene.SIZE.ordinal()] = MathX.clamp01(
					0.45f + productivity * 0.35f + random.nextFloat() * 0.20f);
		} else if (diet < 0.35f) {
			values[Gene.SIZE.ordinal()] = MathX.clamp01(
					0.15f + productivity * 0.55f + random.nextFloat() * 0.30f);
		}

		Genome genome = new Genome(values, base.seed(), random.nextLong(), 0);
		LineageRecord record2 = LineageRecord.of(genome, population);
		record2.variance = 0.04f + random.nextFloat() * 0.04f;
		return record.add(record2);
	}

	/** Biome-derived inputs to founding, normalised to the [0,1] scale the genome uses. */
	/**
	 * Biome-derived inputs to founding, normalised to the [0,1] scale the genome uses.
	 *
	 * @param caveRichness how much the caves under the region can support — high in lush caves,
	 *                     low but never zero elsewhere
	 */
	public record Climate(float temperature, float humidity, float productivity, float caveRichness) {
	}
}
