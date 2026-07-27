package dev.jsz.primordia.genome;

import dev.jsz.primordia.util.MathX;

import java.util.random.RandomGenerator;

/**
 * Inheritance operators: crossover, point mutation and speciation.
 * <p>
 * The design goal is that lineages stay recognisable across many generations while
 * still being able to invent genuinely new body plans. Three mechanisms cooperate:
 * <ul>
 *   <li><b>Block crossover</b> — genes are cut at a few points rather than shuffled
 *       independently, so linked traits (all the leg genes, all the colour genes) tend to
 *       travel together and offspring look like plausible children rather than noise.</li>
 *   <li><b>Plasticity-weighted drift</b> — {@link Gene#plasticity} makes colour wander fast
 *       and limb counts wander slowly, which is what keeps a clade visually coherent.</li>
 *   <li><b>Rare macro-mutation</b> — an occasional large jump on a single structural locus.
 *       This is the only source of genuinely new body plans; without it populations just
 *       polish whatever they started with.</li>
 * </ul>
 */
public final class Mutation {
	/** Per-locus chance of a point mutation, before plasticity and the MUTABILITY meta-gene. */
	public static final float BASE_POINT_RATE = 0.06f;
	/** Standard deviation of a point mutation, before plasticity scaling. */
	public static final float BASE_SIGMA = 0.07f;
	/** Chance per reproduction event of one large structural jump. */
	public static final float MACRO_RATE = 0.02f;
	/** Genetic distance beyond which offspring are considered a new lineage. */
	public static final float SPECIATION_DISTANCE = 0.28f;

	private Mutation() {
	}

	/** Sexual reproduction: block crossover of the two parents, then mutation. */
	public static Genome breed(Genome a, Genome b, RandomGenerator random) {
		float[] child = crossover(a, b, random);
		float mutability = 0.4f + 1.6f * (a.raw(Gene.MUTABILITY) + b.raw(Gene.MUTABILITY)) * 0.5f;
		applyPointMutations(child, mutability, random);
		maybeMacroMutate(child, mutability, random);

		long seed = mixSeeds(a.seed(), b.seed(), random);
		int generation = Math.max(a.generation(), b.generation()) + 1;
		Genome candidate = new Genome(child, seed, a.lineage(), generation);
		return assignLineage(candidate, a, random);
	}

	/** Asexual reproduction: clone with mutation. Used for founder blooms and budding creatures. */
	public static Genome mutate(Genome parent, RandomGenerator random) {
		float[] child = parent.copyValues();
		float mutability = 0.4f + 1.6f * parent.raw(Gene.MUTABILITY);
		applyPointMutations(child, mutability, random);
		maybeMacroMutate(child, mutability, random);

		long seed = mixSeeds(parent.seed(), parent.seed() * 31L, random);
		Genome candidate = new Genome(child, seed, parent.lineage(), parent.generation() + 1);
		return assignLineage(candidate, parent, random);
	}

	// ------------------------------------------------------------------ internals

	private static float[] crossover(Genome a, Genome b, RandomGenerator random) {
		float[] out = new float[Gene.COUNT];
		int cuts = 1 + random.nextInt(3);
		boolean fromA = random.nextBoolean();
		int nextCut = random.nextInt(Gene.COUNT);
		int cutsLeft = cuts;

		for (int i = 0; i < Gene.COUNT; i++) {
			if (i == nextCut && cutsLeft > 0) {
				fromA = !fromA;
				cutsLeft--;
				nextCut = i + 1 + random.nextInt(Math.max(1, Gene.COUNT - i));
			}
			Gene gene = Gene.VALUES[i];
			float va = a.raw(gene);
			float vb = b.raw(gene);
			// Quantitative traits blend; structural ones inherit whole so limb counts stay integral.
			if (gene.plasticity >= 0.4f && random.nextFloat() < 0.5f) {
				float w = random.nextFloat();
				out[i] = MathX.lerp(va, vb, w);
			} else {
				out[i] = fromA ? va : vb;
			}
		}
		return out;
	}

	private static void applyPointMutations(float[] values, float mutability, RandomGenerator random) {
		for (int i = 0; i < values.length; i++) {
			Gene gene = Gene.VALUES[i];
			float rate = BASE_POINT_RATE * gene.plasticity * mutability;
			if (random.nextFloat() < rate) {
				float sigma = BASE_SIGMA * gene.plasticity;
				values[i] = MathX.clamp01(values[i] + (float) random.nextGaussian() * sigma);
			}
		}
	}

	private static void maybeMacroMutate(float[] values, float mutability, RandomGenerator random) {
		if (random.nextFloat() >= MACRO_RATE * mutability) return;
		// Bias macro-mutations toward structural loci: those are what create new body plans.
		int index;
		do {
			index = random.nextInt(Gene.COUNT);
		} while (Gene.VALUES[index].plasticity > 0.55f && random.nextFloat() < 0.7f);
		values[index] = MathX.clamp01(values[index] + (random.nextBoolean() ? 1f : -1f) * (0.25f + random.nextFloat() * 0.5f));
	}

	private static Genome assignLineage(Genome candidate, Genome reference, RandomGenerator random) {
		if (distance(candidate, reference) < SPECIATION_DISTANCE) {
			return candidate;
		}
		// Diverged far enough to count as a new clade — fork the lineage id.
		float[] values = candidate.copyValues();
		return new Genome(values, candidate.seed(), random.nextLong(), candidate.generation());
	}

	private static long mixSeeds(long a, long b, RandomGenerator random) {
		long mixed = a ^ Long.rotateLeft(b, 32);
		// Occasionally re-roll so identical twins are rare and the mesh cache stays varied.
		if (random.nextFloat() < 0.25f) {
			mixed ^= random.nextLong();
		}
		return mixed;
	}

	/**
	 * Normalised genetic distance in [0,1]: root-mean-square difference across all loci,
	 * weighted so that structural divergence counts for more than a colour shift.
	 * Used for speciation, mate choice and the field-journal clade view.
	 */
	public static float distance(Genome a, Genome b) {
		double sum = 0.0;
		double weightSum = 0.0;
		for (Gene gene : Gene.VALUES) {
			// Low plasticity == structurally important == weighted heavily.
			double w = 1.0 - 0.6 * gene.plasticity;
			double d = a.raw(gene) - b.raw(gene);
			sum += w * d * d;
			weightSum += w;
		}
		return (float) Math.sqrt(sum / weightSum);
	}
}
