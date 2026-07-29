package dev.jsz.primordia;

import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.ecology.VanillaInteractions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the food web across the two faunas.
 * <p>
 * The point of routing vanilla mobs through {@link EnergyBudget#isWorthHunting} rather than giving
 * each one a bespoke rule is that both sides then answer the same question the same way. A fox and
 * a creature of the same mass should be equally plausible as predator and as prey, and changing the
 * window should move both together. That is easy to state and easy to lose — a per-mob threshold
 * added later would look perfectly reasonable and quietly split the two apart again.
 */
class VanillaInteractionTest {

	// Referred to by registry id rather than by EntityType constant. Those constants are registry
	// entries and touching one before the registries exist throws from the static initialiser —
	// and bootstrapping the game to read a lookup table would be a heavy way to test a map.
	private static final String CHICKEN = "minecraft:chicken";
	private static final String RABBIT = "minecraft:rabbit";
	private static final String FOX = "minecraft:fox";
	private static final String WOLF = "minecraft:wolf";
	private static final String SHEEP = "minecraft:sheep";
	private static final String PIG = "minecraft:pig";
	private static final String COW = "minecraft:cow";
	private static final String HORSE = "minecraft:horse";
	private static final String POLAR_BEAR = "minecraft:polar_bear";
	private static final String ZOMBIE = "minecraft:zombie";
	private static final String SKELETON = "minecraft:skeleton";
	private static final String IRON_GOLEM = "minecraft:iron_golem";
	private static final String RAVAGER = "minecraft:ravager";
	private static final String WARDEN = "minecraft:warden";

	@Test
	void everyMobInTheWebHasAMassOnPrimordiasScale() {
		String[] inWeb = {
				CHICKEN, RABBIT, FOX, WOLF, SHEEP, PIG, COW, HORSE,
				POLAR_BEAR, ZOMBIE, SKELETON, IRON_GOLEM, RAVAGER, WARDEN,
		};
		for (String type : inWeb) {
			float mass = VanillaInteractions.massOf(type);
			// A creature runs from about 0.02 to 1.0, and a vanilla mob has to be comparable or the
			// size window means nothing when it is applied across the two.
			assertTrue(mass > 0f, type + " has no mass and is silently outside the food web");
			assertTrue(mass <= 1.0f, type + " masses " + mass + ", off the top of the scale");
		}
	}

	@Test
	void mobsOutsideTheFoodWebReportNothing() {
		// Absent from the table means absent from the web — the creatures ignore them and they
		// ignore the creatures. Zero is what the targeting predicates check for.
		for (String type : new String[]{
				"minecraft:cod", "minecraft:squid", "minecraft:bat", "minecraft:allay"}) {
			assertEquals(0f, VanillaInteractions.massOf(type), 0f,
					type + " was given a mass; it should be outside the food web entirely");
		}
	}

	@Test
	void aFoxHuntsSmallCreaturesAndNotLargeOnes() {
		float fox = VanillaInteractions.massOf(FOX);

		// Something a fox's own size, give or take, is prey.
		assertTrue(EnergyBudget.isWorthHunting(fox, fox * 0.5f),
				"a fox will not hunt something half its size");
		// A horse is not.
		assertFalse(EnergyBudget.isWorthHunting(fox, VanillaInteractions.massOf(HORSE)),
				"a fox is hunting horses");
		// Nor is something so small the chase does not repay itself.
		assertFalse(EnergyBudget.isWorthHunting(fox, fox * 0.05f),
				"a fox is hunting something it could not possibly get a meal out of");
	}

	@Test
	void theWindowIsTheSameInBothDirections() {
		// A creature hunting a cow and a cow's predator hunting that creature are the same
		// question asked from opposite ends, and must be decided by the same rule. If these ever
		// diverge, one fauna is playing by rules the other is not.
		float wolf = VanillaInteractions.massOf(WOLF);
		float creature = wolf * 0.5f;

		assertTrue(EnergyBudget.isWorthHunting(wolf, creature),
				"a wolf will not hunt a creature half its size");
		assertFalse(EnergyBudget.isWorthHunting(creature, wolf),
				"a creature half a wolf's size is hunting the wolf");
	}

	@Test
	void predatorsAreLargerThanTheirVanillaPrey() {
		// Sanity on the table itself: the mobs meant to be hunters must actually out-mass the
		// things they are meant to hunt, or the window silently excludes every pairing and the
		// whole layer does nothing.
		assertTrue(VanillaInteractions.massOf(WOLF)
				> VanillaInteractions.massOf(RABBIT));
		assertTrue(VanillaInteractions.massOf(POLAR_BEAR)
				> VanillaInteractions.massOf(WOLF));
		assertTrue(VanillaInteractions.massOf(RAVAGER)
				> VanillaInteractions.massOf(PIG));
	}
}
