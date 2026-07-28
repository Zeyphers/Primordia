package dev.jsz.primordia.ecology;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * What a dead creature leaves behind, derived from its body plan.
 * <p>
 * <b>Only the player's kills drop items.</b> This used to fire on every death whatever the cause,
 * which meant a predator working through a herd converted it into beef, leather and bone lying on
 * the ground — food nothing in the world could eat, and the most visible symptom of the ecology not
 * closing its loop. A creature killed by another creature now leaves a carcass, and the carcass is
 * the food; see {@link CreatureEntity#becomeCarcass}.
 * <p>
 * A player can still take a carcass someone else made, at a yield scaled by how much of it is left.
 * Finding a fresh kill and driving the predator off it is a good thing to be able to do.
 */
public final class SurvivalDrops {
	private SurvivalDrops() {
	}

	/** True when a player is responsible for this death, directly or via a projectile or pet. */
	public static boolean killedByPlayer(DamageSource source) {
		if (source == null) return false;
		Entity attacker = source.getAttacker();
		if (attacker instanceof PlayerEntity) return true;
		// A tamed companion's kill is the owner's kill; it would be odd for the animal you sent in
		// to leave you nothing.
		return attacker instanceof CreatureEntity creature && creature.isDomesticated();
	}

	/**
	 * Yield scaled by {@code fraction} of the body remaining, for harvesting a carcass that a
	 * predator has already been eating.
	 */
	public static void dropLoot(CreatureEntity creature, float fraction) {
		if (creature.getWorld().isClient()) return;
		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return;

		float share = Math.max(0f, Math.min(1f, fraction));
		boolean carnivore = plan.genome.raw(Gene.DIET) > 0.5f;

		int meatCount = scale(1 + (int) Math.min(8, plan.mass * 4.5f), share);
		if (meatCount > 0) {
			boolean cooked = creature.isOnFire();
			creature.dropStack(new ItemStack(
					carnivore
							? (cooked ? Items.COOKED_BEEF : Items.BEEF)
							: (cooked ? Items.COOKED_MUTTON : Items.MUTTON),
					meatCount));
		}

		int hideCount = scale((int) Math.min(5, plan.mass * 2.5f), share);
		if (hideCount > 0) {
			creature.dropStack(new ItemStack(Items.LEATHER, hideCount));
		}

		// Bone survives being eaten, so it does not scale with what is left of the soft tissue.
		int boneCount = 1 + (int) Math.min(6, plan.mass * 3.0f);
		creature.dropStack(new ItemStack(Items.BONE, boneCount));
	}

	/**
	 * What a carcass leaves when it rots away untouched: bone, and not much of it.
	 * <p>
	 * Deliberately small, and deliberately non-zero. A kill site that a scavenger never found should
	 * be readable on the ground weeks later — that is one of the few ambient signals the player has
	 * that something happened here without them. A carcass eaten down to nothing leaves nothing,
	 * so bones on the ground genuinely mean the local scavengers missed one.
	 */
	public static void dropSkeletalRemains(CreatureEntity carcass) {
		if (carcass.getWorld().isClient()) return;
		BodyPlan plan = carcass.getBodyPlan();
		if (plan == null) return;
		int boneCount = 1 + (int) Math.min(3, plan.mass * 1.5f);
		carcass.dropStack(new ItemStack(Items.BONE, boneCount));
	}

	private static int scale(int full, float share) {
		return Math.round(full * share);
	}
}
