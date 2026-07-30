package dev.jsz.primordia.entity;

import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What a given creature will accept as a bribe.
 * <p>
 * The food is drawn from a table appropriate to the creature's diet and then picked by its lineage
 * id, so the choice is <b>stable for a lineage and discoverable by inspection</b>: a carnivore
 * never wants wheat, and once you learn what one member of a clade eats, its relatives want the
 * same thing. That makes taming a matter of observing the animal rather than brute-forcing your
 * hotbar, and it means the genome scanner is genuinely useful rather than decorative.
 */
public final class TamingPreference {

	private static final Item[] HERBIVORE_FOODS = {
			Items.WHEAT, Items.CARROT, Items.APPLE, Items.SWEET_BERRIES,
			Items.MELON_SLICE, Items.BEETROOT, Items.POTATO, Items.PUMPKIN,
			Items.HAY_BLOCK, Items.GLOW_BERRIES, Items.KELP, Items.SUGAR_CANE
	};

	private static final Item[] CARNIVORE_FOODS = {
			Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN,
			Items.RABBIT, Items.COD, Items.SALMON, Items.ROTTEN_FLESH,
			Items.TROPICAL_FISH, Items.COOKED_BEEF
	};

	private static final Item[] OMNIVORE_FOODS = {
			Items.BREAD, Items.EGG, Items.COOKIE, Items.HONEYCOMB,
			Items.PUMPKIN_PIE, Items.CHICKEN, Items.CARROT, Items.SWEET_BERRIES,
			Items.BEETROOT, Items.COD
	};

	private TamingPreference() {
	}

	public static Item favouriteFood(Genome genome) {
		Item[] table = switch (DietGroup.of(genome)) {
			case HERBIVORE -> HERBIVORE_FOODS;
			case CARNIVORE -> CARNIVORE_FOODS;
			case OMNIVORE -> OMNIVORE_FOODS;
		};
		// Keyed on lineage, not seed, so siblings and descendants share a preference.
		int index = (int) Math.floorMod(genome.lineage() >> 8, table.length);
		return table[index];
	}

	/**
	 * Chance per feeding that the creature is won over.
	 * <p>
	 * Timid animals are won over easily; aggressive predators take real persistence. Large animals
	 * are harder still, which keeps the most powerful mounts from being trivial to acquire.
	 */
	public static float tameChance(Genome genome, float mass) {
		float base = 0.42f;
		base -= 0.22f * genome.raw(Gene.AGGRESSION);
		base += 0.12f * genome.raw(Gene.SOCIABILITY);
		base -= 0.10f * genome.raw(Gene.TERRITORIALITY);
		// Mass is a strong brake: a house-sized animal should be an undertaking.
		base /= (1f + mass * 2.2f);
		return Math.max(0.04f, Math.min(0.7f, base));
	}
}
