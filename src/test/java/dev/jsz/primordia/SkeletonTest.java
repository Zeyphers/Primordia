package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.skeleton.Skeleton;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bind-pose contract. If a zeroed pose is not exactly the identity, every mesh in the game
 * renders subtly deformed the instant it spawns — and that is very hard to spot by eye, because
 * it looks like the generator simply made an odd-shaped animal.
 */
class SkeletonTest {

	@Test
	void zeroPoseProducesIdentitySkinMatrices() {
		Random random = new Random(101);
		Matrix4f identity = new Matrix4f();

		for (int trial = 0; trial < 100; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			Skeleton skeleton = new Skeleton(plan);
			skeleton.resetPose();
			skeleton.updateWorld();
			skeleton.updateSkinMatrices();

			for (int i = 0; i < skeleton.boneCount(); i++) {
				Matrix4f skin = skeleton.skinMatrix(i);
				for (int c = 0; c < 4; c++) {
					for (int r = 0; r < 4; r++) {
						assertEquals(identity.get(c, r), skin.get(c, r), 1e-3f,
								"bone " + plan.bones[i].name + " skin matrix is not identity at bind pose");
					}
				}
			}
		}
	}

	@Test
	void boneHeadAndTailMatchTheBindPoseDefinition() {
		Random random = new Random(202);
		Vector3f scratch = new Vector3f();

		for (int trial = 0; trial < 100; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			Skeleton skeleton = new Skeleton(plan);

			for (int i = 0; i < skeleton.boneCount(); i++) {
				skeleton.boneHead(i, scratch);
				assertEquals(plan.bones[i].head.x, scratch.x, 1e-3f);
				assertEquals(plan.bones[i].head.y, scratch.y, 1e-3f);
				assertEquals(plan.bones[i].head.z, scratch.z, 1e-3f);

				skeleton.boneTail(i, scratch);
				assertEquals(plan.bones[i].tail.x, scratch.x, 1e-3f,
						"bone " + plan.bones[i].name + " tail X drifted");
				assertEquals(plan.bones[i].tail.y, scratch.y, 1e-3f,
						"bone " + plan.bones[i].name + " tail Y drifted");
				assertEquals(plan.bones[i].tail.z, scratch.z, 1e-3f,
						"bone " + plan.bones[i].name + " tail Z drifted");
			}
		}
	}

	@Test
	void aimBonePointsTheBoneWhereItIsAsked() {
		Random random = new Random(303);
		Quaternionf q = new Quaternionf();
		Vector3f v = new Vector3f();
		Vector3f head = new Vector3f();
		Vector3f tail = new Vector3f();

		for (int trial = 0; trial < 50; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			Skeleton skeleton = new Skeleton(plan);

			for (int i = 0; i < skeleton.boneCount(); i++) {
				if (skeleton.boneLength(i) < 1e-3f) continue;

				Vector3f desired = new Vector3f(
						random.nextFloat() * 2f - 1f,
						random.nextFloat() * 2f - 1f,
						random.nextFloat() * 2f - 1f);
				if (desired.lengthSquared() < 1e-4f) continue;
				desired.normalize();

				skeleton.aimBone(i, desired, q, v);
				skeleton.updateBoneWorld(i);

				skeleton.boneHead(i, head);
				skeleton.boneTail(i, tail);
				Vector3f actual = new Vector3f(tail).sub(head).normalize();

				assertEquals(1f, actual.dot(desired), 1e-3f,
						"bone " + plan.bones[i].name + " did not aim where it was told");
			}
		}
	}

	@Test
	void rootTransformPropagatesToEveryBone() {
		BodyPlan plan = BodyPlanBuilder.build(Genome.random(new Random(404)));
		Skeleton skeleton = new Skeleton(plan);
		Vector3f before = new Vector3f();
		Vector3f after = new Vector3f();

		skeleton.boneHead(plan.headBone, before);
		skeleton.rootTransform.identity().translate(0f, 1.5f, 0f);
		skeleton.updateWorld();
		skeleton.boneHead(plan.headBone, after);

		assertEquals(before.y + 1.5f, after.y, 1e-3f, "root translation did not reach the head");
		assertEquals(before.x, after.x, 1e-3f);
		assertEquals(before.z, after.z, 1e-3f);
	}
}
