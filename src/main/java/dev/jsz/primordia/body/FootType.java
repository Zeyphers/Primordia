package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;

/**
 * What a creature stands on.
 * <p>
 * {@link Gene#FOOT_TYPE} has existed since the gene table was written and was read by nothing at
 * all — every creature in the game, whatever it was, ended a leg in one short forward-pointing
 * bone. The locus was rolled on every spawn, banded by {@link dev.jsz.primordia.genome.Archetype
 * #CAVE_CRAWLER}, saved to disk, and then ignored.
 * <p>
 * Feet are worth the geometry. They are at eye level on anything small, they are the part of an
 * animal nearest the player, and they say what the ground is like where it lives — a hoof is open
 * country, a splayed foot is soft ground, a talon is something that grips. Two creatures identical
 * from the knee up read as different animals if one has hooves and the other has claws.
 */
public enum FootType {
	/** One solid blunt toe. Open, hard ground. */
	HOOF(1, 0.80f, 1.25f, 0.90f),
	/** A short round pad. The default-looking foot, and the commonest. */
	PAD(1, 0.95f, 1.05f, 0.80f),
	/** Three toes fanned forward. Soft ground, spread load. */
	SPLAYED(3, 1.05f, 0.78f, 0.70f),
	/** Long narrow toes that grip. Predators and climbers. */
	TALON(3, 1.35f, 0.72f, 0.55f),
	/** Wide and flat, toes joined. Mud and shallow water. */
	WEBBED(3, 0.90f, 0.88f, 0.85f),
	/** A small hard point. Arthropods, which stand on the tips of their legs. */
	CHITIN(1, 0.55f, 0.75f, 0.55f);

	/** Toes emitted. */
	public final int toes;
	/** Forward extent, as a multiple of the creature's foot size. */
	public final float reach;
	/** Toe thickness, as a multiple of what a plain foot would use. */
	public final float girth;
	/** How blunt the far end is: 1 keeps its width, 0 tapers to nothing. */
	public final float bluntness;

	FootType(int toes, float reach, float girth, float bluntness) {
		this.toes = toes;
		this.reach = reach;
		this.girth = girth;
		this.bluntness = bluntness;
	}

	public static final FootType[] VALUES = values();

	public static FootType of(Genome genome) {
		return VALUES[genome.discrete(Gene.FOOT_TYPE, 0, VALUES.length - 1)];
	}

	/** How far this foot's toes fan out to either side, in radians from straight ahead. */
	public float spread() {
		return toes <= 1 ? 0f : (this == WEBBED ? 0.55f : 0.42f);
	}

	/**
	 * The narrowest radius this foot will emit, as a multiple of the leg's own thickness.
	 * <p>
	 * The mesher sizes its blend radius and its sampling grid against the thinnest thing on the
	 * animal, and that is very often a toe tip. Both of those are settled long before the feet are
	 * built, so the figure has to be predictable from the type alone rather than measured after
	 * the fact — an assumed taper that the actual foot then undercut left the blend radius wider
	 * than the toe it was blending, which fairs the foot away into the ankle.
	 */
	public float narrowestFactor(float limbTaper, float footTaper) {
		return limbTaper * girth * Math.max(footTaper, bluntness);
	}
}
