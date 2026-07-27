package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.util.MathX;

/**
 * Assigns each baked vertex up to {@value #MAX_INFLUENCES} bone influences.
 * <p>
 * The surface comes out of the SDF with no notion of which bone it belongs to, so weights are
 * derived geometrically: a vertex is influenced by a bone in inverse proportion to its distance
 * from that bone's capsule <i>surface</i> (not its axis — otherwise a fat torso would out-compete
 * a thin leg bone right next to it). The inverse-square falloff gives soft shoulders and hips
 * for free, which is exactly the deformation you want where a limb meets the body.
 */
public final class SkinBinder {
	public static final int MAX_INFLUENCES = 4;
	/** Prevents division blow-up for vertices sitting exactly on a bone surface. */
	private static final float SOFTNESS = 0.03f;

	private SkinBinder() {
	}

	/**
	 * @param outIndices 4 ints per vertex
	 * @param outWeights 4 floats per vertex, normalised to sum to 1
	 */
	public static void bind(BodyPlan plan, float[] positions, int vertexCount,
	                        int[] outIndices, float[] outWeights) {
		BoneDef[] bones = plan.bones;
		float[] bestWeight = new float[MAX_INFLUENCES];
		int[] bestBone = new int[MAX_INFLUENCES];

		for (int v = 0; v < vertexCount; v++) {
			float px = positions[v * 3];
			float py = positions[v * 3 + 1];
			float pz = positions[v * 3 + 2];

			java.util.Arrays.fill(bestWeight, 0f);
			java.util.Arrays.fill(bestBone, 0);

			for (int b = 0; b < bones.length; b++) {
				BoneDef bone = bones[b];
				if (!bone.emitsGeometry && bone.length() <= 1e-5f) continue;

				float t = MathX.projectOntoSegment(px, py, pz,
						bone.head.x, bone.head.y, bone.head.z,
						bone.tail.x, bone.tail.y, bone.tail.z);
				float ax = bone.head.x + (bone.tail.x - bone.head.x) * t;
				float ay = bone.head.y + (bone.tail.y - bone.head.y) * t;
				float az = bone.head.z + (bone.tail.z - bone.head.z) * t;
				float dx = px - ax, dy = py - ay, dz = pz - az;
				float radius = MathX.lerp(bone.radiusHead, bone.radiusTail, t);

				// Distance to the capsule surface, floored at zero for vertices inside it.
				float d = Math.max(0f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);
				float w = 1f / ((d + SOFTNESS) * (d + SOFTNESS));


				// Insertion sort into the top-N list.
				for (int i = 0; i < MAX_INFLUENCES; i++) {
					if (w > bestWeight[i]) {
						for (int j = MAX_INFLUENCES - 1; j > i; j--) {
							bestWeight[j] = bestWeight[j - 1];
							bestBone[j] = bestBone[j - 1];
						}
						bestWeight[i] = w;
						bestBone[i] = b;
						break;
					}
				}
			}

			float sum = 0f;
			for (int i = 0; i < MAX_INFLUENCES; i++) sum += bestWeight[i];
			int base = v * MAX_INFLUENCES;
			if (sum <= MathX.EPS) {
				// Unreachable in practice, but a vertex with no influences would explode the
				// skinning matrix, so pin it rigidly to the root.
				outIndices[base] = plan.rootBone;
				outWeights[base] = 1f;
				for (int i = 1; i < MAX_INFLUENCES; i++) {
					outIndices[base + i] = plan.rootBone;
					outWeights[base + i] = 0f;
				}
			} else {
				for (int i = 0; i < MAX_INFLUENCES; i++) {
					outIndices[base + i] = bestBone[i];
					outWeights[base + i] = bestWeight[i] / sum;
				}
			}
		}
	}
}
