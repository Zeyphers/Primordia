package dev.jsz.primordia;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BodyPlanBuilder;
import dev.jsz.primordia.genome.Archetype;
import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.mesh.MeshBaker;
import dev.jsz.primordia.mesh.MeshData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that every quad is wound the same way round the surface.
 * <p>
 * This is invisible in vanilla and glaring under a shader pack, which is exactly why it is worth a
 * test. Vanilla's entity shading takes the interpolated vertex normal and stops there. Shader
 * packs do not: they branch on {@code gl_FrontFacing} and flip the normal for back faces. The
 * creatures render on a no-cull layer, so a quad wound the wrong way is not discarded — it is
 * drawn with its normal inverted, and one inverted quad in a smooth surface reads as a hard facet.
 * Enough of them and the mesh's own grid appears on the creature the moment shaders go on.
 */
class QuadWindingTest {

	/** Fraction of quads whose winding disagrees with the surface normal they carry. */
	private static double misWoundFraction(MeshData mesh) {
		int wrong = 0;
		int counted = 0;

		for (int i = 0; i < mesh.quads.length; i += 4) {
			int a = mesh.quads[i], b = mesh.quads[i + 1], c = mesh.quads[i + 2], d = mesh.quads[i + 3];

			// Average the shading normals the renderer will actually emit for this quad.
			float nx = 0f, ny = 0f, nz = 0f;
			for (int k = 0; k < 4; k++) {
				int v = mesh.quads[i + k];
				nx += mesh.normals[v * 3];
				ny += mesh.normals[v * 3 + 1];
				nz += mesh.normals[v * 3 + 2];
			}

			// The GPU does not rasterise quads — it splits each into (a,b,c) and (a,c,d), and
			// decides gl_FrontFacing per triangle. Checking the quad as a whole would miss a
			// quad bent far enough that its two halves face opposite ways, which is precisely
			// the case that shows up as a facet.
			counted += 2;
			if (!facesSameWay(mesh, a, b, c, nx, ny, nz)) wrong++;
			if (!facesSameWay(mesh, a, c, d, nx, ny, nz)) wrong++;
		}
		return counted == 0 ? 0 : wrong / (double) counted;
	}

	/** True when a triangle's rasterised facing agrees with the shading normal it carries. */
	private static boolean facesSameWay(MeshData mesh, int a, int b, int c,
	                                    float nx, float ny, float nz) {
		float e1x = mesh.positions[b * 3] - mesh.positions[a * 3];
		float e1y = mesh.positions[b * 3 + 1] - mesh.positions[a * 3 + 1];
		float e1z = mesh.positions[b * 3 + 2] - mesh.positions[a * 3 + 2];
		float e2x = mesh.positions[c * 3] - mesh.positions[a * 3];
		float e2y = mesh.positions[c * 3 + 1] - mesh.positions[a * 3 + 1];
		float e2z = mesh.positions[c * 3 + 2] - mesh.positions[a * 3 + 2];

		float gx = e1y * e2z - e1z * e2y;
		float gy = e1z * e2x - e1x * e2z;
		float gz = e1x * e2y - e1y * e2x;
		// A sliver triangle has no meaningful facing; it also covers no pixels.
		if (gx * gx + gy * gy + gz * gz < 1e-18f) return true;

		return gx * nx + gy * ny + gz * nz >= 0f;
	}

	@Test
	void quadsAreWoundConsistentlyWithTheirShadingNormals() {
		Random random = new Random(4242);
		double worst = 0;
		String worstCase = "";

		for (int trial = 0; trial < 30; trial++) {
			Genome genome = trial % 3 == 0
					? Archetype.ARACHNID.create(random)
					: Genome.random(random);
			BodyPlan plan = BodyPlanBuilder.build(genome);
			MeshData mesh = MeshBaker.bake(plan, 32);

			double fraction = misWoundFraction(mesh);
			if (fraction > worst) {
				worst = fraction;
				worstCase = "trial " + trial + " with " + mesh.quadCount + " quads";
			}
		}

		System.out.printf("[QuadWindingTest] worst mis-wound fraction: %.4f%% (%s)%n",
				worst * 100, worstCase);
		// Zero is not reachable while the mesh is made of quads. Minecraft's entity layers draw
		// DrawMode.QUADS, the dual vertices are not coplanar, and a quad bent hard enough over a
		// knuckle has its two triangles facing away from each other — no winding and neither
		// diagonal can reconcile that. What is fixed is the part that was avoidable: the baker
		// aligns winding to the shipped normals and picks the better diagonal, which took the
		// worst case from about 0.5% to under 0.3%. Getting the rest would mean emitting
		// triangles and a custom render layer.
		assertTrue(worst < 0.003,
				String.format("%.3f%% of quad halves face against their own shading normal (%s) — "
						+ "under a shader pack those flip via gl_FrontFacing and read as hard "
						+ "facets in a smooth surface", worst * 100, worstCase));
	}
}
