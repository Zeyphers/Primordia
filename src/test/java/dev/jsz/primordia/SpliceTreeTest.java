package dev.jsz.primordia;

import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.DecodeAccuracy;
import dev.jsz.primordia.lab.GuideData;
import dev.jsz.primordia.splice.SpliceBranch;
import dev.jsz.primordia.splice.SpliceDepth;
import dev.jsz.primordia.splice.SpliceEffects;
import dev.jsz.primordia.splice.SpliceLoadout;
import dev.jsz.primordia.splice.SpliceTree;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the splice tree: the rules from {@code MD/SPLICING.md} that the player is promised.
 * <p>
 * The unlock arithmetic is the whole balance of the feature and it is invisible — a branch that
 * opens one lineage too early is a design decision quietly rewritten, and nothing else in the suite
 * would notice. So the rules are tested as rules rather than as code paths: the tree is authored,
 * what fills it is not, and every assertion here is about the seam between those two.
 */
class SpliceTreeTest {

	/** A guide holding {@code count} lineages, each pinned to {@code value} at {@code locus}. */
	private static GuideData guideWith(Gene locus, float value, int count, int filed) {
		GuideData data = GuideData.empty();
		Random random = new Random(4242);
		for (int i = 0; i < count; i++) {
			Genome genome = Genome.random(random).with(locus, value);
			// file() records one specimen per call; the accuracy ladder is driven by how many.
			for (int f = 0; f < filed; f++) data.file(genome);
		}
		return data;
	}

	// ------------------------------------------------------------------ the linkage blocks

	@Test
	void everyBranchIsAContiguousRunOfLoci() {
		for (SpliceBranch branch : SpliceBranch.VALUES) {
			var genes = branch.genes();
			assertFalse(genes.isEmpty(), branch + " carries no loci");
			for (int i = 0; i < genes.size(); i++) {
				assertEquals(branch.first.ordinal() + i, genes.get(i).ordinal(),
						branch + " is not contiguous — it is a list wearing a block's name");
			}
		}
	}

	@Test
	void noLocusBelongsToTwoBranches() {
		for (Gene gene : Gene.VALUES) {
			int owners = 0;
			for (SpliceBranch branch : SpliceBranch.VALUES) {
				if (branch.carries(gene)) owners++;
			}
			assertTrue(owners <= 1, gene + " is carried by " + owners + " branches");
		}
	}

	/**
	 * Rule 2 only earns its drawbacks if the player can see them. A locus in a block with no row in
	 * the effects table is one the splice screen cannot describe, which turns an adopted package
	 * back into a menu of benefits.
	 */
	@Test
	void everyLocusInEveryBranchIsInTheEffectsTable() {
		for (SpliceBranch branch : SpliceBranch.VALUES) {
			for (Gene gene : branch.genes()) {
				assertNotNull(SpliceEffects.rowFor(gene),
						gene + " is in " + branch + " but has no row in the effects table");
			}
		}
	}

	/**
	 * Rule 2 exists so that a splice is a trade rather than a purchase, and a branch that cannot
	 * cost the player anything has quietly opted out of it.
	 * <p>
	 * {@link SpliceBranch#COLOUR} and {@link SpliceBranch#LIGHT} are exempt by design, not by
	 * oversight. Colour is the tutorial — it exists so the player learns the mechanic on something
	 * that cannot hurt them — and {@code MD/SPLICING.md} §9 picks Light as the vertical slice
	 * precisely because it is harmless, so the loop can be proven before the balance problem has to
	 * be solved. Both still cost a slot, which is the only currency the design actually has.
	 */
	@Test
	void everyBranchWithStatsAlsoCarriesACost() {
		for (SpliceBranch branch : SpliceBranch.VALUES) {
			if (branch == SpliceBranch.COLOUR || branch == SpliceBranch.LIGHT) continue;
			var rows = SpliceEffects.rowsFor(branch);
			assertTrue(rows.stream().anyMatch(r -> r.atFull() != 0), branch + " does nothing at all");
			assertTrue(rows.stream().anyMatch(SpliceEffects.Row::canCost),
					branch + " is all upside — the package rule is not being paid");
		}
	}

	/** A two-sided locus must actually be able to land on both sides. */
	@Test
	void aClimateDonorFromTheWrongPlaceIsACost() {
		var row = SpliceEffects.rowFor(Gene.TEMP_PREFERENCE);
		assertNotNull(row);
		assertTrue(row.beneficial(1.0f), "a heat-adapted donor should help against fire");
		assertFalse(row.beneficial(0.0f), "a cold-adapted donor should burn worse, not merely less well");
		assertEquals(0, row.magnitude(0.5f), 1e-9, "wild type should be neither");
	}

	// ------------------------------------------------------------------ the depth rule

	@Test
	void aBranchStaysShutUntilSomethingStrongEnoughIsFullyCharacterised() {
		// Six lineages, all strong, none of them studied past a glance.
		GuideData glanced = guideWith(Gene.BIOLUMINESCENCE, 0.9f, 6, 1);
		assertNull(SpliceTree.reached(glanced, SpliceBranch.LIGHT),
				"a branch opened on lineages that were never characterised");

		GuideData studied = guideWith(Gene.BIOLUMINESCENCE, 0.9f, 1,
				DecodeAccuracy.COMPLETE.decodesRequired);
		assertEquals(SpliceDepth.TRACE, SpliceTree.reached(studied, SpliceBranch.LIGHT),
				"one complete strong lineage should open the first node and no more");
	}

	@Test
	void weakLineagesNeverOpenAnything() {
		GuideData weak = guideWith(Gene.BIOLUMINESCENCE, 0.20f, 8,
				DecodeAccuracy.COMPLETE.decodesRequired);
		assertNull(SpliceTree.reached(weak, SpliceBranch.LIGHT),
				"eight thoroughly studied dim animals opened a light branch");
	}

	@Test
	void depthTracksTheCountOfStrongLineages() {
		int complete = DecodeAccuracy.COMPLETE.decodesRequired;
		assertEquals(SpliceDepth.TRACE,
				SpliceTree.reached(guideWith(Gene.BIOLUMINESCENCE, 0.9f, 2, complete), SpliceBranch.LIGHT));
		assertEquals(SpliceDepth.EXPRESSED,
				SpliceTree.reached(guideWith(Gene.BIOLUMINESCENCE, 0.9f, 3, complete), SpliceBranch.LIGHT));
		assertEquals(SpliceDepth.DOMINANT,
				SpliceTree.reached(guideWith(Gene.BIOLUMINESCENCE, 0.9f, 5, complete), SpliceBranch.LIGHT));
	}

	/**
	 * The rule that makes finding a great donor early into anticipation rather than an anticlimax:
	 * the strong animal is on file and visible, and only some of it can be carried yet.
	 */
	@Test
	void depthCapsWhatIsCarriedRatherThanWhatIsFound() {
		GuideData data = guideWith(Gene.BIOLUMINESCENCE, 0.95f, 1,
				DecodeAccuracy.COMPLETE.decodesRequired);
		var best = SpliceTree.bestDonor(data, SpliceBranch.LIGHT);
		assertNotNull(best);
		assertEquals(0.95f, best.potency(), 0.01f, "the guide should report what the animal is");

		SpliceLoadout loadout = new SpliceLoadout();
		loadout.install(SpliceBranch.LIGHT, best.lineage(), best.label(), best.genome(),
				SpliceTree.capIn(data, SpliceBranch.LIGHT));
		assertEquals(SpliceDepth.TRACE.cap, loadout.inBranch(SpliceBranch.LIGHT).potency(), 0.01f,
				"a Trace node let a 0.95 donor through at full strength");
	}

	// ------------------------------------------------------------------ slots

	@Test
	void slotsStartAtTwoAndAreBoughtByMastery() {
		assertEquals(SpliceTree.BASE_SLOTS, SpliceTree.slots(GuideData.empty()));

		GuideData mastered = guideWith(Gene.BIOLUMINESCENCE, 0.9f, 5,
				DecodeAccuracy.COMPLETE.decodesRequired);
		assertEquals(SpliceTree.BASE_SLOTS + 1, SpliceTree.slots(mastered),
				"taking a branch to Dominant should buy a slot");
	}

	// ------------------------------------------------------------------ the loadout

	@Test
	void oneBranchIsCarriedOnceAndReversionIsClean() {
		GuideData data = guideWith(Gene.BIOLUMINESCENCE, 0.9f, 1,
				DecodeAccuracy.COMPLETE.decodesRequired);
		var donor = SpliceTree.bestDonor(data, SpliceBranch.LIGHT);
		SpliceLoadout loadout = new SpliceLoadout();

		loadout.install(SpliceBranch.LIGHT, donor.lineage(), "a", donor.genome(), 1f);
		loadout.install(SpliceBranch.LIGHT, donor.lineage(), "b", donor.genome(), 1f);
		assertEquals(1, loadout.used(), "the same branch was carried twice");
		assertEquals("b", loadout.inBranch(SpliceBranch.LIGHT).label());

		assertTrue(loadout.revert(SpliceBranch.LIGHT));
		assertTrue(loadout.isEmpty(), "reversion left something behind");
		assertFalse(loadout.revert(SpliceBranch.LIGHT), "reverting nothing reported success");
	}

	@Test
	void aLoadoutRoundTripsThroughNbt() {
		GuideData data = guideWith(Gene.SPEED, 0.8f, 1, DecodeAccuracy.COMPLETE.decodesRequired);
		var donor = SpliceTree.bestDonor(data, SpliceBranch.PHYSIOLOGY);
		SpliceLoadout before = new SpliceLoadout();
		before.install(SpliceBranch.PHYSIOLOGY, donor.lineage(), "runner", donor.genome(), 0.75f);

		SpliceLoadout after = SpliceLoadout.fromNbt(before.writeNbt());
		assertEquals(1, after.used());
		var carried = after.inBranch(SpliceBranch.PHYSIOLOGY);
		assertNotNull(carried);
		assertEquals("runner", carried.label());
		assertEquals(before.inBranch(SpliceBranch.PHYSIOLOGY).potency(), carried.potency(), 0.005f);
		assertEquals(before.exhaustionMultiplier(), after.exhaustionMultiplier(), 0.005f);
	}

	/** Carrying nothing must cost nothing — the wild-type player is the baseline. */
	@Test
	void anEmptyLoadoutIsInert() {
		SpliceLoadout empty = new SpliceLoadout();
		assertEquals(1f, empty.exhaustionMultiplier(), 1e-6);
		assertEquals(0f, empty.glowStrength(), 1e-6);
	}
}
