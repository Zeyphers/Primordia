package dev.jsz.primordia.anim;

import dev.jsz.primordia.util.MathX;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * FABRIK (Forward And Backward Reaching Inverse Kinematics) for limb chains.
 * <p>
 * Chosen over an analytic two-bone solve because limbs here have two <i>or</i> three segments
 * depending on the genome, and FABRIK handles both with the same code and no trigonometry.
 * It converges in a handful of iterations and never produces the sudden pops that Jacobian
 * methods can.
 * <p>
 * FABRIK's one weakness is that it has no opinion about which way a joint should bend — left to
 * itself it will happily invert a knee between frames. {@link #applyPoleConstraint} fixes that by
 * rolling the whole solved chain about the root-to-target axis until the mid joint lines up with
 * the limb's pole vector, which is the bind-pose bend direction from
 * {@link dev.jsz.primordia.body.LimbChain#poleDirection}.
 * <p>
 * All state is caller-owned scratch, so a solver instance can be reused every frame without
 * allocating.
 */
public final class Fabrik {
	private static final int DEFAULT_ITERATIONS = 8;
	/**
	 * Distance from the target at which the solver stops refining, in blocks. Two millimetres is
	 * far below what is visible on a creature, and it doubles as the bound on how far a joint can
	 * shift between two solves of the same pose — which is what "no popping" actually means here.
	 */
	public static final float TOLERANCE = 0.002f;

	private final Vector3f delta = new Vector3f();
	private final Vector3f rootStart = new Vector3f();
	private final Vector3f axis = new Vector3f();
	private final Vector3f currentPerp = new Vector3f();
	private final Vector3f desiredPerp = new Vector3f();
	private final Vector3f planeNormal = new Vector3f();
	private final Quaternionf correction = new Quaternionf();

	/**
	 * Solves the chain in place.
	 * <p>
	 * {@code joints} and {@code lengths} may be longer than the chain — only the first
	 * {@code n+1} and {@code n} entries are touched. That lets callers keep one oversized
	 * scratch buffer for creatures whose limb segment count varies with the genome.
	 *
	 * @param joints  world-space joint positions; {@code joints[0]} is pinned, {@code joints[n]} is the effector
	 * @param lengths fixed bone lengths
	 * @param n       number of bones in the chain
	 * @param target  desired world position of {@code joints[n]}
	 * @param pole    direction the mid joints should bend toward; may be null
	 */
	public void solve(Vector3f[] joints, float[] lengths, int n, Vector3f target, Vector3f pole) {
		solve(joints, lengths, n, target, pole, null, null, DEFAULT_ITERATIONS);
	}

	public void solve(Vector3f[] joints, float[] lengths, int n, Vector3f target, Vector3f pole, int iterations) {
		solve(joints, lengths, n, target, pole, null, null, iterations);
	}

	/**
	 * Without a bind plane. The signs are then enforced in whatever frame this frame's axis
	 * produces, which is fine for a fixed target and wrong for a limb whose foot travels.
	 */
	public void solve(Vector3f[] joints, float[] lengths, int n, Vector3f target, Vector3f pole,
	                  float[] bendSigns, int iterations) {
		solve(joints, lengths, n, target, pole, bendSigns, null, iterations);
	}

	/**
	 * @param bendSigns per-interior-joint bind-pose bend directions from
	 *                  {@link dev.jsz.primordia.body.LimbChain#bendSigns}; when supplied, the limb
	 *                  is held in a plane and each joint is kept on its original side of the axis
	 * @param bindPerp  the plane those signs were recorded in, from
	 *                  {@link dev.jsz.primordia.body.LimbChain#bindPerp}; without it the signs are
	 *                  enforced in a frame that rotates with the foot and eventually inverts them
	 */
	public void solve(Vector3f[] joints, float[] lengths, int n, Vector3f target, Vector3f pole,
	                  float[] bendSigns, Vector3f bindPerp, int iterations) {
		if (n == 0) return;
		rootStart.set(joints[0]);

		float total = 0f;
		for (int i = 0; i < n; i++) total += lengths[i];

		// Out of reach: there is no bent solution, so lay the chain out straight at the target.
		if (rootStart.distanceSquared(target) > total * total) {
			delta.set(target).sub(rootStart);
			if (delta.lengthSquared() < 1e-10f) {
				delta.set(0f, -1f, 0f);
			}
			delta.normalize();
			for (int i = 1; i <= n; i++) {
				joints[i].set(joints[i - 1]).fma(lengths[i - 1], delta);
			}
			return;
		}

		prepareBend(joints, n, target, pole);

		for (int iter = 0; iter < iterations; iter++) {
			// Enforce the limb's plane and bend directions before each pass, never after: the
			// projection moves joints and so breaks bone lengths, and only a completed forward
			// pass restores them exactly. Alternating constrain-then-solve converges on a solution
			// that satisfies both.
			if (bendSigns != null && n >= 2) {
				constrainToPlane(joints, n, target, pole, bendSigns, bindPerp);
			}

			// Backward pass: pin the effector to the target, walk toward the root.
			joints[n].set(target);
			for (int i = n - 1; i >= 0; i--) {
				moveToward(joints[i], joints[i + 1], lengths[i]);
			}
			// Forward pass: restore the root, walk back out to the effector.
			joints[0].set(rootStart);
			for (int i = 1; i <= n; i++) {
				moveToward(joints[i], joints[i - 1], lengths[i - 1]);
			}

			// Convergence is checked at the end, never at the top. prepareBend may have just
			// rebuilt the chain along a chord where the bone lengths are not yet correct; a
			// top-of-loop check would see the effector already on the target, break out, and
			// return that stretched layout. Only a completed forward pass guarantees exact lengths.
			if (joints[n].distanceSquared(target) < TOLERANCE * TOLERANCE) break;
		}

		// A chain with recorded bend signs has already been held in its plane throughout; the
		// whole-chain roll below would only be able to satisfy one joint anyway.
		if (pole != null && n >= 2 && bendSigns == null) {
			applyPoleConstraint(joints, n, target, pole);
		}
	}

	/**
	 * Flattens the limb into the plane spanned by its axis and its pole, and mirrors any interior
	 * joint that has strayed to the wrong side of the axis.
	 * <p>
	 * This is what a real leg does — knee, hock and ankle are a planar linkage, not a ball joint —
	 * and enforcing it removes the rotational freedom that lets a three-bone chain flip between
	 * frames. The per-joint sign check is the part that survives digitigrade limbs, where the knee
	 * and hock sit on deliberately opposite sides and no single rotation can place both correctly.
	 */
	private void constrainToPlane(Vector3f[] joints, int n, Vector3f target, Vector3f pole,
	                              float[] bendSigns, Vector3f bindPerp) {
		if (pole == null) return;

		axis.set(target).sub(joints[0]);
		if (axis.lengthSquared() < 1e-10f) return;
		axis.normalize();

		desiredPerp.set(pole).fma(-pole.dot(axis), axis);
		if (desiredPerp.lengthSquared() < 1e-8f) return;
		desiredPerp.normalize();

		// Re-anchor to the plane the limb was grown in.
		//
		// The vector above is the pole flattened against *this frame's* hip-to-target axis, and that
		// axis swings with the foot: measured up to 113 degrees from the bind pose over ordinary
		// ground. The bend signs below were recorded in the bind plane, so once the two are more than
		// ninety degrees apart they disagree about which side is which, and the re-siding that exists
		// to keep a knee facing the right way starts driving it the wrong way instead. Flipping the
		// rebuilt vector back onto the bind plane's half-space costs one dot product and makes the
		// sign convention mean the same thing at every point in the stride.
		if (bindPerp != null && bindPerp.lengthSquared() > 0.5f
				&& desiredPerp.dot(bindPerp) < 0f) {
			desiredPerp.negate();
		}

		planeNormal.set(axis).cross(desiredPerp);
		if (planeNormal.lengthSquared() < 1e-8f) return;
		planeNormal.normalize();

		for (int i = 1; i < n; i++) {
			delta.set(joints[i]).sub(joints[0]);

			// Flatten: drop whatever has drifted out of the limb's plane.
			delta.fma(-delta.dot(planeNormal), planeNormal);

			// Re-side: mirror across the axis if this joint has bent the wrong way.
			int signIndex = i - 1;
			if (signIndex < bendSigns.length && bendSigns[signIndex] != 0f) {
				float along = delta.dot(desiredPerp);
				if (along * bendSigns[signIndex] < 0f) {
					delta.fma(-2f * along, desiredPerp);
				}
			}

			joints[i].set(joints[0]).add(delta);
		}
	}

	/**
	 * Breaks the symmetry of a straight chain before iterating.
	 * <p>
	 * FABRIK cannot introduce a bend into a chain that is colinear with its target: both passes
	 * only ever move joints along the line they already lie on, so the chain stays straight
	 * forever and the effector stops at full extension instead of reaching the target. That case
	 * is not exotic — it is a leg standing still with its foot directly below the hip, which is
	 * the most common pose in the game.
	 * <p>
	 * The fix is to bow the interior joints out along the pole direction first, giving the solver
	 * a bend to refine. This only fires when the chain really is near-straight; an already-bent
	 * chain is left alone so that seeding from the previous frame keeps its temporal coherence.
	 */
	private void prepareBend(Vector3f[] joints, int n, Vector3f target, Vector3f pole) {
		if (n < 2) return;

		float total = 0f;
		for (int i = 0; i < n; i++) {
			total += joints[i].distance(joints[i + 1]);
		}
		if (total < MathX.EPS) return;

		// Only reseed a chain that is currently extended. Straightness is measured against the
		// chain's own span, not against the target direction — a straight chain pointing somewhere
		// other than the target is still straight, and still the degenerate starting point that
		// makes FABRIK converge slowly. A chain already bent from the previous frame is left
		// untouched so that seeding stays temporally coherent while walking.
		if (joints[0].distance(joints[n]) < total * 0.99f) return;

		axis.set(target).sub(joints[0]);
		if (axis.lengthSquared() < 1e-10f) {
			axis.set(0f, -1f, 0f);
		}
		axis.normalize();

		// Pick a bend direction perpendicular to the limb axis, preferring the pole.
		if (pole != null) {
			desiredPerp.set(pole);
		} else {
			desiredPerp.set(0f, 1f, 0f);
			if (Math.abs(axis.dot(desiredPerp)) > 0.9f) {
				desiredPerp.set(1f, 0f, 0f);
			}
		}
		desiredPerp.fma(-desiredPerp.dot(axis), axis);
		if (desiredPerp.lengthSquared() < 1e-8f) {
			// The pole was parallel to the axis and carries no usable direction; any
			// perpendicular will do, since the pole constraint cannot express a preference here.
			desiredPerp.set(0f, 1f, 0f).cross(axis);
			if (desiredPerp.lengthSquared() < 1e-8f) {
				desiredPerp.set(1f, 0f, 0f).cross(axis);
			}
		}
		desiredPerp.normalize();

		// Size the bow to the slack that actually has to be absorbed. Treating the chain as two
		// halves of length total/2 spanning a base of d, the perpendicular offset at the midpoint
		// is sqrt((total/2)² - (d/2)²) by Pythagoras. For a two-bone chain that is the exact elbow
		// position, so the solver starts already converged; for longer chains it is a slight
		// overestimate but still a far better guess than a fixed fraction.
		//
		// Sizing this properly matters most in the hardest case: a target close to the root needs
		// a near-180° fold, which is precisely where FABRIK converges slowest and where a small
		// initial bow leaves it short of the target after any sane iteration count.
		float d = joints[0].distance(target);
		float half = total * 0.5f;
		float halfSpan = d * 0.5f;
		float bulge = (float) Math.sqrt(Math.max(0f, half * half - halfSpan * halfSpan));

		// Rebuild the guess outright rather than nudging the old one: lay the joints along the
		// chord to the target, then bow them out. Nudging is not enough when the chain currently
		// points somewhere else entirely, because the offset joints are still nowhere near the
		// solution and FABRIK has to walk the whole way there.
		for (int i = 1; i < n; i++) {
			float t = (float) i / n;
			joints[i].set(joints[0])
					.fma(t * d, axis)
					.fma(bulge * (float) Math.sin(Math.PI * t), desiredPerp);
		}
		// The effector is deliberately left alone; the backward pass pins it to the target on the
		// very next line of work, and moving it here would only invite the stale-convergence trap.
	}

	/** Repositions {@code point} to sit exactly {@code length} away from {@code anchor}. */
	private void moveToward(Vector3f point, Vector3f anchor, float length) {
		delta.set(point).sub(anchor);
		float d = delta.length();
		if (d < MathX.EPS) {
			// Coincident joints have no direction to preserve; nudge along -Y so the pass continues.
			delta.set(0f, -1f, 0f);
			d = 1f;
		}
		delta.mul(1f / d);
		point.set(anchor).fma(length, delta);
	}

	/**
	 * Rotates the interior joints about the root-to-effector axis so the limb bends toward
	 * {@code pole}. Endpoints are unaffected, so this preserves the FABRIK solution exactly while
	 * removing its rotational ambiguity — a knee stays a knee.
	 */
	private void applyPoleConstraint(Vector3f[] joints, int n, Vector3f target, Vector3f pole) {
		axis.set(target).sub(joints[0]);
		if (axis.lengthSquared() < 1e-10f) return;
		axis.normalize();

		int mid = n / 2;
		// Component of the mid joint perpendicular to the limb axis.
		currentPerp.set(joints[mid]).sub(joints[0]);
		currentPerp.fma(-currentPerp.dot(axis), axis);
		if (currentPerp.lengthSquared() < 1e-8f) return;
		currentPerp.normalize();

		desiredPerp.set(pole);
		desiredPerp.fma(-desiredPerp.dot(axis), axis);
		if (desiredPerp.lengthSquared() < 1e-8f) return;
		desiredPerp.normalize();

		MathX.rotationBetween(currentPerp, desiredPerp, correction);

		for (int i = 1; i < n; i++) {
			delta.set(joints[i]).sub(joints[0]);
			correction.transform(delta);
			joints[i].set(joints[0]).add(delta);
		}
	}
}
