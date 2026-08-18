package dev.jsz.primordia.sound;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * How many different voices the synthesiser actually produces.
 * <p>
 * {@code gradle voiceDiversityReport}. Variety in a parameter is not variety in a voice: if every
 * timbre parameter is a function of the same gene then the population sits on a line through the
 * parameter space however wide each individual range looks, and every creature is somewhere between
 * two sounds rather than being its own. That is a property of the distribution and cannot be judged
 * by listening to one specimen, which is exactly the case {@code diversityReport} exists for on the
 * body side.
 * <p>
 * The number to read is <b>PC1</b> — the share of all variation lying along a single axis. At 100%
 * the population is one sound with a volume knob.
 */
public final class VoiceDiversityReport {

	/** The timbre and phrasing parameters a listener could plausibly tell apart. */
	private static final String[] NAMES = {
			"f0", "formant1", "formantSpread", "openQuotient", "speedQuotient", "spectralTilt",
			"aspiration", "chaos", "subharmonic", "biphonation", "jitter", "shimmer",
			"jumpChance", "vibratoRate", "vibratoDepth", "stridulation", "syllables",
			"syllableLen", "gapLen", "attack", "release", "nasality"};

	private static float[] vector(VoiceProfile v) {
		return new float[]{
				(float) Math.log(v.f0()), (float) Math.log(v.formantHz()[0]),
				v.formantHz()[3] / v.formantHz()[0],
				v.openQuotient(), v.speedQuotient(), (float) Math.log(v.spectralTilt()),
				v.aspiration(), v.chaos(), v.subharmonic(), v.biphonation(),
				v.jitter(), v.shimmer(), v.jumpChance(), v.vibratoRate(), v.vibratoDepth(),
				v.stridulation(), v.syllables(), v.syllableLen(), v.gapLen(),
				v.attack(), v.release(), v.nasality()};
	}

	public static void main(String[] args) {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 4242L;
		int per = 400;

		List<float[]> rows = new ArrayList<>();
		List<VoiceProfile> profiles = new ArrayList<>();
		List<Float> aggression = new ArrayList<>();
		for (Archetype a : Archetype.VALUES) {
			for (int i = 0; i < per; i++) {
				Random r = new Random(seed + a.ordinal() * 7919L + i * 104729L);
				Genome g = a.create(r);
				BodyPlan plan = BodyPlanBuilder.build(g);
				VoiceProfile vp = VoiceProfile.of(g, plan);
				profiles.add(vp);
				rows.add(vector(vp));
				aggression.add(g.raw(Gene.AGGRESSION));
			}
		}

		System.out.println();
		System.out.println("family distribution");
		java.util.Map<VoiceFamily, Integer> fam = new java.util.EnumMap<>(VoiceFamily.class);
		for (VoiceFamily f : VoiceFamily.VALUES) fam.put(f, 0);
		for (VoiceProfile v : profiles) fam.merge(v.family(), 1, Integer::sum);
		for (VoiceFamily f : VoiceFamily.VALUES) {
			System.out.printf("  %-9s %5.1f%%%n", f.label(), fam.get(f) * 100f / profiles.size());
		}

		int n = rows.size(), d = NAMES.length;
		// Standardise: every parameter contributes its shape, not its units.
		float[] mean = new float[d], sd = new float[d];
		for (float[] row : rows) for (int j = 0; j < d; j++) mean[j] += row[j] / n;
		for (float[] row : rows) {
			for (int j = 0; j < d; j++) sd[j] += (row[j] - mean[j]) * (row[j] - mean[j]) / n;
		}
		for (int j = 0; j < d; j++) sd[j] = (float) Math.max(1e-6, Math.sqrt(sd[j]));
		double[][] z = new double[n][d];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < d; j++) z[i][j] = (rows.get(i)[j] - mean[j]) / sd[j];
		}

		System.out.println("Primordia voice diversity — seed " + seed + ", " + n + " voices");
		System.out.println();
		System.out.printf("%-16s %10s %12s%n", "parameter", "spread", "r(aggression)");
		float[] agg = new float[n];
		for (int i = 0; i < n; i++) agg[i] = aggression.get(i);
		for (int j = 0; j < d; j++) {
			double[] col = new double[n];
			for (int i = 0; i < n; i++) col[i] = z[i][j];
			System.out.printf("%-16s %10.3f %12.2f%n", NAMES[j], sd[j], correlation(col, agg));
		}

		// Covariance, then power iteration for the leading axes.
		double[][] cov = new double[d][d];
		for (double[] row : z) {
			for (int a2 = 0; a2 < d; a2++) {
				for (int b = 0; b < d; b++) cov[a2][b] += row[a2] * row[b] / n;
			}
		}
		double total = 0;
		for (int j = 0; j < d; j++) total += cov[j][j];
		System.out.println();
		double explained = 0;
		for (int k = 0; k < 4; k++) {
			double[] v = power(cov, d);
			double lambda = rayleigh(cov, v, d);
			explained += lambda;
			System.out.printf("PC%d explains %5.1f%% of all voice variation (running total %5.1f%%)%n",
					k + 1, lambda / total * 100.0, explained / total * 100.0);
			deflate(cov, v, lambda, d);
		}
		System.out.println();
		System.out.printf("nearest-neighbour distance: mean %.3f, worst pair %.4f (of %d dimensions)%n",
				neighbour(z, d, false), neighbour(z, d, true), d);
	}

	// ---- linear algebra, small and self-contained ------------------------------------------

	private static double[] power(double[][] m, int d) {
		double[] v = new double[d];
		java.util.Arrays.fill(v, 1.0 / Math.sqrt(d));
		for (int it = 0; it < 200; it++) {
			double[] next = new double[d];
			for (int i = 0; i < d; i++) for (int j = 0; j < d; j++) next[i] += m[i][j] * v[j];
			double norm = 0;
			for (double x : next) norm += x * x;
			norm = Math.sqrt(Math.max(1e-12, norm));
			for (int i = 0; i < d; i++) v[i] = next[i] / norm;
		}
		return v;
	}

	private static double rayleigh(double[][] m, double[] v, int d) {
		double num = 0;
		for (int i = 0; i < d; i++) for (int j = 0; j < d; j++) num += v[i] * m[i][j] * v[j];
		return num;
	}

	private static void deflate(double[][] m, double[] v, double lambda, int d) {
		for (int i = 0; i < d; i++) for (int j = 0; j < d; j++) m[i][j] -= lambda * v[i] * v[j];
	}

	private static double correlation(double[] a, float[] b) {
		int n = a.length;
		double ma = 0, mb = 0;
		for (int i = 0; i < n; i++) { ma += a[i] / n; mb += b[i] / (double) n; }
		double sa = 0, sb = 0, sab = 0;
		for (int i = 0; i < n; i++) {
			double da = a[i] - ma, db = b[i] - mb;
			sa += da * da; sb += db * db; sab += da * db;
		}
		return sab / Math.sqrt(Math.max(1e-12, sa * sb));
	}

	/** Mean (or worst) distance to the closest other voice — how distinguishable neighbours are. */
	private static double neighbour(double[][] z, int d, boolean worst) {
		int n = Math.min(z.length, 1200);
		double acc = 0, least = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double best = Double.MAX_VALUE;
			for (int j = 0; j < n; j++) {
				if (i == j) continue;
				double s = 0;
				for (int k = 0; k < d; k++) {
					double delta = z[i][k] - z[j][k];
					s += delta * delta;
					if (s >= best) break;
				}
				if (s < best) best = s;
			}
			best = Math.sqrt(best);
			acc += best / n;
			least = Math.min(least, best);
		}
		return worst ? least : acc;
	}
}
