package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Walks a creature into the lava it has been pointed at, for {@code /primordia debug lava}.
 * <p>
 * A debug goal, and the only one in the mod: it is registered on every creature but is inert
 * unless {@link CreatureEntity#getLavaMarch()} has been set, which nothing but the command does.
 * A goal is the right shape for this even so, because the alternative — steering from the command
 * or from {@code tick} — loses the argument with the goal selector on the very next tick. Every
 * other goal re-issues its own navigation continuously, so the only way to send an animal
 * somewhere it does not want to go is to be a goal that outranks them.
 * <p>
 * Sits at priority 0, ahead of even {@code StayGoal}, so a creature that has been told to stay
 * still goes: the command means all of them.
 */
public class LavaMarchGoal extends Goal {
	/** How often the path is re-issued while marching, in ticks. */
	private static final int REPATH_INTERVAL = 20;
	/** Close enough to be committed — the last stride is walked by the move control alone. */
	private static final double ARRIVAL = 2.0;

	private final CreatureEntity creature;
	private final double speed;
	private int repathTimer;

	public LavaMarchGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		// Carcasses are excluded because they are dead creatures still using this entity type, and a
		// body that walks to the lava on its own is a worse bug than the one being debugged.
		return creature.getLavaMarch() != null && !creature.isCarcass();
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public void start() {
		repathTimer = 0;
	}

	@Override
	public void stop() {
		creature.getNavigation().stop();
	}

	@Override
	public void tick() {
		BlockPos target = creature.getLavaMarch();
		if (target == null) return;

		creature.getLookControl().setLookAt(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

		// Once it is standing in the stuff the march is over and the goal releases the animal back to
		// its own AI — which promptly panics, which is the correct and much funnier outcome.
		if (creature.isInLava()) {
			creature.clearLavaMarch();
			return;
		}

		double distanceSqr = creature.distanceToSqr(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

		// Inside the last couple of blocks, drive the move control directly. Navigation refuses to
		// finish this part: a path node in lava is only reachable because the malus was zeroed, and
		// the last node is still standing on a surface the evaluator will not put a node above.
		if (distanceSqr < ARRIVAL * ARRIVAL) {
			creature.getMoveControl().setWantedPosition(
					target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, speed);
			return;
		}

		if (--repathTimer > 0) return;
		repathTimer = REPATH_INTERVAL;

		boolean pathed = creature.getNavigation().moveTo(
				target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
		// No route — walled off, or the lava is down a drop nothing can path into. Shove it at the
		// target in a straight line instead, so the animal at least commits and the command's effect
		// is visible rather than a herd standing about looking thoughtful.
		if (!pathed) {
			creature.getMoveControl().setWantedPosition(
					target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
		}
	}
}
