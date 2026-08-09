package dev.jsz.primordia.lab;

import net.minecraft.world.item.ItemStack;

/**
 * The arithmetic of cold storage, in one place because two very different things need it.
 * <p>
 * A sample ages by comparing the world clock against the tick it was collected on, so there is no
 * countdown anywhere to slow down: {@link SampleData#freshness} derives the answer fresh every time
 * it is asked. Cooling therefore cannot work by pausing a timer. What it does instead is push the
 * collection tick forward, so that less of the elapsed time counts against the sample. Move the
 * collection tick forward by nine ticks out of every ten, and ten ticks of world time cost the
 * sample one tick of age.
 * <p>
 * Doing it in whole ticks on a ten-tick cycle keeps the sums exact. Adding nine tenths of a tick
 * every tick would need a fractional remainder stored somewhere per stack, and rounding it would
 * drift over the hours a sample can sit in a cooler.
 * <p>
 * The alternative design was to stop the clock outright, which {@link SampleData#preserved} still
 * supports. It was rejected because a cooler that halts decay entirely removes the decision the
 * shelf life exists to create: with it in your pocket there is never a reason to hurry, and the
 * whole pipeline goes slack. Ninety percent slower turns a frantic errand into a comfortable one
 * without turning it into no errand at all.
 */
public final class ColdStorage {

	/** Ticks per cooling cycle. */
	public static final int CYCLE = 10;
	/**
	 * Ticks of ageing forgiven per cycle. Nine in ten, hence a tenth of the normal rate.
	 * <p>
	 * Never raise this to {@link #CYCLE}: at parity the collection tick advances exactly as fast as
	 * the world clock and the sample stops ageing altogether, which is the halt this deliberately
	 * is not.
	 */
	public static final int FORGIVEN_PER_CYCLE = 9;

	/** How much slower a cooled sample ages, as a fraction, for tooltips and tests. */
	public static final float SLOWDOWN = (float) FORGIVEN_PER_CYCLE / CYCLE;

	private ColdStorage() {
	}

	/** Whether this is the tick of the cycle on which cooling is applied. */
	public static boolean isCoolingTick(long gameTime) {
		return gameTime % CYCLE == 0L;
	}

	/**
	 * One cycle of cooling applied to a payload, or the payload unchanged when it has no clock to
	 * move. Pure, and the only place the arithmetic actually happens.
	 * <p>
	 * Split out from the stack version so it can be tested without booting Minecraft: everything
	 * around it needs registries, and this needs nothing but two longs.
	 */
	public static SampleData cool(SampleData data) {
		if (data == null || data.isPreserved()) return data;
		// Forward, not back. Age is world time minus collection time, so a later collection tick is
		// a younger sample.
		return new SampleData(
				data.genome(), data.collectedAtTick() + FORGIVEN_PER_CYCLE, data.lineageHex());
	}

	/**
	 * Ages one stack more slowly, if it is a sample with a running clock.
	 * <p>
	 * A preserved sample has no clock to move and is left alone. So is anything that is not carrying
	 * sample data at all, which is the ordinary case for the cooler's own block item and for
	 * whatever a player has managed to get past the slot filter.
	 *
	 * @return true when the stack was changed, so the caller knows to mark itself dirty
	 */
	public static boolean cool(ItemStack stack) {
		if (stack.isEmpty()) return false;
		SampleData data = SampleData.get(stack);
		SampleData slowed = cool(data);
		if (slowed == null || slowed == data) return false;
		slowed.write(stack);
		return true;
	}
}
