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
		float volume,
		/** Which kind of noise this animal makes. See {@link VoiceFamily}. */
		VoiceFamily family
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
				2, 0.28f, 0.10f, 0.02f, 0.12f, 0.1f, 0.8f, VoiceFamily.MOAN);
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
		final float v1 = variate(genome, 0x9E3779B9);
		final float v2 = variate(genome, 0x85EBCA6B);
		final float v3 = variate(genome, 0xC2B2AE35);
		final float v4 = variate(genome, 0x27D4EB2F);
		final float v5 = variate(genome, 0x165667B1);
		final float v6 = variate(genome, 0x2545F491);
		final float v7 = variate(genome, 0x94D049BB);

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
		//
		// Aggression is weighted down through this whole section, and that is the point of it.
		// Measured over 4400 voices, nine parameters here tracked aggression between 0.56 and 0.95,
		// so the entire roughness of a voice was one gene and two components accounted for 68% of
		// all variation in the population. A crow is not an angry animal and a stag's roar is nearly
		// tonal; roughness is a property of an individual larynx, and the individual variate now
		// carries as much of it as the temper does.
		float chaos = Mth.clamp(
				aggression * 0.26f + (diet == DietGroup.CARNIVORE ? 0.14f : 0f)
						+ v2 * 0.34f + metabolism * 0.12f,
				0.03f, 0.85f);

		// Period doubling puts a band an octave below f0. It is why a growl sounds bigger than the
		// animal making it — the ear reads the subharmonic as the fundamental.
		float subharmonic = Mth.clamp(aggression * 0.24f + (float) Math.log1p(mass) * 0.22f
						+ v6 * 0.36f, 0.02f, 0.8f);

		// Two sources at once. Kept modest — a little is roughness, a lot is a horse whinny.
		float biphonation = Mth.clamp(0.02f + v4 * 0.34f + aggression * 0.05f, 0f, 0.45f);
		// Deliberately not a simple ratio: a simple one fuses into one tone, and the beating is the
		// entire point.
		float biphonationRatio = 1.21f + v5 * 0.55f;

		float jumpChance = Mth.clamp(0.03f + aggression * 0.10f + fear * 0.12f + v7 * 0.26f,
				0f, 0.45f);

		// ---- source shaping ----------------------------------------------------------------------
		//
		// Spectral tilt. Low cutoff is a dull, breathy, closed sound; high is a hard bright blare.
		// Big animals are darker, and effort brightens (applied per call in the synthesiser).
		float spectralTilt = Mth.clamp(
				700f + aggression * 1100f + (1f - (float) Math.log1p(mass)) * 900f
						+ v3 * 2300f + headElongation * 500f,
				450f, 5200f);

		float aspiration = Mth.clamp(
				0.10f + metabolism * 0.20f + (float) Math.log1p(mass) * 0.14f
						- aggression * 0.05f + v5 * 0.28f,
				0.06f, 0.85f);

		// Centred so the variate can use the whole legal range. Written around 0.74 these two sat
		// hard against their own clamps, which quietly threw away most of the individual
		// variation and left temper as the only thing still moving them.
		float openQuotient = Mth.clamp(0.575f - aggression * 0.09f + (v1 - 0.5f) * 0.56f,
				0.25f, 0.9f);
		float speedQuotient = Mth.clamp(0.575f + aggression * 0.11f + (v5 - 0.5f) * 0.54f,
				0.25f, 0.9f);

		// Derived from chaos before, which made them a third copy of the same axis rather than
		// separate readings. Hoarseness and loudness instability really are correlated with rasp,
		// but they are not the same measurement, and modelling them as one flattened both.
		float jitter = Mth.clamp(0.006f + chaos * 0.030f + fear * 0.02f + v7 * 0.035f, 0f, 0.10f);
		float shimmer = Mth.clamp(0.02f + chaos * 0.14f + v6 * 0.22f, 0f, 0.4f);

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


		// ---- family: what kind of noise this is -------------------------------------------------
		//
		// Everything above places the animal on a sheet of size against temper. This is the axis
		// that is not on that sheet, and it is a mechanism rather than a setting: a whistle is not a
		// quiet roar, it is a different way of making sound. See VoiceFamily for the measurements
		// that made it necessary.
		VoiceFamily family = chooseFamily(acousticSize, sociability, metabolism, fear,
				aggression, headElongation, jawWidth, v3, v4);

		switch (family) {
			case BELLOW -> {
				f0 *= 0.70f;
				subharmonic = Math.max(subharmonic, 0.58f);
				spectralTilt *= 0.70f;
				syllables = Math.max(1, syllables / 2);
				syllableLen *= 1.75f;
				attack = Mth.clamp(attack * 2.4f, 0.02f, 0.30f);
				release *= 1.5f;
				vibratoDepth *= 0.45f;
			}
			case BARK -> {
				// The silence is half the sound. A bark with the gaps closed is a growl.
				syllableLen *= 0.34f;
				gapLen = Math.max(gapLen, 0.055f);
				syllables = Mth.clamp(syllables + 1, 2, 8);
				attack = 0.005f;
				release *= 0.32f;
				spectralTilt *= 1.45f;
				chaos = Math.min(0.9f, chaos * 1.15f);
				for (int i = 0; i < formantMotion.length; i++) formantMotion[i] *= 1.4f;
			}
			case TRILL -> {
				// Past about fifteen a second the syllable stops being an event and the rate becomes
				// the timbre, which is the entire identity of this family.
				syllables = 8;
				syllableLen = Mth.clamp(syllableLen * 0.20f, 0.028f, 0.10f);
				gapLen = Mth.clamp(gapLen * 0.25f, -0.01f, 0.03f);
				f0 *= 1.32f;
				chaos *= 0.30f;
				subharmonic *= 0.30f;
				jitter *= 0.5f;
				vibratoRate *= 1.6f;
			}
			case WHISTLE -> {
				// Suppressed rather than driven: what is left when the nonlinearities are taken away
				// is one partial, and that is what a whistle is.
				f0 *= 1.55f;
				chaos *= 0.10f;
				subharmonic *= 0.12f;
				biphonation *= 0.18f;
				aspiration *= 0.30f;
				jumpChance *= 0.25f;
				spectralTilt *= 0.55f;
				openQuotient = Mth.clamp(openQuotient + 0.18f, 0.25f, 0.92f);
				vibratoDepth = Mth.clamp(vibratoDepth * 2.4f + 0.006f, 0f, 0.05f);
				for (int i = 0; i < formantBw.length; i++) formantBw[i] *= 0.42f;
			}
			case RASP -> {
				// No usable fundamental; the tract shapes broadband noise and the formants carry the
				// whole size cue on their own.
				aspiration = Math.max(aspiration, 0.78f);
				chaos = Math.max(chaos, 0.62f);
				subharmonic *= 0.35f;
				spectralTilt *= 1.30f;
				f0 *= 0.92f;
				syllableLen *= 1.35f;
				attack = Mth.clamp(attack * 1.8f, 0.01f, 0.20f);
				for (int i = 0; i < formantBw.length; i++) formantBw[i] *= 1.55f;
			}
			case MOAN -> {
				f0 *= 0.84f;
				syllables = Mth.clamp(syllables / 2, 1, 2);
				syllableLen *= 2.10f;
				attack = Mth.clamp(attack * 3.0f, 0.03f, 0.34f);
				release *= 1.9f;
				chaos *= 0.38f;
				jumpChance *= 0.3f;
				vibratoRate *= 0.55f;
			}
			case KNOCK -> {
				// Borrows the insect excitation at a rate slow enough that the knocks are separate
				// events. Above roughly thirty a second this family stops knocking and starts buzzing.
				stridulation = Math.max(stridulation, 0.62f);
				stridulationRate = 7f + v4 * 11f;
				syllableLen *= 0.55f;
				syllables = Mth.clamp(syllables, 2, 6);
				for (int i = 0; i < formantBw.length; i++) formantBw[i] *= 0.55f;
			}
			case WARBLE -> {
				biphonation = Math.max(biphonation, 0.32f);
				biphonationRatio = 1.37f + v5 * 0.42f;
				jumpChance = Math.max(jumpChance, 0.38f);
				syllables = Mth.clamp(syllables + 1, 3, 8);
				vibratoRate *= 1.35f;
				for (int i = 0; i < formantMotion.length; i++) formantMotion[i] *= 1.8f;
			}
		}

		f0 = Mth.clamp(f0, 40f, 1800f);
		syllableLen = Mth.clamp(syllableLen, 0.025f, 0.95f);
		chaos = Mth.clamp(chaos, 0f, 0.9f);
		subharmonic = Mth.clamp(subharmonic, 0f, 0.85f);
		aspiration = Mth.clamp(aspiration, 0.04f, 0.92f);
		stridulation = Mth.clamp(stridulation, 0f, 1f);

		return new VoiceProfile(hash, f0, formantHz, formantBw, formantMotion,
				openQuotient, speedQuotient, spectralTilt, aspiration,
				chaos, subharmonic, biphonation, biphonationRatio,
				jitter, shimmer, jumpChance, vibratoRate, vibratoDepth,
				stridulation, stridulationRate,
				syllables, syllableLen, gapLen, attack, release, nasality, volume, family);
	}

	/** Pitch handed to the sound engine. Kept at 1 — the pitch is baked into the samples. */
	public float playbackPitch() {
		return 1.0f;
	}

	/**
	 * Traits each family is defined by, in the order {@link #chooseFamily} compares them:
	 * size, sociability, metabolism, fear, aggression, head elongation, jaw width.
	 * <p>
	 * A family is a point in trait space and an animal joins the one it most resembles. This
	 * replaced a set of hand-summed scores, which is the obvious way to do it and does not work:
	 * scores built from different numbers of terms have different means, so the families with the
	 * fattest sums simply win. Measured, that put 78% of the population into three families and left
	 * warble on 0.9% — eight families on paper and three in the world. Distances to prototypes are
	 * naturally comparable, so the spread comes out of the geometry instead of out of tuning.
	 */
	private static final float[][] FAMILY_TRAITS = {
			//        size  social  metab   fear   aggr  elong    jaw
			/* BELLOW  */ {0.90f, 0.40f, 0.25f, 0.20f, 0.70f, 0.50f, 0.60f},
			/* BARK    */ {0.50f, 0.30f, 0.70f, 0.30f, 0.80f, 0.45f, 0.60f},
			/* TRILL   */ {0.08f, 0.80f, 0.90f, 0.50f, 0.30f, 0.40f, 0.40f},
			/* WHISTLE */ {0.25f, 0.60f, 0.60f, 0.88f, 0.15f, 0.50f, 0.22f},
			/* RASP    */ {0.40f, 0.18f, 0.50f, 0.62f, 0.62f, 0.88f, 0.38f},
			/* MOAN    */ {0.80f, 0.88f, 0.28f, 0.35f, 0.22f, 0.45f, 0.50f},
			/* KNOCK   */ {0.18f, 0.45f, 0.50f, 0.45f, 0.40f, 0.12f, 0.85f},
			/* WARBLE  */ {0.45f, 0.92f, 0.68f, 0.28f, 0.35f, 0.72f, 0.45f},
	};

	/**
	 * How much each trait counts toward which family an animal belongs to.
	 * <p>
	 * Aggression is weighted down hard and deliberately. It is already the strongest axis in the
	 * continuous parameters — nine of them correlated with it above 0.8 — and letting it also choose
	 * the family would rebuild the same collapse one level up, with steps in it.
	 */
	private static final float[] FAMILY_WEIGHTS = {1.15f, 1.00f, 0.85f, 0.95f, 0.35f, 0.90f, 0.80f};

	/**
	 * The family this animal most resembles.
	 * <p>
	 * Continuous in the genome, so a lineage keeps its kind of voice and crosses into a neighbouring
	 * one gradually rather than on a single mutation — the same property that makes a clade stay
	 * visually recognisable while still being able to change.
	 */
	private static VoiceFamily chooseFamily(float acousticSize, float sociability, float metabolism,
	                                        float fear, float aggression, float headElongation,
	                                        float jawWidth, float v3, float v4) {
		// Size onto 0..1 over the range that actually occurs, so it competes on the same footing as
		// the loci, which are already normalised.
		float[] traits = {
				Mth.clamp(acousticSize * 1.7f, 0f, 1f),
				sociability, metabolism, fear, aggression, headElongation, jawWidth};

		int pick = 0;
		float best = Float.MAX_VALUE;
		for (int i = 0; i < FAMILY_TRAITS.length; i++) {
			float d = 0f;
			for (int t = 0; t < traits.length; t++) {
				float delta = (traits[t] - FAMILY_TRAITS[i][t]) * FAMILY_WEIGHTS[t];
				d += delta * delta;
			}
			// A slight per-genome tilt, so animals sitting on a boundary do not all fall the same
			// way and no family goes unheard for want of a corner of gene space nothing occupies.
			d *= 0.90f + (i % 2 == 0 ? v3 : v4) * 0.20f;
			if (d < best) {
				best = d;
				pick = i;
			}
		}
		return VoiceFamily.VALUES[pick];
	}

	/**
	 * An individual's own vocal anatomy, as a smooth projection of the whole genome onto [0,1].
	 * <p>
	 * Two animals of the same size and temper still do not have the same voice, because the exact
	 * dimensions of a larynx are not predictable from any gross trait. That individual part has to
	 * be modelled, and it has to be <b>heritable</b>: a calf sounds like its mother.
	 * <p>
	 * This used to be a scramble of {@link Genome#hashCode()}, which is
	 * {@code Arrays.hashCode(values)} — a single point mutation changes it completely. So every
	 * offspring drew a fresh individual voice unrelated to either parent, and a lineage kept only
	 * the part of its voice that came from its body while the rest resampled every generation.
	 * <p>
	 * A weighted sum over every locus fixes that. Each gene contributes at most about a
	 * hundredth of the result, so a mutation nudges the voice instead of replacing it, while
	 * different salts give near-independent projections — which is what lets this carry real weight
	 * in the parameters below without collapsing them onto each other.
	 */
	private static float variate(Genome genome, int salt) {
		double acc = 0, weight = 0;
		for (int i = 0; i < Gene.COUNT; i++) {
			// Deterministic per-locus coefficient in [-1,1]; no state, no allocation.
			int x = (i * 0x9E3779B9) ^ salt;
			x ^= x >>> 16;
			x *= 0x7FEB352D;
			x ^= x >>> 15;
			float w = ((x >>> 8) / (float) (1 << 23)) - 1f;
			acc += w * genome.raw(Gene.VALUES[i]);
			weight += Math.abs(w);
		}
		// Centred on the midpoint a uniform genome would give, then stretched so the usable range
		// is actually used — a sum of ninety independent terms is otherwise tightly clustered by
		// the central limit theorem, and a variate that never leaves the middle is not variation.
		double unit = weight > 1e-6 ? acc / weight : 0.0;
		return (float) Mth.clamp(0.5 + unit * 4.6, 0.0, 1.0);
	}
}
