package dev.jsz.primordia.genome;

import java.util.EnumMap;
import java.util.Map;

/**
 * What each locus does, in one sentence, for anything that shows a genome to a person.
 * <p>
 * The {@link Gene} constants carry no prose of their own on purpose — an enum whose constants each
 * hold a paragraph stops being readable as a list, and the list is what that file is for. Kept
 * beside it instead, with {@code GeneDocTest} asserting every locus is covered so that a new gene
 * cannot be added without one.
 * <p>
 * These describe the <b>direction of the slider</b> rather than the trait in the abstract. The
 * reader is about to drag it, and what they need to know is what happens when they do: "body mass"
 * is a label, "everything gets bigger, and heavier animals move and breed more slowly" is a tooltip.
 */
public final class GeneDoc {

	private static final Map<Gene, String> TEXT = new EnumMap<>(Gene.class);

	private static void put(Gene gene, String text) {
		TEXT.put(gene, text);
	}

	static {
		// ---- gross morphology ----------------------------------------------------------------
		put(Gene.SIZE, "Overall body mass. Drives almost everything downstream: reach, health, "
				+ "energy cost, how deep the voice sits and how slowly the animal breeds.");
		put(Gene.TORSO_LENGTH, "How long the barrel of the body is, shoulder to hip.");
		put(Gene.TORSO_GIRTH, "How thick the torso is. Wide bodies weigh more and need their legs "
				+ "spaced further apart to keep them from fusing into one another.");
		put(Gene.TORSO_TAPER, "How much narrower the hips are than the shoulders. High gives a "
				+ "wedge, low an even tube.");
		put(Gene.SPINE_SEGMENTS, "Vertebrae in the trunk. More segments bend more smoothly over "
				+ "broken ground and cost more to mesh.");
		put(Gene.SPINE_ARCH, "Curve of the backbone: low sags like a lizard, high humps like a cat.");
		put(Gene.BLEND_SMOOTHNESS, "How softly limbs fair into the body. Too high and a leg "
				+ "dissolves into the torso instead of joining it.");

		// ---- neck and head -------------------------------------------------------------------
		put(Gene.NECK_LENGTH, "Shoulders to skull. Also lengthens the vocal tract, which lowers the "
				+ "formants and makes the animal sound bigger than its pitch alone suggests.");
		put(Gene.NECK_SEGMENTS, "Neck joints. More joints curve where fewer hinge.");
		put(Gene.NECK_THICKNESS, "How heavy the neck is for its length.");
		put(Gene.HEAD_SIZE, "Skull size against the body.");
		put(Gene.HEAD_ELONGATION, "Snout length. Long snouts spread the formants apart, and are the "
				+ "difference between a wolf and a bear at the same body mass.");
		put(Gene.JAW_SIZE, "How large the jaw is. Feeds straight into bite damage and predation.");
		put(Gene.CRANIUM_BULGE, "Braincase swelling behind the eyes.");
		put(Gene.EYE_SIZE, "Eye diameter. Large eyes read as nocturnal, or as prey.");
		put(Gene.EYE_COUNT, "Past the tick, a second pair of eyes.");
		put(Gene.EYE_SPACING, "How far apart the eyes sit: forward-facing predator against wide-set "
				+ "prey with a field of view behind it.");

		// ---- tail --------------------------------------------------------------------------
		put(Gene.TAIL_LENGTH, "Tail length. Long tails sway with the gait and carry much of what a "
				+ "silhouette reads as balance.");
		put(Gene.TAIL_SEGMENTS, "Tail joints. More joints whip, fewer stick out stiffly.");
		put(Gene.TAIL_THICKNESS, "How heavy the tail is at its base.");

		// ---- legs --------------------------------------------------------------------------
		put(Gene.LEG_PAIRS, "Pairs of legs. Two is a quadruped and keeps the opposed elbow-back, "
				+ "stifle-forward convention; three or more have no forelimbs and hindlimbs, just "
				+ "legs, and radiate their knees outward instead.");
		put(Gene.LEG_SEGMENTS, "Joints per leg. Three allows a digitigrade ankle bending against "
				+ "its own knee; two is a straight-through limb.");
		put(Gene.LEG_LENGTH, "How tall the animal stands. Long legs cover more ground per step, so "
				+ "they lower cadence, and cadence is what decides whether legs stride or blur.");
		put(Gene.LEG_THICKNESS, "Limb bulk. Thin limbs approach the mesher's minimum and force a "
				+ "finer, slower bake to keep them from vanishing between sample points.");
		put(Gene.LEG_SPLAY, "How far out to the side the feet plant. A wide stance is stable and "
				+ "spends fore-and-aft reach, which shortens the stride.");
		put(Gene.LIMB_RATIO, "How much a limb narrows from shoulder to foot. Low is a broad "
				+ "shoulder over a narrow ankle; high is a column.");
		put(Gene.DIGITIGRADE, "How far the animal walks on its toes, with a raised hock, rather "
				+ "than flat on the sole.");
		put(Gene.FOOT_SIZE, "Foot and toe size.");
		put(Gene.GAIT_OFFSET, "Phase spacing between the legs — the difference between a trot and a "
				+ "crawl at the same speed.");

		// ---- arms --------------------------------------------------------------------------
		put(Gene.ARM_PAIRS, "Whether a biped grows arms. Anything walking on four or more legs has "
				+ "no forelimbs free to be anything else.");
		put(Gene.ARM_LENGTH, "How far the arms reach.");
		put(Gene.ARM_THICKNESS, "Arm bulk.");

		// ---- dorsal ------------------------------------------------------------------------
		put(Gene.DORSAL_SPINES, "Past the tick, a row of spines or plates along the back.");
		put(Gene.DORSAL_SPINE_LENGTH, "How far those spines project.");

		// ---- ecology -----------------------------------------------------------------------
		put(Gene.DIET, "Continuous: pure herbivore at zero, pure carnivore at one. The interesting "
				+ "animals are in the middle.");
		put(Gene.METABOLISM, "Energy burn rate. Fast metabolisms move and breed quickly and starve "
				+ "quickly, and they push the voice toward chatter.");
		put(Gene.SPEED, "Top movement speed, capped against body mass — a heavy animal cannot buy "
				+ "its way out of being heavy.");
		put(Gene.STAMINA, "How long effort can be sustained before the animal has to stop.");
		put(Gene.AGGRESSION, "Willingness to attack rather than flee. Also roughens the voice, "
				+ "though far less than it once did.");
		put(Gene.SOCIABILITY, "How strongly the animal seeks its own kind. Social lineages call "
				+ "more often, and in longer and more tonal phrases.");
		put(Gene.FEAR, "How readily it flees. High fear pushes the voice toward whistles, which is "
				+ "what alarm calls converge on because a pure tone is hard to place.");
		put(Gene.CURIOSITY, "How far it will go to investigate something new.");
		put(Gene.TERRITORIALITY, "How hard it defends ground against its own kind.");
		put(Gene.NOCTURNALITY, "Whether it keeps to the night or the day. Past the tick, the night — most animals sit below it.");
		put(Gene.TEMP_PREFERENCE, "The temperature band it can settle in. A founder drawn at the "
				+ "wrong end simply never takes hold where it was born.");
		put(Gene.HUMIDITY_PREFERENCE, "The moisture band it can settle in.");
		put(Gene.ARMOR, "Damage resistance, with visible plating to match.");
		put(Gene.FECUNDITY, "How many offspring per breeding, against what each one costs.");
		put(Gene.MATURATION_RATE, "How quickly a juvenile reaches adult size. Slow growth means "
				+ "longer spent small, vulnerable and high-voiced.");
		put(Gene.LIFESPAN, "How long it lives, and so how many generations fit into a session.");
		put(Gene.BURROWING, "How much it digs.");
		put(Gene.NEST_BUILDING, "How much it builds nests, which is what makes territory matter.");
		put(Gene.GRAZING_IMPACT, "How hard it strips vegetation. High enough and the population "
				+ "eats out its own region and crashes.");
		put(Gene.MUTABILITY, "How fast this lineage mutates. Evolvability, itself evolvable — a "
				+ "population under pressure can evolve to change faster.");

		// ---- colour ------------------------------------------------------------------------
		put(Gene.HUE, "Primary body colour.");
		put(Gene.HUE_SECONDARY, "Second colour, used by the pattern.");
		put(Gene.SATURATION, "Colour intensity. Cave and deep-water lineages drift pale.");
		put(Gene.BRIGHTNESS, "How light or dark the animal reads overall.");
		put(Gene.PATTERN_TYPE, "Which markings the skin carries.");
		put(Gene.PATTERN_SCALE, "How large the markings are, from fine speckling to broad blocks.");
		put(Gene.PATTERN_CONTRAST, "How hard the pattern stands against the base colour.");
		put(Gene.COUNTERSHADING, "Pale underside against a dark back. The commonest camouflage in "
				+ "nature, and it works by cancelling the shading that would reveal the shape.");

		// ---- ornament ----------------------------------------------------------------------
		put(Gene.CLAWS, "Past the tick, claws on the feet.");
		put(Gene.HAND_STYLE, "What the forelimb ends in. Past the tick it is a hand, gripping rather "
				+ "than merely standing on the ground.");
		put(Gene.FOOT_TYPE, "Foot shape, from a hoof that keeps its width to the ground through to "
				+ "a talon that comes to a point.");
		put(Gene.SPINE_STYLE, "The form dorsal growths take: broad plates low, sharp spikes high. "
				+ "Past the tick the back carries them even where the dorsal locus alone would not.");
		put(Gene.FUR_CREST, "Past the tick, a crest or mane.");
		put(Gene.ARMOR_COVERAGE, "How much of the body the plating actually covers. Past the tick the "
				+ "hide thickens into a continuous armoured back.");
		put(Gene.EYE_STYLE, "Pupil and eye shape.");
		put(Gene.HORN_TYPE, "Which kind of horn: spikes, curves, antlers, crests.");
		put(Gene.HORN_SIZE, "How large the horns grow.");
		put(Gene.HORN_PAIRS, "Past the tick, a second pair of horns.");
		put(Gene.EAR_TYPE, "Ear shape.");
		put(Gene.EAR_SIZE, "Ear size. Large ears shed heat as well as hear.");
		put(Gene.FRILL, "Past the tick, a neck frill.");
		put(Gene.SNOUT_TYPE, "Past the tick, a distinct snout form such as a beak or a trunk.");
		put(Gene.TUSKS, "Past the tick, tusks projecting from the jaw.");
		put(Gene.TAIL_SHAPE, "Tail cross-section, round through flattened to finned.");
		put(Gene.TAIL_FIN_DEPTH, "How deep the tail fin is, where there is one.");

		// ---- light -------------------------------------------------------------------------
		put(Gene.BIOLUMINESCENCE, "Past the tick the animal glows and emits real light. The "
				+ "brightest lineages are underground, where there is a reason to be.");
		put(Gene.GLOW_REGION, "Which part of the body carries the glow.");
		put(Gene.GLOW_HUE, "What colour it glows.");

		// ---- body plan variants -------------------------------------------------------------
		put(Gene.BODY_SEGMENTATION, "Past the tick the torso divides into distinct segments rather "
				+ "than one barrel. The insect and crustacean tell.");
		put(Gene.ABDOMEN_SIZE, "How large the rear segment is, once the body is segmented.");
		put(Gene.LEG_CLUSTERING, "Whether the legs bunch together along the body or spread evenly "
				+ "down it.");
		put(Gene.LEG_ARCH, "How far the mid-joint tents above the hip. The arachnid tell, and it "
				+ "widens the stance to give those long legs somewhere to stand.");
		put(Gene.JAW_WIDTH, "How wide the gape is. The strongest timbre cue after body size: a wide "
				+ "jaw opens the front of the vocal tract and pulls the first formant up.");
		put(Gene.SUBTERRANEAN, "How committed the animal is to living underground.");
	}

	/** One sentence on what moving this locus does. */
	public static String describe(Gene gene) {
		return TEXT.getOrDefault(gene, "No description yet.");
	}

	/** Whether this locus has prose at all. The completeness test reads this. */
	public static boolean documented(Gene gene) {
		return TEXT.containsKey(gene);
	}

	private GeneDoc() {
	}
}
