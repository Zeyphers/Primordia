package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.EnumSet;

/**
 * Tempt goal that dynamically checks the creature's favourite food on every tick rather than
 * baking a static item ingredient at entity construction time (which runs before the genome arrives).
 */
public class CreatureTemptGoal extends Goal {
	private static final TargetPredicate TEMPT_PREDICATE = TargetPredicate.createNonAttackable().setBaseMaxDistance(10.0).ignoreVisibility();

	private final CreatureEntity creature;
	private final double speed;
	private PlayerEntity closestPlayer;
	private int cooldown;

	public CreatureTemptGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (cooldown > 0) {
			cooldown--;
			return false;
		}
		if (creature.isTamed()) return false;
		if (creature.getControllingPassenger() != null) return false;

		closestPlayer = creature.getWorld().getClosestPlayer(TEMPT_PREDICATE, creature);
		if (closestPlayer == null) return false;

		return isTempting(closestPlayer.getMainHandStack()) || isTempting(closestPlayer.getOffHandStack());
	}

	@Override
	public boolean shouldContinue() {
		if (closestPlayer == null || !closestPlayer.isAlive()) return false;
		if (creature.squaredDistanceTo(closestPlayer) > 144.0) return false;
		return isTempting(closestPlayer.getMainHandStack()) || isTempting(closestPlayer.getOffHandStack());
	}

	@Override
	public void start() {
		creature.getNavigation().startMovingTo(closestPlayer, speed);
	}

	@Override
	public void stop() {
		closestPlayer = null;
		creature.getNavigation().stop();
		cooldown = 10;
	}

	@Override
	public void tick() {
		if (closestPlayer == null) return;
		creature.getLookControl().lookAt(closestPlayer, 30.0f, 30.0f);
		if (creature.squaredDistanceTo(closestPlayer) < 6.25) {
			creature.getNavigation().stop();
		} else {
			creature.getNavigation().startMovingTo(closestPlayer, speed);
		}
	}

	private boolean isTempting(ItemStack stack) {
		return !stack.isEmpty() && stack.isOf(creature.getFavouriteFood());
	}
}
