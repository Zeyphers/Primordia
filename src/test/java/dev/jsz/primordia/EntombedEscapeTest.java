package dev.jsz.primordia;

import dev.jsz.primordia.entity.CreatureEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The escape from a wall is worth pinning down even though the search itself needs a level to run
 * against: the constants are the whole behaviour, and each has a failure mode that is invisible
 * until something is dying inside a rock.
 * <p>
 * Read as compile-time constants rather than by reflection, which would force the entity class to
 * initialise and take Minecraft's whole bootstrap with it.
 */
class EntombedEscapeTest {

	@Test
	void aStuckCreatureIsGivenTimeToWalkOutBeforeItIsMoved() {
		assertTrue(CreatureEntity.ENTOMBED_RELOCATE_TICKS >= 20,
				"relocating within a second turns every clipped corner into a teleport");
		assertTrue(CreatureEntity.ENTOMBED_RELOCATE_TICKS <= 100,
				"suffocation costs a point of health per tick, so a long wait is a death sentence");
	}

	@Test
	void theShoveIsEnoughToSlideOutAndNotToLaunch() {
		assertTrue(CreatureEntity.ENTOMBED_SHOVE > 0.01,
				"too gentle to free a body wedged in a block");
		assertTrue(CreatureEntity.ENTOMBED_SHOVE < 0.2,
				"a shove this hard reads as the creature being fired out of the wall");
	}

	@Test
	void itRunsRatherThanStrolls() {
		assertTrue(CreatureEntity.ENTOMBED_SPEED > 1.0,
				"escaping at walking pace is what this was meant to stop");
	}

	@Test
	void theSearchStaysWithinReach() {
		assertTrue(CreatureEntity.ENTOMBED_SEARCH_RADIUS >= 2.0,
				"too tight to find the way out of a thick wall");
		assertTrue(CreatureEntity.ENTOMBED_SEARCH_RADIUS <= 8.0,
				"relocating this far is a teleport, not an escape");
	}
}
