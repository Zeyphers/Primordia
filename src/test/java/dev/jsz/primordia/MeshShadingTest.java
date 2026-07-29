package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.FaceNormal;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins flat shading as a property of drawing rather than of the mesh.
 * <p>
 * There was, for a long time, a "smooth normals" slider that could not produce a sharp surface at
 * any setting. Surface Nets shares each vertex between up to four quads and a vertex carries one
 * normal, so both ends of that slider were choosing between two ways of computing the same
 * <i>shared</i> vector — the analytic gradient at one end, the average of the surrounding faces at
 * the other. An average of four face normals is a smooth normal. There was no sharp end to reach.
 * <p>
 * The fix is not to unshare the vertices in the mesh. Both renderers already walk the quad index
 * array and emit one vertex per index, so what reaches the GPU is four unshared vertices per quad
 * whatever the mesh holds — flat shading is just a matter of handing those four the same normal.
 * {@link FaceNormal} computes it, from the posed positions, at draw time.
 */
class MeshShadingTest {
	private static final int TRIALS = 12;

	private static MeshData bake(Genome genome) {
		BodyPlan plan = BodyPlanBuilder.build(genome);
		return MeshBaker.bake(plan, 24);
	}

	/**
	 * The mesh must not know about the shading mode at all.
	 * <p>
	 * This is the property that keeps the setting free: it costs no memory, needs no rebake, and
	 * cannot invalidate the cache. Baking a second variant would have quadrupled the vertex count
	 * for a decision the renderer can make per frame.
	 */
	@Test
	void shadingIsNotBakedIntoTheMesh() {
		Random random = new Random(4040);
		for (int trial = 0; trial < TRIALS; trial++) {
			Genome genome = Genome.random(random);
			MeshData mesh = bake(genome);
			if (mesh.quadCount == 0) continue;

			// Vertices stay shared — that sharing is what makes the stored mesh a quarter the size
			// of what is drawn from it.
			assertTrue(mesh.vertexCount < mesh.quads.length,
					"the mesh is no longer sharing vertices between faces");

			// And baking the same genome twice gives the same thing, since nothing about shading
			// reaches the baker.
			MeshData again = bake(genome);
			assertEquals(mesh.vertexCount, again.vertexCount, "bake is not deterministic");
			assertArrayEquals(mesh.normals, again.normals, 0f, "baked normals differ between bakes");
		}
	}

	/**
	 * Every face's normal must be a unit vector, whatever the quad's shape.
	 * <p>
	 * A Surface Nets quad is routinely non-planar — four independently placed cell vertices — which
	 * is why this uses Newell's method rather than one cross product. A single cross product
	 * describes only the triangle it was taken from, and on a quad bent over a knuckle the two
	 * halves disagree.
	 */
	@Test
	void everyFaceNormalIsUnitLength() {
		Random random = new Random(5150);
		Vector3f normal = new Vector3f();
		int faces = 0;

		for (int trial = 0; trial < TRIALS; trial++) {
			MeshData mesh = bake(Genome.random(random));
			for (int i = 0; i < mesh.quads.length; i += 4) {
				FaceNormal.compute(mesh.positions, mesh.quads, i, normal);
				assertEquals(1f, normal.length(), 1e-3f, "a face normal is not unit length");
				faces++;
			}
		}
		assertTrue(faces > 1000, "too few faces to be meaningful: " + faces);
	}

	/**
	 * A face normal must point the same way as the smooth normals of its own corners.
	 * <p>
	 * They will not be identical — that difference is the whole point of the setting — but they must
	 * not be opposed. Newell's sign follows the winding, and {@code MeshBaker.alignWindingToNormals}
	 * has already reconciled the winding with the outward direction, so a face pointing inward would
	 * mean that reconciliation had come undone and the creature would be lit inside out.
	 */
	@Test
	void faceNormalsAgreeWithTheSurfaceTheyBelongTo() {
		Random random = new Random(6060);
		Vector3f normal = new Vector3f();
		int faces = 0;
		int opposed = 0;

		for (int trial = 0; trial < TRIALS; trial++) {
			MeshData mesh = bake(Genome.random(random));
			for (int i = 0; i < mesh.quads.length; i += 4) {
				FaceNormal.compute(mesh.positions, mesh.quads, i, normal);

				float dot = 0f;
				for (int k = 0; k < 4; k++) {
					int p = mesh.quads[i + k] * 3;
					dot += normal.x * mesh.normals[p]
							+ normal.y * mesh.normals[p + 1]
							+ normal.z * mesh.normals[p + 2];
				}
				faces++;
				if (dot < 0f) opposed++;
			}
		}
		// Stated as a share, and here is why (PITFALLS §12): a quad bent over a knuckle has two
		// triangle halves that genuinely disagree, and no winding choice reconciles them — the same
		// residual QuadWindingTest documents. A handful is inherent; a wholesale flip is a bug.
		assertTrue(opposed < faces / 50, String.format(
				"%d of %d faces point against their own surface", opposed, faces));
	}

	@Test
	void degenerateQuadsStillProduceAUsableNormal() {
		// Four coincident corners have no direction to face. The normal still has to be finite and
		// unit length or it poisons the lighting of everything drawn in the same batch.
		float[] positions = {1f, 2f, 3f};
		int[] quads = {0, 0, 0, 0};
		Vector3f normal = new Vector3f();

		FaceNormal.compute(positions, quads, 0, normal);

		assertTrue(Float.isFinite(normal.x) && Float.isFinite(normal.y) && Float.isFinite(normal.z),
				"a degenerate face produced a non-finite normal");
		assertEquals(1f, normal.length(), 1e-5f, "a degenerate face produced a non-unit normal");
	}
}
