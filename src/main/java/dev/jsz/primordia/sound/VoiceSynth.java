package dev.jsz.primordia.sound;

import net.minecraft.util.Mth;

import javax.sound.sampled.AudioFormat;

/**
 * Renders a {@link VoiceProfile} making a {@link CallType} into raw PCM.
 *
 * <h2>The chain</h2>
 * <pre>
 *   glottal source ─┬─ main oscillator (chaos, subharmonics, jitter, shimmer)
 *                   ├─ second oscillator (biphonation)
 *                   └─ aspiration noise, gated by the glottal cycle
 *          │
 *   spectral tilt ── one-pole lowpass: the glottal return phase
 *          │
 *   cascade ──────── four moving formants in series, plus a nasal antiresonance
 *          │
 *   saturation ───── tanh, driven by call effort
 *          │
 *   envelope ─────── overlapping syllables, then RMS normalisation and limiting
 * </pre>
 *
 * <h2>Three things that must not be done differently</h2>
 * An earlier version of this file got each of them wrong and sounded, accurately, like a piano
 * being hit at random. They are worth stating because each is a tempting simplification:
 * <ol>
 *   <li><b>The formants are cascaded, not summed in parallel.</b> Klatt's synthesiser uses a
 *       cascade for voiced sound precisely because it gets the relative formant amplitudes right
 *       for free. Summing bandpass outputs in parallel gives every formant an amplitude somebody
 *       had to choose, and the usual result is the hollow, hooty timbre of an electric organ.</li>
 *   <li><b>The source is tilted, not impulsive.</b> Real glottal flow closes over a finite return
 *       phase, so its spectrum rolls off above a few hundred hertz. A bare impulse does not roll
 *       off at all, and an impulse train exciting a sharp resonator is the textbook physical model
 *       of a <i>struck string</i>. {@link VoiceProfile#spectralTilt} is the fix.</li>
 *   <li><b>The instability is aperiodic, not an LFO.</b> A sine-wave tremolo is heard as a synth
 *       effect no matter how it is tuned. Real roughness comes from the folds failing to repeat
 *       themselves, which is modelled here with a chaotic map — see {@link #chaosStep}.</li>
 * </ol>
 *
 * <h2>Nonlinear phenomena</h2>
 * The bioacoustics literature identifies four irregularities that pervade vertebrate calls, and
 * they carry most of the impression that something alive is making the sound. All four are here:
 * deterministic chaos, subharmonics, biphonation and frequency jumps. See {@link VoiceProfile}.
 *
 * <h2>Why 22 kHz mono</h2>
 * Mono because OpenAL will not position a stereo buffer in 3D space — a stereo call would follow
 * the player's head instead of coming from the animal. 22,050 Hz because it puts the Nyquist limit
 * above every formant this synthesiser produces, and halves both memory and cost against 44.1 kHz.
 */
public final class VoiceSynth {

	public static final int SAMPLE_RATE = 22050;

	/**
	 * Signed 16-bit little-endian mono, which is what OpenAL wants handed to {@code alBufferData}
	 * and what {@code OpenAlUtil.audioFormatToOpenAl} accepts.
	 */
	public static final AudioFormat FORMAT =
			new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

	/** Ceiling on one call. Long enough for a death bellow, short enough to bound the cache. */
	private static final float MAX_SECONDS = 2.6f;

	/** Hard ceiling before the 16-bit rail. The limiter keeps peaks under this. */
	private static final float CEILING = 0.94f;

	/** Fade applied at both ends, in seconds. Removes the click of starting mid-waveform. */
	private static final float EDGE_FADE = 0.005f;

	/** Samples between filter coefficient updates as the formants move. About 1.5 ms. */
	private static final int COEFF_INTERVAL = 32;

	private VoiceSynth() {
	}

	/**
	 * @param variant which of several renderings of the same call this is, so a creature repeating
	 *                itself is not looping one identical waveform
	 */
	public static byte[] render(VoiceProfile v, CallType call, int variant) {
		final Rng rng = new Rng(mix(v.hash(), call.ordinal() * 977 + variant * 31 + 1));

		// ---- phrasing ---------------------------------------------------------------------------
		final float sylLen = Mth.clamp(v.syllableLen() * call.syllableScale, 0.05f, 1.2f);
		final float gapLen = v.gapLen();
		int count = Mth.clamp(Math.round(v.syllables() * call.lengthScale), 1, 8);

		float stride = Math.max(sylLen * 0.35f, sylLen + gapLen);
		float total = (count - 1) * stride + sylLen;
		if (total > MAX_SECONDS) {
			count = Math.max(1, (int) ((MAX_SECONDS - sylLen) / stride) + 1);
			total = Math.min(MAX_SECONDS, (count - 1) * stride + sylLen);
		}

		final int n = Math.max(1, (int) (total * SAMPLE_RATE));
		final float[] out = new float[n];
		final float[] env = new float[n];
		final float[] pos = new float[n];

		// Per-syllable register shifts. A frequency jump is a bifurcation of the whole oscillator,
		// so it lands on a syllable boundary rather than drifting in.
		final float[] sylPitch = new float[count];
		for (int s = 0; s < count; s++) {
			sylPitch[s] = 1f;
			if (s > 0 && rng.unit() < v.jumpChance() * (0.4f + call.effort)) {
				final float[] jumps = {0.5f, 0.667f, 1.5f, 2.0f};
				sylPitch[s] = jumps[(int) (rng.unit() * 4) & 3];
			}
		}

		buildEnvelope(env, pos, v, call, count, sylLen, stride, rng);

		// ---- filters ----------------------------------------------------------------------------
		final Resonator[] formants = {new Resonator(), new Resonator(), new Resonator(), new Resonator()};
		final AntiResonator nasal = new AntiResonator();
		final Resonator rasp = new Resonator();
		rasp.set(Mth.clamp(v.formantHz()[1] * 1.8f + 1800f, 1500f, 9000f), 500f);

		// Effort opens the mouth, which raises the first formant, and hardens the voice, which
		// lifts the spectral tilt. Both are measured effects of loud phonation, in every animal
		// anyone has put a microphone in front of.
		final float effortShift = 1f + call.effort * 0.12f;
		final float tiltHz = Mth.clamp(v.spectralTilt() * (1f + call.effort * 1.1f), 400f, 7000f);
		final float tiltCoeff = onePole(tiltHz);

		// ---- the run ----------------------------------------------------------------------------
		final float dt = 1f / SAMPLE_RATE;
		final float baseF0 = v.f0() * call.pitchScale * (0.97f + rng.unit() * 0.06f);
		final float drive = 1f + call.effort * (2.0f + v.chaos() * 4.0f);
		final float noiseMix = Mth.clamp(
				v.aspiration() + (call == CallType.HURT ? 0.20f : 0f) + call.effort * 0.12f, 0f, 0.9f);
		final float chaosAmt = Mth.clamp(v.chaos() * (0.45f + call.effort * 0.9f), 0f, 1f);

		float phase = 0f, phase2 = 0f, raspPhase = 0f;
		float prevFlow = 0f, prevFlow2 = 0f;
		float tiltState = 0f, dcState = 0f;
		float periodJitter = 0f, periodAmp = 1f, chaosPeriod = 1f;
		float chaosState = 0.41f + rng.unit() * 0.2f;
		int cycle = 0;
		int syl = 0;
		float nextBoundary = stride;

		for (int i = 0; i < n; i++) {
			final float t = i * dt;
			final float u = t / total;
			if (t >= nextBoundary && syl < count - 1) {
				syl++;
				nextBoundary += stride;
			}
			final float q = pos[i];

			// ---- pitch --------------------------------------------------------------------------
			float f = baseF0 * sylPitch[syl] * (1f + call.sweep * u);
			// A syllable's own arc: up into the middle, down out of it. Animals do not hold a pitch.
			f *= 1f + 0.06f * Mth.sin((float) Math.PI * Mth.clamp(q, 0f, 1f)) * (1f + call.effort);
			f *= 1f + v.vibratoDepth() * Mth.sin((float) (2 * Math.PI) * v.vibratoRate() * t);
			f *= 1f + periodJitter;
			f *= chaosPeriod;
			f = Mth.clamp(f, 20f, SAMPLE_RATE * 0.25f);

			phase += f * dt;
			phase2 += f * v.biphonationRatio() * dt;
			if (phase2 >= 1f) phase2 -= (int) phase2;

			if (phase >= 1f) {
				phase -= (int) phase;
				cycle++;
				// Once per glottal cycle, not once per sample. Cycle-to-cycle variation is what the
				// ear hears as hoarseness; per-sample variation is just broadband noise.
				periodJitter = (rng.unit() - 0.5f) * 2f * v.jitter() * (0.5f + call.effort);
				periodAmp = 1f - rng.unit() * v.shimmer() * (0.5f + call.effort);

				chaosState = chaosStep(chaosState);
				chaosPeriod = 1f + (chaosState - 0.5f) * 0.9f * chaosAmt;
			}

			// ---- source -------------------------------------------------------------------------
			float cycleAmp = periodAmp;
			// Period doubling: every other cycle is weaker, which puts a band at f0/2. The ear reads
			// that band as the fundamental, so a growl sounds like it came from a bigger animal.
			if ((cycle & 1) == 1) cycleAmp *= 1f - v.subharmonic() * 0.5f * (0.35f + call.effort);
			// Every third, at strong chaos: period-3 windows are a real feature of chaotic phonation
			// and they sound considerably nastier than doubling alone.
			if (chaosAmt > 0.45f && cycle % 3 == 0) cycleAmp *= 1f - chaosAmt * 0.3f;
			cycleAmp *= 1f - chaosState * chaosAmt * 0.55f;

			final float flow = glottalFlow(phase, v.openQuotient(), v.speedQuotient());
			float source = (flow - prevFlow) * 12f * cycleAmp;
			prevFlow = flow;

			// Biphonation: a second oscillator at an unrelated frequency, beating against the first.
			if (v.biphonation() > 0.001f) {
				final float flow2 = glottalFlow(phase2, v.openQuotient() * 0.9f, v.speedQuotient());
				source += (flow2 - prevFlow2) * 12f * v.biphonation() * cycleAmp;
				prevFlow2 = flow2;
			}

			// Aspiration, loudest while the folds are open — turbulence needs a gap to blow through.
			// Modulating it by the cycle is what keeps it sounding like breath rather than hiss.
			final float noise = rng.bipolar();
			final float openness = phase < v.openQuotient() ? 1f : 0.25f;
			source += noise * noiseMix * 0.5f * openness;

			// ---- spectral tilt ------------------------------------------------------------------
			// The return phase. Without this the source is an impulse train and the cascade below
			// rings like a struck string, whatever its bandwidths are.
			tiltState += tiltCoeff * (source - tiltState);
			float x = tiltState;

			// ---- cascade ------------------------------------------------------------------------
			if ((i % COEFF_INTERVAL) == 0) {
				final float qq = Mth.clamp(q, 0f, 1f);
				for (int k = 0; k < 4; k++) {
					final float target = v.formantHz()[k]
							* (1f + v.formantMotion()[k] * qq)
							* (k == 0 ? effortShift : 1f);
					formants[k].set(target, v.formantBw()[k] * (1f + call.effort * 0.25f));
				}
				if (v.nasality() > 0.001f) nasal.set(v.formantHz()[0] * 1.45f, 260f);
			}
			for (int k = 0; k < 4; k++) x = formants[k].step(x);
			if (v.nasality() > 0.001f) x = Mth.lerp(v.nasality(), x, nasal.step(x));

			// ---- insects ------------------------------------------------------------------------
			if (v.stridulation() > 0.001f) {
				raspPhase += v.stridulationRate() * dt;
				if (raspPhase >= 1f) raspPhase -= (int) raspPhase;
				final float strike = (float) Math.exp(-raspPhase * 9f);
				x = Mth.lerp(v.stridulation(), x, rasp.step(noise * strike) * 2.2f);
			}

			// tanh rather than a hard clip, so pushing harder adds harmonics instead of aliasing.
			x = (float) Math.tanh(x * drive);

			// Block DC before the envelope so the fades cannot leave a step at either end.
			final float blocked = x - dcState;
			dcState += blocked * 0.002f;

			out[i] = blocked * env[i];
		}

		return finish(out, call);
	}

	/**
	 * Fills the amplitude and syllable-position arrays.
	 * <p>
	 * Syllables may overlap — {@link VoiceProfile#gapLen} can be negative — and their amplitudes add.
	 * That matters more than it sounds: syllables separated by clean silence are heard as discrete
	 * events, which is to say as <i>notes</i>, and a sequence of notes is a melody rather than an
	 * animal. Overlapping them, and varying their lengths, is most of what makes a call read as one
	 * continuous act of breathing.
	 */
	private static void buildEnvelope(float[] env, float[] pos, VoiceProfile v, CallType call,
	                                  int count, float sylLen, float stride, Rng rng) {
		final int strideSamples = Math.max(1, (int) (stride * SAMPLE_RATE));

		for (int s = 0; s < count; s++) {
			// Repeated calls fade as the animal runs out of breath, and no two syllables are the
			// same length — metronomic phrasing is unmistakably synthetic.
			final float gain = (1f - s / (float) count * 0.30f * call.lengthScale)
					* (0.82f + rng.unit() * 0.18f);
			final float len = sylLen * (0.85f + rng.unit() * 0.3f);
			final float attack = Math.min(v.attack(), len * 0.45f);
			final float release = Math.min(v.release(), len * 0.85f);
			final int lenSamples = Math.max(1, (int) (len * SAMPLE_RATE));
			final int base = s * strideSamples;

			for (int i = 0; i < lenSamples; i++) {
				final int idx = base + i;
				if (idx >= env.length) break;

				final float t = i / (float) SAMPLE_RATE;
				float e;
				if (t < attack) {
					final float a = t / attack;
					e = a * a * (3f - 2f * a);
				} else if (t > len - release) {
					final float r = Mth.clamp((len - t) / release, 0f, 1f);
					e = r * r * (0.35f + 0.65f * r);
				} else {
					e = 1f;
				}
				env[idx] = Math.min(1f, env[idx] + e * gain);
				pos[idx] = i / (float) lenSamples;
			}
		}
	}

	/**
	 * Scales to a target loudness, limits the peaks, fades both edges and quantises to 16-bit.
	 * <p>
	 * Normalised on RMS rather than peak, and to a target that depends on how hard the animal is
	 * pushing. Peak normalisation makes every call exactly as loud as every other, so a creature
	 * breathing in its sleep arrives at the same level as a death roar — which is both wrong and,
	 * on a quiet call, unpleasantly loud.
	 */
	private static byte[] finish(float[] buf, CallType call) {
		double energy = 0;
		for (float x : buf) energy += x * x;
		final float rms = (float) Math.sqrt(energy / Math.max(1, buf.length));

		final float target = 0.10f + call.effort * 0.13f;
		float scale = rms > 1e-5f ? target / rms : 0f;

		float peak = 0f;
		for (float x : buf) peak = Math.max(peak, Math.abs(x));
		if (peak * scale > CEILING) scale = CEILING / peak;

		final int fade = Math.min((int) (EDGE_FADE * SAMPLE_RATE), buf.length / 2);
		final byte[] pcm = new byte[buf.length * 2];

		for (int i = 0; i < buf.length; i++) {
			float x = buf[i] * scale;
			if (fade > 0) {
				if (i < fade) x *= i / (float) fade;
				else if (i >= buf.length - fade) x *= (buf.length - 1 - i) / (float) fade;
			}
			final int s = Mth.clamp((int) (x * 32767f), -32768, 32767);
			pcm[i * 2] = (byte) (s & 0xFF);
			pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
		}
		return pcm;
	}

	/**
	 * The logistic map at {@code r = 3.9}, deep in its chaotic regime.
	 * <p>
	 * Chaotic rather than random, and that distinction is the point. White noise applied to the
	 * period sounds like noise; a chaotic orbit wanders, lingers near unstable cycles and breaks
	 * away again, which is what actual aperiodic vocal folds do and what the literature means by
	 * <i>deterministic chaos</i>. It is also cheap: one multiply-add per glottal cycle.
	 */
	private static float chaosStep(float state) {
		final float next = 3.9f * state * (1f - state);
		// Clear of the fixed points at the ends, where the orbit would collapse to silence.
		return Mth.clamp(next, 0.02f, 0.98f);
	}

	/**
	 * Glottal flow over one cycle: an opening phase, a faster closing phase, then the closed phase.
	 * <p>
	 * {@code open} is the share of the cycle the folds are apart for; {@code speed} is how much of
	 * that is spent opening rather than closing. A fast close means a sharper spectrum and a harder
	 * voice, which is why pushing harder is audible as more than volume.
	 */
	private static float glottalFlow(float phase, float open, float speed) {
		final float o = Mth.clamp(open, 0.2f, 0.95f);
		if (phase >= o) return 0f;
		final float rise = o * Mth.clamp(1f - speed * 0.5f, 0.3f, 0.85f);
		if (phase < rise) {
			final float a = phase / rise;
			return 3f * a * a - 2f * a * a * a;
		}
		final float a = (phase - rise) / Math.max(1e-4f, o - rise);
		return 1f - a * a;
	}

	/** One-pole lowpass coefficient for a given cutoff. */
	private static float onePole(float cutoffHz) {
		final float w = (float) (2 * Math.PI * cutoffHz / SAMPLE_RATE);
		return Mth.clamp(w / (w + 1f), 0.001f, 0.999f);
	}

	private static int mix(int a, int b) {
		int h = a * 0x9E3779B9 + b;
		h ^= h >>> 15;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return h;
	}

	/**
	 * A Klatt resonator — one formant, in cascade.
	 * <p>
	 * Normalised to unity gain at DC ({@code a = 1 - b - c}) rather than to unity gain at the peak.
	 * That is the correct normalisation for a cascade: each stage passes the level through and only
	 * shapes it, so four in series produce the relative formant amplitudes of a real tract without
	 * anybody choosing them.
	 */
	private static final class Resonator {
		private float a, b, c;
		private float y1, y2;

		void set(float freqHz, float bandwidthHz) {
			final float f = Mth.clamp(freqHz, 20f, SAMPLE_RATE * 0.47f);
			final float r = (float) Math.exp(-Math.PI * Math.max(20f, bandwidthHz) / SAMPLE_RATE);
			this.c = -r * r;
			this.b = 2f * r * Mth.cos((float) (2 * Math.PI * f / SAMPLE_RATE));
			this.a = 1f - b - c;
		}

		float step(float x) {
			final float y = a * x + b * y1 + c * y2;
			y2 = y1;
			y1 = y;
			return y;
		}
	}

	/** A Klatt antiresonator — the notch a nasal cavity puts into the spectrum. */
	private static final class AntiResonator {
		private float a, b, c;
		private float x1, x2;

		void set(float freqHz, float bandwidthHz) {
			final float f = Mth.clamp(freqHz, 20f, SAMPLE_RATE * 0.47f);
			final float r = (float) Math.exp(-Math.PI * Math.max(20f, bandwidthHz) / SAMPLE_RATE);
			final float rc = -r * r;
			final float rb = 2f * r * Mth.cos((float) (2 * Math.PI * f / SAMPLE_RATE));
			final float ra = 1f - rb - rc;
			this.a = 1f / Math.max(1e-6f, ra);
			this.b = -rb * a;
			this.c = -rc * a;
		}

		float step(float x) {
			final float y = a * x + b * x1 + c * x2;
			x2 = x1;
			x1 = x;
			return y;
		}
	}

	/**
	 * xorshift64*, seeded per call.
	 * <p>
	 * Deterministic and self-contained rather than {@code RandomSource}: synthesis runs off the game
	 * threads, and the same creature making the same call must produce byte-identical audio every
	 * time or the cache would be pointless.
	 */
	private static final class Rng {
		private long s;

		Rng(long seed) {
			this.s = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
		}

		long next() {
			s ^= s >>> 12;
			s ^= s << 25;
			s ^= s >>> 27;
			return s * 0x2545F4914F6CDD1DL;
		}

		/** Uniform in [0,1). */
		float unit() {
			return (next() >>> 40) / (float) (1 << 24);
		}

		/** Uniform in [-1,1). */
		float bipolar() {
			return unit() * 2f - 1f;
		}
	}
}
