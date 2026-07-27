package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.LimbChain;
import dev.jsz.primordia.genome.Genome;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzzes the developmental step. Any genome at all must produce a structurally valid animal —
 * there is no authoring pass to catch a bad one, so the invariants have to hold for every point
 * in the gene space.
 */
class BodyPlanTest {
	private static final int TRIALS = 500;

	@Test
	void everyGenomeProducesAStructurallyValidPlan() {
		Random random = new Random(2026);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);

			assertTrue(plan.bones.length > 0, "plan has no bones");

			for (int i = 0; i < plan.bones.length; i++) {
				BoneDef bone = plan.bones[i];
				// The whole skeleton solve is a single forward pass, which is only valid if
				// every parent is resolved before its child.
				assertTrue(bone.parent < i, "bone " + bone.name + " parents forward to " + bone.parent);
				assertTrue(bone.parent >= -1, "bone " + bone.name + " has an invalid parent index");
				assertTrue(bone.radiusHead > 0f && bone.radiusTail > 0f,
						"bone " + bone.name + " has a non-positive radius");
				assertAllFinite(bone.head.x, bone.head.y, bone.head.z);
				assertAllFinite(bone.tail.x, bone.tail.y, bone.tail.z);
			}

			assertEquals(-1, plan.bones[plan.rootBone].parent, "root bone is not actually a root");
			assertTrue(plan.headBone >= 0 && plan.headBone < plan.bones.length);
			assertTrue(plan.hipHeight > 0f);
			assertTrue(plan.mass > 0f);
			assertTrue(plan.blendRadius > 0f);
			assertAllFinite(plan.boundsMin.x, plan.boundsMin.y, plan.boundsMin.z);
			assertAllFinite(plan.boundsMax.x, plan.boundsMax.y, plan.boundsMax.z);
			assertTrue(plan.boundsMax.x > plan.boundsMin.x);
			assertTrue(plan.boundsMax.y > plan.boundsMin.y);
			assertTrue(plan.boundsMax.z > plan.boundsMin.z);
		}
	}

	@Test
	void everyCreatureCanStandOnItsOwnLegs() {
		Random random = new Random(77);
		for (int trial = 0; trial < TRIALS; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			assertTrue(plan.legs.length >= 2, "creature has fewer than one pair of legs");

			for (LimbChain leg : plan.legs) {
				// Feet are pinned to the ground plane by construction.
				assertEquals(0f, leg.restEffector.y, 1e-4f, "rest foot is not on the ground");

				float reach = leg.origin.distance(leg.restEffector);
				assertTrue(leg.totalLength >= reach - 1e-3f,
						"leg is too short to reach its own rest position: bones "
								+ leg.totalLength + " vs reach " + reach);
				// Some slack is required or IK has no bend room and the leg locks straight.
				assertTrue(leg.totalLength > reach * 1.001f, "leg has no slack for IK to bend into");
				assertTrue(leg.bones.length >= 2);
				assertEquals(1f, leg.poleDirection.length(), 1e-4f, "pole direction is not normalised");
			}
		}
	}

	@Test
	void legPairsAreLeftRightSymmetric() {
		Random random = new Random(555);
		for (int trial = 0; trial < 100; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			assertEquals(0, plan.legs.length % 2, "legs did not come in pairs");

			for (int i = 0; i < plan.legs.length; i += 2) {
				LimbChain left = plan.legs[i];
				LimbChain right = plan.legs[i + 1];
				assertEquals(-1, left.side);
				assertEquals(1, right.side);
				assertEquals(left.restEffector.x, -right.restEffector.x, 1e-4f, "legs are not mirrored on X");
				assertEquals(left.restEffector.z, right.restEffector.z, 1e-4f, "paired legs are at different depths");
				assertEquals(left.totalLength, right.totalLength, 1e-3f, "paired legs differ in length");
			}
		}
	}

	@Test
	void gaitPhasesAreDistributedNotIdentical() {
		Random random = new Random(31337);
		int identicalPhaseCreatures = 0;
		for (int trial = 0; trial < 100; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			boolean allSame = true;
			for (LimbChain leg : plan.legs) {
				assertTrue(leg.gaitPhase >= 0f && leg.gaitPhase < 1f, "gait phase out of range");
				if (Math.abs(leg.gaitPhase - plan.legs[0].gaitPhase) > 1e-4f) allSame = false;
			}
			if (allSame) identicalPhaseCreatures++;
		}
		// Left and right always alternate, so no creature should have every foot in lockstep.
		assertEquals(0, identicalPhaseCreatures, "some creatures move every leg in perfect unison");
	}

	@Test
	void developmentIsDeterministic() {
		// The mesh cache assumes this: same genome must always mean same geometry.
		Random random = new Random(8);
		for (int trial = 0; trial < 50; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan a = BodyPlanBuilder.build(genome);
			BodyPlan b = BodyPlanBuilder.build(genome);

			assertEquals(a.bones.length, b.bones.length);
			assertEquals(a.legs.length, b.legs.length);
			assertEquals(a.mass, b.mass, 0f);
			for (int i = 0; i < a.bones.length; i++) {
				assertEquals(a.bones[i].head, b.bones[i].head);
				assertEquals(a.bones[i].tail, b.bones[i].tail);
			}
		}
	}

	private static void assertAllFinite(float... values) {
		for (float v : values) {
			assertTrue(Float.isFinite(v), "value was not finite: " + v);
		}
	}
}
