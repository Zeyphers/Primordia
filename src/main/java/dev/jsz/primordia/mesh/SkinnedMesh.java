package dev.jsz.primordia.mesh;

import dev.jsz.primordia.skeleton.Skeleton;
import org.joml.Matrix4f;

/**
 * Scratch buffer holding one frame's skinned vertex positions and normals.
 * <p>
 * Skinning is done <b>once per unique vertex</b> and the quads then index into the result. Since
 * a Surface Nets vertex is shared by roughly four quads, doing the transform inline while
 * emitting quads would repeat every matrix multiply about four times over. For a creature with a
 * few thousand vertices that difference is worth having.
 * <p>
 * A single instance is shared by the renderer across all creatures — rendering is single
 * threaded, and each creature is fully emitted before the next begins.
 */
public final class SkinnedMesh {
	private float[] positions = new float[0];
	private float[] normals = new float[0];
	private int vertexCount;

	/** Transforms the bind-pose mesh by the skeleton's current skinning palette. */
	public void skin(MeshData mesh, Skeleton skeleton) {
		int count = mesh.vertexCount;
		ensureCapacity(count);
		vertexCount = count;

		int bones = skeleton.boneCount();
		int[] indices = mesh.boneIndices;
		float[] weights = mesh.boneWeights;
		float[] src = mesh.positions;
		float[] srcN = mesh.normals;

		for (int v = 0; v < count; v++) {
			int p = v * 3;
			float x = src[p], y = src[p + 1], z = src[p + 2];
			float nx = srcN[p], ny = srcN[p + 1], nz = srcN[p + 2];

			float ox = 0f, oy = 0f, oz = 0f;
			float onx = 0f, ony = 0f, onz = 0f;

			int wBase = v * SkinBinder.MAX_INFLUENCES;
			for (int i = 0; i < SkinBinder.MAX_INFLUENCES; i++) {
				float w = weights[wBase + i];
				if (w <= 0f) continue;
				int bone = indices[wBase + i];
				if (bone < 0 || bone >= bones) continue;
				Matrix4f m = skeleton.skinMatrix(bone);

				// JOML is column-major: mCR is column C, row R, so a row of the transform reads
				// m0R, m1R, m2R, m3R. Inlined rather than using transformPosition to avoid
				// allocating or clobbering a shared scratch vector in the inner loop.
				ox += w * (m.m00() * x + m.m10() * y + m.m20() * z + m.m30());
				oy += w * (m.m01() * x + m.m11() * y + m.m21() * z + m.m31());
				oz += w * (m.m02() * x + m.m12() * y + m.m22() * z + m.m32());

				// Normals ignore translation. Skipping the inverse-transpose is safe here because
				// skinning matrices are rigid (rotation plus translation, never scale).
				onx += w * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
				ony += w * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
				onz += w * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
			}

			positions[p] = ox;
			positions[p + 1] = oy;
			positions[p + 2] = oz;

			float len = (float) Math.sqrt(onx * onx + ony * ony + onz * onz);
			if (len < 1e-6f) {
				normals[p] = 0f;
				normals[p + 1] = 1f;
				normals[p + 2] = 0f;
			} else {
				normals[p] = onx / len;
				normals[p + 1] = ony / len;
				normals[p + 2] = onz / len;
			}
		}
	}

	private void ensureCapacity(int count) {
		if (positions.length < count * 3) {
			positions = new float[count * 3];
			normals = new float[count * 3];
		}
	}

	public float[] positions() {
		return positions;
	}

	public float[] normals() {
		return normals;
	}

	public int vertexCount() {
		return vertexCount;
	}
}
