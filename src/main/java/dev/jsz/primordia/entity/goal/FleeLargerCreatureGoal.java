package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.entity.CreatureEntity;
import dev.jsz.primordia.genome.Gene;
import dev.jsz.primordia.genome.Genome;
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
		if (creature.isCarcass() || creature.isPosing()) return false;
		if (creature.getControllingPassenger() != null) return false;

		BodyPlan myPlan = creature.getBodyPlan();
		if (myPlan == null) return false;

		Box searchBox = creature.getBoundingBox().expand(searchRange(), 4.0, searchRange());
		List<CreatureEntity> nearby = creature.getWorld().getEntitiesByClass(
				CreatureEntity.class, searchBox,
				other -> other != creature && other.isAlive() && !other.isCarcass());

		for (CreatureEntity other : nearby) {
			BodyPlan otherPlan = other.getBodyPlan();
			if (otherPlan == null) continue;
			if (!isThreat(other, otherPlan, myPlan)) continue;

			Vec3d away = NoPenaltyTargeting.findFrom(creature, 16, 7, other.getPos());
			if (away != null) {
				threat = other;
				fleeTarget = away;
				return true;
			}
		}
		return false;
	}

	/**
	 * How far this creature watches for danger, from {@link Gene#FEAR}.
	 * <p>
	 * Flight distance was a fixed twelve blocks for every animal. Deriving it from the fear locus
	 * means a nervous lineage keeps its distance and a bold one lets predators get close — and
	 * since being caught is now survivable, both are viable strategies with different costs, which
	 * is the sort of thing selection can actually act on.
	 */
	private double searchRange() {
		Genome g = creature.getGenome();
		return g == null ? 12.0 : 6.0 + 14.0 * g.raw(Gene.FEAR);
	}

	/**
	 * Whether this animal is worth running from: something large enough to be dangerous, or
	 * something that has already decided to eat you.
	 * <p>
	 * The second condition is the one that matters. Fleeing on size alone means a prey animal
	 * ignores the predator actively chasing it as long as the predator is not much bigger, which is
	 * how a bounded chase ends in a kill anyway — the prey has to actually run for the chase budget
	 * in {@code CreatureAttackGoal} to ever expire.
	 */
	private boolean isThreat(CreatureEntity other, BodyPlan otherPlan, BodyPlan myPlan) {
		if (other.getTarget() == creature) return true;
		return otherPlan.mass > myPlan.mass * 1.6f
				|| otherPlan.hipHeight > myPlan.hipHeight * 1.35f;
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
