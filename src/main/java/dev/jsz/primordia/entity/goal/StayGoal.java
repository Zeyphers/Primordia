package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Holds a creature in place while it has been told to stay.
 * <p>
 * It works by <i>claiming</i> the movement, jump and look controls at the highest priority rather
 * than by disabling the AI: every other goal that wants to move the creature is then locked out
 * for as long as this one runs, and releases normally the moment it stops. Disabling the AI
 * outright would also stop the creature defending itself, and a companion that has to be told to
 * stand up before it will fight back is not much of a companion.
 */
public class StayGoal extends Goal {
	private final CreatureEntity creature;

	public StayGoal(CreatureEntity creature) {
		this.creature = creature;
		setControls(EnumSet.of(Control.MOVE, Control.JUMP, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		// A creature being ridden is not sitting, whatever the flag says.
		return creature.isSitting() && !creature.hasPassengers() && creature.isOnGround();
	}

	@Override
	public boolean shouldContinue() {
		return creature.isSitting() && !creature.hasPassengers();
	}

	@Override
	public void start() {
		creature.getNavigation().stop();
	}

	@Override
	public void tick() {
		creature.getNavigation().stop();
	}
}
