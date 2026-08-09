package dev.jsz.primordia.body;

import dev.jsz.primordia.genome.Genome;
import org.joml.Vector3f;

/**
 * The fully decoded phenotype: a bind-pose skeleton, the SDF description of the flesh
 * hanging off it, the IK chains, and the colour scheme.
 * <p>
 * A BodyPlan is a pure function of a {@link Genome} — no world state, no randomness beyond
 * the genome's own seed — which is what allows meshes to be cached by genome and baked on a
 * worker thread. Built by {@link BodyPlanBuilder}.
 */
public final class BodyPlan {
	public final Genome genome;
	public final BoneDef[] bones;
	public final SdfBlob[] blobs;
	public final LimbChain[] legs;
	public final LimbChain[] arms;
	public final BodyPalette palette;

	/** Smoothing radius for the SDF smooth-union; larger values give blobbier, more amphibian bodies. */
	public final float blendRadius;

	public final int rootBone;
	public final int headBone;
	/** Hinged mandible, child of {@link #headBone}. Always present; every creature has a mouth. */
	public final int jawBone;
	/**
	 * Rotation about the jaw's own X that brings the mandible up flush with the skull, in radians.
	 * <p>
	 * The mouth is <b>baked open</b> and closed by the animator, not the reverse. Baking it shut
	 * would leave no seam for the mesher to resolve and the two surfaces would come out welded, so
	 * the bind pose gapes and the resting pose is this rotation applied. It is stored rather than
	 * assumed because it depends on how far ajar the plan chose to bake.
	 */
	public final float jawRestAngle;

	/**
	 * Fraction of the baked gape a creature holds even at rest — animals close their jaws, they do
	 * not clench them.
	 * <p>
	 * Lives here rather than in the animator because the mesher needs it too: it decides how long
	 * a tooth can be without fouling the other jaw, and the pose it must check against is the
	 * tightest one the creature ever actually adopts. Solving against a fully shut mouth instead
	 * rejects teeth for colliding in a pose that never occurs.
	 */
	public static final float JAW_REST_SLACK = 0.06f;

	/** The tightest the mouth ever closes, in radians of jaw rotation. */
	public float tightestJawClosure() {
		return jawRestAngle * (1f - JAW_REST_SLACK);
	}

	/** Bind-pose height of the hip above the ground plane (y = 0). */
	public final float hipHeight;
	/** Overall bind-pose bounding box in model space, padded to include the blend radius. */
	public final Vector3f boundsMin;
	public final Vector3f boundsMax;

	/** Nose-to-tail length; used for collision box sizing and stride length. */
	public final float bodyLength;
	/** Rough mass proxy in arbitrary units, derived from volume. Drives food need and step timing. */
	public final float mass;

	/**
	 * Radius of the thinnest limb segment. The mesher raises its sampling resolution until cells
	 * are smaller than this — a feature narrower than one cell falls between samples and vanishes
	 * from the mesh entirely, which is what made thin legs disappear.
	 */
	public final float minLimbRadius;

	/**
	 * Narrowest daylight between the tips of two separate limbs, surface to surface.
	 * <p>
	 * The companion to {@link #minLimbRadius}, and the other half of what the mesher has to be able
	 * to see. That one says how fine the sampling must be for a limb not to vanish; this one says
	 * how far the surface may be grown before two limbs stop being two limbs. Voxel mode inflates
	 * the whole body to rescue limbs thinner than a voxel, and inflation eats a gap from both sides
	 * at once — so without this it cheerfully welded a creature's feet together in the course of
	 * saving its legs.
	 * <p>
	 * {@code Float.MAX_VALUE} when there is nothing to compare, i.e. fewer than two limbs.
	 */
	public final float minLimbGap;

	/**
	 * Teeth, as geometry emitted outside the signed distance field entirely. See {@link ToothDef}.
	 */
	public final ToothDef[] teeth;

	public BodyPlan(Genome genome, BoneDef[] bones, SdfBlob[] blobs, LimbChain[] legs, LimbChain[] arms,
	                BodyPalette palette, float blendRadius, int rootBone, int headBone, int jawBone,
	                float jawRestAngle, float hipHeight, Vector3f boundsMin, Vector3f boundsMax,
	                ToothDef[] teeth,
	                float bodyLength, float mass, float minLimbRadius, float minLimbGap) {
		this.minLimbGap = minLimbGap;
		this.teeth = teeth;
		this.jawBone = jawBone;
		this.jawRestAngle = jawRestAngle;
		this.genome = genome;
		this.bones = bones;
		this.blobs = blobs;
		this.legs = legs;
		this.arms = arms;
		this.palette = palette;
		this.blendRadius = blendRadius;
		this.rootBone = rootBone;
		this.headBone = headBone;
		this.hipHeight = hipHeight;
		this.boundsMin = boundsMin;
		this.boundsMax = boundsMax;
		this.bodyLength = bodyLength;
		this.mass = mass;
		this.minLimbRadius = minLimbRadius;
	}

	public int boneCount() {
		return bones.length;
	}

	/** Width of the bind-pose bounding box on X, i.e. how wide the creature stands. */
	public float width() {
		return boundsMax.x - boundsMin.x;
	}

	/** Total standing height, used for the entity's collision box. */
	public float height() {
		return boundsMax.y - Math.min(0f, boundsMin.y);
	}
}
