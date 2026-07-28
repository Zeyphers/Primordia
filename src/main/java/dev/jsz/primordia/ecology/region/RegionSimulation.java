package dev.jsz.primordia.ecology.region;

import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Mutation;
import dev.jsz.primordia.util.MathX;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Advances a region's ecology through the days nobody watched.
 * <p>
 * A population-level model rather than a simulation of individuals: births, deaths, predation and
 * selection are arithmetic on counts and mean genomes. That is what makes the world keep moving
 * where the player is not — a step costs microseconds, so a region can be caught up through three
 * months of absence in less time than one entity takes to tick once.
 * <p>
 * <b>Deterministic by construction.</b> All randomness comes from the region seed mixed with the
 * step index, never from a shared or thread-local source. Given the same record and the same number
 * of steps this produces the same result every time, which is the only reason any of it can be
 * tested — an ecology that is subtly wrong still looks exactly like an ecology.
 * <p>
 * The model is intentionally crude in places. It is not trying to be ecologically accurate; it is
 * trying to produce populations that rise, fall, adapt and occasionally die out, at a rate a player
 * can perceive across a few in-game days.
 */
public final class RegionSimulation {
	/** Ticks per simulation step: one in-game day. */
	public static final long TICKS_PER_STEP = 24000L;

	/**
	 * Most steps run in one catch-up.
	 * <p>
	 * Returning to a save after a long absence would otherwise integrate thousands of days on the
	 * tick that loads the region and stall the server. Past this the population has reached
	 * whatever equilibrium it was heading for anyway, so the remainder is relaxed toward it in one
	 * move instead of stepped through.
	 */
	public static final int MAX_CATCHUP_STEPS = 90;

	/** Biomass of plant food a fully vegetated, maximally productive region supplies per step. */
	private static final float PLANT_SUPPLY = 14f;
	/** Fraction of available prey biomass a predator population can actually catch in a step. */
	private static final float PREDATION_EFFICIENCY = 0.30f;
	/** Vegetation regrown per step, as a fraction of the gap to the biome's ceiling. */
	private static final float REGROWTH_RATE = 0.16f;
	/** How hard grazing draws vegetation down, per unit of plant demand actually met. */
	private static final float GRAZING_DRAW = 0.010f;
	/** Baseline per-step mortality before starvation and predation. */
	private static final float BASE_MORTALITY = 0.055f;
	/** Extra per-step mortality at total starvation. */
	private static final float STARVATION_MORTALITY = 0.42f;
	/** Ceiling on a single lineage's population, so no one clade fills the region alone. */
	private static final float LINEAGE_CEILING = 90f;
	/** How fast selection moves a mean genome per step, before per-gene plasticity. */
	private static final float SELECTION_RATE = 0.030f;
	/** Neutral drift per step, before plasticity and the lineage's own mutability. */
	private static final float DRIFT_RATE = 0.008f;
	/** Fraction of a population that leaves for the neighbouring regions each step. */
	private static final float MIGRATION_RATE = 0.035f;
	/** Population below which a lineage is gone. */
	private static final float EXTINCTION_FLOOR = 0.5f;
	/** Population a lineage needs before it can split in two. */
	private static final float SPECIATION_MIN_POPULATION = 12f;

	private RegionSimulation() {
	}

	/**
	 * Brings a region up to the current day.
	 * <p>
	 * Called when a region is about to be materialised, and on the neighbours whose migration feeds
	 * it. Regions the player is standing in are <i>not</i> integrated — their entities are doing the
	 * simulating, and running both would count every birth and death twice.
	 */
	public static void integrate(RegionNeighbourhood ledger, RegionRecord record, long currentDay) {
		if (!record.founded) return;
		long owed = currentDay - record.lastStep;
		if (owed <= 0) return;

		int steps = (int) Math.min(owed, MAX_CATCHUP_STEPS);
		for (int i = 0; i < steps; i++) {
			// Seeded from the region and the absolute step index, so the same region integrated
			// over the same span always produces the same history no matter how it was split up
			// into catch-ups.
			Random random = new Random(record.seed ^ ((record.lastStep + i) * 0x9E3779B97F4A7C15L));
			step(ledger, record, random);
		}
		if (owed > MAX_CATCHUP_STEPS) {
			relaxToEquilibrium(record);
		}
		record.lastStep = currentDay;
		record.dirty = true;
	}

	/** Marks a region as current without simulating it — for regions whose entities are live. */
	public static void skipTo(RegionRecord record, long currentDay) {
		if (record.lastStep != currentDay) {
			record.lastStep = currentDay;
			record.dirty = true;
		}
	}

	// ------------------------------------------------------------------ one step

	/**
	 * One day of ecology. Public so tests can drive a region forward without going through the
	 * day-clock bookkeeping in {@link #integrate}; nothing in the mod should call it directly.
	 */
	public static void step(RegionNeighbourhood ledger, RegionRecord record, RandomGenerator random) {
		List<LineageRecord> lineages = record.lineages;
		if (lineages.isEmpty()) {
			regrowVegetation(record, 0f);
			return;
		}

		int n = lineages.size();
		float[] mass = new float[n];
		float[] pop = new float[n];
		float[] meatShare = new float[n];
		float[] plantDemand = new float[n];
		float[] meatDemand = new float[n];

		float totalPlantDemand = 0f;
		for (int i = 0; i < n; i++) {
			LineageRecord l = lineages.get(i);
			// Snapshot before anything changes it, so the trend reported to the player is the
			// change across exactly one day rather than across whatever happened to be measured.
			l.previousCount = l.total();
			mass[i] = Math.max(0.02f, l.meanMass());
			pop[i] = Math.max(0f, l.total());
			meatShare[i] = MathX.clamp01(l.meanOf(Gene.DIET));
			float need = pop[i] * mass[i] * (0.55f + 1.1f * l.meanOf(Gene.METABOLISM));
			plantDemand[i] = need * (1f - meatShare[i]);
			meatDemand[i] = need * meatShare[i];
			totalPlantDemand += plantDemand[i];
		}

		// --- plants -----------------------------------------------------------
		float plantSupply = record.vegetation * record.productivity * PLANT_SUPPLY;
		float plantRatio = totalPlantDemand <= 0f ? 1f
				: MathX.clamp01(plantSupply / totalPlantDemand);

		// --- predation --------------------------------------------------------
		// Each predator draws from the lineages it could actually hunt, sharing them out by
		// biomass. The same size window the entity layer uses, so a predator that would ignore
		// something in the world does not eat it here.
		float[] losses = new float[n];
		float[] meatRatio = new float[n];
		for (int i = 0; i < n; i++) {
			if (meatDemand[i] <= 0f) {
				meatRatio[i] = 1f;
				continue;
			}
			float available = 0f;
			for (int j = 0; j < n; j++) {
				if (j == i) continue;
				if (EnergyBudget.isWorthHunting(mass[i], mass[j])) available += pop[j] * mass[j];
			}
			float catchable = available * PREDATION_EFFICIENCY;
			meatRatio[i] = catchable <= 0f ? 0f : MathX.clamp01(catchable / meatDemand[i]);
			float taken = Math.min(catchable, meatDemand[i]);
			if (taken <= 0f || available <= 0f) continue;

			for (int j = 0; j < n; j++) {
				if (j == i) continue;
				if (!EnergyBudget.isWorthHunting(mass[i], mass[j])) continue;
				float share = (pop[j] * mass[j]) / available;
				losses[j] += (taken * share) / mass[j];
			}
		}

		// --- births, deaths, selection ---------------------------------------
		float grazed = 0f;
		for (int i = 0; i < n; i++) {
			LineageRecord l = lineages.get(i);
			float satisfaction = MathX.clamp01(
					(1f - meatShare[i]) * plantRatio + meatShare[i] * meatRatio[i]);
			grazed += plantDemand[i] * plantRatio;

			float fecundity = 0.10f + 0.45f * l.meanOf(Gene.FECUNDITY);
			float crowding = 1f - MathX.clamp01(pop[i] / LINEAGE_CEILING);
			float births = pop[i] * fecundity * satisfaction * crowding;

			float lifespan = 0.5f + 1.4f * l.meanOf(Gene.LIFESPAN);
			float mortality = BASE_MORTALITY / lifespan
					+ STARVATION_MORTALITY * (1f - satisfaction);
			float deaths = pop[i] * MathX.clamp01(mortality) + losses[i];

			float next = Math.max(0f, pop[i] + births - deaths);
			// Everything lives in count while the region is dormant; entities are what `count`
			// becomes on materialisation, and there are none out there right now.
			l.count = next;
			l.held = 0f;

			float predationPressure = pop[i] <= 0f ? 0f : MathX.clamp01(losses[i] / pop[i] * 4f);
			select(l, record, satisfaction, plantRatio, meatRatio[i], predationPressure);
			drift(l, random);
			l.ageMass();
			if (births > 0.5f) l.generation++;
		}

		regrowVegetation(record, grazed);
		speciate(record, random);
		record.pruneExtinct();
		migrate(ledger, record, random);
		record.dirty = true;
	}

	/**
	 * Moves a lineage's mean genome along the fitness gradient.
	 * <p>
	 * The cheap trick that makes population-level evolution possible: rather than simulating
	 * individuals and letting the survivors define the new mean, the mean is nudged directly toward
	 * whatever would have survived. It is not the same thing, but over dozens of steps it produces
	 * the same shape of answer — populations under predation get faster and more armoured,
	 * populations that are short of food get smaller — and it costs a handful of multiplications.
	 * <p>
	 * Every shift is scaled by the locus's own plasticity, so structural genes move slowly and
	 * cosmetic ones move fast. That is what keeps a clade recognisable while it adapts, and it is
	 * the same rule {@link Mutation} applies to individuals.
	 */
	private static void select(LineageRecord l, RegionRecord record, float satisfaction,
	                           float plantRatio, float meatRatio, float predationPressure) {
		float hunger = 1f - satisfaction;

		// Every trait here is pushed from both sides. A one-way push is not selection, it is a
		// ratchet: run it for the few hundred steps of a region's pre-history and the locus arrives
		// pinned at whichever end it was being pushed toward, identically in every region.
		//
		// `SIZE` was exactly that — `-hunger` and nothing else — and since satisfaction is almost
		// never a clean 1.0, every lineage in the world was driven to minimum body size before the
		// player ever saw it. The world had no large animals in it at all, and no amount of varying
		// the founding stock would have produced any.

		// Size: a well-fed population grows into the food available, a hungry one shrinks. Being
		// large is also protection — EnergyBudget's prey window means outgrowing your predator is a
		// genuine escape — so predation pushes the other way from hunger.
		nudge(l, Gene.SIZE, (satisfaction - 0.80f) * 1.2f + predationPressure * 0.6f);
		// A low metabolism is cheap to run but slow; hunger favours it and predation does not.
		nudge(l, Gene.METABOLISM, -hunger * 0.6f + predationPressure * 0.3f);

		// Under predation: faster, warier, better armoured, breeding harder. All of it costs food,
		// so hunger claws each one back and the trait settles somewhere instead of railing.
		nudge(l, Gene.SPEED, predationPressure - hunger * 0.5f);
		nudge(l, Gene.STAMINA, predationPressure * 0.7f - hunger * 0.4f);
		nudge(l, Gene.ARMOR, predationPressure * 0.6f - hunger * 0.5f);
		// Vigilance costs foraging time, so a population nothing is hunting relaxes.
		nudge(l, Gene.FEAR, predationPressure * 0.8f - 0.18f);
		nudge(l, Gene.FECUNDITY, predationPressure * 0.5f - hunger * 0.6f);

		// Diet slides toward whichever food was actually available. A herbivore in a stripped
		// region with prey about it will, over enough generations, stop being a herbivore.
		nudge(l, Gene.DIET, (meatRatio - plantRatio) * 0.5f);

		// Climate matching: the region pulls its inhabitants toward fitting it.
		nudge(l, Gene.TEMP_PREFERENCE, record.temperature - l.meanOf(Gene.TEMP_PREFERENCE));
		nudge(l, Gene.HUMIDITY_PREFERENCE, record.humidity - l.meanOf(Gene.HUMIDITY_PREFERENCE));
	}

	private static void nudge(LineageRecord l, Gene gene, float direction) {
		int i = gene.ordinal();
		l.mean[i] = MathX.clamp01(l.mean[i] + direction * SELECTION_RATE * gene.plasticity);
	}

	/**
	 * Neutral drift, and the variance that goes with it.
	 * <p>
	 * Scaled by the lineage's own {@link Gene#MUTABILITY}, so evolvability is itself heritable and a
	 * clade under pressure can evolve to change faster — the same property the individual-level
	 * mutation code has, preserved here so the two layers do not disagree about how fast a lineage
	 * moves.
	 */
	private static void drift(LineageRecord l, RandomGenerator random) {
		float mutability = 0.5f + 1.5f * l.meanOf(Gene.MUTABILITY);
		for (int i = 0; i < l.mean.length; i++) {
			float sigma = DRIFT_RATE * Gene.VALUES[i].plasticity * mutability;
			l.mean[i] = MathX.clamp01(l.mean[i] + (float) random.nextGaussian() * sigma);
		}
		// Variance widens with drift and is pulled back by selection culling the extremes.
		//
		// The coefficients set where it settles, and that is what decides whether two animals of
		// one lineage are visibly different individuals or the same animal twice. It used to
		// equilibrate around 0.035, which — multiplied by a colour gene's plasticity — is a jitter
		// of about two percent. A herd looked like a row of copies.
		l.variance = MathX.clamp(l.variance + DRIFT_RATE * mutability * 1.1f - l.variance * 0.09f,
				0.02f, 0.5f);
	}

	/**
	 * Splits a lineage that has spread too far apart to still be one thing.
	 * <p>
	 * Uses the same {@link Mutation#SPECIATION_DISTANCE} the entity-level breeding path uses, so a
	 * clade tree assembled from the ledger and one assembled from births the player watched are the
	 * same tree, rather than two systems with different opinions about what a species is.
	 */
	private static void speciate(RegionRecord record, RandomGenerator random) {
		List<LineageRecord> born = new ArrayList<>();
		for (LineageRecord l : record.lineages) {
			if (l.variance < Mutation.SPECIATION_DISTANCE) continue;
			if (l.total() < SPECIATION_MIN_POPULATION) continue;
			if (record.lineages.size() + born.size() >= RegionRecord.MAX_LINEAGES) break;

			LineageRecord offshoot = l.copy();
			offshoot.id = random.nextLong();
			offshoot.seed = random.nextLong();
			offshoot.count = l.count * 0.4f;
			offshoot.generation = l.generation;
			l.count -= offshoot.count;

			// Push the two apart along the loci that were varying most, so the split is visible
			// rather than being two identical populations with different names.
			for (int i = 0; i < offshoot.mean.length; i++) {
				float push = (float) random.nextGaussian() * l.variance * Gene.VALUES[i].plasticity;
				offshoot.mean[i] = MathX.clamp01(offshoot.mean[i] + push);
				l.mean[i] = MathX.clamp01(l.mean[i] - push * 0.5f);
			}
			offshoot.variance = l.variance * 0.45f;
			l.variance *= 0.45f;
			born.add(offshoot);
		}
		for (LineageRecord offshoot : born) {
			record.add(offshoot);
		}
	}

	/**
	 * Bleeds a fraction of every population into the neighbouring regions.
	 * <p>
	 * The single most important line in the whole design. Migration is what spreads a successful
	 * lineage across a continent, what makes a region that crashed get repopulated by something
	 * different from what was there before, and what turns walking a long way into a
	 * biogeographical observation instead of a series of unrelated random draws. Without it every
	 * region is an island and the world is a set of independent samples.
	 * <p>
	 * Weighted by how well the destination's climate suits the emigrants, so a lineage adapted to
	 * a swamp spreads readily through wetland and barely at all into a desert — which is what makes
	 * a biome boundary read as a boundary.
	 */
	private static void migrate(RegionNeighbourhood ledger, RegionRecord record, RandomGenerator random) {
		for (RegionPos neighbourPos : record.pos.neighbours()) {
			RegionRecord neighbour = ledger.existing(neighbourPos);
			// Only into regions the world already knows about. Founding a region from migration
			// would have a player's exploration silently generate ecology for the whole map.
			if (neighbour == null || !neighbour.founded) continue;

			for (LineageRecord l : new ArrayList<>(record.lineages)) {
				if (l.count < 2f) continue;
				float suitability = suitability(l, neighbour);
				float emigrants = l.count * MIGRATION_RATE * suitability;
				if (emigrants < 0.05f) continue;

				l.count -= emigrants;
				LineageRecord there = neighbour.lineage(l.id);
				if (there == null) {
					LineageRecord seed = l.copy();
					seed.count = emigrants;
					seed.held = 0f;
					neighbour.add(seed);
				} else {
					there.count += emigrants;
				}
				neighbour.dirty = true;
			}
		}
	}

	/** How well a lineage's climate preferences match a region, in [0,1]. */
	static float suitability(LineageRecord l, RegionRecord region) {
		float dt = Math.abs(l.meanOf(Gene.TEMP_PREFERENCE) - region.temperature);
		float dh = Math.abs(l.meanOf(Gene.HUMIDITY_PREFERENCE) - region.humidity);
		return MathX.clamp01(1f - (dt + dh) * 0.7f);
	}

	private static void regrowVegetation(RegionRecord record, float grazed) {
		float regrown = (record.productivity - record.vegetation) * REGROWTH_RATE;
		record.vegetation = MathX.clamp01(record.vegetation + regrown - grazed * GRAZING_DRAW);
	}

	/**
	 * Collapses a very long absence into one move toward where the model was heading anyway.
	 * <p>
	 * Beyond the catch-up cap the exact trajectory stopped mattering — what the player will see is
	 * the endpoint. Nudging populations toward the region's carrying capacity gets there without
	 * stepping through years of arithmetic on a tick that is holding up the server.
	 */
	private static void relaxToEquilibrium(RegionRecord record) {
		float capacity = record.productivity * PLANT_SUPPLY;
		for (LineageRecord l : record.lineages) {
			float target = MathX.clamp(capacity / Math.max(0.05f, l.meanMass()) * 0.25f,
					1f, LINEAGE_CEILING);
			l.count += (target - l.count) * 0.5f;
		}
		record.vegetation = MathX.clamp01(record.productivity * 0.85f);
		record.pruneExtinct();
	}
}
