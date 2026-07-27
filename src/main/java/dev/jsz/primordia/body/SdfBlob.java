package dev.jsz.primordia.body;

import org.joml.Vector3f;

/**
 * An ellipsoid attached to a bone, in bind-pose model space. Blobs add the detail that
 * capsules alone cannot express — cranial bulge, eyes, jaw mass, dorsal plates — and are
 * skinned to their owning bone so they follow the animation for free.
 *
 * @param bone      index into {@link BodyPlan#bones} that this blob is skinned to
 * @param center    centre in bind-pose model space
 * @param radii     ellipsoid semi-axes
 * @param feature   semantic tag, drives vertex colouring
 * @param subtract  when true the blob is carved out of the body instead of added (mouth lines, gill slits)
 */
public record SdfBlob(int bone, Vector3f center, Vector3f radii, Feature feature, boolean subtract) {
	public float maxRadius() {
		return Math.max(radii.x, Math.max(radii.y, radii.z));
	}
}
