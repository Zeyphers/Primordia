package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * Causes smaller creatures to flee in fear when a significantly larger creature approaches.
 */
public class FleeLargerCreatureGoal extends Goal {
	private final CreatureEntity creature;
	private final double speed;
	private CreatureEntity threat;
	private Vec3 fleeTarget;

	public FleeLargerCreatureGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setFlags(EnumSet.of(Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		if (creature.isTamed()) return false;
		if (creature.isCarcass() || creature.isPosing()) return false;
		if (creature.getControllingPassenger() != null) return false;

		BodyPlan myPlan = creature.getBodyPlan();
		if (myPlan == null) return false;

		AABB searchBox = creature.getBoundingBox().inflate(searchRange(), 4.0, searchRange());
		List<CreatureEntity> nearby = creature.level().getEntitiesOfClass(
				CreatureEntity.class, searchBox,
				other -> other != creature && other.isAlive() && !other.isCarcass());

		for (CreatureEntity other : nearby) {
			BodyPlan otherPlan = other.getBodyPlan();
			if (otherPlan == null) continue;
			if (!isThreat(other, otherPlan, myPlan)) continue;

			Vec3 away = DefaultRandomPos.getPosAway(creature, 16, 7, other.position());
			if (away != null) {
				threat = other;
				fleeTarget = away;
				return true;
			}
		}
		return false;
	}

	private double searchRange() {
		Genome g = creature.getGenome();
		return g == null ? 12.0 : 6.0 + 14.0 * g.raw(Gene.FEAR);
	}

	private boolean isThreat(CreatureEntity other, BodyPlan otherPlan, BodyPlan myPlan) {
		if (other.getTarget() == creature) return true;
		return otherPlan.mass > myPlan.mass * 1.6f
				|| otherPlan.hipHeight > myPlan.hipHeight * 1.35f;
	}

	@Override
	public boolean canContinueToUse() {
		return threat != null && threat.isAlive() && !creature.getNavigation().isDone();
	}

	@Override
	public void start() {
		if (fleeTarget != null) {
			creature.getNavigation().moveTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, speed);
		}
	}

	@Override
	public void stop() {
		threat = null;
		fleeTarget = null;
		creature.getNavigation().stop();
	}
}
