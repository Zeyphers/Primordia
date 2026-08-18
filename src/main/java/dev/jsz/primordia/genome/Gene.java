package dev.jsz.primordia.genome;

/**
 * The gene loci of a creature. Every gene is a scalar normalised to {@code [0,1]};
 * meaning is assigned at decode time by {@link dev.jsz.primordia.body.BodyPlanBuilder}
 * (morphology) or by the ecology systems (behaviour).
 * <p>
 * Ordinals are the wire format, so <b>append new genes at the end only</b> and never
 * reorder — {@link Genome#decode(String)} reads by index. {@link #plasticity} scales how
 * far a locus drifts per mutation event: structural genes are conservative, colour genes
 * are volatile, which is what makes lineages stay recognisable while still diverging.
 */
public enum Gene {
	// ---- gross morphology -------------------------------------------------
	SIZE(0.35f),
	TORSO_LENGTH(0.45f, false, true),
	TORSO_GIRTH(0.5f, false, true),
	TORSO_TAPER(0.6f),
	SPINE_SEGMENTS(0.25f, 3, 8),
	SPINE_ARCH(0.6f),
	BLEND_SMOOTHNESS(0.5f, false, true),

	// ---- neck & head ------------------------------------------------------
	NECK_LENGTH(0.5f, false, true),
	NECK_SEGMENTS(0.25f, 1, 4),
	NECK_THICKNESS(0.5f),
	HEAD_SIZE(0.4f, false, true),
	HEAD_ELONGATION(0.6f),
	JAW_SIZE(0.7f, false, true),
	CRANIUM_BULGE(0.6f),
	EYE_SIZE(0.7f),
	EYE_COUNT(0.15f, 0.85f),
	EYE_SPACING(0.6f),

	// ---- tail -------------------------------------------------------------
	TAIL_LENGTH(0.6f),
	TAIL_SEGMENTS(0.3f, 1, 6),
	TAIL_THICKNESS(0.6f),

	// ---- legs -------------------------------------------------------------
	LEG_PAIRS(0.12f, 1, 4),
	LEG_SEGMENTS(0.2f, 2, 3),
	LEG_LENGTH(0.5f, false, true),
	LEG_THICKNESS(0.55f, false, true),
	LEG_SPLAY(0.6f, false, true),
	LIMB_RATIO(0.5f),
	DIGITIGRADE(0.5f),
	FOOT_SIZE(0.6f),
	GAIT_OFFSET(0.4f),

	// ---- arms / manipulators ---------------------------------------------
	ARM_PAIRS(0.1f),
	ARM_LENGTH(0.5f),
	ARM_THICKNESS(0.55f, false, true),

	// ---- ornament ---------------------------------------------------------
	DORSAL_SPINES(0.5f, 0.55f),
	DORSAL_SPINE_LENGTH(0.6f),

	// ---- ecology / behaviour (drives later milestones, drifts from day one) -
	DIET(0.3f),
	METABOLISM(0.45f),
	SPEED(0.4f),
	STAMINA(0.4f),
	AGGRESSION(0.5f),
	SOCIABILITY(0.5f),
	FEAR(0.5f),
	CURIOSITY(0.5f),
	TERRITORIALITY(0.45f),
	NOCTURNALITY(0.4f),
	TEMP_PREFERENCE(0.3f, false, true),
	HUMIDITY_PREFERENCE(0.3f, false, true),
	ARMOR(0.4f),
	FECUNDITY(0.35f),
	MATURATION_RATE(0.35f),
	LIFESPAN(0.3f),
	BURROWING(0.35f),
	NEST_BUILDING(0.35f),
	GRAZING_IMPACT(0.4f),

	/** Meta-gene: scales this individual's own mutation rate, so evolvability itself evolves. */
	MUTABILITY(0.6f),

	// ---- appearance -------------------------------------------------------
	HUE(0.8f),
	HUE_SECONDARY(0.8f),
	SATURATION(0.7f),
	BRIGHTNESS(0.7f),
	PATTERN_TYPE(0.9f, true),
	PATTERN_SCALE(0.8f),
	PATTERN_CONTRAST(0.8f),
	COUNTERSHADING(0.6f),

	// ---- detailed anatomical options --------------------------------------
	CLAWS(0.5f, 0.35f),
	HAND_STYLE(0.5f, true, 0.35f),
	FOOT_TYPE(0.5f, true),
	SPINE_STYLE(0.5f, true, 0.40f),
	FUR_CREST(0.5f, 0.45f),
	ARMOR_COVERAGE(0.5f, 0.42f),
	EYE_STYLE(0.5f, true),

	// ---- cranial ornament -------------------------------------------------
	// Horn type is structural (a lineage keeps its horns); horn size is a display trait and
	// drifts fast, which is what lets sexual selection run away with it over generations.
	HORN_TYPE(0.30f, true),
	HORN_SIZE(0.65f),
	HORN_PAIRS(0.18f, 0.74f),
	EAR_TYPE(0.40f, true),
	EAR_SIZE(0.6f),
	FRILL(0.45f, 0.62f),
	SNOUT_TYPE(0.35f, true, 0.68f),
	TUSKS(0.4f, 0.62f),

	// ---- tail shape -------------------------------------------------------
	TAIL_SHAPE(0.30f, true),
	TAIL_FIN_DEPTH(0.55f),

	// ---- bioluminescence --------------------------------------------------
	BIOLUMINESCENCE(0.45f, false, true, 0.82f),
	GLOW_REGION(0.55f, true),
	GLOW_HUE(0.85f),

	// ---- body architecture ------------------------------------------------
	/** 0 = one continuous trunk, 1 = cephalothorax and abdomen joined by a narrow waist. */
	BODY_SEGMENTATION(0.12f, 0.62f),
	ABDOMEN_SIZE(0.4f),
	/** 0 = legs spread evenly along the spine, 1 = all pairs packed onto the front segment. */
	LEG_CLUSTERING(0.18f),
	/** How far the mid joint rides above the hip. High values give the arachnid stance. */
	LEG_ARCH(0.25f, false, true),

	/** Broad crushing jaw against a narrow snatching one. */
	JAW_WIDTH(0.5f),

	/**
	 * How committed a lineage is to living underground: 0 surface, 1 fully cave-dwelling.
	 * <p>
	 * A locus rather than a flag on the archetype, because everything else about where a creature
	 * lives is heritable and this should be too. A surface lineage can drift down into the caves
	 * over generations and a cave one can come back out, and the region ledger will place each
	 * where its genome says it belongs without anything having declared it.
	 * <p>
	 * Low plasticity: moving between the surface and the dark is a change of everything — light,
	 * food, temperature, what can see you — so it should take a lineage many generations rather
	 * than happening in one unlucky mutation.
	 */
	SUBTERRANEAN(0.12f);

	public static final Gene[] VALUES = values();
	public static final int COUNT = VALUES.length;

	/** Relative drift magnitude per mutation event, in [0,1]. */
	public final float plasticity;

	/**
	 * Whether this locus selects a kind rather than an amount.
	 * <p>
	 * A magnitude has a sensible middle — a medium-length neck is a real neck, and drawing founders
	 * toward the middle keeps them plausible animals. A <i>category</i> has no middle: the centre of
	 * {@link dev.jsz.primordia.body.HornType} is not a compromise between a spike and a crest, it is
	 * simply option three. Drawing a categorical locus toward 0.5 therefore does not make founders
	 * moderate, it makes them all pick the same option — which is exactly what happened, and why
	 * antlers turned up on 2.6% of creatures and casques on 0.4% while nothing at all had a plain
	 * pair of eyes.
	 * <p>
	 * Founders draw these uniformly. Mutation is unaffected: drifting <i>between</i> neighbouring
	 * kinds is still governed by {@link #plasticity}, so a lineage keeps its horns.
	 */
	public final boolean categorical;

	/**
	 * Whether this locus has a viability window rather than merely a look.
	 * <p>
	 * {@link #plasticity} conflates two things that usually travel together and sometimes do not:
	 * how fast a locus drifts under mutation, and how safe it is to draw a founder anywhere along
	 * it. {@code LEG_THICKNESS} is volatile — limb proportions really do drift quickly — but a
	 * founder drawn at the bottom of it is a wireframe, not an animal. {@code TEMP_PREFERENCE} is
	 * volatile and a founder drawn at the wrong end simply does not belong where it was born.
	 * <p>
	 * Widening the founder draw by plasticity alone broke six regression tests at once, every one
	 * of them on a locus of this kind: limbs at a 50:1 slenderness ratio, a blend radius wider than
	 * the limb it was blending, knees above the hip on ordinary quadrupeds, skin weights bleeding
	 * across skeleton branches, glow on 15% of a plains population, and desert lineages that
	 * preferred the cold. Marking them keeps them on the old clustered draw, so founders start
	 * inside the window and mutation is still free to walk a lineage out of it later.
	 */
	public final boolean constrained;

	/**
	 * Lowest and highest whole value this locus decodes to, or {@code -1} when it is a magnitude
	 * rather than a count.
	 * <p>
	 * Declared here rather than at the call site so the number exists once. The editor draws a tick
	 * per option and snaps the slider to them, and a tick that disagrees with the decode is worse
	 * than no tick — it tells the player a lie they can act on. {@link Genome#discrete(Gene)} reads
	 * these, so the control and the creature cannot come apart.
	 */
	public final int discreteLo;
	public final int discreteHi;

	/** Whether this locus decodes to a whole count with named options. */
	public boolean isDiscrete() {
		return discreteHi > discreteLo;
	}

	/**
	 * The value at which a presence/absence trait switches on, or {@code -1} for a locus that has
	 * no such boundary.
	 * <p>
	 * Thirteen loci are switches wearing a slider: below the cut there are no claws, no frill, no
	 * second pair of eyes, and above it there are. The cut used to live at whichever call site
	 * happened to test it, which made it invisible to anything trying to describe the locus — and a
	 * boundary the player cannot see is a boundary they find by accident, after dragging a slider a
	 * long way for no visible reason. Declared here it is drawn as a tick, and the tick and the
	 * decode read the same number.
	 * <p>
	 * Where a locus drives more than one boundary this is the <b>first</b> one — the point where the
	 * trait appears at all. {@code DORSAL_SPINES} grows its row of spines here and only counts as
	 * armour higher up; {@code BIOLUMINESCENCE} starts glowing here and grows discrete photophores
	 * higher still. Those stricter tests are separate questions asked by their own systems, and they
	 * stay at their call sites rather than pretending to be the locus's own number.
	 */
	public final float threshold;

	/** Whether this locus switches a trait on at a named value. */
	public boolean hasThreshold() {
		return threshold >= 0f;
	}

	Gene(float plasticity, int discreteLo, int discreteHi) {
		this(plasticity, false, false, discreteLo, discreteHi, -1f);
	}

	Gene(float plasticity) {
		this(plasticity, false, false);
	}

	Gene(float plasticity, float threshold) {
		this(plasticity, false, false, -1, -1, threshold);
	}

	Gene(float plasticity, boolean categorical) {
		this(plasticity, categorical, false);
	}

	Gene(float plasticity, boolean categorical, float threshold) {
		this(plasticity, categorical, false, -1, -1, threshold);
	}

	Gene(float plasticity, boolean categorical, boolean constrained) {
		this(plasticity, categorical, constrained, -1, -1, -1f);
	}

	Gene(float plasticity, boolean categorical, boolean constrained, float threshold) {
		this(plasticity, categorical, constrained, -1, -1, threshold);
	}

	Gene(float plasticity, boolean categorical, boolean constrained,
	     int discreteLo, int discreteHi, float threshold) {
		this.discreteLo = discreteLo;
		this.discreteHi = discreteHi;
		this.threshold = threshold;
		this.plasticity = plasticity;
		this.categorical = categorical;
		this.constrained = constrained;
	}
}
