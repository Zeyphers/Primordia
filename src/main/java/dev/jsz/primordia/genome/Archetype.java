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
	CHAOS(0.50f, 0.50f, 0.50f, 0.00f),
	/** Upright, two long digitigrade legs, heavy tail for balance, small forelimbs. */
	BIPED(0.55f, 0.50f, 0.50f, 0.25f),
	/** Many splayed legs, low slung, small, armoured, segmented. */
	INSECTOID(0.50f, 0.62f, 0.70f, 0.00f),
	/** Enormous long-necked, long-tailed quadruped browser. */
	SAURIAN(0.12f, 0.60f, 0.62f, 0.65f),
	/** Bulky low-headed quadruped built for cropping vegetation in volume. */
	GRAZER(0.10f, 0.50f, 0.45f, 0.45f),
	/** Fast, social, mid-sized carnivore. */
	PACK_HUNTER(0.85f, 0.45f, 0.45f, 0.30f),
	/** Huge bipedal predator: massive jaws, vestigial arms, counterweight tail. */
	APEX(0.93f, 0.55f, 0.50f, 0.60f),
	/** Long-limbed, light-boned, built purely for speed. */
	SPRINTER(0.30f, 0.55f, 0.30f, 0.25f),
	/** Two-part body, four pairs of clustered high-kneed legs, eight eyes. Spider-shaped. */
	ARACHNID(0.82f, 0.55f, 0.65f, 0.10f),
	/** Broad, flat, heavily armoured; stalked eyes, big claws, a paddle tail. */
	CRUSTACEAN(0.55f, 0.45f, 0.85f, 0.20f),
	/**
	 * Small, pale, many-legged and bioluminescent. Lives in the dark and climbs.
	 * <p>
	 * The one archetype defined by where it lives rather than by what its body is for. Everything
	 * about it follows from the cave: it is small because a cave feeds little, it glows because
	 * nothing else down there does, it climbs because a cave has as much wall and ceiling as it has
	 * floor, and it is pale because colour is worth nothing where there is no light to show it.
	 */
	CAVE_CRAWLER(0.62f, 0.42f, 0.72f, 0.00f);

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

		// Everything that is not a cave crawler is pinned to the surface.
		//
		// SUBTERRANEAN is a new locus and no other archetype bands it, so it was taking the moderate
		// random draw — which clears the threshold often enough that ordinary surface animals were
		// coming out marked as cave dwellers, and the region ledger duly buried them. A locus that
		// decides where an animal lives cannot be left to chance in the archetypes that have already
		// decided.
		if (this != CAVE_CRAWLER) {
			band(v, Gene.SUBTERRANEAN, 0f, 0.25f, random);
		}

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
				// Longer, and held close to the body rather than sprawled. "Pillar legs" was
				// already the intent behind the thickness band below, but nothing had ever
				// actually narrowed the stance to match it — legs this heavy at a splay left to
				// chance came out sprawled far wider than a sauropod's near-vertical columns, and
				// the standing rest pose already sat close to the IK solver's stretch limit before
				// a single step had been taken, which is what read as the legs snapping.
				band(v, Gene.LEG_LENGTH, 0.55f, 0.85f, random);
				band(v, Gene.LEG_SPLAY, 0.05f, 0.25f, random);      // narrow, columnar stance
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
			case CAVE_CRAWLER -> {
				// Where it lives, which is the locus everything else here is downstream of.
				band(v, Gene.SUBTERRANEAN, 0.82f, 1f, random);

				// Small. A cave produces a fraction of the food a meadow does, and the size band is
				// the difference between something that can live on that and something that cannot.
				// It is also what keeps them climbable: the entity's wall-climbing is gated on mass.
				band(v, Gene.SIZE, 0.04f, 0.20f, random);
				band(v, Gene.TORSO_LENGTH, 0.20f, 0.50f, random);
				band(v, Gene.TORSO_GIRTH, 0.15f, 0.40f, random);

				// Many short legs held wide. A climber wants its weight close to the surface it is
				// on and its feet spread around it, which is the opposite of the tall narrow stance
				// a runner wants.
				band(v, Gene.LEG_PAIRS, 0.55f, 1f, random);        // three or four pairs
				band(v, Gene.LEG_SEGMENTS, 0.55f, 1f, random);
				band(v, Gene.LEG_LENGTH, 0.18f, 0.42f, random);
				band(v, Gene.LEG_THICKNESS, 0.22f, 0.45f, random);
				band(v, Gene.LEG_SPLAY, 0.75f, 1f, random);
				band(v, Gene.LEG_ARCH, 0.55f, 0.90f, random);
				band(v, Gene.DIGITIGRADE, 0f, 0.25f, random);
				band(v, Gene.CLAWS, 0.70f, 1f, random);            // what it hangs on with
				band(v, Gene.FOOT_TYPE, 0.60f, 1f, random);

				// It glows. This is the point of them — the thing you see first is a light moving
				// on a cave wall, and only then what is carrying it.
				band(v, Gene.BIOLUMINESCENCE, 0.90f, 1f, random);
				band(v, Gene.GLOW_REGION, 0f, 1f, random);
				// Cold colours. Warm bioluminescence reads as fire or lava and a cave has both;
				// blue-green is unmistakably alive.
				band(v, Gene.GLOW_HUE, 0.42f, 0.72f, random);

				// Pale and unpatterned. Pigment is a display trait and there is nothing down there
				// to display to, so cave animals the world over lose it.
				band(v, Gene.SATURATION, 0f, 0.18f, random);
				band(v, Gene.BRIGHTNESS, 0.55f, 0.90f, random);
				band(v, Gene.PATTERN_CONTRAST, 0f, 0.20f, random);
				band(v, Gene.COUNTERSHADING, 0f, 0.20f, random);

				// Large eyes and long feelers, or none worth the name — both are real cave
				// strategies, and the band is wide enough to produce each.
				band(v, Gene.EYE_SIZE, 0.10f, 0.95f, random);
				band(v, Gene.ARM_PAIRS, 0.45f, 0.80f, random);     // one pair, used as antennae
				band(v, Gene.ARM_LENGTH, 0.35f, 0.70f, random);

				// Awake in the dark, which underground is always.
				band(v, Gene.NOCTURNALITY, 0.75f, 1f, random);
				band(v, Gene.TEMP_PREFERENCE, 0.30f, 0.55f, random);
				band(v, Gene.HUMIDITY_PREFERENCE, 0.55f, 0.90f, random);
				// Scavengers and small predators. Nothing photosynthesises down there, so a pure
				// plant-eater has nothing to eat.
				band(v, Gene.DIET, 0.45f, 0.85f, random);
				band(v, Gene.FEAR, 0.55f, 0.90f, random);
				band(v, Gene.AGGRESSION, 0.10f, 0.40f, random);
				band(v, Gene.FECUNDITY, 0.55f, 0.90f, random);

				band(v, Gene.NECK_LENGTH, 0f, 0.20f, random);
				band(v, Gene.TAIL_LENGTH, 0.10f, 0.45f, random);
				band(v, Gene.HEAD_SIZE, 0.25f, 0.55f, random);
				band(v, Gene.BLEND_SMOOTHNESS, 0.10f, 0.40f, random);
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

	/** Where this body plan sits on the diet axis. The shape and the trophic level agree. */
	public final float dietCentre;
	/** The climate this body plan is built for, on the [0,1] scales the region records use. */
	public final float tempCentre;
	public final float humidityCentre;
	/**
	 * How much standing crop the region needs before this body plan is viable at all.
	 * <p>
	 * A four-tonne browser is not a thing a scree slope can produce, however the dice fall.
	 */
	public final float productivityNeed;

	Archetype(float dietCentre, float tempCentre, float humidityCentre, float productivityNeed) {
		this.dietCentre = dietCentre;
		this.tempCentre = tempCentre;
		this.humidityCentre = humidityCentre;
		this.productivityNeed = productivityNeed;
	}

	/**
	 * Picks a surface body plan suited to the job it is being founded for and the place it is being
	 * founded in.
	 * <p>
	 * This used to be a flat roll across the nine surface archetypes, after which the founder
	 * overwrote {@code DIET} and {@code SIZE} to whatever trophic slot it was filling. Two things
	 * followed from that, and both undercut the premise that an animal can be read.
	 * <p>
	 * <b>Shape said nothing about behaviour.</b> A region's grazer could be an {@link #APEX} — the
	 * body built around massive jaws and vestigial arms — with its diet quietly set to 0.1, and its
	 * predator could be a {@link #SAURIAN}, whose whole plan is a small head on a long browsing
	 * neck. The field guide asks the player to learn what a creature is by looking at it, and the
	 * generator was rolling the shape and the trophic level independently.
	 * <p>
	 * <b>And every biome drew from the same bag.</b> Climate reached only as far as hue: a desert
	 * and a rainforest produced the same distribution of bodies in different colours. Weighting the
	 * draw by climate is what makes somewhere feel like somewhere — the mechanism is already proven
	 * by cave richness, which is the one environmental term that was wired to anything.
	 * <p>
	 * Weighted rather than deterministic, so a region still holds surprises: an unlikely body plan
	 * in an unlikely place is uncommon, not impossible, which is the difference between a rule and
	 * a straitjacket.
	 */
	public static Archetype pickFor(RandomGenerator random, float diet,
	                                float temperature, float humidity, float productivity) {
		float[] weights = new float[VALUES.length];
		float total = 0f;
		for (int i = 0; i < VALUES.length; i++) {
			Archetype a = VALUES[i];
			if (a == CHAOS || a == CAVE_CRAWLER) continue;

			// Trophic fit dominates: a body is for getting a kind of food.
			float w = affinity(diet, a.dietCentre, 0.28f);
			w *= 0.45f + 0.55f * affinity(temperature, a.tempCentre, 0.45f);
			w *= 0.45f + 0.55f * affinity(humidity, a.humidityCentre, 0.45f);
			// A one-sided term: too little standing crop rules a large body plan out, too much
			// never rules anything out. Deserts have no sauropods; rainforests still have spiders.
			if (productivity < a.productivityNeed) {
				float shortfall = (a.productivityNeed - productivity) / Math.max(0.05f, a.productivityNeed);
				w *= Math.max(0.04f, 1f - shortfall * shortfall);
			}
			weights[i] = Math.max(1e-4f, w);
			total += weights[i];
		}

		float roll = random.nextFloat() * total;
		for (int i = 0; i < VALUES.length; i++) {
			roll -= weights[i];
			if (roll <= 0f && weights[i] > 0f) return VALUES[i];
		}
		return randomSurface(random);
	}

	/** A soft bell: 1 when the value sits on the centre, falling off over {@code width}. */
	private static float affinity(float value, float centre, float width) {
		float d = (value - centre) / width;
		return (float) Math.exp(-d * d);
	}

	/**
	 * A structured archetype that belongs above ground.
	 * <p>
	 * {@link #CAVE_CRAWLER} is excluded because it is defined by living somewhere the surface
	 * founder never looks. Rolled into a meadow it would be a small pale glowing thing standing in
	 * daylight with none of the reasons it is shaped that way — and the region ledger would then
	 * keep placing it there, since a lineage's home is decided by its genome.
	 */
	public static Archetype randomSurface(RandomGenerator random) {
		Archetype picked;
		do {
			picked = randomStructured(random);
		} while (picked == CAVE_CRAWLER);
		return picked;
	}

	/** Whether a genome's {@link Gene#SUBTERRANEAN} commits it to living underground. */
	public static boolean isSubterranean(Genome genome) {
		return genome.raw(Gene.SUBTERRANEAN) >= SUBTERRANEAN_THRESHOLD;
	}

	/**
	 * Where a lineage stops being a surface animal that visits caves and becomes a cave animal.
	 * <p>
	 * Deliberately high. Everything about the placement, the spawning and the light budget keys off
	 * this, so a lineage drifting a little way down the locus should not suddenly relocate its
	 * whole population underground.
	 */
	public static final float SUBTERRANEAN_THRESHOLD = 0.66f;
}
