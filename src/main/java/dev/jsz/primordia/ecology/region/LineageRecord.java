package dev.jsz.primordia.ecology.region;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.util.MathX;
import net.minecraft.nbt.CompoundTag;

import java.util.random.RandomGenerator;

/**
 * One lineage's presence in one region: how many there are, what the average one is like, and how
 * much they vary.
 * <p>
 * This is the representation a population takes when nobody is looking at it. An individual is an
 * entity with a genome; a population is a mean genome, a variance and a count — three numbers and a
 * vector, which can be advanced through a hundred simulated days in the time it takes to tick one
 * entity once. That asymmetry is the whole reason the world can keep moving where the player is not.
 * <p>
 * The mean is stored quantised to one byte per locus rather than as floats. Genomes sampled from it
 * are perturbed by {@link #variance} anyway, so resolution finer than 1/255 describes a precision
 * the model does not have — and it takes the whole record under a kilobyte, which is what makes
 * keeping one for every region a player has ever visited affordable.
 */
public final class LineageRecord {
	/** Shared clade id, the same one {@link Genome#lineage()} carries. */
	public long id;
	/** Structural seed for genomes sampled out of this lineage. */
	public long seed;
	/** Mean genome, one value per {@link Gene}, each in [0,1]. */
	public final float[] mean = new float[Gene.COUNT];
	/** Genetic spread within the lineage. Sampling adds noise of this magnitude. */
	public float variance;
	/**
	 * Population, deliberately fractional.
	 * <p>
	 * Rounding this to an integer on every load and unload is how small populations quietly
	 * evaporate: 1.4 becomes 1, then 1 becomes 0, and a lineage disappears without ever having been
	 * killed by anything. See {@link #held}, which is the other half of not leaking.
	 */
	public float count;
	/**
	 * Population not currently represented by an entity: the fractional remainder, plus anything
	 * over the per-region entity budget.
	 * <p>
	 * Live entities are authoritative for a loaded region, so writing back would otherwise discard
	 * everything the budget refused to spawn — a herd of forty would come back as twenty-four
	 * every single time the player walked past.
	 */
	public float held;
	/** Generations elapsed, carried into sampled genomes so the journal can read it. */
	public int generation;
	/**
	 * Population at the end of the previous simulated day.
	 * <p>
	 * Kept only so the player can be told whether a population is growing or crashing. A number on
	 * its own says nothing — "31 individuals" is not information until you know it was 44 yesterday
	 * — and a world that is moving without you is worth nothing if you cannot perceive that it
	 * moved.
	 */
	public float previousCount;

	/**
	 * Steps since {@link #meanMass()} last actually built a body plan, and the value it got.
	 * <p>
	 * Mass has to come from the development step, and development is not cheap — a few hundred
	 * small allocations. Drift moves the mean genome every single simulated step, so a naive
	 * {@code meanMass()} rebuilds a body plan per lineage per step: founding a region runs a few
	 * hundred steps, which is over a thousand builds on the tick a player walks into new terrain.
	 * <p>
	 * Worse than the cost is what it does to {@link BodyPlanCache}, which is bounded at 512 entries
	 * and shared with the renderer. A founding would evict every plan belonging to a creature
	 * actually on screen and they would all rebuild mid-frame.
	 * <p>
	 * Mass moves slowly — {@code SIZE} has low plasticity and drift is a fraction of a percent per
	 * step — so sampling it every few steps is indistinguishable from sampling it every step.
	 */
	private transient int massAge = Integer.MAX_VALUE;
	private transient float cachedMass = 0.2f;

	/** Steps between actual body-plan builds when reading mass. */
	private static final int MASS_REFRESH_STEPS = 8;

	public LineageRecord() {
	}

	public static LineageRecord of(Genome genome, float count) {
		LineageRecord record = new LineageRecord();
		record.id = genome.lineage();
		record.seed = genome.seed();
		record.generation = genome.generation();
		float[] values = genome.copyValues();
		System.arraycopy(values, 0, record.mean, 0, Gene.COUNT);
		record.variance = 0.02f;
		record.count = count;
		return record;
	}

	public LineageRecord copy() {
		LineageRecord other = new LineageRecord();
		other.id = id;
		other.seed = seed;
		System.arraycopy(mean, 0, other.mean, 0, Gene.COUNT);
		other.variance = variance;
		other.count = count;
		other.held = held;
		other.generation = generation;
		other.previousCount = previousCount;
		return other;
	}

	/** Total population, whether or not it is currently walking about as entities. */
	public float total() {
		return count + held;
	}

	/**
	 * Moves one individual out of the record and into the world. Returns false when there is not a
	 * whole animal left to take.
	 * <p>
	 * Paired with {@link #give()}, and the two are the only places the population changes without
	 * something having been born or died. Keeping the arithmetic to one subtraction and one
	 * addition of exactly 1.0 is what makes the cycle lossless: any rounding, clamping or
	 * recomputation here would leak a fraction per load, which is invisible in play and empties the
	 * world over an afternoon of exploring.
	 */
	public boolean take() {
		if (count < 1f) return false;
		count -= 1f;
		return true;
	}

	/** Moves one individual out of the world and back into the record. */
	public void give() {
		count += 1f;
	}

	/** Change in population over the last simulated day: positive is growing. */
	public float trend() {
		return total() - previousCount;
	}

	public float meanOf(Gene gene) {
		return mean[gene.ordinal()];
	}

	/** The genome of the average member, used for mass and diet lookups by the regional sim. */
	public Genome meanGenome() {
		return new Genome(mean.clone(), seed, id, generation);
	}

	/**
	 * Typical body mass of this lineage.
	 * <p>
	 * Goes through the body plan rather than reading {@link Gene#SIZE} directly, because mass is an
	 * emergent property of the whole development step — girth, limb bulk and segmentation all feed
	 * it — and the ecology has to agree with the entity layer about how big an animal is or a
	 * predator that can eat something at region scale will refuse to at entity scale.
	 */
	public float meanMass() {
		if (massAge >= MASS_REFRESH_STEPS) {
			// Built directly rather than through BodyPlanCache: these mean genomes are transient
			// and nothing else will ever ask for them again, so caching them only evicts the plans
			// of creatures that are on screen.
			BodyPlan plan = BodyPlanBuilder.build(meanGenome());
			cachedMass = plan == null ? 0.2f : plan.mass;
			massAge = 0;
		}
		return cachedMass;
	}

	/** Marks one simulated step as elapsed, for the purpose of refreshing cached mass. */
	public void ageMass() {
		if (massAge < Integer.MAX_VALUE) massAge++;
	}

	/** Total biomass: what a predator sees when it looks at this lineage as food. */
	public float biomass() {
		return total() * meanMass();
	}

	/**
	 * Draws an individual out of the population: the mean, jittered by the variance.
	 * <p>
	 * Per-gene plasticity scales the jitter, for the same reason it scales mutation — a lineage
	 * whose colour varies widely but whose limb count does not is what a real clade looks like, and
	 * sampling uniformly would produce siblings with different numbers of legs.
	 */
	public Genome sample(RandomGenerator random) {
		float[] values = new float[Gene.COUNT];
		for (int i = 0; i < Gene.COUNT; i++) {
			float jitter = (float) random.nextGaussian() * variance * Gene.VALUES[i].plasticity;
			values[i] = MathX.clamp01(mean[i] + jitter);
		}
		return new Genome(values, random.nextLong(), id, generation);
	}

	// ---------------------------------------------------------------------- nbt

	public CompoundTag writeNbt() {
		CompoundTag nbt = new CompoundTag();
		nbt.putLong("Id", id);
		nbt.putLong("Seed", seed);
		nbt.putFloat("Variance", variance);
		nbt.putFloat("Count", count);
		nbt.putFloat("Held", held);
		nbt.putInt("Generation", generation);
		nbt.putFloat("PreviousCount", previousCount);
		byte[] packed = new byte[Gene.COUNT];
		for (int i = 0; i < Gene.COUNT; i++) {
			packed[i] = (byte) (Math.round(MathX.clamp01(mean[i]) * 255f) - 128);
		}
		nbt.putByteArray("Mean", packed);
		return nbt;
	}

	public static LineageRecord readNbt(CompoundTag nbt) {
		LineageRecord record = new LineageRecord();
		record.id = nbt.getLongOr("Id", 0L);
		record.seed = nbt.getLongOr("Seed", 0L);
		record.variance = nbt.getFloatOr("Variance", 0f);
		record.count = nbt.getFloatOr("Count", 0f);
		record.held = nbt.getFloatOr("Held", 0f);
		record.generation = nbt.getIntOr("Generation", 0);
		record.previousCount = nbt.getFloatOr("PreviousCount", 0f);
		byte[] packed = nbt.getByteArray("Mean").orElse(new byte[0]);
		for (int i = 0; i < Gene.COUNT && i < packed.length; i++) {
			record.mean[i] = (packed[i] + 128) / 255f;
		}
		return record;
	}
}
