package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.body.ToothDef;
import dev.jsz.primordia.sdf.BodySdf;
import dev.jsz.primordia.skeleton.Skeleton;
import dev.jsz.primordia.util.MathX;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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
	/** Returned when a tooth cannot exist at any length without fouling the opposing jaw. */
	private static final float NO_ROOM = -1f;

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

	/**
	 * Flat arrays for the tooth geometry, ready to concatenate onto a baked mesh.
	 *
	 * @param emitted indices into {@link BodyPlan#teeth} of the teeth actually built, in the order
	 *                their vertices appear. Teeth with nowhere to go are skipped, so this is not
	 *                the identity — and assuming it was is what made the clipping figures
	 *                nonsense: with a tooth missing, every vertex after it was attributed to its
	 *                neighbour, and half of those were checked against the wrong jaw entirely.
	 */
	public record Result(float[] positions, float[] normals, float[] colors, float[] emissive,
	                     int[] boneIndices, float[] boneWeights, int[] quads, int vertexCount,
	                     int[] emitted) {
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
					new int[0], new float[0], new int[0], 0, new int[0]);
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

		// The mouth as the player mostly sees it: shut. Teeth are placed in the open bind pose,
		// where they all sit harmlessly in the gap, so this is the only pose in which a tooth
		// being too long is observable at all.
		Skeleton closed = new Skeleton(plan);
		closed.resetPose();
		closed.setLocalRotation(plan.jawBone,
				new Quaternionf().rotateX(-plan.tightestJawClosure()));
		closed.updateWorld();
		closed.updateSkinMatrices();

		int[] emitted = new int[teeth.length];
		int emittedCount = 0;

		int v = 0;
		int q = 0;
		for (int index = 0; index < teeth.length; index++) {
			ToothDef tooth = teeth[index];
			axis.set(tooth.direction());
			if (axis.lengthSquared() < 1e-8f) continue;
			axis.normalize();

			// The marched surface decides where the gum is; the plan's ceiling decides how far
			// past it the tooth may go. The march is the larger term of the two — a root sits on
			// the bone axis, so it has the whole thickness of the jaw to cross before it emerges
			// — which is why capping the protrusion alone changed nothing measurable.
			// The marched surface decides where the gum is; the plan's ceiling decides how far
			// past it the tooth may stand. Both are needed and they measure different things —
			// the march is simply where the flesh ends, and is often the larger of the two.
			float emerges = surfaceDistance(plan, tooth.bone(), tooth.root(), axis, tooth.radius());
			float extent = longestThatStaysBuried(plan, closed, tooth, axis,
					emerges, emerges + tooth.clearance());
			// Better absent than standing through the skull. Rare enough not to thin a jaw out.
			if (extent == NO_ROOM) continue;
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

			emitted[emittedCount++] = index;

			// Wind so the outward face is front-facing: base, next base, next tip, tip.
			for (int s = 0; s < SIDES; s++) {
				int next = (s + 1) % SIDES;
				quads[q++] = ring + s * 2;
				quads[q++] = ring + next * 2;
				quads[q++] = ring + next * 2 + 1;
				quads[q++] = ring + s * 2 + 1;
			}
		}

		// Degenerate teeth are skipped, so trim to what was written rather than to what was
		// reserved — trailing zeroed vertices would render as a speck at the origin.
		if (v < vertexCount) {
			positions = java.util.Arrays.copyOf(positions, v * 3);
			normals = java.util.Arrays.copyOf(normals, v * 3);
			colors = java.util.Arrays.copyOf(colors, v * 3);
			emissive = java.util.Arrays.copyOf(emissive, v);
			boneIndices = java.util.Arrays.copyOf(boneIndices, v * SkinBinder.MAX_INFLUENCES);
			boneWeights = java.util.Arrays.copyOf(boneWeights, v * SkinBinder.MAX_INFLUENCES);
			quads = java.util.Arrays.copyOf(quads, q);
			vertexCount = v;
		}
		return new Result(positions, normals, colors, emissive, boneIndices, boneWeights,
				quads, vertexCount, java.util.Arrays.copyOf(emitted, emittedCount));
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
	 * The longest a tooth can be without its point coming out through the jaw it closes against.
	 * <p>
	 * Solved against the closed pose rather than reasoned about. Both jaws are rigid bodies, so
	 * the tip travels a straight line as the tooth lengthens and a bisection lands on the limit
	 * exactly. Successive attempts to derive this limit in closed form were all wrong — the
	 * protrusion was capped against the opposing jaw's thickness, which sounds right and changed
	 * the measured clipping by under one percent, because the length is dominated by the march out
	 * to the gum rather than by the protrusion past it. Computing it removes the reasoning.
	 */
	private static float longestThatStaysBuried(BodyPlan plan, Skeleton closed, ToothDef tooth,
	                                            Vector3f axis, float shortest, float desired) {
		int opposing = tooth.bone() == plan.jawBone ? plan.headBone : plan.jawBone;
		Matrix4f pose = closed.skinMatrix(tooth.bone());

		Vector3f origin = pose.transformPosition(new Vector3f(tooth.root()));
		Vector3f along = pose.transformDirection(new Vector3f(axis)).normalize();

		// Solved against the tooth's axis, but the mesh emits a ring of vertices around it, so the
		// axis has to be buried by at least the tooth's own half-width or the corners of the ring
		// surface even though its centre does not.
		float margin = tooth.radius();

		if (buried(plan, opposing, origin, along, desired, margin)) return desired;

		// Bisect from a tooth that barely leaves the gum up to the one that was asked for. The
		// low end must itself be safe or the search means nothing — returning it regardless when
		// it is not is exactly the mistake that made an earlier version of this guard report no
		// clipping while a fifth of every mouth was still coming through the skull. A floor under
		// a clamp is not a clamp.
		// A tooth whose gum is already past the opposing jaw cannot be made to fit at any length.
		// Emit it as short as it can be rather than deleting it: dropping these took 93% of a
		// saurian's teeth, and a toothless predator is a worse artefact than a stub. What is left
		// is the smallest possible overlap rather than a fang standing through the skull.
		float low = shortest;
		if (!buried(plan, opposing, origin, along, low, margin)) return NO_ROOM;

		float high = desired;
		for (int i = 0; i < 14; i++) {
			float mid = (low + high) * 0.5f;
			if (buried(plan, opposing, origin, along, mid, margin)) low = mid;
			else high = mid;
		}
		return low;
	}

	/** True when a tip at this extent has not come through the far side of the opposing jaw. */
	private static boolean buried(BodyPlan plan, int opposing, Vector3f origin, Vector3f along,
	                              float extent, float margin) {
		float x = origin.x + along.x * extent;
		float y = origin.y + along.y * extent;
		float z = origin.z + along.z * extent;

		BoneDef bone = plan.bones[opposing];
		float t = MathX.projectOntoSegment(x, y, z,
				bone.head.x, bone.head.y, bone.head.z, bone.tail.x, bone.tail.y, bone.tail.z);
		float ax = bone.head.x + (bone.tail.x - bone.head.x) * t;
		float ay = bone.head.y + (bone.tail.y - bone.head.y) * t;
		float az = bone.head.z + (bone.tail.z - bone.head.z) * t;
		float dx = x - ax, dy = y - ay, dz = z - az;

		// Still on its own side of the opposing jaw's axis: nothing has been crossed.
		boolean lower = opposing == plan.headBone;
		if (lower ? y <= ay - margin : y >= ay + margin) return true;

		float outside = (float) Math.sqrt(dx * dx + dy * dy + dz * dz)
				- MathX.lerp(bone.radiusHead, bone.radiusTail, t);
		// The opposing jaw is more than its capsule — cranium, muzzle, ramus, chin all count as
		// flesh a tooth can hide inside.
		for (SdfBlob blob : plan.blobs) {
			if (blob.bone() != opposing) continue;
			float bx = (x - blob.center().x) / blob.radii().x;
			float by = (y - blob.center().y) / blob.radii().y;
			float bz = (z - blob.center().z) / blob.radii().z;
			float k = Math.min(blob.radii().x, Math.min(blob.radii().y, blob.radii().z));
			outside = Math.min(outside, ((float) Math.sqrt(bx * bx + by * by + bz * bz) - 1f) * k);
		}
		return outside <= -margin;
	}

	/**
	 * Distance from a tooth's root out to where it leaves <b>its own jaw</b>.
	 * <p>
	 * Its own jaw, not the body. Marching the whole field looks right and is badly wrong for any
	 * tooth near the hinge: directly above the back of a mandible is not open mouth, it is the
	 * skull, so the march tunnelled up through the head and reported the top of the cranium as the
	 * gum line. Those teeth then started outside the opposing jaw before they had any length at
	 * all, which is why no amount of shortening moved them — a saurian, whose jaw runs most of the
	 * length of a small head, lost 93% of its teeth that way.
	 * <p>
	 * Against the owning bone alone the question is the one actually being asked: where does this
	 * tooth leave the gum it is set in?
	 */
	private static float surfaceDistance(BodyPlan plan, int bone, Vector3f root, Vector3f direction,
	                                     float radius) {
		float step = Math.max(radius * 0.5f, 1e-3f);
		float travelled = 0f;
		float limit = step * 120f;
		while (travelled < limit) {
			travelled += step;
			if (distanceToOwnJaw(plan, bone,
					root.x + direction.x * travelled,
					root.y + direction.y * travelled,
					root.z + direction.z * travelled) > 0f) {
				return travelled;
			}
		}
		return step * 8f;
	}

	/** Signed distance to the capsule and blobs belonging to one bone, and nothing else. */
	private static float distanceToOwnJaw(BodyPlan plan, int index, float x, float y, float z) {
		BoneDef bone = plan.bones[index];
		float t = MathX.projectOntoSegment(x, y, z,
				bone.head.x, bone.head.y, bone.head.z, bone.tail.x, bone.tail.y, bone.tail.z);
		float ax = bone.head.x + (bone.tail.x - bone.head.x) * t;
		float ay = bone.head.y + (bone.tail.y - bone.head.y) * t;
		float az = bone.head.z + (bone.tail.z - bone.head.z) * t;
		float dx = x - ax, dy = y - ay, dz = z - az;
		float nearest = (float) Math.sqrt(dx * dx + dy * dy + dz * dz)
				- MathX.lerp(bone.radiusHead, bone.radiusTail, t);

		for (SdfBlob blob : plan.blobs) {
			if (blob.bone() != index || blob.subtract()) continue;
			float bx = (x - blob.center().x) / blob.radii().x;
			float by = (y - blob.center().y) / blob.radii().y;
			float bz = (z - blob.center().z) / blob.radii().z;
			float k = Math.min(blob.radii().x, Math.min(blob.radii().y, blob.radii().z));
			nearest = Math.min(nearest, ((float) Math.sqrt(bx * bx + by * by + bz * bz) - 1f) * k);
		}
		return nearest;
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
