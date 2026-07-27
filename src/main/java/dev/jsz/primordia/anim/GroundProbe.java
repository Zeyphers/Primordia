package dev.jsz.primordia.anim;

/**
 * Supplies terrain height to the gait controller. Abstracted so the animation code stays free of
 * Minecraft world types — the same {@link CreatureAnimator} can be driven by a real
 * {@code ClientWorld} in game or by a flat stub in a unit test.
 */
@FunctionalInterface
public interface GroundProbe {
	/**
	 * Finds the surface a foot could stand on near {@code (x, z)}.
	 * <p>
	 * Implementations must reject surfaces the creature could not actually have stepped onto —
	 * notably the sides of walls and tree trunks, which a foot passing by will otherwise plant on
	 * and appear glued to. Returning {@code NaN} is the correct answer for such a column; the
	 * caller falls back to the creature's own foot level.
	 *
	 * @param referenceY the creature's foot level, the origin for step-up and drop limits
	 * @return the surface Y, or {@code Float.NaN} when nothing standable is in range
	 */
	float groundY(double x, double z, double referenceY);

	/** A flat world at a fixed height. Useful for tests and as a fallback. */
	static GroundProbe flat(float y) {
		return (x, z, startY) -> y;
	}
}
