package dev.jsz.primordia;

import dev.jsz.primordia.genome.Genome;
import dev.jsz.primordia.lab.ColdStorage;
import dev.jsz.primordia.lab.SampleData;
import dev.jsz.primordia.item.SampleCoolerBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cover for the Sample Cooler's arithmetic.
 * <p>
 * The cooler cannot pause anything: freshness is derived from the world clock every time it is
 * asked, so the only lever is where the collection tick sits. These check that the lever moves the
 * sample at a tenth of the normal rate, and — more importantly — that it never quite stops it,
 * because a cooler that halted decay outright would remove the deadline the shelf life exists to
 * impose.
 * <p>
 * Deliberately tests the payload form rather than the stack form. Everything on the item side needs
 * Minecraft's registries and this needs nothing but two longs, which is why the arithmetic was
 * split out to be reachable on its own.
 */
class ColdStorageTest {

	private static SampleData sampleAt(long tick) {
		return SampleData.of(Genome.random(new Random(99)), tick);
	}

	/** Runs a payload through {@code ticks} of world time inside a cooler. */
	private static SampleData cooledFor(SampleData start, long ticks) {
		SampleData data = start;
		for (long t = 1; t <= ticks; t++) {
			if (ColdStorage.isCoolingTick(t)) data = ColdStorage.cool(data);
		}
		return data;
	}

	@Test
	void aCooledSampleAgesAtATenthOfTheNormalRate() {
		long elapsed = 10_000L;
		SampleData cooled = cooledFor(sampleAt(0L), elapsed);

		// Age is world time minus the collection tick, and the cooler has walked that tick forward.
		long agedBy = elapsed - cooled.collectedAtTick();
		long expected = elapsed / ColdStorage.CYCLE;

		assertEquals(expected, agedBy, ColdStorage.CYCLE,
				"a cooled sample should age about a tenth as fast");
		assertTrue(agedBy > 0, "the cooler stopped decay entirely rather than slowing it");
	}

	@Test
	void aCooledSampleOutlastsAnUncooledOneByRoughlyTenfold() {
		// Uncooled, the shelf life is spent exactly once.
		SampleData warm = sampleAt(0L);
		assertEquals(0f, warm.freshness(SampleData.SHELF_LIFE), 1e-4f,
				"an uncooled sample should be spent at exactly its shelf life");

		// Cooled over that same span, most of the sample is still there.
		SampleData cooled = cooledFor(sampleAt(0L), SampleData.SHELF_LIFE);
		float remaining = cooled.freshness(SampleData.SHELF_LIFE);
		assertTrue(remaining > 0.85f,
				"a cooled sample should still be nearly fresh after one shelf life, was " + remaining);
	}

	@Test
	void coolingNeverOutrunsTheClock() {
		// The forgiven ticks must stay strictly under the cycle. At parity the collection tick
		// advances exactly as fast as the world clock and the sample is pinned at perfectly fresh,
		// which is the halt this is deliberately not.
		assertTrue(ColdStorage.FORGIVEN_PER_CYCLE < ColdStorage.CYCLE,
				"cooling at or above the cycle length halts decay instead of slowing it");

		long elapsed = 5_000L;
		SampleData cooled = cooledFor(sampleAt(0L), elapsed);
		assertTrue(cooled.collectedAtTick() <= elapsed,
				"the collection tick overtook the world clock");
		assertTrue(cooled.freshness(elapsed) < 1f, "a cooled sample never aged at all");
	}

	@Test
	void aSampleStillSpoilsInACoolerGivenLongEnough() {
		// The point of slowing rather than halting: leave one in cold storage indefinitely and it
		// still goes off. Ten shelf lives is the span a tenth-rate decay needs to spend one.
		SampleData cooled = cooledFor(sampleAt(0L), SampleData.SHELF_LIFE * 11L);
		assertEquals(0f, cooled.freshness(SampleData.SHELF_LIFE * 11L), 1e-4f,
				"a cooled sample never spoils, so the shelf life imposes no deadline at all");
	}

	@Test
	void aPreservedSampleHasNoClockToMove() {
		SampleData preserved = sampleAt(0L).preserved();
		assertSame(preserved, ColdStorage.cool(preserved),
				"moved the clock on a sample that has none");
	}

	@Test
	void theAdvertisedSlowdownMatchesTheArithmetic() {
		assertEquals(0.9f, ColdStorage.SLOWDOWN, 1e-6f,
				"the stated slowdown and the tick arithmetic disagree");
	}

	@Test
	void sampleCoolerSuppressesComponentUpdateAnimation() throws Exception {
		java.lang.reflect.Method method = SampleCoolerBlockItem.class.getDeclaredMethod(
				"allowComponentsUpdateAnimation",
				net.minecraft.world.entity.player.Player.class,
				net.minecraft.world.InteractionHand.class,
				net.minecraft.world.item.ItemStack.class,
				net.minecraft.world.item.ItemStack.class);
		assertNotNull(method, "SampleCoolerBlockItem must override allowComponentsUpdateAnimation");
	}
}
