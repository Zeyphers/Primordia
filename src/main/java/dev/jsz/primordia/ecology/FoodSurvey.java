package dev.jsz.primordia.ecology;

import dev.jsz.primordia.body.DietGroup;
import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * Measures how much food a patch of world can supply, and therefore how large an animal it can
 * support.
 * <p>
 * This is the first piece of the ecology milestone, brought forward because size without a
 * constraint is just a number going up: a world where every creature can be enormous has no big
 * creatures, only a uniform scale. Tying bulk to local productivity is what makes a giant mean
 * something — it says this valley could feed it.
 * <p>
 * Deliberately cheap and approximate. It samples on a coarse lattice rather than every block,
 * because it runs on spawn and then only every few seconds per creature, and precision here buys
 * nothing that a sparse sample does not already give.
 */
public final class FoodSurvey {
	/** Horizontal radius sampled around the creature. */
	private static final int RADIUS = 12;
	/** Vertical span sampled, centred on the creature. */
	private static final int HEIGHT = 5;
	/** Lattice step. Every third block in each axis is plenty for a density estimate. */
	private static final int STEP = 3;

	private FoodSurvey() {
	}

	/**
	 * Vegetation density in the surrounding area, roughly 0 (barren) to 1 (jungle floor).
	 */
	public static float plantDensity(World world, BlockPos origin) {
		int sampled = 0;
		int edible = 0;
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		for (int dx = -RADIUS; dx <= RADIUS; dx += STEP) {
			for (int dz = -RADIUS; dz <= RADIUS; dz += STEP) {
				for (int dy = -HEIGHT; dy <= HEIGHT; dy += STEP) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!world.isChunkLoaded(cursor)) continue;
					sampled++;
					var state = world.getBlockState(cursor);
					if (state.isIn(BlockTags.REPLACEABLE_BY_TREES)
							|| state.isIn(BlockTags.LEAVES)
							|| state.isIn(BlockTags.FLOWERS)
							|| state.isIn(BlockTags.CROPS)) {
						edible++;
					}
				}
			}
		}
		if (sampled == 0) return 0f;
		// Even a lush biome only fills a fraction of sampled volume, so scale up to reach 1.
		return Math.min(1f, (edible / (float) sampled) * 4.5f);
	}

	/** Prey availability for hunters: the biomass of smaller creatures within range. */
	public static float preyDensity(World world, CreatureEntity hunter) {
		Box box = hunter.getBoundingBox().expand(RADIUS, HEIGHT, RADIUS);
		var plan = hunter.getBodyPlan();
		float hunterMass = plan == null ? 0.2f : plan.mass;

		float biomass = 0f;
		// Carcasses are food, but they are not a standing prey population — counting them would
		// report a valley as well stocked precisely because everything in it has been killed.
		for (CreatureEntity other : world.getEntitiesByClass(CreatureEntity.class, box,
				e -> e != hunter && e.isAlive() && !e.isCarcass())) {
			var theirs = other.getBodyPlan();
			if (theirs == null || theirs.mass >= hunterMass * 0.85f) continue;
			biomass += theirs.mass;
		}
		return Math.min(1f, biomass * 2.5f);
	}

	/**
	 * The largest body mass this location can sustain for the given diet.
	 * <p>
	 * Carnivores get a lower ceiling from the same landscape: a food chain loses most of its energy
	 * at each step, so predators are always rarer and smaller than the herbivores beneath them.
	 */
	public static float carryingCapacity(World world, BlockPos origin, DietGroup diet, float preyDensity) {
		float plants = plantDensity(world, origin);
		return switch (diet) {
			case HERBIVORE -> 0.05f + plants * 1.6f;
			case OMNIVORE -> 0.05f + plants * 1.0f + preyDensity * 0.5f;
			case CARNIVORE -> 0.04f + preyDensity * 1.1f + plants * 0.25f;
		};
	}
}
