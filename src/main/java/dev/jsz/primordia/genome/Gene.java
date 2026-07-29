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
	TORSO_LENGTH(0.45f),
	TORSO_GIRTH(0.5f),
	TORSO_TAPER(0.6f),
	SPINE_SEGMENTS(0.25f),
	SPINE_ARCH(0.6f),
	BLEND_SMOOTHNESS(0.5f),

	// ---- neck & head ------------------------------------------------------
	NECK_LENGTH(0.5f),
	NECK_SEGMENTS(0.25f),
	NECK_THICKNESS(0.5f),
	HEAD_SIZE(0.4f),
	HEAD_ELONGATION(0.6f),
	JAW_SIZE(0.7f),
	CRANIUM_BULGE(0.6f),
	EYE_SIZE(0.7f),
	EYE_COUNT(0.15f),
	EYE_SPACING(0.6f),

	// ---- tail -------------------------------------------------------------
	TAIL_LENGTH(0.6f),
	TAIL_SEGMENTS(0.3f),
	TAIL_THICKNESS(0.6f),

	// ---- legs -------------------------------------------------------------
	LEG_PAIRS(0.12f),
	LEG_SEGMENTS(0.2f),
	LEG_LENGTH(0.5f),
	LEG_THICKNESS(0.55f),
	LEG_SPLAY(0.6f),
	LIMB_RATIO(0.5f),
	DIGITIGRADE(0.5f),
	FOOT_SIZE(0.6f),
	GAIT_OFFSET(0.4f),

	// ---- arms / manipulators ---------------------------------------------
	ARM_PAIRS(0.1f),
	ARM_LENGTH(0.5f),
	ARM_THICKNESS(0.55f),

	// ---- ornament ---------------------------------------------------------
	DORSAL_SPINES(0.5f),
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
	TEMP_PREFERENCE(0.3f),
	HUMIDITY_PREFERENCE(0.3f),
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
	PATTERN_TYPE(0.9f),
	PATTERN_SCALE(0.8f),
	PATTERN_CONTRAST(0.8f),
	COUNTERSHADING(0.6f),

	// ---- detailed anatomical options --------------------------------------
	CLAWS(0.5f),
	HAND_STYLE(0.5f),
	FOOT_TYPE(0.5f),
	SPINE_STYLE(0.5f),
	FUR_CREST(0.5f),
	ARMOR_COVERAGE(0.5f),
	EYE_STYLE(0.5f),

	// ---- cranial ornament -------------------------------------------------
	// Horn type is structural (a lineage keeps its horns); horn size is a display trait and
	// drifts fast, which is what lets sexual selection run away with it over generations.
	HORN_TYPE(0.30f),
	HORN_SIZE(0.65f),
	HORN_PAIRS(0.18f),
	EAR_TYPE(0.40f),
	EAR_SIZE(0.6f),
	FRILL(0.45f),
	SNOUT_TYPE(0.35f),
	TUSKS(0.4f),

	// ---- tail shape -------------------------------------------------------
	TAIL_SHAPE(0.30f),
	TAIL_FIN_DEPTH(0.55f),

	// ---- bioluminescence --------------------------------------------------
	BIOLUMINESCENCE(0.45f),
	GLOW_REGION(0.55f),
	GLOW_HUE(0.85f),

	// ---- body architecture ------------------------------------------------
	/** 0 = one continuous trunk, 1 = cephalothorax and abdomen joined by a narrow waist. */
	BODY_SEGMENTATION(0.12f),
	ABDOMEN_SIZE(0.4f),
	/** 0 = legs spread evenly along the spine, 1 = all pairs packed onto the front segment. */
	LEG_CLUSTERING(0.18f),
	/** How far the mid joint rides above the hip. High values give the arachnid stance. */
	LEG_ARCH(0.25f),

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

	Gene(float plasticity) {
		this.plasticity = plasticity;
	}
}
