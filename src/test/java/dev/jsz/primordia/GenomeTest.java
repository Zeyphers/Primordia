package dev.jsz.primordia;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.genome.Mutation;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GenomeTest {

	@Test
	void encodeDecodeRoundTrips() {
		Random random = new Random(1234);
		for (int trial = 0; trial < 200; trial++) {
			Genome original = Genome.random(random);
			Genome decoded = Genome.decode(original.encode());

			assertNotNull(decoded);
			assertEquals(original.seed(), decoded.seed());
			assertEquals(original.lineage(), decoded.lineage());
			assertEquals(original.generation(), decoded.generation());
			for (Gene gene : Gene.VALUES) {
				// Exact, not approximate: genomes are quantised to the wire's 16-bit grid at
				// construction, so a round trip must be bit-identical. Anything less breaks
				// genome equality, and the mesh cache is keyed on it.
				assertEquals(original.raw(gene), decoded.raw(gene), 0f,
						"gene " + gene + " drifted through serialisation");
			}
		}
	}

	@Test
	void malformedCodesDecodeToNullRatherThanThrowing() {
		assertNull(Genome.decode(null));
		assertNull(Genome.decode(""));
		assertNull(Genome.decode("!!!not base64!!!"));
		assertNull(Genome.decode("QUJD")); // valid base64, far too short
	}

	@Test
	void equalGenomesHashEqually() {
		// The mesh cache is keyed by genome, so this is a correctness requirement, not a nicety.
		Genome a = Genome.random(new Random(7));
		Genome b = Genome.decode(a.encode());
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	void allGeneValuesStayInRange() {
		Random random = new Random(99);
		Genome genome = Genome.random(random);
		for (int generation = 0; generation < 500; generation++) {
			genome = Mutation.mutate(genome, random);
			for (Gene gene : Gene.VALUES) {
				float v = genome.raw(gene);
				assertTrue(v >= 0f && v <= 1f, gene + " escaped [0,1] at generation " + generation + ": " + v);
			}
		}
	}

	@Test
	void offspringResembleParentsMoreThanStrangers() {
		Random random = new Random(4242);
		double kinSum = 0, strangerSum = 0;
		int trials = 200;

		for (int i = 0; i < trials; i++) {
			Genome a = Genome.random(random);
			Genome b = Genome.random(random);
			Genome stranger = Genome.random(random);
			Genome child = Mutation.breed(a, b, random);

			kinSum += Math.min(Mutation.distance(child, a), Mutation.distance(child, b));
			strangerSum += Mutation.distance(child, stranger);
		}
		// Inheritance has to actually inherit, or "evolution" is just a random walk.
		assertTrue(kinSum / trials < strangerSum / trials,
				"children are no closer to their parents than to random genomes");
	}

	@Test
	void generationCounterAdvances() {
		Random random = new Random(11);
		Genome parent = Genome.random(random);
		Genome child = Mutation.mutate(parent, random);
		assertEquals(parent.generation() + 1, child.generation());
	}
}
