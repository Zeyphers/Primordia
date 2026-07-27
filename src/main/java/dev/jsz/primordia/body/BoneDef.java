package dev.jsz.primordia.body;

import org.joml.Vector3f;

/**
 * One bone of the bind-pose skeleton, expressed in <b>model space</b> (metres, Y up, +Z forward).
 * <p>
 * A bone is a segment from {@code head} to {@code tail}; its local axis is +Y, so
 * {@link dev.jsz.primordia.skeleton.Skeleton} derives the bind rotation as "rotate +Y onto
 * (tail - head)". Each bone also carries the tapered capsule that contributes it to the body
 * SDF, which is why the skeleton and the silhouette can never drift out of sync.
 */
public final class BoneDef {
	public final String name;
	/** Index into {@link BodyPlan#bones}, or -1 for the root. */
	public final int parent;
	public final Vector3f head;
	public final Vector3f tail;
	public final float radiusHead;
	public final float radiusTail;
	public final Feature feature;
	/** When false the bone drives animation but contributes no geometry (e.g. IK helper bones). */
	public final boolean emitsGeometry;

	/**
	 * Which piece of anatomy this bone belongs to, for the purposes of surface blending.
	 * {@link #AXIAL} is the trunk — spine, neck, head, tail — and every limb gets its own id.
	 * <p>
	 * The SDF smooth-union blends anything within its radius, which is what makes a limb grow out
	 * of a hip instead of intersecting it. But it does not distinguish "adjacent" from merely
	 * "nearby", so on a six-legged creature the legs are close enough to fuse into each other and
	 * the animal comes out webbed. Grouping lets the mesher blend within a limb and into the
	 * trunk, while keeping separate limbs strictly apart.
	 */
	public final int blendGroup;

	/** Blend group of the trunk: spine, neck, head and tail all share it. */
	public static final int AXIAL = 0;

	public BoneDef(String name, int parent, Vector3f head, Vector3f tail,
	               float radiusHead, float radiusTail, Feature feature, boolean emitsGeometry,
	               int blendGroup) {
		this.name = name;
		this.parent = parent;
		this.head = head;
		this.tail = tail;
		this.radiusHead = radiusHead;
		this.radiusTail = radiusTail;
		this.feature = feature;
		this.emitsGeometry = emitsGeometry;
		this.blendGroup = blendGroup;
	}

	public BoneDef(String name, int parent, Vector3f head, Vector3f tail,
	               float radiusHead, float radiusTail, Feature feature, boolean emitsGeometry) {
		this(name, parent, head, tail, radiusHead, radiusTail, feature, emitsGeometry, AXIAL);
	}

	public BoneDef(String name, int parent, Vector3f head, Vector3f tail, float radiusHead, float radiusTail) {
		this(name, parent, head, tail, radiusHead, radiusTail, Feature.BODY, true, AXIAL);
	}

	public float length() {
		return head.distance(tail);
	}

	/** Largest radius, used when padding the SDF sampling bounds. */
	public float maxRadius() {
		return Math.max(radiusHead, radiusTail);
	}
}
