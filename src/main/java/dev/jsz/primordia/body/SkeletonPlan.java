package dev.jsz.primordia.body;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The bones inside a {@link BodyPlan}, as a body plan of their own.
 * <p>
 * What is left when a carcass has finished rotting. It is built as a BodyPlan rather than as a
 * separate mesh format so that everything downstream is unchanged: the same {@code BodySdf}, the
 * same Surface Nets extraction, the same voxel snapping, the same skinning. A skeleton is not a
 * special case anywhere below this class — it is a body that happens to be shaped like bones.
 * <p>
 * <b>The bone array keeps its indices.</b> Every bone of the original survives here in the same
 * slot, only thinner, and every lump of skeletal detail is a blob skinned to one of them. That is
 * not tidiness: mesh vertices carry bone indices, and the animator poses the bones of the
 * <i>flesh</i> plan fetched from {@link BodyPlanCache}. Insert a rib as a bone and every index past
 * it shifts, so the mesh skins to the wrong joints and the skeleton comes apart the moment it is
 * posed. Ribs, pelvis, skull and the rest are therefore all blobs.
 * <p>
 * Blobs are axis-aligned ellipsoids with no rotation of their own, so anything that has to follow a
 * curve — a rib, an ilium, a jaw — is built as a chain of small spheres instead of one stretched
 * ellipsoid. At the voxel sizes these are seen at, a chain of spheres and a swept tube are the same
 * handful of blocks.
 */
public final class SkeletonPlan {

	/**
	 * How much of its flesh radius each kind of bone keeps as its shaft.
	 * <p>
	 * Thicker than a real skeleton's proportions, and deliberately. These are rendered at one
	 * Minecraft pixel per voxel, where a bone at anatomical thickness is under a single cell across
	 * and comes out of the lattice as a line of disconnected specks. The floor is set by what
	 * survives the grid, not by what a femur looks like.
	 */
	private static final float SHAFT_TRUNK = 0.34f;
	private static final float SHAFT_LIMB = 0.46f;
	private static final float SHAFT_HEAD = 0.34f;
	private static final float SHAFT_JAW = 0.22f;

	/** Sphere radius of a rib, against the girth of the body it has to enclose. */
	private static final float RIB_RADIUS = 0.085f;
	/**
	 * Ceiling on a vertebra's thickness, as a fraction of the animal's nose-to-tail length.
	 * <p>
	 * The gaps in a ribcage are worth more than the exactness of any one bone. A spine held to this
	 * leaves room between the ribs on a heavy body, where scaling purely off girth does not.
	 */
	private static final float TRUNK_CAP_OF_LENGTH = 0.030f;

	private SkeletonPlan() {
	}

	public static BodyPlan of(BodyPlan plan) {
		BoneDef[] bones = new BoneDef[plan.bones.length];
		float thinnest = Float.MAX_VALUE;
		// A spine is a rod down the middle of an animal, and its thickness has to do with how big
		// the animal is, not with how fat. Scaling it off the local flesh radius alone gave a
		// barrel-chested grazer a barrel-shaped backbone: the vertebrae then met the ribs, the ribs
		// met each other, and the whole ribcage filled in as one solid block of bone.
		float trunkCap = plan.bodyLength * TRUNK_CAP_OF_LENGTH;
		for (int i = 0; i < plan.bones.length; i++) {
			BoneDef b = plan.bones[i];
			float scale = shaftScale(b);
			boolean trunk = isVertebral(b);
			float head = trunk ? Math.min(b.radiusHead * scale, trunkCap) : b.radiusHead * scale;
			float tail = trunk ? Math.min(b.radiusTail * scale, trunkCap) : b.radiusTail * scale;
			bones[i] = new BoneDef(b.name, b.parent, b.head, b.tail,
					head, tail, b.feature, b.emitsGeometry,
					b.blendGroup);
			if (b.emitsGeometry) thinnest = Math.min(thinnest, bones[i].maxRadius());
		}

		List<SdfBlob> blobs = new ArrayList<>();
		for (int i = 0; i < plan.bones.length; i++) {
			// The thinned bone, never the flesh one it came from. Everything hung on a bone is a
			// multiple of that bone's own thickness, and measuring those multiples against the
			// muscle that used to be there instead inflates every vertebra and every knuckle until
			// the ribcage is a solid barrel with a skeleton somewhere inside it.
			BoneDef b = bones[i];
			if (!b.emitsGeometry || b.length() < 1e-5f) continue;
			if (i == plan.headBone) {
				skull(blobs, i, b);
			} else if (i == plan.jawBone) {
				mandible(blobs, i, b);
			} else if (isVertebral(b)) {
				vertebrae(blobs, i, b);
			} else {
				epiphyses(blobs, i, b);
			}
		}
		ribcage(blobs, plan);
		girdle(blobs, plan, plan.legs, true);
		girdle(blobs, plan, plan.arms, false);

		Vector3f min = new Vector3f(plan.boundsMin);
		Vector3f max = new Vector3f(plan.boundsMax);
		for (SdfBlob blob : blobs) {
			if (blob.subtract()) continue;
			min.min(new Vector3f(blob.center()).sub(blob.radii()));
			max.max(new Vector3f(blob.center()).add(blob.radii()));
		}

		// Bones stand apart from one another; a skeleton fused into a single lump of ivory is a
		// carcass again. Enough blend to fillet a joint, no more.
		float blend = Math.max(1e-4f, plan.blendRadius * 0.18f);
		// The true thinnest part, reported honestly. MeshBaker reads this to decide how far to grow
		// the field so the finest feature survives the voxel lattice, so overstating it — which an
		// earlier version did, to keep the smooth-mode sampling grid cheap — buys a cheaper bake at
		// the price of ribs that arrive as a scatter of loose blocks.
		float minRadius = thinnest;

		return new BodyPlan(plan.genome, bones, blobs.toArray(new SdfBlob[0]),
				plan.legs, plan.arms, BodyPalette.bone(), blend,
				plan.rootBone, plan.headBone, plan.jawBone, plan.jawRestAngle, plan.hipHeight,
				// A skeleton is built from the flesh plan's own limbs, so it inherits their
				// clearance: the bones inside a leg cannot be further apart than the leg was.
				min, max, plan.teeth, plan.bodyLength, plan.mass, minRadius, plan.minLimbGap);
	}

	private static float shaftScale(BoneDef b) {
		if (b.feature == Feature.JAW) return SHAFT_JAW;
		if (b.feature == Feature.HEAD) return SHAFT_HEAD;
		if (b.feature == Feature.LIMB || b.feature == Feature.FOOT || b.feature == Feature.HAND) {
			return SHAFT_LIMB;
		}
		return SHAFT_TRUNK;
	}

	private static boolean isVertebral(BoneDef b) {
		return b.name.startsWith("spine") || b.name.startsWith("neck") || b.name.startsWith("tail");
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
		return new Vector3f(a).lerp(b, t);
	}

	private static void sphere(List<SdfBlob> out, int bone, Vector3f at, float r, Feature feature) {
		if (r <= 1e-4f) return;
		out.add(new SdfBlob(bone, new Vector3f(at), new Vector3f(r, r, r), feature, false));
	}

	/**
	 * A run of vertebrae down one bone: a centrum at each station with a neural spine standing off
	 * it. The spine is what makes a back read as a back rather than as a rod.
	 */
	private static void vertebrae(List<SdfBlob> out, int index, BoneDef b) {
		float girth = Math.max(b.radiusHead, b.radiusTail);
		int count = Math.max(1, Math.round(b.length() / Math.max(1e-3f, girth * 0.9f)));
		boolean tail = b.name.startsWith("tail");
		for (int i = 0; i < count; i++) {
			float u = (i + 0.5f) / count;
			float r = b.radiusHead + (b.radiusTail - b.radiusHead) * u;
			Vector3f at = lerp(b.head, b.tail, u);
			sphere(out, index, at, r * 1.35f, Feature.BODY);
			// Neural spine: two beads climbing off the centrum, shorter down the tail.
			float reach = r * (tail ? 1.1f : 2.0f);
			for (int k = 1; k <= 2; k++) {
				Vector3f up = new Vector3f(at).add(0f, reach * k * 0.5f, 0f);
				sphere(out, index, up, r * (1.15f - 0.35f * k), Feature.BODY);
			}
		}
	}

	/** The knuckles at each end of a long bone, which is most of what stops it reading as a stick. */
	private static void epiphyses(List<SdfBlob> out, int index, BoneDef b) {
		sphere(out, index, b.head, b.radiusHead * 1.75f, b.feature);
		sphere(out, index, b.tail, b.radiusTail * 1.6f, b.feature);
	}

	/**
	 * Cranium, muzzle and a pair of orbits carved back out of them.
	 * <p>
	 * The sockets are subtractive blobs — the one place the field is cut into rather than added to.
	 * A skull with painted-on eyes is a mask; a skull with holes is a skull.
	 */
	private static void skull(List<SdfBlob> out, int index, BoneDef b) {
		float r = b.radiusHead;
		float len = b.length();
		Vector3f dir = new Vector3f(b.tail).sub(b.head).normalize();

		Vector3f brain = new Vector3f(b.head).fma(len * 0.24f, dir);
		out.add(new SdfBlob(index, brain, new Vector3f(r * 2.3f, r * 2.2f, r * 2.5f),
				Feature.HEAD, false));
		// Muzzle, tapering to the tip.
		for (int i = 1; i <= 4; i++) {
			float u = 0.34f + 0.66f * (i / 4f);
			sphere(out, index, new Vector3f(b.head).fma(len * u, dir), r * (2.0f - 1.1f * u), Feature.HEAD);
		}
		// Orbits: a rim of bone with the socket cut out of the middle of it.
		for (int s = -1; s <= 1; s += 2) {
			Vector3f eye = new Vector3f(b.head).fma(len * 0.36f, dir)
					.add(s * r * 1.7f, r * 0.75f, 0f);
			out.add(new SdfBlob(index, eye, new Vector3f(r * 0.95f, r * 0.95f, r * 0.85f),
					Feature.HEAD, true));
		}
	}

	/** Two rami running forward from the hinge and meeting at the chin. */
	private static void mandible(List<SdfBlob> out, int index, BoneDef b) {
		float r = Math.max(b.radiusHead, 1e-3f);
		for (int s = -1; s <= 1; s += 2) {
			for (int i = 0; i <= 4; i++) {
				float u = i / 4f;
				Vector3f at = lerp(b.head, b.tail, u);
				// The two halves converge as they run forward, so the jaw closes to a chin.
				at.add(s * r * 1.5f * (1f - u * 0.75f), 0f, 0f);
				sphere(out, index, at, r * (1.5f - 0.4f * u), Feature.JAW);
			}
		}
	}

	/**
	 * Ribs off the trunk vertebrae: out, down, and back in under the belly.
	 * <p>
	 * Sprung from the spine bones rather than from a rib count, so a long-backed animal gets more of
	 * them and a stubby one fewer, the same way the vertebrae themselves follow the spine.
	 */
	private static void ribcage(List<SdfBlob> out, BodyPlan plan) {
		List<Integer> spine = new ArrayList<>();
		for (int i = 0; i < plan.bones.length; i++) {
			if (plan.bones[i].name.startsWith("spine")) spine.add(i);
		}
		if (spine.isEmpty()) return;

		// The chest, not the whole back: the rear of the spine carries the pelvis instead.
		int first = Math.max(0, (int) (spine.size() * 0.18f));
		int last = spine.size() - 1;
		for (int s = first; s <= last; s++) {
			int index = spine.get(s);
			BoneDef b = plan.bones[index];
			for (int station = 0; station < 1; station++) {
				float u = 0.5f;
				Vector3f root = lerp(b.head, b.tail, u);
				float girth = b.radiusHead + (b.radiusTail - b.radiusHead) * u;
				// Barrel: widest at mid-chest, tucked at both ends.
				float k = (s - first + u) / Math.max(1f, last - first + 1f);
				float barrel = 0.55f + 0.45f * (float) Math.sin(Math.PI * Math.min(1f, k * 1.05f));
				float drop = girth * (1.15f + 0.55f * barrel);
				float flare = girth * (0.60f + 0.60f * barrel);
				for (int side = -1; side <= 1; side += 2) {
					rib(out, index, root, side, flare, drop, girth);
				}
			}
		}
	}

	/** One rib, as a chain of beads following a quarter-circle out and under. */
	private static void rib(List<SdfBlob> out, int bone, Vector3f root, int side,
	                        float flare, float drop, float girth) {
		int beads = 7;
		for (int i = 1; i <= beads; i++) {
			float t = (float) i / beads;
			// Quarter turn: all width at the top, all drop at the bottom.
			float x = side * flare * (float) Math.sin(t * Math.PI * 0.55f);
			float y = -drop * (1f - (float) Math.cos(t * Math.PI * 0.62f));
			// and raked backwards as it descends, which is what makes a cage look like one
			float z = -drop * 0.30f * t;
			Vector3f at = new Vector3f(root).add(x, y, z);
			sphere(out, bone, at, girth * RIB_RADIUS * (1.25f - 0.35f * t), Feature.BODY);
		}
	}

	/**
	 * Pelvis or shoulder girdle, sized off the joints it has to reach rather than off the spine.
	 * <p>
	 * The sockets have to land on the limbs — a girdle measured from the trunk's own girth comes out
	 * wider than the animal and swallows the legs whole.
	 */
	private static void girdle(List<SdfBlob> out, BodyPlan plan, LimbChain[] limbs, boolean hind) {
		if (limbs == null || limbs.length < 2) return;
		// The pair nearest the relevant end of the body: hips at the back, shoulders at the front.
		int wanted = hind ? maxPair(limbs) : 0;
		Vector3f left = null, right = null;
		for (LimbChain limb : limbs) {
			if (limb.pairIndex != wanted) continue;
			if (limb.side < 0) left = limb.origin;
			else right = limb.origin;
		}
		if (left == null || right == null) return;

		int bone = nearestSpineBone(plan, new Vector3f(left).add(right).mul(0.5f));
		float half = Math.max(0.02f, new Vector3f(left).sub(right).length() * 0.5f);
		Vector3f mid = new Vector3f(left).add(right).mul(0.5f);

		// Sacrum, or the strap across the front of the chest.
		sphere(out, bone, mid, half * 0.45f, Feature.BODY);

		for (Vector3f joint : new Vector3f[]{left, right}) {
			float s = Math.signum(joint.x - mid.x);
			if (s == 0f) s = 1f;
			sphere(out, bone, joint, half * 0.42f, Feature.BODY);
			if (hind) {
				// Ilium forward and up over the joint, ischium back and under it.
				for (int i = 1; i <= 3; i++) {
					float t = i / 3f;
					Vector3f blade = new Vector3f(joint)
							.add(s * half * 0.10f * t, half * 0.75f * t, half * 0.95f * t);
					sphere(out, bone, blade, half * (0.36f - 0.10f * t), Feature.BODY);
					Vector3f under = new Vector3f(joint)
							.add(-s * half * 0.10f * t, -half * 0.60f * t, -half * 0.85f * t);
					sphere(out, bone, under, half * (0.30f - 0.10f * t), Feature.BODY);
				}
			} else {
				// Scapula: a blade lying up and back over the ribs.
				for (int i = 1; i <= 3; i++) {
					float t = i / 3f;
					Vector3f blade = new Vector3f(joint)
							.add(-s * half * 0.06f * t, half * 1.05f * t, -half * 0.45f * t);
					sphere(out, bone, blade, half * (0.34f - 0.12f * t), Feature.BODY);
				}
			}
		}
	}

	private static int maxPair(LimbChain[] limbs) {
		int max = 0;
		for (LimbChain limb : limbs) max = Math.max(max, limb.pairIndex);
		return max;
	}

	/** Which bone a girdle should ride on, so it follows the body when the carcass is posed. */
	private static int nearestSpineBone(BodyPlan plan, Vector3f at) {
		int best = plan.rootBone;
		float bestDistance = Float.MAX_VALUE;
		for (int i = 0; i < plan.bones.length; i++) {
			BoneDef b = plan.bones[i];
			if (!b.name.startsWith("spine")) continue;
			float d = new Vector3f(b.head).add(b.tail).mul(0.5f).distanceSquared(at);
			if (d < bestDistance) {
				bestDistance = d;
				best = i;
			}
		}
		return best;
	}
}
