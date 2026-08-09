package dev.jsz.primordia.genome;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.EarType;
import dev.jsz.primordia.body.EyeStyle;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.HornType;
import dev.jsz.primordia.body.TailShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Measures how much variety the creature generator actually produces.
 * <p>
 * Samples founders through the real {@link Archetype} → {@link Genome} → {@link BodyPlanBuilder}
 * path and counts what comes out. It exists because creature variety is a <i>distribution</i>
 * property, and distributions cannot be eyeballed: the generator offers on the order of a thousand
 * ornament combinations, and the interesting question is not how many it can produce but how many
 * it produces <i>often enough for a player to ever meet one</i>. Those two numbers turned out to
 * differ by two orders of magnitude.
 * <p>
 * Trait incidence is read off the finished {@link BodyPlan} — every bone and blob carries a
 * {@link Feature} tag — rather than by re-testing the thresholds in {@code BodyPlanBuilder}. That
 * way this measures the generator's actual output and does not quietly go stale the moment one of
 * those thresholds is retuned.
 * <p>
 * Run with {@code gradle diversityReport}. Lives in the test source set; nothing here ships.
 */
public final class DiversityReport {

	/** Identity genes: the ones a player uses to tell two animals apart at a glance. */
	private static final Gene[] IDENTITY = {
			Gene.HEAD_ELONGATION, Gene.JAW_WIDTH, Gene.CRANIUM_BULGE, Gene.SNOUT_TYPE,
			Gene.EAR_TYPE, Gene.EAR_SIZE, Gene.HORN_TYPE, Gene.HORN_SIZE, Gene.HORN_PAIRS,
			Gene.FRILL, Gene.TUSKS, Gene.FUR_CREST, Gene.SPINE_STYLE, Gene.TORSO_TAPER,
			Gene.SPINE_ARCH, Gene.TAIL_SHAPE, Gene.TAIL_FIN_DEPTH, Gene.NECK_THICKNESS,
			Gene.EYE_STYLE, Gene.EYE_SPACING, Gene.PATTERN_TYPE, Gene.PATTERN_SCALE,
	};

	/**
	 * Features a creature may or may not have. Excludes the ones every creature has by
	 * construction — body, head, jaw, eye, limb, foot — which carry no information.
	 */
	private static final Feature[] OPTIONAL = {
			Feature.TAIL, Feature.PLATE, Feature.CLAWS, Feature.HAND, Feature.SPINE,
			Feature.HAIR, Feature.EYE_STALK, Feature.HORN, Feature.TUSK, Feature.BEAK,
			Feature.EAR, Feature.FRILL, Feature.FIN, Feature.ABDOMEN,
	};

	public static void main(String[] args) {
		int n = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
		long seed = args.length > 1 ? Long.parseLong(args[1]) : 20260807L;
		Random rng = new Random(seed);

		Map<String, int[]> tail = counter(), horn = counter(), ear = counter(), eye = counter();
		Map<Feature, Integer> features = new LinkedHashMap<>();
		Map<Integer, Integer> legs = new TreeMap<>(), arms = new TreeMap<>(), bones = new TreeMap<>();
		Map<String, Integer> signatures = new HashMap<>();
		Map<Archetype, Integer> archetypes = new LinkedHashMap<>();

		double[] sum = new double[IDENTITY.length];
		double[] sumSq = new double[IDENTITY.length];
		float[][] identity = new float[n][IDENTITY.length];
		int[] featureLoad = new int[n];
		float heightMax = 0;
		int overTall = 0;

		for (int i = 0; i < n; i++) {
			Archetype archetype = Archetype.randomSurface(rng);
			Genome g = archetype.create(rng);
			BodyPlan plan = BodyPlanBuilder.build(g);

			archetypes.merge(archetype, 1, Integer::sum);
			bump(tail, TailShape.of(g).name());
			bump(horn, HornType.of(g).name());
			bump(ear, EarType.of(g).name());
			bump(eye, EyeStyle.of(g).name());

			List<Feature> here = present(plan);
			for (Feature f : here) features.merge(f, 1, Integer::sum);
			for (Feature f : OPTIONAL) if (here.contains(f)) featureLoad[i]++;
			legs.merge(plan.legs == null ? 0 : plan.legs.length, 1, Integer::sum);
			arms.merge(plan.arms == null ? 0 : plan.arms.length, 1, Integer::sum);
			bones.merge(bucket(plan.bones.length, 10), 1, Integer::sum);

			for (int k = 0; k < IDENTITY.length; k++) {
				float v = g.raw(IDENTITY[k]);
				sum[k] += v;
				sumSq[k] += v * v;
				identity[i][k] = v;
			}

			float h = plan.height();
			heightMax = Math.max(heightMax, h);
			if (h > 2.45f) overTall++;

			signatures.merge(signature(g, plan), 1, Integer::sum);
		}

		System.out.println("=".repeat(78));
		System.out.printf("PRIMORDIA DIVERSITY REPORT   n=%d  seed=%d%n", n, seed);
		System.out.println("=".repeat(78));

		section("ARCHETYPE");
		archetypes.entrySet().stream()
				.sorted(Comparator.comparingInt(e -> -e.getValue()))
				.forEach(e -> bar(e.getKey().name().toLowerCase(), e.getValue(), n));

		section("ORNAMENT ENUMS   (a variant under ~5% is one a player will never meet)");
		enumTable("TailShape", tail, n);
		enumTable("HornType", horn, n);
		enumTable("EarType", ear, n);
		enumTable("EyeStyle", eye, n);

		section("TRAIT INCIDENCE   (read off the built BodyPlan, not the thresholds)");
		List<Map.Entry<Feature, Integer>> fs = new ArrayList<>(features.entrySet());
		fs.sort(Comparator.comparingInt(e -> -e.getValue()));
		for (var e : fs) bar(e.getKey().name().toLowerCase(), e.getValue(), n);

		section("STRUCTURE");
		System.out.println("  legs:");
		legs.forEach((k, v) -> bar("    " + k, v, n));
		System.out.println("  arms:");
		arms.forEach((k, v) -> bar("    " + k, v, n));
		System.out.println("  bone count (bucketed by 10):");
		bones.forEach((k, v) -> bar("    " + k + "-" + (k + 9), v, n));
		System.out.printf("%n  tallest built: %.2f m    over 2.45 m (near the rescale cap): %.1f%%%n",
				heightMax, 100.0 * overTall / n);

		section("IDENTITY GENE SPREAD   (sd 0.29 = uniform, 0.17 = the moderate draw)");
		System.out.printf("  %-22s %6s %6s%n", "gene", "mean", "sd");
		for (int k = 0; k < IDENTITY.length; k++) {
			double mean = sum[k] / n;
			double sd = Math.sqrt(Math.max(0, sumSq[k] / n - mean * mean));
			System.out.printf("  %-22s %6.3f %6.3f  %s%n",
					IDENTITY[k].name().toLowerCase(), mean, sd, spreadBar(sd));
		}

		section("DISTINCTNESS   (the numbers that answer \"I have seen them all\")");
		distinctness(identity, featureLoad, n);

		List<Map.Entry<String, Integer>> top = new ArrayList<>(signatures.entrySet());
		top.sort(Comparator.comparingInt(e -> -e.getValue()));
		System.out.printf("%n  coarse silhouettes           : %d distinct, top 10 cover %.1f%%%n",
				signatures.size(),
				100.0 * top.stream().limit(10).mapToInt(Map.Entry::getValue).sum() / n);
		System.out.println("  most common:");
		top.stream().limit(4).forEach(e ->
				System.out.printf("    %5.2f%%  %s%n", 100.0 * e.getValue() / n, e.getKey()));
	}

	/**
	 * How different creatures actually look from one another.
	 * <p>
	 * Counting distinct combinations was the obvious metric and it is a misleading one: it scores
	 * this generator at 98% unique, because two animals that differ only in having a slightly
	 * longer muzzle count as different creatures. The player's complaint is not that creatures
	 * repeat exactly — they almost never do — it is that they are all built from middling values of
	 * everything, so the differences are too small to register.
	 * <p>
	 * These three measure that directly:
	 * <ul>
	 *   <li><b>amplitude</b> — mean distance of an identity gene from 0.5. Low means every ornament
	 *       is rendered at half strength: a medium muzzle, medium ears, medium horns, on everything.</li>
	 *   <li><b>nearest neighbour</b> — for each creature, the distance to the most similar other
	 *       creature in the sample. This is the closest thing to "how often do I meet something that
	 *       looks like one I have already seen".</li>
	 *   <li><b>feature load</b> — how many of the optional features an average creature carries. When
	 *       everything has plates and claws and a fin, presence stops distinguishing anything.</li>
	 * </ul>
	 */
	private static void distinctness(float[][] identity, int[] featureLoad, int n) {
		double amplitude = 0;
		for (float[] row : identity) {
			for (float v : row) amplitude += Math.abs(v - 0.5f);
		}
		amplitude /= (double) n * IDENTITY.length;

		double[] centroid = new double[IDENTITY.length];
		for (float[] row : identity) {
			for (int k = 0; k < IDENTITY.length; k++) centroid[k] += row[k];
		}
		for (int k = 0; k < IDENTITY.length; k++) centroid[k] /= n;

		double fromCentre = 0;
		for (float[] row : identity) {
			double d = 0;
			for (int k = 0; k < IDENTITY.length; k++) {
				double x = row[k] - centroid[k];
				d += x * x;
			}
			fromCentre += Math.sqrt(d);
		}
		fromCentre /= n;

		// O(m^2), so on a subsample. 1200 is enough for the mean to settle.
		int m = Math.min(1200, n);
		double nearest = 0;
		for (int i = 0; i < m; i++) {
			double best = Double.MAX_VALUE;
			for (int j = 0; j < m; j++) {
				if (i == j) continue;
				double d = 0;
				for (int k = 0; k < IDENTITY.length && d < best; k++) {
					double x = identity[i][k] - identity[j][k];
					d += x * x;
				}
				if (d < best) best = d;
			}
			nearest += Math.sqrt(best);
		}
		nearest /= m;

		double load = 0;
		for (int v : featureLoad) load += v;
		load /= n;

		System.out.printf("  ornament amplitude           : %.3f   <- higher is better "
				+ "(0.13 = the moderate draw, 0.25 = uniform)%n", amplitude);
		System.out.printf("  spread from the average       : %.3f   <- higher is better%n", fromCentre);
		System.out.printf("  nearest-neighbour distance   : %.3f   <- higher is better "
				+ "(how unlike its closest twin a creature is)%n", nearest);
		System.out.printf("  optional features carried    : %.1f of %d   <- lower is better%n",
				load, OPTIONAL.length);
	}

	/**
	 * A creature reduced to what someone would remember about it a minute later: limb counts, the
	 * four ornament enums, and whether its main proportions are small or large.
	 * <p>
	 * Deliberately coarse. A finer signature scores almost every creature as unique — which is true
	 * and useless, because the question is how many <i>recognisably</i> different animals there are,
	 * not how many distinct floating-point genomes.
	 */
	private static String signature(Genome g, BodyPlan plan) {
		StringBuilder s = new StringBuilder();
		s.append(plan.legs == null ? 0 : plan.legs.length).append('L');
		s.append(plan.arms == null ? 0 : plan.arms.length).append('A');
		s.append('|').append(TailShape.of(g).name().charAt(0));
		s.append(HornType.of(g).name().charAt(0));
		s.append(EarType.of(g).name().charAt(0));
		s.append(EyeStyle.of(g).name().charAt(0));
		s.append('|');
		for (Gene gene : new Gene[]{Gene.SIZE, Gene.HEAD_ELONGATION, Gene.JAW_WIDTH,
				Gene.NECK_LENGTH, Gene.TAIL_LENGTH, Gene.TORSO_GIRTH}) {
			s.append(g.raw(gene) < 0.5f ? '-' : '+');
		}
		return s.toString();
	}

	/** Which semantic features this body actually grew, from its bones and blobs. */
	private static List<Feature> present(BodyPlan plan) {
		boolean[] seen = new boolean[Feature.values().length];
		for (var b : plan.bones) if (b.feature != null) seen[b.feature.ordinal()] = true;
		for (var b : plan.blobs) if (b.feature() != null) seen[b.feature().ordinal()] = true;
		List<Feature> out = new ArrayList<>();
		for (int i = 0; i < seen.length; i++) if (seen[i]) out.add(Feature.values()[i]);
		return out;
	}

	// ------------------------------------------------------------------ output

	private static Map<String, int[]> counter() {
		return new LinkedHashMap<>();
	}

	private static void bump(Map<String, int[]> m, String key) {
		m.computeIfAbsent(key, k -> new int[1])[0]++;
	}

	private static void enumTable(String name, Map<String, int[]> counts, int n) {
		System.out.println("  " + name + ":");
		counts.entrySet().stream()
				.sorted(Comparator.comparingInt(e -> -e.getValue()[0]))
				.forEach(e -> bar("    " + e.getKey().toLowerCase(), e.getValue()[0], n));
	}

	private static void bar(String label, int count, int n) {
		double pct = 100.0 * count / n;
		int width = (int) Math.round(pct / 2);
		String flag = pct < 5 ? "  rare" : pct > 75 ? "  ubiquitous" : "";
		System.out.printf("    %-24s %5.1f%%  %s%s%n", label, pct, "#".repeat(width), flag);
	}

	private static String spreadBar(double sd) {
		return "#".repeat((int) Math.round(sd * 60));
	}

	private static int bucket(int v, int size) {
		return (v / size) * size;
	}

	private static void section(String title) {
		System.out.println("\n" + "-".repeat(78));
		System.out.println(title);
		System.out.println("-".repeat(78));
	}
}
