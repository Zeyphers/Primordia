package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.body.BodyPlan;
import dev.jsz.primordia.ecology.EnergyBudget;
import dev.jsz.primordia.entity.CreatureActivity;
import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Sends a meat-eater to a body and has it eat.
 * <p>
 * This is the half of predation that was missing. Before carcasses existed a kill produced item
 * drops and nothing else, so killing gained the predator nothing at all — the loop had no closing
 * term and therefore nothing to satisfy. Feeding takes time, happens in one place, and is what
 * actually raises {@code energy}, which is what makes {@code wantsToHunt()} go false.
 * <p>
 * Omnivores use this too, which gives scavenging away for free: an animal that did not make the
 * kill can still profit from it, so the {@link dev.jsz.primordia.genome.Gene#DIET} gradient has a
 * viable middle rather than two ends with a gap between them.
 */
public class FeedOnCarcassGoal extends Goal {
	private static final double SEARCH_RADIUS = 20.0;
	private static final double SEARCH_HEIGHT = 8.0;
	/** Squared distance at which the creature is close enough to tear at the body. */
	private static final double REACH_SQ = 2.6 * 2.6;
	/** Ticks between searches, so a hungry creature with nothing to scavenge is not scanning constantly. */
	private static final int SEARCH_INTERVAL = 30;
	/** Give up if the body cannot be reached in this long — it may be across a ravine. */
	private static final int APPROACH_TIMEOUT = 300;

	private final CreatureEntity creature;
	private final double speed;
	private CreatureEntity carcass;
	private int cooldown;
	private int approachTicks;

	public FeedOnCarcassGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (creature.isCarcass() || creature.isAsleep() || creature.isPosing()) return false;
		if (!creature.getDietGroup().hunts()) return false;
		if (creature.getTarget() != null) return false;
		if (!creature.isHungry()) return false;
		if (cooldown-- > 0) return false;
		cooldown = SEARCH_INTERVAL;

		carcass = findCarcass();
		return carcass != null;
	}

	@Override
	public boolean canContinueToUse() {
		return carcass != null
				&& carcass.isAlive()
				&& carcass.getCarcassNutrition() > 0f
				&& creature.getTarget() == null
				&& creature.getEnergy() < 1f
				&& approachTicks < APPROACH_TIMEOUT;
	}

	@Override
	public void start() {
		approachTicks = 0;
		moveToCarcass();
	}

	@Override
	public void stop() {
		carcass = null;
		approachTicks = 0;
		creature.getNavigation().stop();
	}

	@Override
	public boolean isInterruptable() {
		return true;
	}

	@Override
	public void tick() {
		if (carcass == null) return;

		creature.getLookControl().setLookAt(carcass.getX(), carcass.getY(0.4), carcass.getZ());

		if (creature.distanceToSqr(carcass) > REACH_SQ) {
			approachTicks++;
			if (creature.getNavigation().isDone()) moveToCarcass();
			return;
		}

		approachTicks = 0;
		creature.getNavigation().stop();

		BodyPlan plan = creature.getBodyPlan();
		if (plan == null) return;

		float taken = carcass.consumeCarcass(EnergyBudget.feedRatePerTick(plan));
		if (taken <= 0f) return;
		creature.addEnergy(taken * EnergyBudget.energyPerNutrition(plan));

		if (creature.tickCount % 20 == 1) {
			creature.triggerActivity(CreatureActivity.FEED);
			// One tear per animation, so the sound is what the creature is visibly doing rather than a
			// loop running underneath it.
			creature.playFeedingSound();
		}
	}

	private void moveToCarcass() {
		if (carcass == null) return;
		creature.getNavigation().moveTo(carcass.getX(), carcass.getY(), carcass.getZ(), speed);
	}

	private CreatureEntity findCarcass() {
		AABB box = creature.getBoundingBox().inflate(SEARCH_RADIUS, SEARCH_HEIGHT, SEARCH_RADIUS);
		List<CreatureEntity> bodies = creature.level().getEntitiesOfClass(CreatureEntity.class, box,
				other -> other.isCarcass() && other.getCarcassNutrition() > 0f);
		return bodies.stream()
				.min(Comparator.comparingDouble(creature::distanceToSqr))
				.orElse(null);
	}
}
