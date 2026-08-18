package dev.jsz.primordia.mesh;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.body.Feature;
import dev.jsz.primordia.util.MathX;

/**
 * Assigns each baked vertex up to {@value #MAX_INFLUENCES} bone influences.
 * <p>
 * The surface comes out of the SDF with no notion of which bone it belongs to, so weights are
 * derived geometrically: a vertex is influenced by a bone in inverse proportion to its distance
 * from that bone's capsule <i>surface</i> (not its axis — otherwise a fat torso would out-compete
 * a thin leg bone right next to it). The inverse-square falloff gives soft shoulders and hips
 * for free, which is exactly the deformation you want where a limb meets the body.
 * <p>
 * <b>Candidates are restricted to bones near the owning bone in the skeleton's own hierarchy, not
 * merely near it in bind-pose space.</b> A crest, frill or other ornament hanging off the neck can
 * sit, at bind pose, geometrically closer to a leg than to anything on its own branch — a tall
 * frill on a short-necked creature, say, arcing down past where a foreleg happens to be. Euclidean
 * distance alone cannot tell "adjacent" from "merely nearby", so that frill vertex would pick up a
 * real weight on the leg bone, and the moment the leg swings through a gait the frill stretches
 * toward it — geometry that is correct at the one pose it was measured in and wrong at every other.
 * <p>
 * A vertex's <i>skeletal</i> neighbourhood is well-defined, though: walk the parent links.
 * {@link #hopDistance} finds the owning bone (the single closest one) and then only admits a
 * candidate if it is within {@link #MAX_HOPS} parent-child steps of that bone. That is enough to
 * cover every legitimate case — blending across a knee, blending a limb root into the shoulder or
 * hip it grows from — because those are one or two hops apart by construction. Reaching a bone on
 * another branch (a frill to a leg, a horn to a tail) needs a walk back up through the neck and
 * spine and down the other side, which is reliably far more hops than any local blend needs, so the
 * filter costs nothing near a joint and rules out everything far away without needing to know what
 * "far away" means in any one creature's particular proportions.
 * <p>
 * <b>Hops cannot separate two limbs, though, and a second rule does that.</b> A vertex may be
 * influenced by the trunk and by <i>one</i> limb — never by two. The hop filter is no help here and
 * no tightening of it ever could be: {@code legA_0} and {@code legB_0} are both children of a spine
 * bone, so a creature's opposite legs are two hops apart and any budget that still allows a knee to
 * blend also allows a leg to reach its neighbour. Distance is no help either, because on an arachnid
 * the neighbouring leg genuinely <i>is</i> nearby — that is the whole shape of the animal.
 * <p>
 * What decides it is {@link BoneDef#blendGroup}, which already exists and already means exactly
 * this: it is what stops the SDF smooth-union fusing adjacent legs into webbing. Honouring it here
 * too is what keeps the two halves of the pipeline consistent. Before this, the field held a
 * spider's legs apart and the skinning tied them back together — a vertex weighted to two limbs is
 * driven by both, follows neither, and drags a stretched face across the gap as the gait swings
 * them apart. Seen in play as toes sticking together between the legs.
 * <p>
 * <b>Finally, a limb may only drive surfaces a limb is entitled to drive.</b> The two rules above
 * both reason about the skeleton, and the remaining fault is about the surface: a frill hangs off a
 * spine bone but sits, in space, right beside a thigh, so the thigh is the nearest capsule and the
 * frill vertex is "owned" by it. Ownership then overrode the group rule — deliberately, because a
 * splayed foot's outer toe reads as trunk by group and welding it to the spine was worse. Measured,
 * that escape was letting a limb drive 26% of all frill vertices (at up to 0.98 weight), 7% of ears
 * and a scatter of plates, horns and light organs; every single one of them was a vertex the field
 * says belongs to the body. So the escape now applies only where the surface really is limb —
 * {@link #limbSurface} — which is exactly the toe case it was added for and none of the ornament.
 */
public final class SkinBinder {
	public static final int MAX_INFLUENCES = 4;
	/** Prevents division blow-up for vertices sitting exactly on a bone surface. */
	private static final float SOFTNESS = 0.03f;

	/**
	 * Widest parent-chain separation, in hops, a candidate bone may have from a vertex's owning
	 * bone.
	 * <p>
	 * Sized against the two distances that actually occur. Every local blend this is meant to
	 * preserve is one or two hops: a knee wants its own segment plus the one above it (1), and on a
	 * three-segment leg a vertex near the foot reaching all the way up into the hip is 3. Reaching
	 * another branch of the skeleton — the case this exists to block — needs a walk back up one limb
	 * and down another, or up through the neck and spine to reach anything hanging off the head.
	 * The shortest such path in the whole generator is an ornament on the very first neck segment of
	 * the shortest possible neck (1 segment) reaching the nearest leg root on the shortest possible
	 * spine (3 segments): ornament→head (1) → neck (1) → spine front (1) → spine hip, 3 segments
	 * apart (2) → leg root (1) = 6 hops. Three hops of local slack leaves a comfortable margin under
	 * that floor in every direction, on every body plan the generator can produce.
	 */
	private static final int MAX_HOPS = 3;

	private static final Feature[] FEATURES = Feature.values();

	/** Flags every bone belonging to a leg or an arm chain. */
	private static boolean[] limbBones(BodyPlan plan) {
		boolean[] out = new boolean[plan.bones.length];
		for (dev.jsz.primordia.body.LimbChain leg : plan.legs) {
			for (int bone : leg.bones) out[bone] = true;
		}
		for (dev.jsz.primordia.body.LimbChain arm : plan.arms) {
			for (int bone : arm.bones) out[bone] = true;
		}
		return out;
	}

	/**
	 * Whether a surface of this kind is part of a limb, and so may be driven by one.
	 * <p>
	 * Everything else — a frill, an ear, a plate, a horn, a light organ, the abdomen — belongs to
	 * the body however close a leg happens to swing past it, and is left to the trunk and to its
	 * own limb group.
	 */
	private static boolean limbSurface(Feature feature) {
		return switch (feature) {
			case LIMB, FOOT, CLAWS, HAND -> true;
			default -> false;
		};
	}

	/*
	 * Trunk flesh is deliberately *not* on that list, and the temptation to add it was measured
	 * rather than argued about. Admitting limb bones to body surfaces so a leg could soften the hip
	 * it grows from put 21% of all trunk vertices on a leg at up to 0.59 of their weight — a hip
	 * that swings with the thigh, which is a worse fault than the crease it was meant to smooth,
	 * and more limb influence on the body than the generator has ever had. The join is already
	 * carried by the leg root's own surface: the flesh around a hip reads as LIMB out of the field,
	 * so it is limb-driven by the rule above and the transition happens there. Trunk vertices took
	 * no visible limb weight before this change and take none after it.
	 */

	private SkinBinder() {
	}

	/**
	 * @param vertexGroup   blend group of the surface each vertex came off, or null when unknown
	 * @param vertexFeature {@link Feature} ordinal of that surface, or null when unknown
	 * @param outIndices    4 ints per vertex
	 * @param outWeights    4 floats per vertex, normalised to sum to 1
	 */
	public static void bind(BodyPlan plan, float[] positions, int vertexCount,
	                        int[] vertexGroup, int[] vertexFeature,
	                        int[] outIndices, float[] outWeights) {
		BoneDef[] bones = plan.bones;
		int[][] hops = hopDistances(bones);
		// Which bones are part of a leg or an arm. Not the same as "has a blend group": the jaw
		// carries one too, and treating it as a limb cost it ownership of its own surface — a jaw
		// vertex then anchored the hop filter on the skull and picked up weight from the spine.
		boolean[] limbBone = limbBones(plan);

		float[] dist = new float[bones.length];
		float[] bestWeight = new float[MAX_INFLUENCES];
		int[] bestBone = new int[MAX_INFLUENCES];

		for (int v = 0; v < vertexCount; v++) {
			float px = positions[v * 3];
			float py = positions[v * 3 + 1];
			float pz = positions[v * 3 + 2];

			// Pass 1: distance to every bone's capsule surface, and which one is nearest — the
			// vertex's owning bone, and the anchor the hop filter measures every candidate against.
			int owner = -1;
			float ownerDist = Float.MAX_VALUE;
			// The one limb this vertex is allowed to be influenced by.
			//
			// Taken from the field where it is known, and only fallen back to "whichever limb is
			// nearest" where it is not. Nearest-limb is a reasonable guess for flesh and a bad one
			// for ornament: a frill, a plate or a dorsal spine is a blob hung off a spine bone, and
			// it can easily dangle closer to a thigh than to anything else. Bound that way it
			// followed the leg, so a creature's veil rippled every time it took a step — the
			// ghosting along ornament edges near the legs.
			//
			// BodySdf#groupAt answers with the group of the part the surface actually belongs to,
			// and a blob carries the group of the bone it hangs off, so a body ornament comes back
			// AXIAL and is excluded from every limb below. Claws and feet still come back on their
			// own leg and are unaffected, which is the distinction nearest-bone could not draw.
			int limbGroup = vertexGroup != null ? vertexGroup[v] : BoneDef.AXIAL;
			boolean groupKnown = vertexGroup != null;
			// Whether this surface is part of a limb at all. Without the field to ask, assume it
			// is: that restores the old nearest-bone behaviour rather than silently tightening it
			// for callers that bind a mesh they did not bake.
			boolean limbSurface = vertexFeature == null
					|| limbSurface(FEATURES[vertexFeature[v]]);
			float limbDist = Float.MAX_VALUE;
			for (int b = 0; b < bones.length; b++) {
				BoneDef bone = bones[b];
				if (!bone.emitsGeometry && bone.length() <= 1e-5f) {
					dist[b] = Float.MAX_VALUE;
					continue;
				}

				float t = MathX.projectOntoSegment(px, py, pz,
						bone.head.x, bone.head.y, bone.head.z,
						bone.tail.x, bone.tail.y, bone.tail.z);
				float ax = bone.head.x + (bone.tail.x - bone.head.x) * t;
				float ay = bone.head.y + (bone.tail.y - bone.head.y) * t;
				float az = bone.head.z + (bone.tail.z - bone.head.z) * t;
				float dx = px - ax, dy = py - ay, dz = pz - az;
				float radius = MathX.lerp(bone.radiusHead, bone.radiusTail, t);

				float d = Math.max(0f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);
				dist[b] = d;
				// The owner anchors the hop filter, so it has to be a bone this surface is allowed
				// to be driven by. Letting a frill be owned by the thigh it dangles past would
				// measure every candidate's hops from the wrong branch of the skeleton.
				boolean eligible = !limbBone[b] || limbSurface || bone.blendGroup == limbGroup;
				if (d < ownerDist && eligible) {
					ownerDist = d;
					owner = b;
				}
				if (!groupKnown && bone.blendGroup != BoneDef.AXIAL && d < limbDist) {
					limbDist = d;
					limbGroup = bone.blendGroup;
				}
			}

			int ownerGroup = owner >= 0 ? bones[owner].blendGroup : BoneDef.AXIAL;

			java.util.Arrays.fill(bestWeight, 0f);
			java.util.Arrays.fill(bestBone, 0);

			// Pass 2: rank every bone within reach of the owner. Reusing the distances from pass 1
			// rather than recomputing them, since the expensive part is the per-bone segment
			// projection above and nothing here needs it a second time.
			if (owner >= 0) {
				int[] ownerHops = hops[owner];
				for (int b = 0; b < bones.length; b++) {
					if (dist[b] == Float.MAX_VALUE) continue;
					if (ownerHops[b] > MAX_HOPS) continue;
					// At most one limb, plus the trunk. Two separate limbs are separate structures
					// that move independently, so a vertex weighted to both is being pulled two
					// ways at once and can follow neither — which is the stretched sheet between a
					// spider's legs. The trunk stays admissible because blending a limb root into
					// the hip it grows from is the one soft join that is genuinely wanted.
					// The owning bone's own limb is always admissible. The field group says which
					// part the surface belongs to and is the right answer for ornament, but it can
					// disagree with the nearest bone — a toe tucked under a short body reads as
					// trunk — and a vertex that cannot follow the bone it is sitting on is worse
					// than one that follows the wrong one: it left a splayed foot's outer toe
					// welded rigidly to the spine.
					int group = bones[b].blendGroup;
					// A limb drives limbs. Ornament, plating, the abdomen and the trunk are the
					// body's, however close a leg swings past them in the bind pose.
					if (limbBone[b] && !limbSurface && group != limbGroup) continue;
					if (group != BoneDef.AXIAL && group != limbGroup && group != ownerGroup) continue;

					float w = 1f / ((dist[b] + SOFTNESS) * (dist[b] + SOFTNESS));

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

	/**
	 * Parent-chain hop distance between every pair of bones, treating {@link BoneDef#parent} links
	 * as an undirected tree. Computed once per bake — bone counts are small, dozens at most, so an
	 * all-pairs BFS costs nothing next to the per-vertex geometry work it guards.
	 */
	private static int[][] hopDistances(BoneDef[] bones) {
		int n = bones.length;
		// Adjacency as parent/child pairs, both directions.
		java.util.List<java.util.List<Integer>> adjacency = new java.util.ArrayList<>(n);
		for (int i = 0; i < n; i++) adjacency.add(new java.util.ArrayList<>());
		for (int i = 0; i < n; i++) {
			int parent = bones[i].parent;
			if (parent < 0 || parent >= n) continue;
			adjacency.get(i).add(parent);
			adjacency.get(parent).add(i);
		}

		int[][] hops = new int[n][n];
		int[] queue = new int[n];
		for (int start = 0; start < n; start++) {
			int[] row = hops[start];
			java.util.Arrays.fill(row, -1);
			row[start] = 0;
			int head = 0, tail = 0;
			queue[tail++] = start;
			while (head < tail) {
				int cur = queue[head++];
				for (int next : adjacency.get(cur)) {
					if (row[next] != -1) continue;
					row[next] = row[cur] + 1;
					queue[tail++] = next;
				}
			}
			// A bone with no path back (should not happen — every bone but the root has a parent,
			// and the root is everyone's ancestor) is treated as unreachable rather than crashing.
			for (int j = 0; j < n; j++) {
				if (row[j] == -1) row[j] = Integer.MAX_VALUE / 2;
			}
		}
		return hops;
	}
}
