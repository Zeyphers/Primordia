package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Tempt goal that dynamically checks the creature's favourite food on every tick rather than
 * baking a static item ingredient at entity construction time (which runs before the genome arrives).
 * <p>
 * A climber will also go <i>up</i> for it. Holding food above a creature that can climb is the one
 * piece of the mechanic a player can actually direct — walk it to a wall, hold its food up, and it
 * scales the wall and hangs there under your hand. Ground pathfinding cannot express any of that: a
 * position three blocks up a sheer face has no path to it, so the creature simply milled about at the
 * bottom, which is what it did before this.
 */
public class CreatureTemptGoal extends Goal {
	private static final TargetingConditions TEMPT_PREDICATE = TargetingConditions.forNonCombat().range(10.0).ignoreLineOfSight();

	/** How far above the creature the food has to be before climbing is the answer rather than walking. */
	private static final double CLIMB_TRIGGER = 1.6;
	/** Roughly where a held item sits above the holder's feet. */
	private static final double HAND_HEIGHT = 0.7;
	/** Slack around the food's height inside which the creature just holds on rather than climbing. */
	private static final double HOLD_BAND = 0.5;
	/**
	 * How far out the creature will still try to reach a wall for. Beyond this it is not climbing to
	 * the food, it is climbing something that happens to be nearby.
	 */
	private static final double CLIMB_RANGE_SQ = 8.0 * 8.0;
	/** Blocks of drop below a ledge before backing down it beats looking for a way round. */
	private static final int MIN_DESCENT = 3;
	/**
	 * How nearly underneath the food a creature must be before going up after it.
	 * <p>
	 * Squared horizontal distance. Small on purpose: from further out the answer is to walk closer, and
	 * a creature that starts climbing the moment food is raised anywhere near it stops following.
	 */
	private static final double CLIMB_UNDERNEATH_SQ = 3.0 * 3.0;

	private final CreatureEntity creature;
	private final double speed;
	private Player closestPlayer;
	private int cooldown;

	public CreatureTemptGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		if (cooldown > 0) {
			cooldown--;
			return false;
		}
		if (creature.isTamed()) return false;
		if (creature.getControllingPassenger() != null) return false;

		closestPlayer = creature.level().getNearestPlayer(creature, 10.0);
		if (closestPlayer == null) return false;
		if (creature.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
				&& !TEMPT_PREDICATE.test(serverLevel, creature, closestPlayer)) {
			closestPlayer = null;
			return false;
		}

		return isTempting(closestPlayer.getMainHandItem()) || isTempting(closestPlayer.getOffhandItem());
	}

	@Override
	public boolean canContinueToUse() {
		if (closestPlayer == null || !closestPlayer.isAlive()) return false;
		if (creature.distanceToSqr(closestPlayer) > 144.0) return false;
		return isTempting(closestPlayer.getMainHandItem()) || isTempting(closestPlayer.getOffhandItem());
	}

	@Override
	public void start() {
		creature.getNavigation().moveTo(closestPlayer, speed);
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
		creature.getLookControl().setLookAt(closestPlayer, 30.0f, 30.0f);

		if (climbToward(closestPlayer)) return;

		if (creature.distanceToSqr(closestPlayer) < 6.25) {
			creature.getNavigation().stop();
		} else {
			creature.getNavigation().moveTo(closestPlayer, speed);
		}
	}

	/**
	 * Goes up after food held out of reach, and reports whether it took over the creature's movement.
	 * <p>
	 * Two phases, and no state to hold between them: if the creature is already against a wall it
	 * climbs, and if it is not it walks at the food's <i>ground</i> position until it meets one. Walking
	 * at the ground position rather than at the player is what makes the first phase work at all — the
	 * player is up in the air, so a path to them is either impossible or goes the long way round, while
	 * a path to the floor beneath them ends at the foot of whatever they are standing on.
	 * <p>
	 * Once on the wall it climbs to the height of the hand and stops there, rather than to the top. An
	 * animal that came up for the food and then carried on past it would not read as having come for
	 * the food.
	 */
	private boolean climbToward(Player tempter) {
		if (!creature.canClimb()) return false;
		// Getting over a lip, either way up, is a committed move. Leave it be.
		if (creature.isMantling() || creature.isDescending()) return true;
		if (creature.distanceToSqr(tempter) > CLIMB_RANGE_SQ) return false;

		double food = tempter.getY() + HAND_HEIGHT;
		double lift = food - creature.getY();

		// Already on the wall: steer to the hand across the face as well as up it, so food held off to
		// one side draws the creature along the wall rather than leaving it climbing a fixed line.
		//
		// Starting a climb and staying on one are deliberately different questions. Answering both with
		// the same threshold is what made them oscillate: a creature that has climbed to the food is, by
		// definition, no longer far below it, so the trigger stopped being met the moment it arrived, the
		// goal handed back to ground movement, and it let go, fell, and started again. It now only lets
		// go once it is back on the floor with the food no longer out of reach.
		if (creature.isClimbing()) {
			if (creature.onGround() && Math.abs(lift) < CLIMB_TRIGGER) return false;
			creature.getNavigation().stop();
			creature.climbToward(creature.getClimbFacing(), tempter.getX(), food, tempter.getZ());
			return true;
		}

		// Climbing is for food walking cannot reach, and nothing else. Everything below narrows it to
		// that case, because the first version did not: it took any wall in any of the four directions,
		// and in a cave a creature is nearly always beside one. Held food a little above head height was
		// enough to send them up whatever happened to be next to them — including walls pointing away
		// from the player — instead of simply walking over. Following is the common case and must not be
		// something climbing can steal.
		double dx = tempter.getX() - creature.getX();
		double dz = tempter.getZ() - creature.getZ();
		if (lift >= CLIMB_TRIGGER && dx * dx + dz * dz <= CLIMB_UNDERNEATH_SQ) {
			Direction toward = towardHorizontally(tempter);
			// Strictly the wall between the creature and the food. No fallback: if the way up is not on
			// the side the food is on, this is not a climb, it is a detour.
			if (toward != null && creature.wallBeside(toward)) {
				creature.getNavigation().stop();
				creature.setClimbing(toward, 1f);
				return true;
			}
			// Otherwise fall through and walk, which is what brings it under the food in the first place.
			return false;
		}

		Direction toward = towardHorizontally(tempter);

		// Food well below, and standing on a ledge with no way down: back over the edge onto the face.
		// Without this a climber lured up something is stranded there, because walking off is the only
		// other way down and the pathfinder will not do it.
		if (-lift >= CLIMB_TRIGGER && dx * dx + dz * dz <= CLIMB_UNDERNEATH_SQ) {
			Direction over = creature.ledgeEdge(toward, MIN_DESCENT);
			if (over != null) {
				creature.getNavigation().stop();
				creature.beginDescent(over);
				return true;
			}
		}

		return false;
	}

	/** Which way the tempter lies, ignoring height. Null when they are directly overhead. */
	private Direction towardHorizontally(Player tempter) {
		double dx = tempter.getX() - creature.getX();
		double dz = tempter.getZ() - creature.getZ();
		if (dx * dx + dz * dz < 0.04) return null;
		return Math.abs(dx) > Math.abs(dz)
				? (dx > 0 ? Direction.EAST : Direction.WEST)
				: (dz > 0 ? Direction.SOUTH : Direction.NORTH);
	}

	private boolean isTempting(ItemStack stack) {
		return !stack.isEmpty() && stack.is(creature.getFavouriteFood());
	}
}
