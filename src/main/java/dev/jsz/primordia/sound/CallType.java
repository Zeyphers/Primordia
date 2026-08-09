package dev.jsz.primordia.sound;

/**
 * The occasions a creature has to make a noise.
 * <p>
 * A call type is not a sound — it is a way of using the one voice a creature has. Each constant
 * carries the shaping the synthesiser applies on top of {@link VoiceProfile}: how long the call
 * runs, where the pitch sits relative to the animal's resting fundamental, and how hard it is
 * pushing its larynx. That last one, {@link #effort}, is what makes a threat and an idle mumble
 * from the same animal recognisably the same creature: both are built from its formants and its
 * fundamental, and only the drive and the brightness differ.
 * <p>
 * The ordinal is the wire format — {@link CreatureVoicePayload} sends a single byte — so
 * <b>append only</b>.
 */
public enum CallType {
	/** Idle contact call. The one heard most, so it is the least strained. */
	AMBIENT(0.85f, 1.00f, 0.30f, 1.00f, 0.00f),
	/** Warning before an attack: short, loud, driven hard, and pitched down to sound bigger. */
	THREAT(0.55f, 0.88f, 1.00f, 0.85f, -0.10f),
	/** Taking damage. Abrupt onset, a rising break of pitch, and audibly noisy. */
	HURT(0.34f, 1.18f, 0.85f, 0.55f, 0.22f),
	/** Dying. The longest call and the only one that runs out of air, sagging in pitch as it goes. */
	DEATH(1.15f, 0.95f, 0.70f, 1.30f, -0.35f),
	/** Advertising for a mate. Long, sustained, strongly vibratoed, and deliberately unlike a threat. */
	MATING(1.35f, 1.06f, 0.45f, 1.55f, 0.12f),
	/** A juvenile. Everything smaller: shorter, higher, and with no weight behind it. */
	CHIRP(0.40f, 1.55f, 0.35f, 0.55f, 0.15f),
	/** Breathing while asleep. Barely voiced at all — mostly air through the same tract. */
	SLEEP(1.00f, 0.80f, 0.05f, 0.70f, -0.08f);

	/** Seconds of one syllable, before the profile's own syllable length scales it. */
	public final float syllableScale;
	/** Multiplier on the resting fundamental. */
	public final float pitchScale;
	/** How hard the animal is pushing: drives distortion, roughness and spectral tilt. */
	public final float effort;
	/** Multiplier on how many syllables the profile would otherwise use, and on total length. */
	public final float lengthScale;
	/** Fractional pitch change across the call. Positive rises, negative sags. */
	public final float sweep;

	CallType(float syllableScale, float pitchScale, float effort, float lengthScale, float sweep) {
		this.syllableScale = syllableScale;
		this.pitchScale = pitchScale;
		this.effort = effort;
		this.lengthScale = lengthScale;
		this.sweep = sweep;
	}

	public static final CallType[] VALUES = values();

	public static CallType byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : AMBIENT;
	}

	/**
	 * Subtitle key. One per call type rather than one per creature: a subtitle names what happened,
	 * and "Creature snarls" is true of every creature that snarls.
	 */
	public String subtitleKey() {
		return "subtitles.primordia.creature." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
