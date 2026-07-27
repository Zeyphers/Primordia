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
	public double prevX, prevY, prevZ;
	/** True while the foot is bearing weight. */
	public boolean grounded = true;
	/** Swing progress in [0,1]; meaningless while grounded. */
	public float swing;
	/** Resolved foot position for this frame, world space. */
	public double currentX, currentY, currentZ;
	/** Set once the foot has been given a real position, so the first frame can snap rather than lerp. */
	public boolean initialised;

	public void snapTo(double x, double y, double z) {
		plantX = prevX = currentX = x;
		plantY = prevY = currentY = y;
		plantZ = prevZ = currentZ = z;
		grounded = true;
		swing = 0f;
		initialised = true;
	}

	/** Begins a swing toward a new plant position. */
	public void beginSwing(double x, double y, double z) {
		prevX = plantX;
		prevY = plantY;
		prevZ = plantZ;
		plantX = x;
		plantY = y;
		plantZ = z;
		grounded = false;
		swing = 0f;
	}
}
