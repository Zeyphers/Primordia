package dev.jsz.primordia.genome;

import java.util.random.RandomGenerator;

/**
 * Founder body plans: named regions of gene space that produce recognisably different animals.
 * <p>
 * A uniform random genome is, statistically, always the same creature — a mid-sized quadruped with
 * average everything, because independent random loci all cluster near their means. Real faunas
 * are not distributed that way; they are a handful of successful structural bets with variation
 * around each. An archetype constrains the loci that define a body plan and leaves everything else
 * free, so two insectoids are visibly kin without being copies.
 * <p>
 * These are <b>starting points only</b>. Nothing marks a creature as belonging to an archetype
 * afterwards, and mutation is free to walk a lineage out of the region it was founded in — a
 * saurian line can shrink over generations until it is something else entirely. The archetype is
 * where a population begins, not what it is.
 */
public enum Archetype {
	/** Uniform draw across all of gene space. Chaotic, occasionally wonderful. */
	CHAOS,
	/** Upright, two long digitigrade legs, heavy tail for balance, small forelimbs. */
	BIPED,
	/** Many splayed legs, low slung, small, armoured, segmented. */
	INSECTOID,
	/** Enormous long-necked, long-tailed quadruped browser. */
	SAURIAN,
	/** Bulky low-headed quadruped built for cropping vegetation in volume. */
	GRAZER,
	/** Fast, social, mid-sized carnivore. */
	PACK_HUNTER,
	/** Huge bipedal predator: massive jaws, vestigial arms, counterweight tail. */
	APEX,
	/** Long-limbed, light-boned, built purely for speed. */
	SPRINTER,
	/** Two-part body, four pairs of clustered high-kneed legs, eight eyes. Spider-shaped. */
	ARACHNID,
	/** Broad, flat, heavily armoured; stalked eyes, big claws, a paddle tail. */
	CRUSTACEAN;

	public static final Archetype[] VALUES = values();

	/** Chance an {@link #ARACHNID} founder rolls the giant size band instead of the usual one. */
	public static final float GIANT_CHANCE = 0.03f;

	/**
	 * Rolls a founder genome for this archetype. Constrained loci are drawn from the archetype's
	 * band; everything else keeps the moderate random draw, which is what supplies the variation
	 * within a body plan.
	 */
	public Genome create(RandomGenerator random) {
		Genome base = Genome.randomModerate(random);
		if (this == CHAOS) return Genome.random(random);

		float[] v = base.copyValues();
		switch (this) {
			case BIPED -> {
				band(v, Gene.LEG_PAIRS, 0.02f, 0.22f, random);      // one pair
				band(v, Gene.LEG_SEGMENTS, 0.6f, 1f, random);       // three segments
				band(v, Gene.LEG_LENGTH, 0.62f, 0.92f, random);
				band(v, Gene.DIGITIGRADE, 0.6f, 1f, random);
				band(v, Gene.TAIL_LENGTH, 0.55f, 0.85f, random);    // counterweight
				band(v, Gene.TAIL_THICKNESS, 0.5f, 0.85f, random);
				band(v, Gene.ARM_PAIRS, 0.45f, 0.8f, random);       // one pair of arms
				band(v, Gene.ARM_LENGTH, 0.35f, 0.7f, random);
				band(v, Gene.TORSO_LENGTH, 0.25f, 0.5f, random);
				band(v, Gene.LEG_SPLAY, 0.0f, 0.25f, random);       // feet under the body
				band(v, Gene.SIZE, 0.3f, 0.62f, random);
			}
			case INSECTOID -> {
				band(v, Gene.LEG_PAIRS, 0.55f, 1f, random);         // three or four pairs
				band(v, Gene.LEG_SEGMENTS, 0.6f, 1f, random);
				band(v, Gene.LEG_SPLAY, 0.65f, 1f, random);         // sprawling
				band(v, Gene.LEG_LENGTH, 0.15f, 0.45f, random);
				// Legs run the full length of the body, the knees stay low, and the trunk stays
				// one piece. All three are what separate this from ARACHNID, which packs the same
				// leg count onto a front segment over high knees, with its mass in an abdomen
				// behind them. Spread is pinned to the bottom of the range rather than merely
				// biased low: with four pairs on a body this short, the legs of an insectoid are
				// nearly touching already, and any clustering at all closes the gap entirely.
				band(v, Gene.LEG_CLUSTERING, 0f, 0.06f, random);
				band(v, Gene.LEG_ARCH, 0f, 0.30f, random);
				band(v, Gene.BODY_SEGMENTATION, 0f, 0.40f, random);
				band(v, Gene.SIZE, 0.02f, 0.28f, random);
				band(v, Gene.TORSO_GIRTH, 0.1f, 0.4f, random);
				band(v, Gene.SPINE_SEGMENTS, 0.6f, 1f, random);     // segmented body
				band(v, Gene.NECK_LENGTH, 0f, 0.2f, random);
				band(v, Gene.ARMOR, 0.6f, 1f, random);
				band(v, Gene.EYE_COUNT, 0.75f, 1f, random);         // multiple eye pairs
				band(v, Gene.EYE_SIZE, 0.55f, 0.95f, random);
				band(v, Gene.BLEND_SMOOTHNESS, 0f, 0.3f, random);   // hard-edged, chitinous
			}
			case SAURIAN -> {
				band(v, Gene.SIZE, 0.82f, 1f, random);
				band(v, Gene.LEG_PAIRS, 0.3f, 0.48f, random);       // two pairs
				band(v, Gene.NECK_LENGTH, 0.75f, 1f, random);
				band(v, Gene.NECK_SEGMENTS, 0.7f, 1f, random);
				band(v, Gene.TAIL_LENGTH, 0.8f, 1f, random);
				band(v, Gene.TAIL_SEGMENTS, 0.7f, 1f, random);
				band(v, Gene.HEAD_SIZE, 0.05f, 0.3f, random);       // famously small head
				band(v, Gene.TORSO_GIRTH, 0.6f, 0.9f, random);
				band(v, Gene.LEG_LENGTH, 0.5f, 0.8f, random);
				band(v, Gene.LEG_THICKNESS, 0.7f, 1f, random);      // pillar legs
				band(v, Gene.DIET, 0f, 0.2f, random);
				band(v, Gene.AGGRESSION, 0f, 0.3f, random);
			}
			case GRAZER -> {
				band(v, Gene.SIZE, 0.55f, 0.85f, random);
				band(v, Gene.LEG_PAIRS, 0.3f, 0.48f, random);
				band(v, Gene.TORSO_GIRTH, 0.65f, 1f, random);       // barrel gut for fermenting
				band(v, Gene.TORSO_LENGTH, 0.55f, 0.85f, random);
				band(v, Gene.NECK_LENGTH, 0.25f, 0.5f, random);
				band(v, Gene.HEAD_SIZE, 0.35f, 0.6f, random);
				band(v, Gene.JAW_SIZE, 0.5f, 0.8f, random);
				band(v, Gene.DIET, 0f, 0.18f, random);
				band(v, Gene.SOCIABILITY, 0.6f, 1f, random);        // herds
				band(v, Gene.GRAZING_IMPACT, 0.6f, 1f, random);
			}
			case PACK_HUNTER -> {
				band(v, Gene.SIZE, 0.28f, 0.55f, random);
				band(v, Gene.LEG_PAIRS, 0.3f, 0.48f, random);
				band(v, Gene.DIET, 0.75f, 1f, random);
				band(v, Gene.JAW_SIZE, 0.6f, 0.95f, random);
				band(v, Gene.SPEED, 0.65f, 1f, random);
				band(v, Gene.LEG_LENGTH, 0.55f, 0.85f, random);
				band(v, Gene.AGGRESSION, 0.6f, 0.9f, random);
				band(v, Gene.SOCIABILITY, 0.7f, 1f, random);
				band(v, Gene.TORSO_GIRTH, 0.2f, 0.45f, random);     // lean
			}
			case APEX -> {
				band(v, Gene.SIZE, 0.78f, 1f, random);
				band(v, Gene.LEG_PAIRS, 0.02f, 0.22f, random);      // bipedal
				band(v, Gene.LEG_SEGMENTS, 0.6f, 1f, random);
				band(v, Gene.LEG_LENGTH, 0.55f, 0.8f, random);
				band(v, Gene.LEG_THICKNESS, 0.7f, 1f, random);
				band(v, Gene.HEAD_SIZE, 0.7f, 1f, random);
				band(v, Gene.JAW_SIZE, 0.85f, 1f, random);
				band(v, Gene.ARM_PAIRS, 0.45f, 0.75f, random);
				band(v, Gene.ARM_LENGTH, 0f, 0.15f, random);        // vestigial forelimbs
				band(v, Gene.TAIL_LENGTH, 0.7f, 1f, random);
				band(v, Gene.TAIL_THICKNESS, 0.6f, 1f, random);
				band(v, Gene.NECK_LENGTH, 0.15f, 0.4f, random);
				band(v, Gene.DIET, 0.85f, 1f, random);
				band(v, Gene.AGGRESSION, 0.75f, 1f, random);
				band(v, Gene.SOCIABILITY, 0f, 0.25f, random);       // solitary
			}
			case SPRINTER -> {
				band(v, Gene.SIZE, 0.25f, 0.5f, random);
				band(v, Gene.LEG_PAIRS, 0.3f, 0.48f, random);
				band(v, Gene.LEG_LENGTH, 0.78f, 1f, random);
				band(v, Gene.LEG_SEGMENTS, 0.6f, 1f, random);
				band(v, Gene.DIGITIGRADE, 0.7f, 1f, random);
				band(v, Gene.SPEED, 0.8f, 1f, random);
				band(v, Gene.STAMINA, 0.6f, 1f, random);
				band(v, Gene.TORSO_GIRTH, 0.05f, 0.3f, random);     // light frame
				band(v, Gene.LEG_THICKNESS, 0.2f, 0.5f, random);
				band(v, Gene.FEAR, 0.6f, 1f, random);
				band(v, Gene.TAIL_LENGTH, 0.5f, 0.85f, random);
			}
			case ARACHNID -> {
				// The three genes that make this shape rather than "insect with long legs":
				// everything clustered on the front segment, the mass carried in a separate
				// abdomen behind it, and the mid joint riding above the hip.
				band(v, Gene.LEG_CLUSTERING, 0.82f, 1f, random);
				band(v, Gene.BODY_SEGMENTATION, 0.80f, 1f, random);
				band(v, Gene.LEG_ARCH, 0.85f, 1f, random);
				band(v, Gene.ABDOMEN_SIZE, 0.55f, 0.95f, random);

				band(v, Gene.LEG_PAIRS, 0.80f, 1f, random);        // four pairs
				band(v, Gene.LEG_SEGMENTS, 0.6f, 1f, random);      // three segments
				band(v, Gene.LEG_SPLAY, 0.80f, 1f, random);
				band(v, Gene.LEG_LENGTH, 0.45f, 0.75f, random);
				band(v, Gene.LEG_THICKNESS, 0.10f, 0.35f, random); // spindly
				// A spider leg is not digitigrade: the second joint bends the same way as the
				// first, which is what lets the whole limb tent up over the body instead of
				// folding into an S and dropping the knee back down.
				band(v, Gene.DIGITIGRADE, 0f, 0.30f, random);
				// Roughly one arachnid in thirty comes out enormous. Kept this rare on purpose:
				// a giant is only frightening if the other twenty-nine were not, and the overall
				// height cap in BodyPlanBuilder still applies, so it cannot run away entirely.
				if (random.nextFloat() < GIANT_CHANCE) {
					band(v, Gene.SIZE, 0.78f, 0.96f, random);
					// Scaled-up spindles would snap under their own weight, and read as wire at
					// that size; a giant needs proportionally heavier legs.
					band(v, Gene.LEG_THICKNESS, 0.45f, 0.75f, random);
				} else {
					band(v, Gene.SIZE, 0.15f, 0.45f, random);
				}
				band(v, Gene.TORSO_LENGTH, 0.10f, 0.35f, random);  // compact cephalothorax
				band(v, Gene.TORSO_GIRTH, 0.35f, 0.65f, random);
				band(v, Gene.SPINE_SEGMENTS, 0f, 0.30f, random);
				band(v, Gene.NECK_LENGTH, 0f, 0.10f, random);
				band(v, Gene.TAIL_LENGTH, 0f, 0.10f, random);
				band(v, Gene.HEAD_SIZE, 0.20f, 0.50f, random);
				band(v, Gene.EYE_STYLE, 0.68f, 0.82f, random);     // the eight-eye cluster
				band(v, Gene.EYE_SIZE, 0.45f, 0.85f, random);
				band(v, Gene.ARM_PAIRS, 0.45f, 0.80f, random);     // one pair of pedipalps
				band(v, Gene.ARM_LENGTH, 0.10f, 0.30f, random);
				band(v, Gene.BLEND_SMOOTHNESS, 0f, 0.25f, random); // hard-edged, chitinous
				band(v, Gene.HORN_TYPE, 0f, 0.30f, random);        // no horns on a spider
				band(v, Gene.DIET, 0.70f, 1f, random);
			}
			case CRUSTACEAN -> {
				band(v, Gene.LEG_PAIRS, 0.55f, 1f, random);        // three or four pairs
				band(v, Gene.LEG_CLUSTERING, 0.55f, 0.85f, random);
				band(v, Gene.LEG_ARCH, 0.55f, 0.85f, random);
				band(v, Gene.LEG_SPLAY, 0.70f, 1f, random);
				band(v, Gene.LEG_LENGTH, 0.20f, 0.45f, random);
				band(v, Gene.SIZE, 0.15f, 0.45f, random);
				band(v, Gene.TORSO_GIRTH, 0.70f, 1f, random);      // broad carapace
				band(v, Gene.TORSO_LENGTH, 0.15f, 0.40f, random);
				band(v, Gene.NECK_LENGTH, 0f, 0.15f, random);
				band(v, Gene.EYE_STYLE, 0.52f, 0.64f, random);     // eyes on stalks
				band(v, Gene.ARMOR, 0.70f, 1f, random);
				band(v, Gene.ARMOR_COVERAGE, 0.70f, 1f, random);
				band(v, Gene.CLAWS, 0.70f, 1f, random);
				band(v, Gene.ARM_PAIRS, 0.86f, 1f, random);        // two pairs, the front ones claws
				band(v, Gene.ARM_LENGTH, 0.40f, 0.70f, random);
				band(v, Gene.TAIL_SHAPE, 0.22f, 0.38f, random);    // flat paddle
				band(v, Gene.TAIL_LENGTH, 0.30f, 0.60f, random);
				band(v, Gene.BLEND_SMOOTHNESS, 0f, 0.25f, random);
				band(v, Gene.HORN_TYPE, 0f, 0.30f, random);
			}
			default -> {
			}
		}
		return new Genome(v, random.nextLong(), random.nextLong(), 0);
	}

	private static void band(float[] values, Gene gene, float lo, float hi, RandomGenerator random) {
		values[gene.ordinal()] = lo + random.nextFloat() * (hi - lo);
	}

	/** Case-insensitive lookup for the spawn command; null when unrecognised. */
	public static Archetype byName(String name) {
		for (Archetype archetype : VALUES) {
			if (archetype.name().equalsIgnoreCase(name)) return archetype;
		}
		return null;
	}

	/** A random archetype, excluding CHAOS so that mixed spawns stay structured. */
	public static Archetype randomStructured(RandomGenerator random) {
		return VALUES[1 + random.nextInt(VALUES.length - 1)];
	}
}
