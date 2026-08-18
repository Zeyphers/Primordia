package dev.jsz.primordia.anim;

/**
 * Per-foot state for the gait controller.
 * <p>
 * Plant positions are stored in <b>absolute world coordinates</b>, deliberately. That is the only
 * way to make a planted foot actually stay put: if plants were stored relative to the creature,
 * every step the body took would drag the foot along with it, which is exactly the sliding
 * artefact procedural walk cycles are meant to eliminate.
 */
public final class FootState {
	/** Where the foot is currently planted (or heading, mid-swing). */
	public double plantX, plantY, plantZ;
	/** Where it lifted off from, so a swing can interpolate between the two. */
	public double xo, prevY, zo;
	/** True while the foot is bearing weight. */
	public boolean grounded = true;
	/** Swing progress in [0,1]; meaningless while grounded. */
	public float swing;
	/**
	 * Seconds elapsed in the current swing, and how long it was given.
	 * <p>
	 * A swing used to be read straight off the gait phase, which is fine as long as every step
	 * begins exactly when the phase says it should. It does not: a foot dragged outside what its
	 * leg can reach has to take a corrective step immediately, whatever the phase is doing, and a
	 * swing timed from the phase would have been either instantaneous or nearly a full cycle long
	 * depending on when it fired. Giving each foot its own clock decouples the two.
	 */
	public float swingTime, swingDuration = 0.25f;
	/** Seconds this foot has been on the ground since it last landed. */
	public float groundedTime;
	/**
	 * Whether the gait phase had this foot in its stance window last frame. The swing is triggered
	 * on the falling edge rather than by the window's value, so a foot that stepped early for its
	 * own reasons is not immediately made to step again.
	 */
	public boolean inStanceWindow = true;
	/** Resolved foot position for this frame, world space. */
	public double currentX, currentY, currentZ;
	/** Set once the foot has been given a real position, so the first frame can snap rather than lerp. */
	public boolean initialised;

	public void snapTo(double x, double y, double z) {
		plantX = xo = currentX = x;
		plantY = prevY = currentY = y;
		plantZ = zo = currentZ = z;
		grounded = true;
		swing = 0f;
		swingTime = 0f;
		inStanceWindow = true;
		initialised = true;
	}

	/** Begins a swing toward a new plant position, to last {@code duration} seconds. */
	public void beginSwing(double x, double y, double z, float duration) {
		xo = currentX;
		prevY = currentY;
		zo = currentZ;
		plantX = x;
		plantY = y;
		plantZ = z;
		grounded = false;
		swing = 0f;
		swingTime = 0f;
		groundedTime = 0f;
		swingDuration = Math.max(0.02f, duration);
	}
}
