package dev.jsz.primordia.sdf;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.body.SdfBlob;
import dev.jsz.primordia.util.MathX;
import org.joml.Vector3f;

/**
 * The signed distance field for one {@link BodyPlan}: every bone contributes a tapered
 * capsule, every {@link SdfBlob} an ellipsoid, and they are combined with a smooth union so
 * limbs fair into the torso instead of intersecting it. This is what makes the creatures look
 * grown rather than assembled.
 * <p>
 * <b>Performance.</b> A bake samples the field tens of thousands of times, so parts are
 * bucketed into a uniform grid (CSR layout) at construction. A sample only evaluates the parts
 * registered in its own cell, which in practice is a handful rather than all fifty. Cells are
 * padded by the blend radius so the smooth union never loses a contributor that would have
 * affected the surface; parts far from the surface may be missed, but only where the returned
 * distance is large and positive anyway, which marching cubes never looks at.
 * <p>
 * Capsules use the cheap "interpolate the radius along the closest-point parameter" form rather
 * than an exact round-cone distance. It is not a strictly metric SDF when the two radii differ
 * a lot, but the zero level set is in the right place and the gradient is smooth, which is all
 * the mesher needs.
 */
public final class BodySdf {
	private static final float FAR = 1.0e4f;
	private static final int TARGET_CELLS_PER_AXIS = 10;

	/** 8 floats per capsule: ax, ay, az, bx, by, bz, rHead, rTail. */
	private final float[] capsules;
	private final byte[] capsuleFeature;
	private final int[] capsuleGroup;
	private final int[] blobGroup;

	/**
	 * Per-sample scratch for the grouped blend. Safe as instance state because a BodySdf is built
	 * and consumed entirely within one bake on one worker thread — see {@link
	 * dev.jsz.primordia.mesh.MeshBaker}. Only the groups actually touched are reset after each
	 * sample, so cost stays proportional to the parts in a cell rather than the group count.
	 */
	private final float[] groupDistance;
	private final boolean[] groupTouched;
	private final int[] touchedGroups;
	/** 6 floats per blob: cx, cy, cz, rx, ry, rz. */
	private final float[] blobs;
	private final byte[] blobFeature;
	private final boolean[] blobSubtract;

	private final int capsuleCount;
	private final int blobCount;

	private final float blendRadius;

	private final Vector3f min;
	private final Vector3f max;
	private final float cellSize;
	private final int nx, ny, nz;
	/** CSR: parts of cell i live in binParts[binStart[i] .. binStart[i+1]). */
	private final int[] binStart;
	private final int[] binParts;

	public BodySdf(BodyPlan plan) {
		this.blendRadius = Math.max(plan.blendRadius, 1e-4f);
		this.min = new Vector3f(plan.boundsMin);
		this.max = new Vector3f(plan.boundsMax);

		int caps = 0;
		for (BoneDef b : plan.bones) {
			if (b.emitsGeometry && b.length() > 1e-5f) caps++;
		}
		this.capsuleCount = caps;
		this.capsules = new float[caps * 8];
		this.capsuleFeature = new byte[caps];
		this.capsuleGroup = new int[caps];

		int c = 0;
		for (BoneDef b : plan.bones) {
			if (!b.emitsGeometry || b.length() <= 1e-5f) continue;
			capsuleGroup[c] = b.blendGroup;
			int o = c * 8;
			capsules[o] = b.head.x;
			capsules[o + 1] = b.head.y;
			capsules[o + 2] = b.head.z;
			capsules[o + 3] = b.tail.x;
			capsules[o + 4] = b.tail.y;
			capsules[o + 5] = b.tail.z;
			capsules[o + 6] = b.radiusHead;
			capsules[o + 7] = b.radiusTail;
			capsuleFeature[c] = (byte) b.feature.ordinal();
			c++;
		}

		this.blobCount = plan.blobs.length;
		this.blobs = new float[blobCount * 6];
		this.blobFeature = new byte[blobCount];
		this.blobSubtract = new boolean[blobCount];
		this.blobGroup = new int[blobCount];
		for (int i = 0; i < blobCount; i++) {
			SdfBlob blob = plan.blobs[i];
			// A blob inherits the blend group of the bone it hangs off, so an eye fairs into the
			// skull it belongs to and nothing else.
			int owner = blob.bone();
			blobGroup[i] = owner >= 0 && owner < plan.bones.length
					? plan.bones[owner].blendGroup
					: BoneDef.AXIAL;
			int o = i * 6;
			blobs[o] = blob.center().x;
			blobs[o + 1] = blob.center().y;
			blobs[o + 2] = blob.center().z;
			blobs[o + 3] = Math.max(blob.radii().x, 1e-4f);
			blobs[o + 4] = Math.max(blob.radii().y, 1e-4f);
			blobs[o + 5] = Math.max(blob.radii().z, 1e-4f);
			blobFeature[i] = (byte) blob.feature().ordinal();
			blobSubtract[i] = blob.subtract();
		}

		int groupCount = 1;
		for (BoneDef b : plan.bones) {
			groupCount = Math.max(groupCount, b.blendGroup + 1);
		}
		this.groupDistance = new float[groupCount];
		this.groupTouched = new boolean[groupCount];
		this.touchedGroups = new int[groupCount];

		// ---- build the uniform acceleration grid ------------------------------
		float span = Math.max(max.x - min.x, Math.max(max.y - min.y, max.z - min.z));
		this.cellSize = Math.max(span / TARGET_CELLS_PER_AXIS, blendRadius * 2f);
		this.nx = Math.max(1, (int) Math.ceil((max.x - min.x) / cellSize));
		this.ny = Math.max(1, (int) Math.ceil((max.y - min.y) / cellSize));
		this.nz = Math.max(1, (int) Math.ceil((max.z - min.z) / cellSize));

		int cellCount = nx * ny * nz;
		int partCount = capsuleCount + blobCount;

		// Cell span each part touches, computed once and reused by both CSR passes.
		int[] spans = new int[partCount * 6];
		int[] counts = new int[cellCount];
		for (int part = 0; part < partCount; part++) {
			float ax, ay, az, bx, by, bz, pad;
			if (part < capsuleCount) {
				int o = part * 8;
				ax = Math.min(capsules[o], capsules[o + 3]);
				ay = Math.min(capsules[o + 1], capsules[o + 4]);
				az = Math.min(capsules[o + 2], capsules[o + 5]);
				bx = Math.max(capsules[o], capsules[o + 3]);
				by = Math.max(capsules[o + 1], capsules[o + 4]);
				bz = Math.max(capsules[o + 2], capsules[o + 5]);
				pad = Math.max(capsules[o + 6], capsules[o + 7]);
			} else {
				int o = (part - capsuleCount) * 6;
				ax = bx = blobs[o];
				ay = by = blobs[o + 1];
				az = bz = blobs[o + 2];
				pad = Math.max(blobs[o + 3], Math.max(blobs[o + 4], blobs[o + 5]));
			}
			// Pad by the blend reach plus a cell, so no contributor to the smooth union is lost.
			pad += blendRadius * 3f + cellSize;

			int s = part * 6;
			spans[s] = axisCell(ax - pad, min.x, nx);
			spans[s + 1] = axisCell(bx + pad, min.x, nx);
			spans[s + 2] = axisCell(ay - pad, min.y, ny);
			spans[s + 3] = axisCell(by + pad, min.y, ny);
			spans[s + 4] = axisCell(az - pad, min.z, nz);
			spans[s + 5] = axisCell(bz + pad, min.z, nz);

			for (int z = spans[s + 4]; z <= spans[s + 5]; z++) {
				for (int y = spans[s + 2]; y <= spans[s + 3]; y++) {
					for (int x = spans[s]; x <= spans[s + 1]; x++) {
						counts[(z * ny + y) * nx + x]++;
					}
				}
			}
		}

		this.binStart = new int[cellCount + 1];
		for (int i = 0; i < cellCount; i++) {
			binStart[i + 1] = binStart[i] + counts[i];
		}
		this.binParts = new int[binStart[cellCount]];

		int[] cursor = new int[cellCount];
		System.arraycopy(binStart, 0, cursor, 0, cellCount);
		for (int part = 0; part < partCount; part++) {
			int s = part * 6;
			for (int z = spans[s + 4]; z <= spans[s + 5]; z++) {
				for (int y = spans[s + 2]; y <= spans[s + 3]; y++) {
					for (int x = spans[s]; x <= spans[s + 1]; x++) {
						binParts[cursor[(z * ny + y) * nx + x]++] = part;
					}
				}
			}
		}
	}

	private int axisCell(float v, float origin, int n) {
		return MathX.clamp((int) Math.floor((v - origin) / cellSize), 0, n - 1);
	}

	private int cellFor(float x, float y, float z) {
		int cx = MathX.clamp((int) Math.floor((x - min.x) / cellSize), 0, nx - 1);
		int cy = MathX.clamp((int) Math.floor((y - min.y) / cellSize), 0, ny - 1);
		int cz = MathX.clamp((int) Math.floor((z - min.z) / cellSize), 0, nz - 1);
		return (cz * ny + cy) * nx + cx;
	}

	// ------------------------------------------------------------------ sampling

	/**
	 * Signed distance to the body surface. Negative inside.
	 * <p>
	 * Parts are combined in two levels rather than one. Within a blend group — a single limb, or
	 * the whole trunk — a smooth union fairs the segments together. <b>Across</b> limb groups a
	 * hard minimum is used, so two legs that happen to pass close to one another stay separate
	 * surfaces; a plain smooth union over everything fuses them, which is what produced webbing
	 * between the legs of many-limbed creatures. The trunk is then smooth-unioned with the limbs
	 * as a whole, preserving the fairing where a limb meets the body.
	 */
	public float eval(float x, float y, float z) {
		int cell = cellFor(x, y, z);
		int from = binStart[cell], to = binStart[cell + 1];
		if (from == to) return FAR;

		int touchedCount = 0;
		// Additive pass, accumulating per blend group.
		for (int i = from; i < to; i++) {
			int part = binParts[i];
			float partDistance;
			int group;
			if (part < capsuleCount) {
				partDistance = capsuleDistance(part, x, y, z);
				group = capsuleGroup[part];
			} else {
				int b = part - capsuleCount;
				if (blobSubtract[b]) continue;
				partDistance = blobDistance(b, x, y, z);
				group = blobGroup[b];
			}

			// Surface detail — horns, eyes, claws, plates — unions hard so it keeps a crisp base
			// instead of melting into the flesh it is mounted on. See Feature#isSurfaceDetail.
			boolean isAttachedFeature = part >= capsuleCount
					&& Feature.VALUES[blobFeature[part - capsuleCount]].isSurfaceDetail();

			if (!groupTouched[group]) {
				groupTouched[group] = true;
				groupDistance[group] = partDistance;
				touchedGroups[touchedCount++] = group;
			} else {
				if (isAttachedFeature) {
					groupDistance[group] = Math.min(groupDistance[group], partDistance);
				} else {
					groupDistance[group] = MathX.smoothMin(groupDistance[group], partDistance, blendRadius);
				}
			}
		}

		float trunk = FAR;
		float limbs = FAR;
		for (int i = 0; i < touchedCount; i++) {
			int group = touchedGroups[i];
			float groupDist = groupDistance[group];
			// Reset as we go, so the scratch is clean for the next sample without wiping the array.
			groupTouched[group] = false;
			if (group == BoneDef.AXIAL) {
				trunk = groupDist;
			} else if (groupDist < limbs) {
				limbs = groupDist;
			}
		}

		float d;
		if (trunk >= FAR) {
			d = limbs;
		} else if (limbs >= FAR) {
			d = trunk;
		} else {
			d = Math.min(trunk, limbs);
		}
		// Subtractive pass, applied after so carving always wins.
		for (int i = from; i < to; i++) {
			int part = binParts[i];
			if (part >= capsuleCount && blobSubtract[part - capsuleCount]) {
				d = MathX.smoothMax(d, -blobDistance(part - capsuleCount, x, y, z), blendRadius);
			}
		}
		return d;
	}

	/**
	 * Surface normal from the analytic field via central differences. Far better than
	 * per-triangle face normals — this is what gives the bodies their smooth organic shading
	 * despite a coarse marching-cubes grid.
	 */
	public void gradient(float x, float y, float z, Vector3f dest) {
		float h = cellSize * 0.04f;
		float dx = eval(x + h, y, z) - eval(x - h, y, z);
		float dy = eval(x, y + h, z) - eval(x, y - h, z);
		float dz = eval(x, y, z + h) - eval(x, y, z - h);
		dest.set(dx, dy, dz);
		if (dest.lengthSquared() < 1e-12f) {
			dest.set(0f, 1f, 0f);
		} else {
			dest.normalize();
		}
	}

	/** Feature tag of the part nearest to the given point; drives vertex colouring. */
	public Feature featureAt(float x, float y, float z) {
		int cell = cellFor(x, y, z);
		int from = binStart[cell], to = binStart[cell + 1];
		float best = FAR;
		int bestFeature = Feature.BODY.ordinal();
		for (int i = from; i < to; i++) {
			int part = binParts[i];
			float d;
			int feature;
			if (part < capsuleCount) {
				d = capsuleDistance(part, x, y, z);
				feature = capsuleFeature[part];
			} else {
				int b = part - capsuleCount;
				if (blobSubtract[b]) continue;
				d = blobDistance(b, x, y, z);
				feature = blobFeature[b];
			}
			if (d < best) {
				best = d;
				bestFeature = feature;
			}
		}
		return Feature.VALUES[bestFeature];
	}

	private float capsuleDistance(int index, float px, float py, float pz) {
		int o = index * 8;
		float ax = capsules[o], ay = capsules[o + 1], az = capsules[o + 2];
		float bx = capsules[o + 3], by = capsules[o + 4], bz = capsules[o + 5];
		float abx = bx - ax, aby = by - ay, abz = bz - az;
		float apx = px - ax, apy = py - ay, apz = pz - az;
		float l2 = abx * abx + aby * aby + abz * abz;
		float t = l2 < 1e-9f ? 0f : MathX.clamp01((apx * abx + apy * aby + apz * abz) / l2);
		float dx = apx - abx * t, dy = apy - aby * t, dz = apz - abz * t;
		float radius = capsules[o + 6] + (capsules[o + 7] - capsules[o + 6]) * t;
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
	}

	private float blobDistance(int index, float px, float py, float pz) {
		int o = index * 6;
		float dx = (px - blobs[o]) / blobs[o + 3];
		float dy = (py - blobs[o + 1]) / blobs[o + 4];
		float dz = (pz - blobs[o + 2]) / blobs[o + 5];
		float k = Math.min(blobs[o + 3], Math.min(blobs[o + 4], blobs[o + 5]));
		// Standard ellipsoid approximation: normalise into the unit sphere, scale back by the
		// smallest semi-axis to keep the result conservative (never overestimates the distance).
		return ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - 1f) * k;
	}

	public Vector3f boundsMin() {
		return min;
	}

	public Vector3f boundsMax() {
		return max;
	}
}
