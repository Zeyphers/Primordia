package dev.jsz.primordia.mesh;

import org.joml.Vector3f;

/**
 * The geometric normal of one quad, for flat shading at draw time.
 * <p>
 * <b>Why this is a rendering concern and not a mesh one.</b> A vertex carries one normal, and
 * Surface Nets shares each vertex between up to four quads, so nothing done to the stored normals
 * can make a surface read as faceted — an average of four face normals is a smooth normal however
 * it is weighted. That is why the old smoothing slider appeared to do nothing: both ends of it were
 * choosing between two ways of computing the same shared, smooth vector.
 * <p>
 * The sharing only exists in {@link MeshData}. Both renderers walk the quad index array and emit a
 * vertex per index, so the buffer that reaches the GPU already has four unshared vertices per quad
 * — flat shading costs nothing more than handing those four the same normal.
 * <p>
 * Computed from the <i>posed</i> positions rather than the bind pose, which is the part that would
 * be lost by baking it: a limb's faces turn as it swings, and a normal frozen at bake time would
 * light a bent leg as though it were straight.
 * <p>
 * Newell's method rather than one cross product, because a Surface Nets quad is routinely
 * non-planar — its four corners are four independently placed cell vertices — and a single cross
 * product describes only the triangle it was taken from. The sign follows the winding, which
 * {@code MeshBaker.alignWindingToNormals} has already reconciled with the outward direction.
 */
public final class FaceNormal {
	private FaceNormal() {
	}

	/**
	 * Writes the unit normal of the quad beginning at {@code quadStart} into {@code dest}.
	 *
	 * @param positions vertex positions, three floats each — posed, not bind pose
	 * @param quads     quad index array, four indices per face
	 * @param quadStart index into {@code quads} of this face's first corner
	 */
	public static void compute(float[] positions, int[] quads, int quadStart, Vector3f dest) {
		float nx = 0f, ny = 0f, nz = 0f;
		for (int k = 0; k < 4; k++) {
			int cur = quads[quadStart + k] * 3;
			int next = quads[quadStart + (k + 1) % 4] * 3;
			nx += (positions[cur + 1] - positions[next + 1]) * (positions[cur + 2] + positions[next + 2]);
			ny += (positions[cur + 2] - positions[next + 2]) * (positions[cur] + positions[next]);
			nz += (positions[cur] - positions[next]) * (positions[cur + 1] + positions[next + 1]);
		}
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 1e-9f) {
			dest.set(nx / len, ny / len, nz / len);
		} else {
			// A degenerate quad has no direction to face. Any unit vector keeps the lighting maths
			// well-defined; the face covers no pixels either way.
			dest.set(0f, 1f, 0f);
		}
	}
}
