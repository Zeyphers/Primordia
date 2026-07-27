package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.LodTier;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the ornament and body-architecture traits: horns, tail shapes, light organs, and the
 * segmented two-part body plan.
 * <p>
 * These are all traits with no invariant of their own to violate — a creature with no horns is
 * perfectly valid — so the thing worth testing is <b>reachability</b>. A branch of the generator
 * that no genome ever reaches is dead code that looks like a feature, and nothing else in the
 * suite would notice.
 */
class OrnamentTest {

	private static int countBlobs(BodyPlan plan, Feature feature) {
		int n = 0;
		for (SdfBlob blob : plan.blobs) {
			if (blob.feature() == feature) n++;
		}
		return n;
	}

	/** A genome with the ornament loci pinned, so one trait can be varied in isolation. */
	private static Genome base(long seed) {
		return Genome.random(new Random(seed))
				.with(Gene.HORN_TYPE, 0f)
				.with(Gene.TAIL_LENGTH, 0.9f)
				.with(Gene.TAIL_SHAPE, 0f)
				.with(Gene.BIOLUMINESCENCE, 0f)
				.with(Gene.SIZE, 0.6f)
				.with(Gene.HEAD_SIZE, 0.6f);
	}

	// ------------------------------------------------------------------ arachnids

	@Test
	void arachnidsAreSegmentedEightLeggedAndManyEyed() {
		Random random = new Random(5150);
		for (int trial = 0; trial < 20; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.ARACHNID.create(random));

			assertEquals(8, plan.legs.length, "an arachnid has four pairs of legs");
			assertEquals(1, countBlobs(plan, Feature.ABDOMEN),
					"no abdomen — the body did not segment");
			assertEquals(8, countBlobs(plan, Feature.EYE),
					"an arachnid should carry the eight-eye cluster");
		}
	}

	@Test
	void arachnidLegsClusterOntoTheFrontSegment() {
		Random random = new Random(5151);
		for (int trial = 0; trial < 20; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.ARACHNID.create(random));

			float frontmost = -Float.MAX_VALUE;
			float rearmost = Float.MAX_VALUE;
			for (LimbChain leg : plan.legs) {
				frontmost = Math.max(frontmost, leg.origin.z);
				rearmost = Math.min(rearmost, leg.origin.z);
			}
			// Strung out along the body is the insectoid plan, not this one.
			assertTrue(frontmost - rearmost < plan.bodyLength * 0.45f,
					"arachnid hips are spread over " + (frontmost - rearmost)
							+ " of a " + plan.bodyLength + " m body — they should cluster");
		}
	}

	@Test
	void arachnidKneesRideAboveTheHip() {
		// The tented leg is the whole silhouette. If the mid joint sits below the hip the
		// creature is just a long-legged insect, whatever else the genome says.
		Random random = new Random(5152);
		int above = 0;
		int total = 0;
		for (int trial = 0; trial < 25; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.ARACHNID.create(random));
			for (LimbChain leg : plan.legs) {
				if (plan.bones[leg.bones[0]].tail.y > leg.origin.y) above++;
				total++;
			}
		}
		assertTrue(above >= total * 0.9,
				"only " + above + " of " + total + " arachnid knees cleared the hip");
	}

	@Test
	void ordinaryQuadrupedsKeepTheirKneesBelowTheHip() {
		// The other half of the claim: the arch is a distinguishing trait, not the new default.
		Random random = new Random(5153);
		for (int trial = 0; trial < 25; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Archetype.GRAZER.create(random));
			for (LimbChain leg : plan.legs) {
				assertTrue(plan.bones[leg.bones[0]].tail.y < leg.origin.y,
						"a grazer grew an arachnid stance");
			}
		}
	}

	// ---------------------------------------------------------------------- horns

	@Test
	void hornsOnlyGrowOnGenomesThatExpressThem() {
		assertEquals(0, countBlobs(BodyPlanBuilder.build(base(11).with(Gene.HORN_TYPE, 0.10f)),
				Feature.HORN), "an unexpressed horn locus still grew horns");
		assertTrue(countBlobs(BodyPlanBuilder.build(base(11).with(Gene.HORN_TYPE, 0.90f)),
				Feature.HORN) > 0, "an expressed horn locus grew nothing");
	}

	@Test
	void everyHornTypeIsReachableAndMeshes() {
		// Walk the expressed part of the locus and check each sixth produces geometry. A type
		// that no value of the gene can select is a branch that will never be seen in game.
		for (int i = 0; i < 6; i++) {
			float locus = 0.40f + (i + 0.5f) / 6f * 0.60f;
			Genome genome = base(20 + i).with(Gene.HORN_TYPE, locus).with(Gene.HORN_SIZE, 0.8f);
			BodyPlan plan = BodyPlanBuilder.build(genome);

			assertTrue(countBlobs(plan, Feature.HORN) > 0,
					"horn locus " + locus + " produced no horn geometry");
			MeshData mesh = MeshBaker.bake(plan, LodTier.resolutionFor(LodTier.MID));
			assertTrue(mesh.quadCount > 0, "horn locus " + locus + " failed to mesh");
		}
	}

	@Test
	void antlersBranchAndNasalHornsDoNot() {
		// ANTLER is the fifth of six expressed types, NASAL the fourth.
		int nasal = countBlobs(BodyPlanBuilder.build(
				base(31).with(Gene.HORN_TYPE, 0.40f + 3.5f / 6f * 0.60f)), Feature.HORN);
		int antler = countBlobs(BodyPlanBuilder.build(
				base(31).with(Gene.HORN_TYPE, 0.40f + 4.5f / 6f * 0.60f)), Feature.HORN);
		assertTrue(antler > nasal,
				"a branching antler (" + antler + " beads) should carry more geometry than a "
						+ "single nasal horn (" + nasal + ")");
	}

	// ----------------------------------------------------------------- tail shape

	@Test
	void flatTailsAreWideAndFinnedTailsAreDeep() {
		Genome flat = base(41).with(Gene.TAIL_SHAPE, 0.30f).with(Gene.TAIL_FIN_DEPTH, 0.6f);
		Genome finned = base(41).with(Gene.TAIL_SHAPE, 0.50f).with(Gene.TAIL_FIN_DEPTH, 0.6f);

		assertTrue(hasFin(BodyPlanBuilder.build(flat), true),
				"a flat tail should be wider across than it is deep");
		assertTrue(hasFin(BodyPlanBuilder.build(finned), false),
				"a finned tail should be deeper than it is wide");
		assertEquals(0, countBlobs(BodyPlanBuilder.build(base(41).with(Gene.TAIL_SHAPE, 0.05f)),
				Feature.FIN), "a round tail should carry no fin geometry");
	}

	private static boolean hasFin(BodyPlan plan, boolean wide) {
		for (SdfBlob blob : plan.blobs) {
			if (blob.feature() != Feature.FIN) continue;
			boolean isWide = blob.radii().x > blob.radii().y * 1.5f;
			boolean isDeep = blob.radii().y > blob.radii().x * 1.5f;
			if (wide ? isWide : isDeep) return true;
		}
		return false;
	}

	// ------------------------------------------------------------ bioluminescence

	@Test
	void onlyBioluminescentCreaturesEmitLight() {
		BodyPlan dark = BodyPlanBuilder.build(base(51).with(Gene.BIOLUMINESCENCE, 0.10f));
		MeshData darkMesh = MeshBaker.bake(dark, LodTier.resolutionFor(LodTier.MID));
		assertEquals(0f, dark.palette.glowStrength, 0f, "a dark creature reported a glow strength");
		for (float e : darkMesh.emissive) {
			assertEquals(0f, e, 0f, "a non-bioluminescent creature emitted light");
		}

		// WHOLE_BODY is the last of six glow regions, so it sits at the top of the locus.
		BodyPlan lit = BodyPlanBuilder.build(base(51)
				.with(Gene.BIOLUMINESCENCE, 0.95f)
				.with(Gene.GLOW_REGION, 0.95f));
		MeshData litMesh = MeshBaker.bake(lit, LodTier.resolutionFor(LodTier.MID));
		assertTrue(lit.palette.glowStrength > 0f, "a bioluminescent creature reported no glow");
		assertTrue(countBlobs(lit, Feature.GLOW) > 0, "a strong glow genotype grew no light organs");

		boolean anyLit = false;
		for (float e : litMesh.emissive) {
			if (e > 0f) anyLit = true;
		}
		assertTrue(anyLit, "a bioluminescent creature baked with no emissive vertices at all");
	}

	@Test
	void emissiveWeightsAreWellFormedForEveryGenome() {
		Random random = new Random(5252);
		for (int trial = 0; trial < 60; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			MeshData mesh = MeshBaker.bake(plan, 14);

			assertEquals(mesh.vertexCount, mesh.emissive.length,
					"emissive buffer does not match the vertex count");
			for (float e : mesh.emissive) {
				assertTrue(e >= 0f && e <= 1f, "emissive weight out of range: " + e);
			}
		}
	}
}
