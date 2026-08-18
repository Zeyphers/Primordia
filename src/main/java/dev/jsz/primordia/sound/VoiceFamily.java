package dev.jsz.primordia.sound;

/**
 * The kind of noise an animal makes, as distinct from how big or how cross it is.
 *
 * <h2>Why this exists</h2>
 * {@link VoiceProfile} derives every timbre parameter from the genome continuously, which sounds
 * like the right thing to do and is not. Measured across 4400 voices, nine of those parameters —
 * open quotient, speed quotient, chaos, subharmonic, shimmer, jump chance, jitter, spectral tilt
 * and biphonation — correlated with {@code AGGRESSION} at |r| between 0.56 and 0.95. The entire
 * roughness character of a voice was one gene. Two principal components accounted for 68% of all
 * variation in the population, and those two were, in effect, size and temper.
 * <p>
 * So the population sat on a sheet: every creature was somewhere between a big calm one and a small
 * cross one, and once a player had heard both corners they had heard the range. Widening the
 * individual parameter ranges cannot fix that, because it stretches the sheet without adding a
 * dimension to it.
 *
 * <h2>What a family is</h2>
 * A family is a <b>mechanism</b>, not a preset. Real animals do not differ by having the same throat
 * turned up louder; they differ by making sound in structurally different ways, and those ways fall
 * into a small number of recognisable kinds. A whistle is not a quiet roar — it is a near-sinusoidal
 * source through a narrow filter, and no amount of turning a roar down produces one. Each constant
 * below names the acoustic structure it reproduces and the animals it was modelled on.
 * <p>
 * Membership comes from the genome, so it is heritable and drifts with a lineage, and it is chosen
 * from loci other than aggression on purpose — a family that tracked temper would simply be the old
 * sheet with steps in it. Within a family aggression still does its work, but it does different work
 * in each: it roughens a bellow, sharpens a bark, and mostly just raises the pitch of a whistle,
 * because a whistling animal pushed harder does not start to growl.
 */
public enum VoiceFamily {

	/**
	 * Long, low, loud, and strongly period-doubled. Red deer, bison, alligator, howler monkey.
	 * <p>
	 * The subharmonic is the point: it puts a band an octave under the fundamental and the ear
	 * reads that as the fundamental, which is how a bellow sounds like a bigger animal than the one
	 * producing it. Slow onset, because a large animal takes time to move that much air.
	 */
	BELLOW,

	/**
	 * Short, hard-onset, repeated, broadband. Dog, seal, baboon, muntjac.
	 * <p>
	 * Almost all of a bark is its envelope — a few tens of milliseconds of attack and a fast decay,
	 * repeated with real silence in between. The silence matters as much as the sound; run the
	 * syllables together and it stops being a bark.
	 */
	BARK,

	/**
	 * A dense run of very short syllables. Wren, squirrel, tree frog, cicada-like birds.
	 * <p>
	 * The individual syllable stops being audible as an event and the repetition rate becomes the
	 * timbre. Small, fast-metabolism animals; the rate is what identifies the species.
	 */
	TRILL,

	/**
	 * Near-pure tone: narrow bandwidths, little noise, deep vibrato. Marmot, kite, dolphin, plover.
	 * <p>
	 * Structurally the opposite of a roar — the nonlinearities are suppressed rather than driven,
	 * and what is left is a single travelling partial. Alarm calls converge on this shape because a
	 * pure tone is hard to localise.
	 */
	WHISTLE,

	/**
	 * Mostly turbulence, barely voiced at all. Snake, goose, hissing cat, heron.
	 * <p>
	 * There is no meaningful fundamental; the tract shapes broadband noise directly, so the animal's
	 * size is carried entirely by the formants. Threat displays across a great many unrelated
	 * lineages land here, which is why it reads as menace regardless of who makes it.
	 */
	RASP,

	/**
	 * One long sustained note, gently onset. Cow, owl, whale, bittern.
	 * <p>
	 * Long-distance contact calls: low, slow and tonal because that is what survives distance and
	 * vegetation. The near-absence of roughness is what separates it from a bellow.
	 */
	MOAN,

	/**
	 * Discrete impulsive knocks with a ringing resonance. Gecko, some frogs, woodpecker drums.
	 * <p>
	 * Excitation is a burst rather than a fold cycle, so it borrows the insect path at a much lower
	 * repetition rate — slow enough that each knock is heard separately, which is the whole
	 * difference between knocking and buzzing.
	 */
	KNOCK,

	/**
	 * Two sources beating against each other, with the register breaking between them. Turkey,
	 * magpie, horse whinny, goat.
	 * <p>
	 * Biphonation and frequent bifurcations. The textbook demonstration that a voice is a nonlinear
	 * system rather than an oscillator, and the most obviously "alive" of these because nothing
	 * built out of one oscillator does it.
	 */
	WARBLE;

	public static final VoiceFamily[] VALUES = values();

	/** Lower-case name, for the lab and the editor. */
	public String label() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
