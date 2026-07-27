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

		return new MeshData(positions, normals, colors, emissive, boneIndices, boneWeights, quads,
				minX, minY, minZ, maxX, maxY, maxZ);
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
