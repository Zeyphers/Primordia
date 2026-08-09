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
 * product describes only the triangle it was taken from.
 * <p>
 * <b>The sign is settled against the posed vertex normals, every frame.</b> Winding alone is not
 * enough once the mesh is deforming. {@code MeshBaker.alignWindingToNormals} reconciles winding
 * with the outward direction at bake time, but it can only do so <i>for the bind pose</i>, and
 * skinning is not a rigid transform: a vertex blended between two bones moves along a chord rather
 * than an arc, so a joint bent far enough collapses the quads across it and can turn them inside
 * out. A crouch on a large creature bends the hips and knees exactly that far, and the faces there
 * came out lit from the wrong side — or, where a quad collapsed outright, handed the arbitrary
 * fallback direction and lit as though facing straight up.
 * <p>
 * The skinned vertex normals do not suffer from this: they are blended rotations, renormalised, and
 * they cannot invert. Taking the sign from them costs one dot product per face and makes the flat
 * normal agree with the smooth one in every pose rather than only in the one the mesh was baked in.
 */
public final class FaceNormal {
	private FaceNormal() {
	}

	/**
	 * Writes the unit normal of the quad beginning at {@code quadStart} into {@code dest}.
	 *
	 * @param positions vertex positions, three floats each — posed, not bind pose
	 * @param normals   posed vertex normals, three floats each, matching {@code positions}
	 * @param quads     quad index array, four indices per face
	 * @param quadStart index into {@code quads} of this face's first corner
	 */
	public static void compute(float[] positions, float[] normals, int[] quads, int quadStart,
	                           Vector3f dest) {
		float nx = 0f, ny = 0f, nz = 0f;
		float sx = 0f, sy = 0f, sz = 0f;
		for (int k = 0; k < 4; k++) {
			int cur = quads[quadStart + k] * 3;
			int next = quads[quadStart + (k + 1) % 4] * 3;
			nx += (positions[cur + 1] - positions[next + 1]) * (positions[cur + 2] + positions[next + 2]);
			ny += (positions[cur + 2] - positions[next + 2]) * (positions[cur] + positions[next]);
			nz += (positions[cur] - positions[next]) * (positions[cur + 1] + positions[next + 1]);
			sx += normals[cur];
			sy += normals[cur + 1];
			sz += normals[cur + 2];
		}
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 1e-9f) {
			// Flip to agree with the corners' own normals. The geometry says which plane the face
			// lies in; only the vertex normals still know which side of it is outside.
			float sign = (nx * sx + ny * sy + nz * sz) < 0f ? -1f : 1f;
			dest.set(sign * nx / len, sign * ny / len, sign * nz / len);
			return;
		}

		// The quad has collapsed — which is exactly what happens across a hard-bent joint, so this
		// is a real case rather than a theoretical one. Its corners still know where outside is.
		float slen = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (slen > 1e-9f) {
			dest.set(sx / slen, sy / slen, sz / slen);
		} else {
			dest.set(0f, 1f, 0f);
		}
	}
}
