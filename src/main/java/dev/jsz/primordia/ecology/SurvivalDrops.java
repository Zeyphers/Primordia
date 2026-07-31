package dev.jsz.primordia.ecology;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SurvivalDrops {
	private SurvivalDrops() {
	}

	public static boolean killedByPlayer(DamageSource source) {
		if (source == null) return false;
		Entity attacker = source.getEntity();
		if (attacker instanceof Player) return true;
		return attacker instanceof CreatureEntity creature && creature.isDomesticated();
	}

	public static void dropLoot(CreatureEntity creature, float fraction) {
		if (!(creature.level() instanceof ServerLevel world)) return;
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return;

		float share = Math.max(0f, Math.min(1f, fraction));
		boolean carnivore = plan.genome.raw(Gene.DIET) > 0.5f;

		int meatCount = scale(1 + (int) Math.min(8, plan.mass * 4.5f), share);
		if (meatCount > 0) {
			boolean cooked = creature.isOnFire();
			boolean fresh = creature.isFreshCarcass();
			var item = !fresh
					? Items.ROTTEN_FLESH
					: (carnivore
							? (cooked ? Items.COOKED_BEEF : Items.BEEF)
							: (cooked ? Items.COOKED_MUTTON : Items.MUTTON));
			creature.spawnAtLocation(world, new ItemStack(item, meatCount));
		}

		int hideCount = scale((int) Math.min(5, plan.mass * 2.5f), share);
		if (hideCount > 0) {
			creature.spawnAtLocation(world, new ItemStack(Items.LEATHER, hideCount));
		}

		int boneCount = 1 + (int) Math.min(6, plan.mass * 3.0f);
		creature.spawnAtLocation(world, new ItemStack(Items.BONE, boneCount));
	}

	public static void dropSkeletalRemains(CreatureEntity carcass) {
		if (!(carcass.level() instanceof ServerLevel world)) return;
		BodyPlan plan = carcass.getBodyPlan();
		if (plan == null) return;
		int boneCount = 1 + (int) Math.min(3, plan.mass * 1.5f);
		carcass.spawnAtLocation(world, new ItemStack(Items.BONE, boneCount));
	}

	private static int scale(int full, float share) {
		return Math.round(full * share);
	}
}
