package dev.jsz.primordia;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GuideChapters;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.lab.SampleData;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the field guide's record keeping.
 * <p>
 * Filing consumes the report item, so a specimen that fails to land in the guide is destroyed
 * outright — the player walked out, found an animal, jabbed it, fed a lab two resources, waited
 * eleven seconds, and got nothing. That failure is silent and unrecoverable, which is why it is
 * worth a test rather than a playthrough.
 */
class GuideFilingTest {

	/** A blank guide's storage. The item itself needs a registry a headless test cannot start. */
	private static NbtCompound guide() {
		return new NbtCompound();
	}

	@Test
	void filingSurvivesTheRoundTripThroughItemComponents() {
		Random random = new Random(4242);
		Genome specimen = Genome.random(random);

		NbtCompound stack = guide();
		GuideData data = GuideData.fromNbt(stack);
		data.file(specimen);
		data.writeInto(stack);

		// Read back from the stack, the way the screen does on the client.
		GuideData reloaded = GuideData.fromNbt(stack);
		assertEquals(1, reloaded.speciesCount());
		assertEquals(1, reloaded.specimensFiled());

		GuideData.Entry entry = reloaded.entries().get(0);
		assertEquals(specimen.lineage(), entry.lineage());
		assertNotNull(entry.genome(), "the stored genome did not decode");
		assertEquals(specimen, entry.genome(),
				"the specimen changed passing through the guide's storage");
	}

	@Test
	void manySpecimensOfOneLineageCollapseIntoOneEntry() {
		Random random = new Random(99);
		Genome founder = Genome.random(random);
		NbtCompound stack = guide();

		// Twelve individuals of the same lineage — the whole point of the guide is that this is one
		// line in a book rather than twelve items in a backpack.
		for (int i = 0; i < 12; i++) {
			Genome sibling = new Genome(Genome.random(random).copyValues(),
					random.nextLong(), founder.lineage(), i);
			GuideData data = GuideData.fromNbt(stack);
			data.file(sibling);
			data.writeInto(stack);
		}

		GuideData data = GuideData.fromNbt(stack);
		assertEquals(1, data.speciesCount(), "one lineage produced more than one entry");
		assertEquals(12, data.specimensFiled());
		assertEquals(DecodeAccuracy.COMPLETE, data.entries().get(0).accuracy(),
				"twelve filed specimens did not fully characterise the species");
	}

	@Test
	void separateLineagesStaySeparateEntries() {
		Random random = new Random(7);
		NbtCompound stack = guide();
		for (int i = 0; i < 5; i++) {
			GuideData data = GuideData.fromNbt(stack);
			data.file(Genome.random(random));   // each gets its own lineage
			data.writeInto(stack);
		}
		GuideData data = GuideData.fromNbt(stack);
		assertEquals(5, data.speciesCount());
		assertEquals(5, data.specimensFiled());
	}

	@Test
	void theRepresentativeSpecimenTracksTheNewestGeneration() {
		Random random = new Random(555);
		Genome old = Genome.random(random).withGeneration(2);
		Genome recent = new Genome(Genome.random(random).copyValues(),
				random.nextLong(), old.lineage(), 40);

		NbtCompound stack = guide();
		GuideData data = GuideData.fromNbt(stack);
		data.file(old);
		data.file(recent);
		data.writeInto(stack);

		GuideData.Entry entry = GuideData.fromNbt(stack).entries().get(0);
		assertEquals(40, entry.generation());
		// A lineage that is visibly evolving should be illustrated by what it has become, not by
		// the first one you happened to catch.
		assertEquals(recent, entry.genome());
	}

	@Test
	void anEmptyGuideReadsCleanlyRatherThanThrowing() {
		GuideData data = GuideData.fromNbt(guide());
		assertEquals(0, data.speciesCount());
		assertEquals(0, data.specimensFiled());
		assertTrue(data.entries().isEmpty());
	}

	@Test
	void namingIsRefusedUntilTheSpeciesIsFullyCharacterised() {
		Random random = new Random(606);
		NbtCompound stack = guide();
		Genome founder = Genome.random(random);

		// Part-way there: the right to name it has not been earned.
		for (int i = 0; i < 5; i++) {
			GuideData part = GuideData.fromNbt(stack);
			part.file(new Genome(founder.copyValues(), random.nextLong(), founder.lineage(), i));
			part.writeInto(stack);
		}
		GuideData partial = GuideData.fromNbt(stack);
		assertFalse(partial.entries().get(0).nameable());
		assertFalse(partial.rename(founder.lineage(), "Greenback"),
				"a half-studied species accepted a name");

		// Finish the work; now it may be named.
		for (int i = 5; i < 12; i++) {
			GuideData more = GuideData.fromNbt(stack);
			more.file(new Genome(founder.copyValues(), random.nextLong(), founder.lineage(), i));
			more.writeInto(stack);
		}
		GuideData complete = GuideData.fromNbt(stack);
		assertTrue(complete.entries().get(0).nameable());
		assertTrue(complete.rename(founder.lineage(), "  Greenback  "));
		complete.writeInto(stack);

		GuideData.Entry named = GuideData.fromNbt(stack).entries().get(0);
		assertEquals("Greenback", named.name(), "the name was not trimmed and stored");
		assertEquals("Greenback", named.displayName());
		assertTrue(named.named());
		// The bloodline marking survives naming: the machines print it and the tree is built on it.
		assertEquals(SampleData.shortLineage(founder), named.label());
	}

	@Test
	void aNameSurvivesFurtherSpecimensAndOverLongOnesAreClipped() {
		Random random = new Random(707);
		NbtCompound stack = guide();
		Genome founder = Genome.random(random);
		for (int i = 0; i < 12; i++) {
			GuideData data = GuideData.fromNbt(stack);
			data.file(new Genome(founder.copyValues(), random.nextLong(), founder.lineage(), i));
			data.writeInto(stack);
		}

		GuideData data = GuideData.fromNbt(stack);
		data.rename(founder.lineage(), "x".repeat(GuideData.MAX_NAME + 40));
		data.writeInto(stack);
		assertEquals(GuideData.MAX_NAME, GuideData.fromNbt(stack).entries().get(0).name().length(),
				"an over-long name was not clipped");

		// Filing a thirteenth specimen must not take the name away — the cruellest possible moment.
		GuideData after = GuideData.fromNbt(stack);
		after.file(new Genome(founder.copyValues(), random.nextLong(), founder.lineage(), 99));
		after.writeInto(stack);
		assertTrue(GuideData.fromNbt(stack).entries().get(0).named(),
				"a further specimen erased the species' name");
	}

	@Test
	void namingAnUnknownBloodlineDoesNothing() {
		GuideData data = GuideData.fromNbt(guide());
		assertFalse(data.rename(1234L, "Nothing"), "named a species that was never filed");
	}

	@Test
	void sealedEntriesOpenOnlyOnceTheWorkIsDone() {
		NbtCompound stack = guide();
		GuideData empty = GuideData.fromNbt(stack);

		// A fresh guide must read as a book with things still to find. If everything were legible
		// from the start the entries would be documentation, which is what they stopped being.
		long openAtStart = GuideChapters.CHAPTERS.stream().filter(c -> c.unlocked(empty)).count();
		assertTrue(openAtStart >= 1, "a new guide is entirely sealed and reads as broken");
		assertTrue(openAtStart < GuideChapters.CHAPTERS.size(),
				"every entry is legible immediately, so nothing is discovered by playing");

		// Filing one specimen opens strictly more than nothing did, and never closes anything.
		Random random = new Random(808);
		GuideData data = GuideData.fromNbt(stack);
		data.file(Genome.random(random));
		data.writeInto(stack);
		GuideData afterOne = GuideData.fromNbt(stack);

		for (GuideChapters.Chapter chapter : GuideChapters.CHAPTERS) {
			if (chapter.unlocked(empty)) {
				assertTrue(chapter.unlocked(afterOne),
						"'" + chapter.title() + "' closed again after filing a specimen");
			}
		}
		assertTrue(GuideChapters.CHAPTERS.stream().filter(c -> c.unlocked(afterOne)).count()
						> openAtStart,
				"filing a specimen opened nothing at all");
	}

	@Test
	void aThoroughlyStudiedCollectionOpensTheWholeBook() {
		Random random = new Random(1234);
		NbtCompound stack = guide();

		// Three related bloodlines, all studied to completion. Three because two of the entries
		// are gated on having met enough kinds to notice they differ — a reader who has genuinely
		// done the work should not be left staring at sealed pages.
		Genome founder = Genome.random(random);
		for (int lineage = 0; lineage < 3; lineage++) {
			float[] values = founder.copyValues();
			// Drift each successive stock slightly, so they read as kin rather than as strangers
			// and the fork-detecting entry can see a relationship between them.
			for (int i = 0; i < values.length; i++) {
				values[i] = Math.min(1f, Math.max(0f, values[i] + 0.04f * lineage));
			}
			long id = founder.lineage() + lineage;
			for (int i = 0; i < 14; i++) {
				GuideData data = GuideData.fromNbt(stack);
				data.file(new Genome(values, random.nextLong(), id, i));
				data.writeInto(stack);
			}
		}

		GuideData full = GuideData.fromNbt(stack);
		List<String> stillSealed = new java.util.ArrayList<>();
		for (GuideChapters.Chapter chapter : GuideChapters.CHAPTERS) {
			if (!chapter.unlocked(full)) stillSealed.add(chapter.title());
		}
		assertTrue(stillSealed.isEmpty(),
				"entries unreachable even after exhaustive study: " + stillSealed);
	}

	@Test
	void everySectionIsWellFormedAndCoversEveryChapter() {
		assertFalse(GuideChapters.SECTIONS.isEmpty(), "the guide has no tabs");

		java.util.Set<String> covered = new java.util.HashSet<>();
		for (GuideChapters.Section section : GuideChapters.SECTIONS) {
			assertFalse(section.title().isBlank(), "a tab has no title");
			// The last two tabs are generated from the reader's own records rather than written.
			boolean generated = GuideChapters.SECTIONS.indexOf(section) >= GuideChapters.REFERENCE_TAB;
			assertTrue(generated || !section.chapters().isEmpty(),
					section.title() + " is an empty tab");
			// A tab draws its icon from this id. A malformed one would leave a blank, and a
			// namespace typo silently resolves to minecraft: and renders as a missing item.
			assertTrue(section.iconItemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"),
					section.title() + " has an unusable icon id: " + section.iconItemId());
			section.chapters().forEach(c -> covered.add(c.title()));
		}

		// Every chapter must be reachable. One left out of the tabs is written, compiled, shipped
		// and unreadable — the worst kind of missing, because nothing reports it.
		for (GuideChapters.Chapter chapter : GuideChapters.CHAPTERS) {
			assertTrue(covered.contains(chapter.title()),
					"chapter '" + chapter.title() + "' is in no tab and cannot be read");
		}
	}

	@Test
	void everyChapterHasATitleAndSomethingToSay() {
		assertFalse(GuideChapters.CHAPTERS.isEmpty(), "the manual is empty");
		for (GuideChapters.Chapter chapter : GuideChapters.CHAPTERS) {
			assertFalse(chapter.title().isBlank(), "a chapter has no title");
			assertFalse(chapter.paragraphs().isEmpty(), chapter.title() + " has no body");
			boolean hasProse = chapter.paragraphs().stream().anyMatch(p -> p.length() > 20);
			assertTrue(hasProse, chapter.title() + " is all blank lines");
		}
	}

	@Test
	void theGuideNeverReportsFiguresItHasNotEarned() {
		Random random = new Random(31);
		Genome specimen = Genome.random(random);
		NbtCompound stack = guide();
		GuideData data = GuideData.fromNbt(stack);
		data.file(specimen);
		data.writeInto(stack);

		// The guide counts total specimens filed, where a report counts what was on file *before*
		// it — so one filed specimen is legitimately worth a coarse bracket here while the report
		// that produced it read as unreferenced. What must never happen is exact figures from a
		// single specimen, which would let the guide skip the progression the lab exists to create.
		GuideData.Entry entry = GuideData.fromNbt(stack).entries().get(0);
		String reported = entry.accuracy().describeFraction(specimen.raw(Gene.AGGRESSION));
		String exact = DecodeAccuracy.COMPLETE.describeFraction(specimen.raw(Gene.AGGRESSION));

		assertFalse(entry.accuracy().atLeast(DecodeAccuracy.GOOD),
				"one specimen resolved to " + entry.accuracy() + " — too generous");
		assertNotEquals(exact, reported,
				"a single specimen produced the same figure as a fully characterised species");
		assertFalse(reported.matches(".*\\d.*"),
				"a single specimen leaked a number: " + reported);
	}
}
