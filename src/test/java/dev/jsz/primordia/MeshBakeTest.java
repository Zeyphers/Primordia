package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import dev.jsz.primordia.mesh.SkinBinder;
import dev.jsz.primordia.mesh.SkinnedMesh;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.skeleton.Skeleton;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MeshBakeTest {

	@Test
	void everyGenomeBakesANonEmptyMesh() {
		Random random = new Random(909);
		for (int trial = 0; trial < 40; trial++) {
			Genome genome = Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			MeshData mesh = MeshBaker.bake(plan, 16);

			assertTrue(mesh.vertexCount > 0, "creature baked to nothing at all");
			assertTrue(mesh.quadCount > 0, "creature baked with no faces");
		}
	}

	@Test
	void meshDataIsInternallyConsistent() {
		Random random = new Random(910);
		for (int trial = 0; trial < 20; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			MeshData mesh = MeshBaker.bake(plan, 14);

			assertEquals(mesh.vertexCount * 3, mesh.positions.length);
			assertEquals(mesh.vertexCount * 3, mesh.normals.length);
			assertEquals(mesh.vertexCount * 3, mesh.colors.length);
			assertEquals(mesh.vertexCount * SkinBinder.MAX_INFLUENCES, mesh.boneIndices.length);
			assertEquals(mesh.vertexCount * SkinBinder.MAX_INFLUENCES, mesh.boneWeights.length);
			assertEquals(0, mesh.quads.length % 4, "quad index buffer is not a multiple of four");

			for (int index : mesh.quads) {
				assertTrue(index >= 0 && index < mesh.vertexCount, "quad references a vertex out of range");
			}
			for (int b : mesh.boneIndices) {
				assertTrue(b >= 0 && b < plan.bones.length, "vertex bound to a non-existent bone");
			}
			for (float c : mesh.colors) {
				assertTrue(c >= 0f && c <= 1f, "vertex colour out of range: " + c);
			}
		}
	}

	@Test
	void skinWeightsAreNormalised() {
		Random random = new Random(911);
		for (int trial = 0; trial < 20; trial++) {
			BodyPlan plan = BodyPlanBuilder.build(Genome.random(random));
			MeshData mesh = MeshBaker.bake(plan, 14);

			for (int v = 0; v < mesh.vertexCount; v++) {
				float sum = 0f;
				for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
					float w = mesh.boneWeights[v * SkinBinder.MAX_INFLUENCES + i];
					assertTrue(w >= 0f, "negative skin weight");
					sum += w;
				}
				// Anything else scales the vertex toward or away from the origin when posed.
				assertEquals(1f, sum, 1e-3f, "skin weights for vertex " + v + " do not sum to 1");
			}
		}
	}

	@Test
	void normalsAreUnitLength() {
		BodyPlan plan = BodyPlanBuilder.build(Genome.random(new Random(912)));
		MeshData mesh = MeshBaker.bake(plan, 16);

		for (int v = 0; v < mesh.vertexCount; v++) {
			int p = v * 3;
			float len = (float) Math.sqrt(
					mesh.normals[p] * mesh.normals[p]
							+ mesh.normals[p + 1] * mesh.normals[p + 1]
							+ mesh.normals[p + 2] * mesh.normals[p + 2]);
			assertEquals(1f, len, 1e-3f, "normal at vertex " + v + " is not unit length");
		}
	}

	@Test
	void skinningAtBindPoseReproducesTheOriginalMesh() {
		// Ties the mesher and the skeleton together: an unposed creature must render exactly as baked.
		BodyPlan plan = BodyPlanBuilder.build(Genome.random(new Random(913)));
		MeshData mesh = MeshBaker.bake(plan, 14);
		Skeleton skeleton = new Skeleton(plan);
		skeleton.resetPose();
		skeleton.updateWorld();
		skeleton.updateSkinMatrices();

		SkinnedMesh skinned = new SkinnedMesh();
		skinned.skin(mesh, skeleton);

		for (int i = 0; i < mesh.vertexCount * 3; i++) {
			assertEquals(mesh.positions[i], skinned.positions()[i], 1e-3f,
					"bind-pose skinning moved a vertex");
		}
	}

	@Test
	void sdfIsNegativeInsideAndPositiveOutside() {
		BodyPlan plan = BodyPlanBuilder.build(Genome.random(new Random(914)));
		BodySdf sdf = new BodySdf(plan);

		// The middle of the torso must be solid.
		var mid = plan.bones[plan.rootBone];
		float cx = (mid.head.x + mid.tail.x) * 0.5f;
		float cy = (mid.head.y + mid.tail.y) * 0.5f;
		float cz = (mid.head.z + mid.tail.z) * 0.5f;
		assertTrue(sdf.eval(cx, cy, cz) < 0f, "the inside of the torso is not inside the body");

		// A point well outside the bounding box must be outside.
		assertTrue(sdf.eval(plan.boundsMax.x + 5f, plan.boundsMax.y + 5f, plan.boundsMax.z + 5f) > 0f,
				"a point far outside the creature reads as inside");
	}

	@Test
	void higherResolutionProducesMoreDetail() {
		BodyPlan plan = BodyPlanBuilder.build(Genome.random(new Random(915)));
		MeshData coarse = MeshBaker.bake(plan, 8);
		MeshData fine = MeshBaker.bake(plan, 20);
		assertTrue(fine.quadCount > coarse.quadCount,
				"the LOD resolution parameter had no effect on mesh density");
	}
}
