package dev.jsz.primordia.skeleton;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.body.BoneDef;
import dev.jsz.primordia.util.MathX;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The posable runtime skeleton for one creature instance.
 * <p>
 * Conventions, which everything else depends on:
 * <ul>
 *   <li>A bone's local axis is <b>+Y</b>, running from its head to its tail. The bind rotation is
 *       therefore "rotate +Y onto (tail - head)".</li>
 *   <li>{@code bindWorld[i]} is the bone's absolute bind frame; {@code bindLocal[i]} is that frame
 *       expressed relative to its parent. Animation supplies an <i>additional</i> local rotation on
 *       top of the bind pose, so a zeroed pose is exactly the bind pose.</li>
 *   <li>{@code skin[i] = world[i] * bindWorld[i]⁻¹} — the matrix that carries a bind-pose vertex to
 *       its posed position. With an identity pose this is the identity matrix.</li>
 * </ul>
 * {@link BodyPlan} guarantees parents precede children, so a single forward pass resolves the
 * whole hierarchy.
 */
public final class Skeleton {
	public final BodyPlan plan;
	private final int count;

	private final Matrix4f[] bindWorld;
	private final Matrix4f[] bindWorldInverse;
	private final Matrix4f[] bindLocal;
	private final float[] lengths;

	/** Per-bone animated rotation, in bone-local space. */
	private final Quaternionf[] pose;
	private final Matrix4f[] world;
	private final Matrix4f[] skin;

	/** Whole-body transform applied above the root bone: body bob, lean and roll live here. */
	public final Matrix4f rootTransform = new Matrix4f();

	private final Matrix4f aimScratch = new Matrix4f();

	public Skeleton(BodyPlan plan) {
		this.plan = plan;
		this.count = plan.bones.length;
		this.bindWorld = new Matrix4f[count];
		this.bindWorldInverse = new Matrix4f[count];
		this.bindLocal = new Matrix4f[count];
		this.lengths = new float[count];
		this.pose = new Quaternionf[count];
		this.world = new Matrix4f[count];
		this.skin = new Matrix4f[count];

		Quaternionf rot = new Quaternionf();
		Vector3f dir = new Vector3f();

		for (int i = 0; i < count; i++) {
			BoneDef bone = plan.bones[i];
			lengths[i] = bone.length();

			dir.set(bone.tail).sub(bone.head);
			if (dir.lengthSquared() < 1e-10f) {
				dir.set(0f, 1f, 0f);
			} else {
				dir.normalize();
			}
			MathX.rotationBetween(MathX.Y_AXIS, dir, rot);

			bindWorld[i] = new Matrix4f().translation(bone.head).rotate(rot);
			bindWorldInverse[i] = new Matrix4f(bindWorld[i]).invert();

			bindLocal[i] = bone.parent < 0
					? new Matrix4f(bindWorld[i])
					: new Matrix4f(bindWorldInverse[bone.parent]).mul(bindWorld[i]);

			pose[i] = new Quaternionf();
			world[i] = new Matrix4f();
			skin[i] = new Matrix4f();
		}

		resetPose();
		updateWorld();
	}

	public int boneCount() {
		return count;
	}

	public float boneLength(int index) {
		return lengths[index];
	}

	public void resetPose() {
		for (Quaternionf q : pose) q.identity();
		rootTransform.identity();
	}

	public Quaternionf localRotation(int index) {
		return pose[index];
	}

	public void setLocalRotation(int index, Quaternionf rotation) {
		pose[index].set(rotation);
	}

	/** Resolves every bone's world matrix from the current pose. Call once per animation update. */
	public void updateWorld() {
		for (int i = 0; i < count; i++) {
			updateBoneWorld(i);
		}
	}

	/**
	 * Recomputes a single bone's world matrix from its parent's current one. Used by the IK pass,
	 * which walks a chain root-to-tip and needs each bone resolved before aiming the next.
	 * The caller is responsible for the parent already being up to date.
	 */
	public void updateBoneWorld(int index) {
		int parent = plan.bones[index].parent;
		Matrix4f parentWorld = parent < 0 ? rootTransform : world[parent];
		world[index].set(parentWorld).mul(bindLocal[index]).rotate(pose[index]);
	}

	/** Bind-pose direction of a bone (head to tail, normalised) in model space. Constant per skeleton. */
	public Vector3f bindDirection(int index, Vector3f dest) {
		BoneDef bone = plan.bones[index];
		dest.set(bone.tail).sub(bone.head);
		return dest.lengthSquared() < 1e-10f ? dest.set(0f, 1f, 0f) : dest.normalize();
	}

	/** Fills the skinning palette. Call after {@link #updateWorld()}, before rendering. */
	public void updateSkinMatrices() {
		for (int i = 0; i < count; i++) {
			skin[i].set(world[i]).mul(bindWorldInverse[i]);
		}
	}

	public Matrix4f skinMatrix(int index) {
		return skin[index];
	}

	public Matrix4f worldMatrix(int index) {
		return world[index];
	}

	public Vector3f boneHead(int index, Vector3f dest) {
		return world[index].getTranslation(dest);
	}

	public Vector3f boneTail(int index, Vector3f dest) {
		return world[index].transformPosition(dest.set(0f, lengths[index], 0f));
	}

	/**
	 * Resolves the world frame a bone would have if its own pose rotation were identity — i.e.
	 * the frame its animated rotation is applied <i>on top of</i>. IK needs this to convert a
	 * desired world-space direction into a local rotation.
	 */
	public Matrix4f restFrameOf(int index, Matrix4f dest) {
		int parent = plan.bones[index].parent;
		Matrix4f parentWorld = parent < 0 ? rootTransform : world[parent];
		return dest.set(parentWorld).mul(bindLocal[index]);
	}

	/**
	 * Points a bone along a world-space direction by writing the appropriate local rotation.
	 * <p>
	 * The direction is pulled back through the bone's rest frame (parent world times bind local)
	 * so the result is expressed in the space the pose rotation is applied in. Twist about the
	 * bone axis is left unconstrained, which is fine for limbs, necks and tails.
	 *
	 * @param scratchQ caller-owned scratch quaternion, avoids allocating per bone per frame
	 * @param scratchV caller-owned scratch vector
	 */
	public void aimBone(int index, Vector3f worldDirection, Quaternionf scratchQ, Vector3f scratchV) {
		restFrameOf(index, aimScratch).invert();
		aimScratch.transformDirection(scratchV.set(worldDirection));
		if (scratchV.lengthSquared() < 1e-10f) {
			pose[index].identity();
			return;
		}
		scratchV.normalize();
		MathX.rotationBetween(MathX.Y_AXIS, scratchV, scratchQ);
		pose[index].set(scratchQ);
	}
}
