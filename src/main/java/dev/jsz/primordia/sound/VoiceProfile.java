package dev.jsz.primordia.sound;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.util.Mth;

/**
 * A creature's vocal apparatus, derived from its genome and its body.
 * <p>
 * This is the whole voice: not a choice between recorded sounds but the physical description of an
 * instrument — how fast the folds beat, how long the tube in front of them is, how unstable and how
 * leaky the whole arrangement is. {@link VoiceSynth} plays that instrument; {@link CallType} decides
 * what it plays. Two creatures with the same body sound the same because they <i>are</i> the same
 * instrument, and a lineage keeps its voice across generations for the same reason it keeps its
 * shape: both are read off the same drifting numbers.
 *
 * <h2>Why a source-filter model</h2>
 * Every land vertebrate makes noise the same way: something buzzes, and a tube in front of it
 * colours the buzz. The buzz sets the pitch, which tracks body mass; the tube sets the timbre,
 * which tracks head and neck length. Modelling those two separately is what makes the results read
 * as animals rather than as synthesiser presets — and it is why a creature's size is audible.
 * Formant spacing is the cue humans actually use to judge how big a growling thing is, and here it
 * falls out of the geometry instead of being dialled in.
 *
 * <h2>Why the instability matters more than the tone</h2>
 * A clean source-filter model does not sound like an animal. It sounds like an organ, because a
 * perfectly periodic source through a fixed filter is what an organ is. What separates a real
 * throat from that are the <b>nonlinear phenomena</b> — the vocal folds are two floppy lumps of
 * tissue being forced by airflow, and they do not behave linearly. The bioacoustics literature
 * names four, and this record carries a parameter for each:
 * <ul>
 *   <li>{@link #chaos} — deterministic chaos, aperiodic vibration. The rasp in a roar or a scream.</li>
 *   <li>{@link #subharmonic} — period doubling, energy appearing at f0/2. The bottom of a growl.</li>
 *   <li>{@link #biphonation} — two sources vibrating at unrelated frequencies at once, beating
 *       against each other. A horse's whinny is the textbook case.</li>
 *   <li>{@link #jumpChance} — abrupt bifurcations, the voice breaking to another register.</li>
 * </ul>
 * These are not decoration on top of the tone. They are most of what makes a call sound alive, and
 * a voice without them reads as a struck instrument however carefully its formants are placed.
 *
 * <h2>Insects</h2>
 * A cricket has no larynx and no vocal tract; it scrapes a file against a scraper, which is a burst
 * of broadband noise rung through a sharp resonance. {@link #stridulation} crossfades to that, so
 * many-legged little things buzz and rasp instead of squeaking like small mammals.
 */
public record VoiceProfile(
		/** Stable identity, used as the voice's cache key. Equal genomes give equal hashes. */
		int hash,
		/** Resting fundamental in Hz — how fast the folds beat. Tracks body mass. */
		float f0,
		/** Four formant centre frequencies in Hz, low to high. The size cue. */
		float[] formantHz,
		/** Formant bandwidths in Hz. Wide is breathy and dull, narrow rings like a struck string. */
		float[] formantBw,
		/**
		 * How far each formant travels across a syllable, as a signed fraction of its centre.
		 * <p>
		 * A real animal moves its jaw and tongue while calling, and the resulting formant glide is a
		 * strong cue that something living is producing the sound. Fixed formants are the single
		 * clearest tell of a synthesiser.
		 */
		float[] formantMotion,
		/** Glottal open quotient in [0.25,0.9]: low is a buzzy blare, high is a soft hoot. */
		float openQuotient,
		/** Pulse asymmetry. Higher closes the folds faster, which brightens and hardens the voice. */
		float speedQuotient,
		/**
		 * Cutoff in Hz of the one-pole lowpass on the source — Klatt's spectral tilt.
		 * <p>
		 * The single most important parameter for not sounding like a mallet instrument. Real glottal
		 * flow has a gradual return phase, so its spectrum rolls off; an ideal impulse does not, and
		 * an impulse into a sharp resonator is a struck string by definition.
		 */
		float spectralTilt,
		/** Turbulent air escaping past the folds, in [0,1]. */
		float aspiration,
		/** Deterministic chaos: aperiodic fold vibration. The rasp. */
		float chaos,
		/** Period doubling, putting energy at f0/2. The bottom of a growl. */
		float subharmonic,
		/** Level of the second, independent oscillator. */
		float biphonation,
		/** Frequency ratio of that second oscillator. Deliberately not a simple fraction. */
		float biphonationRatio,
		/** Cycle-to-cycle pitch instability. Hoarseness. */
		float jitter,
		/** Cycle-to-cycle loudness instability. */
		float shimmer,
		/** Chance per syllable that the voice breaks to another register. */
		float jumpChance,
		/** Deliberate pitch wobble in Hz, and its depth as a fraction of f0. */
		float vibratoRate,
		float vibratoDepth,
		/** Crossfade to the insect path, in [0,1]. See the class note. */
		float stridulation,
		/** Rasp repetition rate in Hz when stridulating. */
		float stridulationRate,
		/** Syllables in a resting call. One is a bellow, six is a chatter. */
		int syllables,
		/** Seconds per syllable, and the silence between them — negative runs them together. */
		float syllableLen,
		float gapLen,
		/** Onset and release in seconds. A short attack is a bark; a long one is a moan. */
		float attack,
		float release,
		/** Strength of the nasal antiresonance, in [0,1]. */
		float nasality,
		/** Playback volume handed to the sound engine, before distance. */
		float volume
) {

	/** Speed of sound in air at body temperature, m/s. Sets the formants from the tube length. */
	private static final float SPEED_OF_SOUND = 350f;

	/**
	 * The voice of something with no genome yet — a creature mid-spawn, or a malformed one. Deep,
	 * dull and quiet, so a bug is audible as a bug rather than as a piercing noise.
	 */
	public static VoiceProfile fallback() {
		return new VoiceProfile(0, 180f,
				new float[]{520f, 1400f, 2600f, 3600f},
				new float[]{130f, 220f, 340f, 460f},
				new float[]{0.08f, -0.05f, 0.02f, 0f},
				0.55f, 0.4f, 2600f, 0.3f,
				0.12f, 0.2f, 0.05f, 1.31f,
				0.02f, 0.06f, 0.05f,
				5f, 0.01f,
				0f, 40f,
				2, 0.28f, 0.10f, 0.02f, 0.12f, 0.1f, 0.8f);
	}

	public static VoiceProfile of(Genome genome, BodyPlan plan) {
		if (genome == null || plan == null) return fallback();

		final DietGroup diet = DietGroup.of(genome);
		final float aggression = genome.raw(Gene.AGGRESSION);
		final float mass = Math.max(0.01f, plan.mass);
		final float hip = Math.max(0.05f, plan.hipHeight);
		final float bodyLength = Math.max(0.1f, plan.bodyLength);

		final float headSize = genome.raw(Gene.HEAD_SIZE);
		final float headElongation = genome.raw(Gene.HEAD_ELONGATION);
		final float neckLength = genome.raw(Gene.NECK_LENGTH);
		final float jawSize = genome.raw(Gene.JAW_SIZE);
		final float jawWidth = genome.raw(Gene.JAW_WIDTH);
		final float metabolism = genome.raw(Gene.METABOLISM);
		final float sociability = genome.raw(Gene.SOCIABILITY);
		final float fear = genome.raw(Gene.FEAR);
		final int legCount = plan.legs != null ? plan.legs.length : 4;

		// A per-genome scramble, so two animals that happen to share a mass are still not the same
		// voice. Individual variation on top of the systematic part, exactly as in a real population.
		final int hash = genome.hashCode();
		final float v1 = unitFromHash(hash, 0x9E3779B9);
		final float v2 = unitFromHash(hash, 0x85EBCA6B);
		final float v3 = unitFromHash(hash, 0xC2B2AE35);
		final float v4 = unitFromHash(hash, 0x27D4EB2F);
		final float v5 = unitFromHash(hash, 0x165667B1);

		// ---- the source: how fast the folds beat -----------------------------------------------
		//
		// Fundamental frequency falls with body size across essentially every animal that has vocal
		// folds — heavier folds beat slower, the same reason a bass string is thicker.
		float acousticSize = mass * 0.8f + hip * hip * 1.6f;
		float f0 = 900f / (1f + 3.2f * (float) Math.pow(Math.max(0.02f, acousticSize), 0.62));
		f0 *= 1.14f - headSize * 0.28f;
		f0 *= 1.0f + (0.5f - aggression) * 0.12f;
		f0 *= 0.88f + v1 * 0.24f;
		f0 = Mth.clamp(f0, 42f, 1500f);

		// ---- the filter: the tube in front of them ----------------------------------------------
		//
		// Modelled as a uniform tube closed at the glottis, whose resonances sit at odd multiples of
		// c/4L. The tube is the snout and throat, not the whole animal — body length barely enters
		// it, because a long-bodied creature does not carry a long windpipe just for being long.
		float tract = 0.045f
				+ hip * (0.13f + 0.22f * headElongation) * (0.6f + headSize * 0.8f)
				+ bodyLength * 0.030f
				+ neckLength * hip * 0.14f;
		// Past about half a metre the tube model stops describing anything real: it would put the
		// first formant underneath the fundamental, where there is no partial left for it to shape.
		tract = Mth.clamp(tract * (0.9f + v2 * 0.2f), 0.030f, 0.52f);

		float f1 = SPEED_OF_SOUND / (4f * tract);
		// A wide, gaping jaw opens the front of the tube and pulls the first formant up; a narrow
		// snatching one leaves it low. This is the single strongest timbre cue after size.
		f1 *= 0.78f + jawWidth * 0.5f + jawSize * 0.22f;
		// The floor that guarantees the filter has something to work on.
		f1 = Math.max(f1, f0 * 1.5f);

		float[] formantHz = {
				Mth.clamp(f1, 90f, 3200f),
				Mth.clamp(f1 * (2.6f + headElongation * 1.1f) * (0.94f + v3 * 0.12f), 200f, 7000f),
				Mth.clamp(f1 * (4.4f + headElongation * 1.6f) * (0.94f + v4 * 0.12f), 400f, 9000f),
				Mth.clamp(f1 * (6.3f + headElongation * 2.0f) * (0.94f + v5 * 0.12f), 700f, 10000f)
		};

		// Bandwidths are deliberately wide. Narrow resonators ring on after the pulse that excited
		// them, and a ringing resonator struck by a periodic source is a struck string — the exact
		// failure that made an early version of this sound like a piano. Soft tissue is lossy, and
		// modelling that loss honestly is what stops the tract from chiming.
		float damping = 0.8f + metabolism * 0.5f + (float) Math.log1p(mass) * 0.25f;
		float[] formantBw = {
				formantHz[0] * 0.13f * damping + 60f,
				formantHz[1] * 0.15f * damping + 85f,
				formantHz[2] * 0.18f * damping + 130f,
				formantHz[3] * 0.22f * damping + 190f
		};

		// The jaw closing through the call drags the first formant down and the second up — the
		// commonest articulatory gesture there is, and audibly a mouth rather than a filter bank.
		float gesture = 0.10f + jawWidth * 0.22f + aggression * 0.10f;
		float[] formantMotion = {
				-gesture,
				gesture * (0.35f + v3 * 0.4f),
				gesture * 0.15f,
				0f
		};

		// ---- nonlinear phenomena: the part that sounds alive -------------------------------------
		//
		// Chaos is aperiodic fold vibration, and it is the whole of the rasp in a roar. Predators
		// and anything pushing hard live at the top of this range.
		float chaos = Mth.clamp(
				aggression * 0.55f + (diet == DietGroup.CARNIVORE ? 0.18f : 0f) + v2 * 0.12f,
				0.03f, 0.85f);

		// Period doubling puts a band an octave below f0. It is why a growl sounds bigger than the
		// animal making it — the ear reads the subharmonic as the fundamental.
		float subharmonic = Mth.clamp(aggression * 0.6f + (float) Math.log1p(mass) * 0.18f + v1 * 0.1f,
				0.02f, 0.8f);

		// Two sources at once. Kept modest — a little is roughness, a lot is a horse whinny.
		float biphonation = Mth.clamp(0.04f + v4 * 0.18f + aggression * 0.12f, 0f, 0.35f);
		// Deliberately not a simple ratio: a simple one fuses into one tone, and the beating is the
		// entire point.
		float biphonationRatio = 1.21f + v5 * 0.55f;

		float jumpChance = Mth.clamp(0.05f + aggression * 0.22f + fear * 0.12f, 0f, 0.45f);

		// ---- source shaping ----------------------------------------------------------------------
		//
		// Spectral tilt. Low cutoff is a dull, breathy, closed sound; high is a hard bright blare.
		// Big animals are darker, and effort brightens (applied per call in the synthesiser).
		float spectralTilt = Mth.clamp(
				900f + aggression * 2600f + (1f - (float) Math.log1p(mass)) * 900f + v3 * 500f,
				450f, 5200f);

		float aspiration = Mth.clamp(
				0.14f + metabolism * 0.22f + (float) Math.log1p(mass) * 0.14f - aggression * 0.06f,
				0.06f, 0.75f);

		float openQuotient = Mth.clamp(0.72f - aggression * 0.33f + v1 * 0.14f, 0.25f, 0.9f);
		float speedQuotient = Mth.clamp(0.35f + aggression * 0.45f + v2 * 0.15f, 0.25f, 0.9f);

		float jitter = Mth.clamp(0.008f + chaos * 0.06f + fear * 0.02f, 0f, 0.10f);
		float shimmer = Mth.clamp(0.04f + chaos * 0.30f, 0f, 0.4f);

		// Vibrato is control, not strain — the opposite signal to chaos. Social animals with long
		// calls use it; it is kept shallow so it never reads as a synthesiser LFO.
		float vibratoRate = 3.5f + sociability * 4.5f + v4 * 2f;
		float vibratoDepth = Mth.clamp(0.004f + sociability * 0.020f - aggression * 0.010f, 0f, 0.03f);

		// ---- insects ----------------------------------------------------------------------------
		float legFactor = Mth.clamp((legCount - 4) / 6f, 0f, 1f);
		float smallness = Mth.clamp(1f - acousticSize * 2.2f, 0f, 1f);
		float stridulation = Mth.clamp(legFactor * smallness * 1.4f, 0f, 1f);
		float stridulationRate = 26f + (1f - smallness) * 20f + legFactor * 40f + v2 * 22f;

		// ---- phrasing ---------------------------------------------------------------------------
		float chatter = Mth.clamp(1.25f - acousticSize * 1.1f, 0f, 1f);
		int syllables = 1 + Math.round(chatter * (2.5f + sociability * 3.5f));
		syllables = Mth.clamp(syllables, 1, 8);

		float syllableLen = Mth.clamp(0.10f + acousticSize * 0.22f + (1f - chatter) * 0.18f, 0.07f, 0.85f);
		// Negative gaps run syllables together. Isolated syllables separated by silence are heard as
		// separate events — as notes — and a call that is a run of notes is a melody, not an animal.
		float gapLen = Mth.clamp(0.055f - sociability * 0.075f + (1f - chatter) * 0.05f, -0.045f, 0.16f);

		float attack = Mth.clamp(0.070f - aggression * 0.050f + syllableLen * 0.10f, 0.008f, 0.16f);
		float release = Mth.clamp(syllableLen * (0.45f + (1f - aggression) * 0.45f), 0.04f, 0.6f);

		float nasality = Mth.clamp(headElongation * 0.55f - jawWidth * 0.2f, 0f, 0.55f);

		float volume = Mth.clamp(0.55f + (float) Math.log1p(mass) * 0.30f, 0.45f, 1.35f);

		return new VoiceProfile(hash, f0, formantHz, formantBw, formantMotion,
				openQuotient, speedQuotient, spectralTilt, aspiration,
				chaos, subharmonic, biphonation, biphonationRatio,
				jitter, shimmer, jumpChance, vibratoRate, vibratoDepth,
				stridulation, stridulationRate,
				syllables, syllableLen, gapLen, attack, release, nasality, volume);
	}

	/** Pitch handed to the sound engine. Kept at 1 — the pitch is baked into the samples. */
	public float playbackPitch() {
		return 1.0f;
	}

	private static float unitFromHash(int hash, int salt) {
		int x = hash ^ salt;
		x ^= x >>> 16;
		x *= 0x7FEB352D;
		x ^= x >>> 15;
		x *= 0x846CA68B;
		x ^= x >>> 16;
		return (x >>> 8) / (float) (1 << 24);
	}
}
