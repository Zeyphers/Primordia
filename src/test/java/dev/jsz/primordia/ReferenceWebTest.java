package dev.jsz.primordia;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GenomeLibrary;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what a species has to be studied <i>through</i> before it is understood.
 * <p>
 * Resolution used to come from a tally of decodes, which meant one cooperative animal poked
 * repeatedly answered every question about its species. A characterisation is supposed to be a web
 * of references — many individuals, and the relatives already on file — so these tests fix the two
 * halves of that: repeat readings of one specimen are one data point, and relatives help without
 * ever standing in for specimens of the animal itself.
 */
class ReferenceWebTest {

	/** {@link GenomeLibrary}'s saved-data type touches the registries as it loads. */
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void repeatSamplesOfOneAnimalAreOneSpecimen() {
		GenomeLibrary library = new GenomeLibrary();
		Genome animal = Genome.random(new Random(11));

		for (int sample = 0; sample < 8; sample++) {
			library.record(animal);
		}

		assertEquals(1, library.distinctCount(animal.lineage()),
				"eight swabs off one creature were counted as more than one individual");
	}

	@Test
	void differentIndividualsOfALineageAccumulate() {
		GenomeLibrary library = new GenomeLibrary();
		Genome first = Genome.random(new Random(12));
		Genome second = first.with(Gene.DIET, first.raw(Gene.DIET) > 0.5f ? 0.1f : 0.9f);

		// The premise of the test: a changed locus is a different animal of the same species.
		assertEquals(first.lineage(), second.lineage(), "editing a locus forked the lineage");

		library.record(first);
		library.record(second);

		assertEquals(2, library.distinctCount(first.lineage()),
				"two distinct individuals were collapsed into one");
	}

	/**
	 * The behaviour this whole mechanism exists to prevent: a single tame animal, sampled until the
	 * kit runs out, must not hand over a complete description of its species.
	 */
	@Test
	void farmingOneAnimalCannotCharacteriseItsSpecies() {
		GenomeLibrary library = new GenomeLibrary();
		Genome animal = Genome.random(new Random(13));

		for (int sample = 0; sample < 20; sample++) {
			library.record(animal);
		}

		DecodeAccuracy reached = DecodeAccuracy.resolve(library.referenceStrength(animal), 1f);
		assertFalse(reached.atLeast(DecodeAccuracy.GOOD),
				"twenty samples from one animal reached " + reached
						+ ", which is a species characterised by a single individual");
	}

	@Test
	void anUnstudiedSpeciesWithNoRelativesIsUnreferenced() {
		GenomeLibrary library = new GenomeLibrary();
		Genome stranger = Genome.random(new Random(14));

		assertEquals(0, library.referenceStrength(stranger),
				"a species nothing on file resembles started out already referenced");
	}

	/**
	 * Relatives are worth real progress and never worth everything — the borrowed contribution is
	 * capped below what {@link DecodeAccuracy#GOOD} costs, whatever is on the shelf.
	 */
	@Test
	void relativesNeverSubstituteForSpecimensOfTheAnimalItself() {
		GenomeLibrary library = new GenomeLibrary();
		Random random = new Random(15);

		// A large, varied collection: whatever happens to be related to the subject, there is a lot
		// of it, so this is the most generous case borrowing can ever face.
		for (int species = 0; species < 40; species++) {
			Genome other = Genome.random(random);
			for (int individual = 0; individual < 10; individual++) {
				library.record(other.with(Gene.DIET, individual / 10f));
			}
		}

		Genome subject = Genome.random(random);
		int borrowed = library.referenceStrength(subject);

		assertEquals(0, library.distinctCount(subject.lineage()),
				"the subject was accidentally part of the collection");
		DecodeAccuracy reached = DecodeAccuracy.resolve(borrowed, 1f);
		assertFalse(reached.atLeast(DecodeAccuracy.GOOD),
				"references alone reached " + reached + " with no specimen of the animal at all");
	}
}
