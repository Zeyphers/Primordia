package dev.jsz.primordia.ecology;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;

/**
 * Handles dynamic survival loot drops when a creature dies, derived from its body plan.
 */
public final class SurvivalDrops {
	private SurvivalDrops() {
	}

	public static void dropLoot(CreatureEntity creature, DamageSource source) {
		if (creature.getWorld().isClient()) return;
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return;

		ServerWorld world = (ServerWorld) creature.getWorld();

		// Meat drops scale with mass
		int meatCount = 1 + (int) Math.min(8, plan.mass * 4.5f);
		ItemStack meatStack = new ItemStack(plan.genome.raw(Gene.DIET) > 0.5f ? Items.BEEF : Items.MUTTON, meatCount);
		if (creature.isOnFire()) {
			meatStack = new ItemStack(plan.genome.raw(Gene.DIET) > 0.5f ? Items.COOKED_BEEF : Items.COOKED_MUTTON, meatCount);
		}
		creature.dropStack(meatStack);

		// Leather & Bone drops
		int hideCount = (int) Math.min(5, plan.mass * 2.5f);
		if (hideCount > 0) {
			creature.dropStack(new ItemStack(Items.LEATHER, hideCount));
		}
		int boneCount = 1 + (int) Math.min(6, plan.mass * 3.0f);
		creature.dropStack(new ItemStack(Items.BONE, boneCount));
	}
}
