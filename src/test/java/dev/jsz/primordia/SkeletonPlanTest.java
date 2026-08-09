package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPalette;
import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.body.SkeletonPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinBinder;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the remains a body leaves behind.
 * <p>
 * The load-bearing invariant is the bone array: mesh vertices are skinned by bone index and the
 * animator poses the <i>flesh</i> plan, so a skeleton whose bones do not line up one-for-one with
 * the body's would come apart the moment the carcass was posed. Nothing about that failure is
 * visible from the geometry on its own, which is why it is asserted here rather than looked at.
 */
class SkeletonPlanTest {
	private static final int TRIALS = 200;

	@Test
	void everyGenomeYieldsASkeletonSkinnedToItsOwnBody() {
		Random random = new Random(31337);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan body = BodyPlanBuilder.build(genome);
			BodyPlan bones = SkeletonPlan.of(body);

			assertEquals(body.bones.length, bones.bones.length,
					"skeleton changed the bone count, so the mesh would skin to the wrong joints");
			for (int i = 0; i < body.bones.length; i++) {
				BoneDef flesh = body.bones[i], bone = bones.bones[i];
				assertEquals(flesh.name, bone.name, "bone " + i + " moved slot");
				assertEquals(flesh.parent, bone.parent, "bone " + i + " changed parent");
				assertEquals(flesh.head, bone.head, "bone " + i + " moved");
				assertEquals(flesh.tail, bone.tail, "bone " + i + " moved");
			}
			assertSame(body.legs, bones.legs, "the IK chains index the bone array and must be shared");
			assertEquals(body.headBone, bones.headBone);
			assertEquals(body.jawBone, bones.jawBone);

			for (SdfBlob blob : bones.blobs) {
				assertTrue(blob.bone() >= 0 && blob.bone() < bones.bones.length,
						"blob skinned to a bone that does not exist");
			}
		}
	}

	@Test
	void boneIsThinnerThanTheFleshItWasInside() {
		Random random = new Random(7);
		for (int trial = 0; trial < 100; trial++) {
			BodyPlan body = BodyPlanBuilder.build(Genome.random(random));
			BodyPlan bones = SkeletonPlan.of(body);
			for (int i = 0; i < body.bones.length; i++) {
				assertTrue(bones.bones[i].maxRadius() <= body.bones[i].maxRadius() + 1e-6f,
						"a bone came out thicker than the limb it was in");
			}
		}
	}

	@Test
	void skeletonsHaveNoHide() {
		BodyPlan bones = SkeletonPlan.of(BodyPlanBuilder.build(Genome.random(new Random(4))));
		assertEquals(0f, bones.palette.glowStrength, 1e-6f,
				"bones kept the animal's bioluminescence");
		// Mottled, not marked. Bone is blotchy and a flat ivory reads as plastic, but anything with
		// edges to it — stripes, spots — is a marking in a hide the skeleton no longer has.
		assertEquals(BodyPalette.PatternType.MARBLE, bones.palette.pattern,
				"a skeleton with a hide pattern reads as painted");
		assertTrue(bones.palette.patternContrast > 0f && bones.palette.patternContrast < 0.3f,
				"the mottling should be visible and no more, was " + bones.palette.patternContrast);
		assertTrue(bones.palette.countershading < 0.25f,
				"a dark-backed skeleton is a lit animal, not a dead one");
	}

	/**
	 * The one that would actually be noticed in play: a skeleton that bakes to nothing is an
	 * invisible entity lying in the world for ten days.
	 */
	@Test
	void everySkeletonBakesToSomethingYouCanSee() {
		Random random = new Random(55);
		for (int trial = 0; trial < 20; trial++) {
			BodyPlan bones = SkeletonPlan.of(BodyPlanBuilder.build(Genome.random(random)));
			MeshData mesh = MeshBaker.bake(bones, 16);
			assertTrue(mesh.quadCount > 0, "a skeleton baked to no faces at all");
			for (int i = 0; i < mesh.vertexCount * SkinBinder.MAX_INFLUENCES; i++) {
				assertTrue(mesh.boneIndices[i] >= 0 && mesh.boneIndices[i] < bones.bones.length,
						"a skeleton vertex is skinned outside the bone array");
			}
		}
	}

	/** The same bake through the voxel path the game renders creatures with by default. */
	@Test
	void skeletonsSurviveTheVoxelGrid() {
		float previous = MeshBaker.voxelSize();
		try {
			MeshBaker.setVoxelSize(1f / 16f);
			Random random = new Random(56);
			for (int trial = 0; trial < 10; trial++) {
				BodyPlan bones = SkeletonPlan.of(BodyPlanBuilder.build(Genome.random(random)));
				assertTrue(MeshBaker.bake(bones, 16).quadCount > 0,
						"a skeleton fell between the voxels and vanished");
			}
		} finally {
			MeshBaker.setVoxelSize(previous);
		}
	}

	/**
	 * Decay is measured against the overworld clock, so the stages are in clock ticks and a day of
	 * {@code /time add} is a day of rotting.
	 */
	@Test
	void aSkeletonOutlastsTheBodyItCameFrom() {
		assertTrue(CreatureEntity.CARCASS_ROT_TICKS < CreatureEntity.CARCASS_LIFETIME,
				"the meat has to fall before the body is stripped, not after");
		assertEquals(24000, CreatureEntity.CARCASS_ROT_TICKS, "rotting is one in-game day");
		assertEquals(48000, CreatureEntity.CARCASS_LIFETIME, "stripping is two in-game days");
		assertEquals(240000, CreatureEntity.SKELETON_LIFETIME, "bones lie for ten in-game days");
		assertTrue(CreatureEntity.SKELETON_LIFETIME > CreatureEntity.CARCASS_LIFETIME * 4,
				"bones should be the stage that lasts, or there is no landmark");
	}
}
