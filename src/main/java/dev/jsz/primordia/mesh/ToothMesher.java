package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.ToothDef;
import dev.jsz.primordia.sdf.BodySdf;
import org.joml.Vector3f;

/**
 * Builds tooth geometry directly, outside the signed distance field.
 * <p>
 * Everything else on a creature is polygonised from the field, which is what makes limbs grow out
 * of hips instead of intersecting them. Teeth want the opposite: a hard edge and a point. Run
 * through the smooth union they come out as rounded lumps welded to the lip, and being finer than
 * one sampling cell they mostly do not survive the mesher at all.
 * <p>
 * Emitted as tapered prisms and appended to the baked mesh afterwards. Each is pinned rigidly to a
 * single bone — no blending — so a lower tooth tracks the mandible exactly and an upper one the
 * skull, and the two rows part cleanly when the jaw drops.
 */
public final class ToothMesher {
	/** Sides per tooth. Five reads as organic without costing much; four looks machined. */
	private static final int SIDES = 5;
	/** Fraction of the base radius the tip keeps. Blunt teeth keep much more. */
	private static final float POINT_TAPER = 0.14f;
	private static final float BLUNT_TAPER = 0.62f;

	/** Enamel. Slightly warm rather than pure white, which reads as plastic. */
	private static final float ENAMEL_R = 0.94f, ENAMEL_G = 0.92f, ENAMEL_B = 0.84f;
	/** The root darkens toward the gum so a tooth does not look stuck on. */
	private static final float ROOT_SHADE = 0.55f;

	private ToothMesher() {
	}

	/** Flat arrays for the tooth geometry, ready to concatenate onto a baked mesh. */
	public record Result(float[] positions, float[] normals, float[] colors, float[] emissive,
	                     int[] boneIndices, float[] boneWeights, int[] quads, int vertexCount) {
	}

	/**
	 * @param sdf the finished body field, used to find where each tooth leaves the flesh. The
	 *            plan cannot know that: gum depth depends on the skull's taper, the mandible's
	 *            girth and the jaw width, and estimating it from the bone axis buried a whole row.
	 */
	public static Result build(BodyPlan plan, BodySdf sdf) {
		ToothDef[] teeth = plan.teeth;
		if (teeth.length == 0) {
			return new Result(new float[0], new float[0], new float[0], new float[0],
					new int[0], new float[0], new int[0], 0);
		}

		int perTooth = SIDES * 2;
		int vertexCount = teeth.length * perTooth;
		float[] positions = new float[vertexCount * 3];
		float[] normals = new float[vertexCount * 3];
		float[] colors = new float[vertexCount * 3];
		float[] emissive = new float[vertexCount];
		int[] boneIndices = new int[vertexCount * SkinBinder.MAX_INFLUENCES];
		float[] boneWeights = new float[vertexCount * SkinBinder.MAX_INFLUENCES];
		int[] quads = new int[teeth.length * SIDES * 4];

		Vector3f axis = new Vector3f();
		Vector3f u = new Vector3f();
		Vector3f w = new Vector3f();
		Vector3f radial = new Vector3f();

		Vector3f base = new Vector3f();
		Vector3f tip = new Vector3f();

		int v = 0;
		int q = 0;
		for (ToothDef tooth : teeth) {
			axis.set(tooth.direction());
			if (axis.lengthSquared() < 1e-8f) continue;
			axis.normalize();

			// The marched surface decides where the gum is; the plan's ceiling decides how far
			// past it the tooth may go. The march is the larger term of the two — a root sits on
			// the bone axis, so it has the whole thickness of the jaw to cross before it emerges
			// — which is why capping the protrusion alone changed nothing measurable.
			float emerges = surfaceDistance(sdf, tooth.root(), axis, tooth.radius());
			float extent = Math.min(emerges + tooth.protrusion(), tooth.maxExtent());
			// Never inverted, however tight the ceiling: a tooth still has to leave the gum.
			extent = Math.max(extent, emerges + tooth.radius() * 0.5f);
			base.set(tooth.root());
			tip.set(tooth.root()).fma(extent, axis);
			float length = base.distance(tip);
			if (length < 1e-6f) continue;

			// Any two vectors perpendicular to the axis will do; the tooth is radially symmetric,
			// so which one is arbitrary as long as they are consistent within a tooth.
			perpendicular(axis, u);
			axis.cross(u, w).normalize();

			float tipRadius = tooth.radius() * (tooth.blunt() ? BLUNT_TAPER : POINT_TAPER);
			// Sloped sides mean the outward normal leans along the axis, not straight out.
			float slope = (tooth.radius() - tipRadius) / length;
			int ring = v;

			for (int s = 0; s < SIDES; s++) {
				double a = s * 2.0 * Math.PI / SIDES;
				float cos = (float) Math.cos(a);
				float sin = (float) Math.sin(a);
				radial.set(u).mul(cos).fma(sin, w);

				writeVertex(positions, normals, colors, emissive, boneIndices, boneWeights,
						v++, tooth, axis, radial, slope, base, tooth.radius(), ROOT_SHADE);
				writeVertex(positions, normals, colors, emissive, boneIndices, boneWeights,
						v++, tooth, axis, radial, slope, tip, tipRadius, 1f);
			}

			// Wind so the outward face is front-facing: base, next base, next tip, tip.
			for (int s = 0; s < SIDES; s++) {
				int next = (s + 1) % SIDES;
				quads[q++] = ring + s * 2;
				quads[q++] = ring + next * 2;
				quads[q++] = ring + next * 2 + 1;
				quads[q++] = ring + s * 2 + 1;
			}
		}

		return new Result(positions, normals, colors, emissive, boneIndices, boneWeights,
				quads, vertexCount);
	}

	private static void writeVertex(float[] positions, float[] normals, float[] colors,
	                                float[] emissive, int[] boneIndices, float[] boneWeights,
	                                int index, ToothDef tooth, Vector3f axis, Vector3f radial,
	                                float slope, Vector3f centre, float radius, float shade) {
		int p = index * 3;
		positions[p] = centre.x + radial.x * radius;
		positions[p + 1] = centre.y + radial.y * radius;
		positions[p + 2] = centre.z + radial.z * radius;

		Vector3f normal = new Vector3f(radial).fma(slope, axis).normalize();
		normals[p] = normal.x;
		normals[p + 1] = normal.y;
		normals[p + 2] = normal.z;

		colors[p] = ENAMEL_R * shade;
		colors[p + 1] = ENAMEL_G * shade;
		colors[p + 2] = ENAMEL_B * shade;
		emissive[index] = 0f;

		// Rigid. A tooth is bone; it does not deform with the flesh around it, and blending it
		// against nearby bones is what would let the jaw's teeth smear toward the skull.
		int b = index * SkinBinder.MAX_INFLUENCES;
		boneIndices[b] = tooth.bone();
		boneWeights[b] = 1f;
		for (int i = 1; i < SkinBinder.MAX_INFLUENCES; i++) {
			boneIndices[b + i] = tooth.bone();
			boneWeights[b + i] = 0f;
		}
	}

	/**
	 * Distance from a tooth's root out to where it leaves the flesh, by walking the field.
	 * <p>
	 * Marched rather than solved because the surface here is a smooth union of a capsule, a
	 * cranial blob and a muzzle, and there is no closed form for where that crosses zero. The step
	 * is tied to the tooth's own radius, so a fine tooth is located finely and a coarse one cheaply.
	 */
	private static float surfaceDistance(BodySdf sdf, Vector3f root, Vector3f direction, float radius) {
		float step = Math.max(radius * 0.5f, 1e-3f);
		float travelled = 0f;
		// Far enough to cross any skull; a tooth that has not emerged by then is inside something
		// it was never going to escape, and the fallback keeps it from vanishing entirely.
		float limit = step * 120f;
		while (travelled < limit) {
			travelled += step;
			if (sdf.eval(root.x + direction.x * travelled,
					root.y + direction.y * travelled,
					root.z + direction.z * travelled) > 0f) {
				return travelled;
			}
		}
		return step * 8f;
	}

	/** Any unit vector perpendicular to {@code axis}. */
	private static void perpendicular(Vector3f axis, Vector3f dest) {
		// Cross with whichever cardinal the axis is least aligned to, so the result never
		// degenerates to zero length.
		if (Math.abs(axis.y) < 0.9f) {
			dest.set(0f, 1f, 0f).cross(axis);
		} else {
			dest.set(1f, 0f, 0f).cross(axis);
		}
		dest.normalize();
	}
}
