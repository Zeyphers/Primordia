package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPalette;
import dev.jsz.primordia.body.EarType;
import dev.jsz.primordia.body.EyeStyle;
import dev.jsz.primordia.body.FootType;
import dev.jsz.primordia.body.GeneOptions;
import dev.jsz.primordia.body.HornType;
import dev.jsz.primordia.body.TailShape;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.GeneDoc;
import dev.jsz.primordia.genome.Genome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The genome is the interface. Every locus a player can drag has to say what it does, and every
 * tick drawn under a slider has to be where the decoder actually changes its mind.
 */
class GeneDocTest {

	@Test
	void everyLocusIsDocumented() {
		List<String> missing = new ArrayList<>();
		for (Gene gene : Gene.VALUES) {
			if (!GeneDoc.documented(gene)) missing.add(gene.name());
		}
		assertTrue(missing.isEmpty(),
				"loci with no description, so their slider is unlabelled in the editor: " + missing);
	}

	/**
	 * A placeholder is worse than nothing: it looks answered. Short is fine where the locus really
	 * is simple — "Ear shape." says everything there is to say — so this only catches the empty and
	 * the unfinished.
	 */
	@Test
	void descriptionsAreRealSentences() {
		List<String> bad = new ArrayList<>();
		for (Gene gene : Gene.VALUES) {
			String text = GeneDoc.describe(gene);
			if (text.length() < 9 || !text.endsWith(".")) bad.add(gene + ": \"" + text + "\"");
		}
		assertTrue(bad.isEmpty(), "descriptions too short or unpunctuated: " + bad);
	}

	/**
	 * The ticks the editor draws come from {@link Gene#discreteLo}, and the creature comes from
	 * {@link Genome#discrete(Gene)}. This is the assertion that they are the same thing.
	 * <p>
	 * A tick that disagrees with the decode is worse than no tick at all: it is a statement about
	 * the creature that the player will act on, and acting on it produces a different animal than
	 * the one the control promised.
	 */
	@Test
	void everyTickDecodesToTheOptionItClaims() {
		Random random = new Random(4242);
		List<String> wrong = new ArrayList<>();
		for (Gene gene : Gene.VALUES) {
			if (!gene.isDiscrete()) continue;
			int options = gene.discreteHi - gene.discreteLo + 1;
			for (int option = 0; option < options; option++) {
				// The editor snaps a slider to the middle of each option's band, which is the value
				// furthest from either boundary and so the one a rounding error cannot move.
				float slider = (option + 0.5f) / options;
				int decoded = Genome.random(random).with(gene, slider).discrete(gene);
				int expected = gene.discreteLo + option;
				if (decoded != expected) {
					wrong.add(String.format("%s tick %d sits at %.4f but decodes to %d, not %d",
							gene, option, slider, decoded, expected));
				}
			}
		}
		assertTrue(wrong.isEmpty(), String.join(System.lineSeparator(), wrong));
	}

	/** And the declared range has to be the range that comes out. */
	@Test
	void discreteLociStayInsideTheirDeclaredRange() {
		Random random = new Random(99);
		for (Gene gene : Gene.VALUES) {
			if (!gene.isDiscrete()) continue;
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (int i = 0; i <= 200; i++) {
				int v = Genome.random(random).with(gene, i / 200f).discrete(gene);
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
			assertEquals(gene.discreteLo, lo, gene + " decodes below its declared floor");
			assertEquals(gene.discreteHi, hi, gene + " decodes above its declared ceiling");
		}
	}
	/**
	 * The same assertion for the loci that pick a <i>named</i> option: every tick the editor draws
	 * has to land in the band of the option whose name it carries.
	 * <p>
	 * {@link GeneOptions} reconstructs the boundaries from the decoding rule rather than from the
	 * decoders themselves, which is exactly the kind of parallel description that goes stale. This
	 * runs the real decoder — {@code FootType.of}, {@code BodyPalette}, and the rest — over the
	 * middle of each band and checks the name that comes back.
	 */
	@Test
	void everyNamedOptionDecodesToItsOwnLabel() {
		Random random = new Random(8181);
		List<String> wrong = new ArrayList<>();
		for (Gene gene : Gene.VALUES) {
			List<GeneOptions.Option> options = GeneOptions.of(gene);
			if (options.isEmpty() || decode(gene, Genome.random(random)) == null) continue;
			for (int i = 0; i < options.size(); i++) {
				float start = options.get(i).start();
				float end = i + 1 < options.size() ? options.get(i + 1).start() : 1f;
				float slider = (start + end) * 0.5f;
				String decoded = decode(gene, Genome.random(random).with(gene, slider));
				if (!options.get(i).label().equals(decoded)) {
					wrong.add(String.format("%s tick \"%s\" spans [%.3f,%.3f) but %.3f decodes to %s",
							gene, options.get(i).label(), start, end, slider, decoded));
				}
			}
		}
		assertTrue(wrong.isEmpty(), String.join(System.lineSeparator(), wrong));
	}

	/** Runs the locus through whichever decoder actually owns it. Null when nothing names it. */
	private static String decode(Gene gene, Genome g) {
		return switch (gene) {
			case FOOT_TYPE -> FootType.of(g).name();
			case EYE_STYLE -> EyeStyle.of(g).name();
			case TAIL_SHAPE -> TailShape.of(g).name();
			case HORN_TYPE -> HornType.of(g).name();
			case EAR_TYPE -> EarType.of(g).name();
			case PATTERN_TYPE -> new BodyPalette(g).pattern.name();
			case GLOW_REGION -> new BodyPalette(g).glowRegion.name();
			default -> null;
		};
	}

	/**
	 * A threshold locus is a switch wearing a slider, and the tick is the whole of what the player
	 * is told about where the switch is. It has to be inside the range and it has to be the value
	 * the trait actually flips at.
	 */
	@Test
	void thresholdsFlipWhereTheyClaimTo() {
		Random random = new Random(1717);
		for (Gene gene : Gene.VALUES) {
			if (!gene.hasThreshold()) continue;
			assertTrue(gene.threshold > 0f && gene.threshold < 1f,
					gene + " declares a threshold of " + gene.threshold + ", which no slider can "
							+ "sit either side of");
			Genome base = Genome.random(random);
			// A quantum of the wire format's own grid: Genome snaps every locus onto 1/65535ths at
			// construction, so a declared threshold that is not itself on the grid lands half a step
			// either side of it. That is a thousandth of a pixel on the slider and does not matter;
			// what matters is that the flip happens at the tick and not somewhere else entirely.
			float quantum = 1f / 65535f;
			assertTrue(base.with(gene, gene.threshold + quantum).expresses(gene),
					gene + " does not express just above its own threshold");
			assertFalse(base.with(gene, gene.threshold - quantum).expresses(gene),
					gene + " expresses just below its own threshold");
		}
	}

	/**
	 * The prose and the tick have to agree about whether there is one.
	 * <p>
	 * Every threshold description in {@link GeneDoc} opens "Past the tick", which is only true if a
	 * tick gets drawn — and a locus that grew a threshold without its sentence being rewritten
	 * describes a slider the player is not looking at. Catches the drift in both directions.
	 */
	@Test
	void prosePromisesATickOnlyWhereThereIsOne() {
		List<String> wrong = new ArrayList<>();
		for (Gene gene : Gene.VALUES) {
			boolean promises = GeneDoc.describe(gene).contains("Past the tick");
			boolean draws = gene.hasThreshold();
			if (promises && !draws) {
				wrong.add(gene + " describes a tick but declares no threshold to draw one at");
			} else if (draws && !promises) {
				wrong.add(gene + " has a threshold at " + gene.threshold
						+ " but its description never mentions the boundary");
			}
		}
		assertTrue(wrong.isEmpty(), String.join(System.lineSeparator(), wrong));
	}

	/**
	 * The glow cut is one boundary with two consumers: the palette shades against it and the editor
	 * draws it. Reading the same field is what keeps the tick honest.
	 */
	@Test
	void theGlowCutIsTheGenesOwn() {
		assertEquals(Gene.BIOLUMINESCENCE.threshold, BodyPalette.GLOW_THRESHOLD,
				"the palette and the slider disagree about where a creature starts glowing");
	}
}
