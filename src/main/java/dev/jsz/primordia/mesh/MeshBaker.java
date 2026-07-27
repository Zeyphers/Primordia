package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.util.Noise;
import org.joml.Vector3f;

/**
 * Turns a {@link BodyPlan} into a renderable {@link MeshData}: polygonise the SDF, colour the
 * vertices, bind them to bones. Pure and thread-safe — no Minecraft state is touched — which is
 * what lets {@link GenomeMeshCache} run it on a worker thread.
 */
public final class MeshBaker {
	/**
	 * How much of each vertex normal comes from the analytic SDF gradient rather than from
	 * averaged face normals. Exposed as a setting because it is the one knob that visibly trades
	 * "smooth but softer" against "crisper but faceted", and which of those looks right depends
	 * on the shader pack doing the lighting.
	 */
	private static volatile float gradientWeight = 0.75f;

	private MeshBaker() {
	}

	/** Set from the client's quality settings; callers must flush the mesh cache afterwards. */
	public static void setGradientWeight(float weight) {
		gradientWeight = Math.max(0f, Math.min(1f, weight));
	}

	public static float gradientWeight() {
		return gradientWeight;
	}

	public static MeshData bake(BodyPlan plan, int resolution) {
		BodySdf sdf = new BodySdf(plan);
		SurfaceNets.Result net = SurfaceNets.extract(sdf, plan.boundsMin, plan.boundsMax,
				resolutionFor(plan, resolution));

		int vertexCount = net.vertexCount();
		if (vertexCount == 0) {
			return new MeshData(new float[0], new float[0], new float[0], new float[0],
					new int[0], new float[0], new int[0], 0f, 0f, 0f, 0f, 0f, 0f);
		}

		float[] positions = net.positions();
		float[] normals = net.normals();
		float[] colors = new float[vertexCount * 3];
		float[] emissive = new float[vertexCount];

		Noise noise = new Noise(plan.genome.seed());
		Vector3f rgb = new Vector3f();
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

		for (int v = 0; v < vertexCount; v++) {
			int p = v * 3;
			float x = positions[p], y = positions[p + 1], z = positions[p + 2];
			Feature feature = sdf.featureAt(x, y, z);
			emissive[v] = Pattern.colorAt(x, y, z, normals[p], normals[p + 1], normals[p + 2],
					feature, plan.palette, noise, rgb);
			colors[p] = rgb.x;
			colors[p + 1] = rgb.y;
			colors[p + 2] = rgb.z;

			if (x < minX) minX = x;
			if (y < minY) minY = y;
			if (z < minZ) minZ = z;
			if (x > maxX) maxX = x;
			if (y > maxY) maxY = y;
			if (z > maxZ) maxZ = z;
		}

		int[] boneIndices = new int[vertexCount * SkinBinder.MAX_INFLUENCES];
		float[] boneWeights = new float[vertexCount * SkinBinder.MAX_INFLUENCES];
		SkinBinder.bind(plan, positions, vertexCount, boneIndices, boneWeights);

		// Accumulate area-weighted face normals across the quads sharing each vertex, then blend
		// them with the SDF gradient.
		//
		// The blend is weighted toward the gradient, not away from it. The gradient is the true
		// normal of the field this mesh approximates, whereas averaged face normals are derived
		// from the faceted approximation itself and so carry the facets they are meant to hide.
		// The face term is kept only as a stabiliser: the field has genuine creases where blend
		// groups meet under a hard minimum, and central differences are noisy right on them.
		float[] smoothNormals = new float[vertexCount * 3];
		int[] quads = net.quads();
		for (int i = 0; i < quads.length; i += 4) {
			int a = quads[i], b = quads[i + 1], c = quads[i + 2], d = quads[i + 3];
			float ax = positions[a * 3], ay = positions[a * 3 + 1], az = positions[a * 3 + 2];
			float bx = positions[b * 3], by = positions[b * 3 + 1], bz = positions[b * 3 + 2];
			float cx = positions[c * 3], cy = positions[c * 3 + 1], cz = positions[c * 3 + 2];
			float e1x = bx - ax, e1y = by - ay, e1z = bz - az;
			float e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
			float fnx = e1y * e2z - e1z * e2y;
			float fny = e1z * e2x - e1x * e2z;
			float fnz = e1x * e2y - e1y * e2x;
			int[] vIndices = { a, b, c, d };
			for (int vi : vIndices) {
				smoothNormals[vi * 3] += fnx;
				smoothNormals[vi * 3 + 1] += fny;
				smoothNormals[vi * 3 + 2] += fnz;
			}
		}

		for (int v = 0; v < vertexCount; v++) {
			int p = v * 3;
			float fnx = smoothNormals[p], fny = smoothNormals[p + 1], fnz = smoothNormals[p + 2];
			float fnLen = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
			if (fnLen > 1e-6f) {
				fnx /= fnLen; fny /= fnLen; fnz /= fnLen;
				float g = gradientWeight;
				float nx = normals[p] * g + fnx * (1f - g);
				float ny = normals[p + 1] * g + fny * (1f - g);
				float nz = normals[p + 2] * g + fnz * (1f - g);
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len > 1e-6f) {
					normals[p] = nx / len;
					normals[p + 1] = ny / len;
					normals[p + 2] = nz / len;
				}
			}
		}

		alignWindingToNormals(positions, normals, quads);

		// Teeth are appended after everything above, not folded into it. They never went through
		// the field, so they need none of the smoothing, skin binding or winding correction that
		// the body's surface does — they are already exactly the shape and orientation they should
		// be, and running them through any of it would only round them off again.
		ToothMesher.Result teeth = ToothMesher.build(plan, sdf);
		if (teeth.vertexCount() > 0) {
			int base = vertexCount;
			positions = concat(positions, teeth.positions());
			normals = concat(normals, teeth.normals());
			colors = concat(colors, teeth.colors());
			emissive = concat(emissive, teeth.emissive());
			boneWeights = concat(boneWeights, teeth.boneWeights());
			boneIndices = concat(boneIndices, teeth.boneIndices());

			int[] toothQuads = teeth.quads();
			int[] merged = java.util.Arrays.copyOf(quads, quads.length + toothQuads.length);
			for (int i = 0; i < toothQuads.length; i++) {
				merged[quads.length + i] = toothQuads[i] + base;
			}
			quads = merged;

			for (int i = 0; i < teeth.positions().length; i += 3) {
				minX = Math.min(minX, teeth.positions()[i]);
				maxX = Math.max(maxX, teeth.positions()[i]);
				minY = Math.min(minY, teeth.positions()[i + 1]);
				maxY = Math.max(maxY, teeth.positions()[i + 1]);
				minZ = Math.min(minZ, teeth.positions()[i + 2]);
				maxZ = Math.max(maxZ, teeth.positions()[i + 2]);
			}
		}

		return new MeshData(positions, normals, colors, emissive, boneIndices, boneWeights, quads,
				minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static float[] concat(float[] a, float[] b) {
		float[] out = java.util.Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	private static int[] concat(int[] a, int[] b) {
		int[] out = java.util.Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/**
	 * Reverses any quad whose rasterised facing disagrees with the normals it carries.
	 * <p>
	 * Two different normals decide how a quad looks. The GPU derives {@code gl_FrontFacing} from
	 * the screen-space winding of the triangle — its geometry. The shader lights it from the
	 * interpolated vertex normal. Where those disagree, a shader pack that negates the normal on
	 * back faces lights the quad inside-out, and because creatures draw on a no-cull layer it is
	 * not culled away but drawn wrong: a hard facet in a smooth surface. Vanilla never notices,
	 * because it shades entities straight from the vertex normal without consulting facing — which
	 * is exactly why this only ever appeared with shaders on.
	 * <p>
	 * {@link SurfaceNets} already aligns winding against the raw field gradient, but the normal
	 * smoothing above runs afterwards and can rotate a vertex normal past the point where that
	 * decision still holds. This is the same test applied to the normals actually shipped, so the
	 * two are consistent by construction rather than by argument.
	 */
	private static void alignWindingToNormals(float[] positions, float[] normals, int[] quads) {
		for (int i = 0; i < quads.length; i += 4) {
			int a = quads[i], b = quads[i + 1], c = quads[i + 2], d = quads[i + 3];

			// Newell's method rather than one corner's cross product. These quads are not planar
			// — the four dual vertices sit independently — and a normal taken from three of the
			// four corners does not simply negate when the order is reversed, so a flip decided
			// that way can fail to take. Newell sums every edge, so reversing the order negates
			// it exactly and one pass is enough.
			float gx = 0f, gy = 0f, gz = 0f;
			int[] loop = {a, b, c, d};
			for (int k = 0; k < 4; k++) {
				int p = loop[k];
				int q = loop[(k + 1) & 3];
				float px = positions[p * 3], py = positions[p * 3 + 1], pz = positions[p * 3 + 2];
				float qx = positions[q * 3], qy = positions[q * 3 + 1], qz = positions[q * 3 + 2];
				gx += (py - qy) * (pz + qz);
				gy += (pz - qz) * (px + qx);
				gz += (px - qx) * (py + qy);
			}

			float nx = normals[a * 3] + normals[b * 3] + normals[c * 3] + normals[d * 3];
			float ny = normals[a * 3 + 1] + normals[b * 3 + 1] + normals[c * 3 + 1] + normals[d * 3 + 1];
			float nz = normals[a * 3 + 2] + normals[b * 3 + 2] + normals[c * 3 + 2] + normals[d * 3 + 2];

			// A zero-area quad has no facing to get wrong and covers no pixels either way.
			if (gx * nx + gy * ny + gz * nz < 0f) {
				quads[i] = d;
				quads[i + 1] = c;
				quads[i + 2] = b;
				quads[i + 3] = a;
			}

			// Winding fixes the quad as a whole; the GPU still rasterises it as two triangles,
			// split (a,b,c) and (a,c,d). A quad bent sharply enough — over a knuckle, along a
			// limb crease — can have one half facing the wrong way whichever way the loop is
			// wound, and no flip can help because the two halves disagree with each other.
			//
			// Rotating the vertex list by one moves the split onto the other diagonal. It is a
			// cyclic permutation, so the winding it just settled is preserved, and it costs
			// nothing: choose whichever diagonal leaves both halves agreeing with the shading.
			if (!bothHalvesAgree(positions, quads, i, nx, ny, nz)) {
				int a0 = quads[i];
				quads[i] = quads[i + 1];
				quads[i + 1] = quads[i + 2];
				quads[i + 2] = quads[i + 3];
				quads[i + 3] = a0;
				// If the other diagonal is no better, the first one was no worse — put it back so
				// the choice stays deterministic rather than depending on which ran last.
				if (!bothHalvesAgree(positions, quads, i, nx, ny, nz)) {
					int a1 = quads[i + 3];
					quads[i + 3] = quads[i + 2];
					quads[i + 2] = quads[i + 1];
					quads[i + 1] = quads[i];
					quads[i] = a1;
				}
			}
		}
	}

	/** True when both triangles the GPU splits this quad into face the way its normals point. */
	private static boolean bothHalvesAgree(float[] positions, int[] quads, int base,
	                                       float nx, float ny, float nz) {
		int a = quads[base], b = quads[base + 1], c = quads[base + 2], d = quads[base + 3];
		return triangleAgrees(positions, a, b, c, nx, ny, nz)
				&& triangleAgrees(positions, a, c, d, nx, ny, nz);
	}

	private static boolean triangleAgrees(float[] positions, int a, int b, int c,
	                                      float nx, float ny, float nz) {
		float e1x = positions[b * 3] - positions[a * 3];
		float e1y = positions[b * 3 + 1] - positions[a * 3 + 1];
		float e1z = positions[b * 3 + 2] - positions[a * 3 + 2];
		float e2x = positions[c * 3] - positions[a * 3];
		float e2y = positions[c * 3 + 1] - positions[a * 3 + 1];
		float e2z = positions[c * 3 + 2] - positions[a * 3 + 2];

		float gx = e1y * e2z - e1z * e2y;
		float gy = e1z * e2x - e1x * e2z;
		float gz = e1x * e2y - e1y * e2x;
		// Slivers cover no pixels, so their facing is not observable.
		if (gx * gx + gy * gy + gz * gz < 1e-18f) return true;
		return gx * nx + gy * ny + gz * nz >= 0f;
	}

	/**
	 * Raises the requested LOD resolution until sampling cells are smaller than the creature's
	 * thinnest limb.
	 * <p>
	 * Surface Nets only produces geometry in cells where the field changes sign. A limb narrower
	 * than one cell can slip entirely between sample points, so the leg is not merely coarse — it
	 * is absent. Long-legged, slender genomes hit this constantly at a fixed resolution.
	 * <p>
	 * Cells are sized to roughly half the limb radius, which guarantees samples land inside it.
	 * Bounded above so that one hair-thin limb cannot demand a grid costing seconds to bake; past
	 * that ceiling the limb is simply too thin to render and the body plan's thickness floor is the
	 * thing keeping it visible.
	 */
	private static int resolutionFor(BodyPlan plan, int requested) {
		float span = Math.max(plan.boundsMax.x - plan.boundsMin.x,
				Math.max(plan.boundsMax.y - plan.boundsMin.y, plan.boundsMax.z - plan.boundsMin.z));
		if (plan.minLimbRadius <= 1e-5f || span <= 1e-5f) return requested;

		int needed = (int) Math.ceil(span / (plan.minLimbRadius * 0.9f));
		// Never below the tier's own resolution, never far above it, never past the ceiling.
		int ceiling = Math.min(Math.round(requested * 1.8f), LodTier.maxResolution());
		return Math.max(requested, Math.min(needed, ceiling));
	}
}
