package dev.jsz.primordia.body;

import org.joml.Vector3f;

/**
 * An IK-driven limb: an ordered chain of bone indices from the attachment joint down to
 * the effector, plus everything the gait controller needs to place its foot.
 * <p>
 * {@code poleDirection} is the bind-pose hint for which way the middle joint bends. FABRIK
 * on its own has no preference and will happily invert a knee, so the solver seeds its
 * initial guess along this vector — that is what makes a knee stay a knee.
 */
public final class LimbChain {
	/** Bone indices, root-most first. The effector is the tail of the last bone. */
	public final int[] bones;
	/** Bind-pose position of the chain root joint (hip / shoulder), model space. */
	public final Vector3f origin;
	/** Bind-pose resting position of the effector (foot), model space. */
	public final Vector3f restEffector;
	/** Model-space direction the mid-joint bends toward. */
	public final Vector3f poleDirection;
	/** -1 = left, +1 = right. */
	public final int side;
	/** 0 = front-most pair, increasing toward the rear. */
	public final int pairIndex;
	/** Phase offset in [0,1) within the gait cycle. */
	public final float gaitPhase;
	/** Sum of the bone lengths — the furthest the effector can reach. */
	public final float totalLength;
	/** True for legs that carry weight and plant on the ground; false for arms. */
	public final boolean weightBearing;

	/**
	 * Which side of the limb axis each interior joint sits on in the bind pose, as +1 or -1,
	 * measured along {@link #poleDirection}. One entry per interior joint.
	 * <p>
	 * A pole vector alone only pins a single joint. That is enough for a two-bone limb, which has
	 * exactly one interior joint, but a three-bone limb has a second joint free to flip around the
	 * axis from frame to frame — the visible knee popping. It also cannot express a digitigrade
	 * leg, whose knee and hock deliberately bend in <i>opposite</i> directions; any single
	 * whole-chain rotation must put them on the same side. Recording the bind-pose sign per joint
	 * fixes both: the solver restores the configuration the limb was actually grown with.
	 */
	public final float[] bendSigns;

	public LimbChain(int[] bones, Vector3f origin, Vector3f restEffector, Vector3f poleDirection,
	                 int side, int pairIndex, float gaitPhase, float totalLength, boolean weightBearing,
	                 float[] bendSigns) {
		this.bones = bones;
		this.origin = origin;
		this.restEffector = restEffector;
		this.poleDirection = poleDirection;
		this.side = side;
		this.pairIndex = pairIndex;
		this.gaitPhase = gaitPhase;
		this.totalLength = totalLength;
		this.weightBearing = weightBearing;
		this.bendSigns = bendSigns;
	}
}
