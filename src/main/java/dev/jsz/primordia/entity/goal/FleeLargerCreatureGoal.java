package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;
import java.util.List;

/**
 * Causes smaller creatures to flee in fear when a significantly larger creature approaches.
 */
public class FleeLargerCreatureGoal extends Goal {
	private final CreatureEntity creature;
	private final double speed;
	private CreatureEntity threat;
	private Vec3d fleeTarget;

	public FleeLargerCreatureGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setControls(EnumSet.of(Control.MOVE));
	}

	@Override
	public boolean canStart() {
		if (creature.isTamed()) return false;
		if (creature.getControllingPassenger() != null) return false;

		BodyPlan myPlan = creature.getBodyPlan();
		if (myPlan == null) return false;

		Box searchBox = creature.getBoundingBox().expand(12.0, 4.0, 12.0);
		List<CreatureEntity> nearby = creature.getWorld().getEntitiesByClass(
				CreatureEntity.class, searchBox, other -> other != creature && other.isAlive());

		for (CreatureEntity other : nearby) {
			BodyPlan otherPlan = other.getBodyPlan();
			if (otherPlan == null) continue;
			// Threat condition: other creature is noticeably heavier or taller
			if (otherPlan.mass > myPlan.mass * 1.6f || otherPlan.hipHeight > myPlan.hipHeight * 1.35f) {
				Vec3d away = NoPenaltyTargeting.findFrom(creature, 16, 7, other.getPos());
				if (away != null) {
					threat = other;
					fleeTarget = away;
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean shouldContinue() {
		return threat != null && threat.isAlive() && !creature.getNavigation().isIdle();
	}

	@Override
	public void start() {
		if (fleeTarget != null) {
			creature.getNavigation().startMovingTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, speed);
		}
	}

	@Override
	public void stop() {
		threat = null;
		fleeTarget = null;
		creature.getNavigation().stop();
	}
}
