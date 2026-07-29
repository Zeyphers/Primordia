package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.EnumSet;

/**
 * Sends a climber up a wall.
 * <p>
 * Minecraft's climbing is entirely passive: {@code LivingEntity} lifts anything whose
 * {@code isClimbing()} is true <i>while it is also horizontally colliding</i>, and that is the whole
 * mechanism. A spider climbs because it is chasing something and blunders into a wall — nothing
 * decides to climb.
 * <p>
 * That is why these were walking about on the floor. Their pathfinder is perfectly good at its job:
 * it routes <i>around</i> obstacles, so the creature never presses into one, never collides
 * horizontally, and the engine never lifts it. Climbing had to become something they choose.
 * <p>
 * The goal is deliberately simple — find a wall, face it, and keep walking into it. Everything above
 * that is the engine's.
 */
public class ClimbWallGoal extends Goal {
	/** How far to look for a wall worth climbing. */
	private static final int SEARCH_RADIUS = 6;
	/** Ticks between attempts, so a creature with no wall nearby is not scanning constantly. */
	private static final int SEARCH_INTERVAL = 60;
	/** Chance per attempt of bothering. Climbing should be something they do, not all they do. */
	private static final float URGE = 0.35f;
	/** Ticks spent on a wall before letting go and going back to the floor. */
	private static final int MAX_CLIMB_TICKS = 200;
	/** Ticks allowed to reach the wall before the attempt is written off. */
	private static final int APPROACH_TIMEOUT = 80;

	private final CreatureEntity creature;
	private final double speed;
	private BlockPos wall;
	private Direction into;
	private int cooldown;
	private int climbTicks;
	private int approachTicks;

	public ClimbWallGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		if (!creature.canClimb()) return false;
		if (creature.isCarcass() || creature.isAsleep() || creature.isPosing()) return false;
		if (creature.isTamed() || creature.getTarget() != null) return false;
		if (cooldown-- > 0) return false;
		cooldown = SEARCH_INTERVAL;
		if (creature.getRandom().nextFloat() > URGE) return false;

		return findWall();
	}

	@Override
	public boolean shouldContinue() {
		if (wall == null || into == null) return false;
		if (creature.getTarget() != null || creature.getAttacker() != null) return false;
		if (climbTicks > MAX_CLIMB_TICKS) return false;
		return approachTicks <= APPROACH_TIMEOUT;
	}

	@Override
	public void start() {
		climbTicks = 0;
		approachTicks = 0;
	}

	@Override
	public void stop() {
		wall = null;
		into = null;
		climbTicks = 0;
		creature.setClimbFacing(null);
		creature.getNavigation().stop();
	}

	@Override
	public boolean canStop() {
		return true;
	}

	@Override
	public void tick() {
		if (wall == null || into == null) return;

		creature.getLookControl().lookAt(
				wall.getX() + 0.5, creature.getY() + 0.5, wall.getZ() + 0.5);

		double dx = (wall.getX() + 0.5) - creature.getX();
		double dz = (wall.getZ() + 0.5) - creature.getZ();
		if (dx * dx + dz * dz > 2.5 * 2.5) {
			approachTicks++;
			if (creature.getNavigation().isIdle()) {
				creature.getNavigation().startMovingTo(
						wall.getX() + 0.5, wall.getY(), wall.getZ() + 0.5, speed);
			}
			return;
		}

		// Close enough. From here the creature is driven at the wall through its move control
		// rather than by the navigator, which would steer around the obstacle instead of into it.
		//
		// It has to be the move control and not a velocity nudge. LivingEntity.applyMovementInput
		// rebuilds velocity from the mob's movement input every tick before it moves, so anything
		// set by hand is overwritten before it can do anything — and with no input the creature
		// stops pressing the wall, horizontalCollision goes false, and the engine's climb never
		// triggers. That is the whole reason they were standing at the foot of a wall shuffling:
		// the lift is gated on a collision that the goal had itself stopped producing.
		creature.getNavigation().stop();
		approachTicks = 0;
		climbTicks++;

		// Aimed through the wall and above, so the input keeps pointing into it as the creature
		// rises rather than being satisfied the moment it arrives.
		creature.getMoveControl().moveTo(
				creature.getX() + into.getOffsetX() * 2.0,
				creature.getY() + 3.0,
				creature.getZ() + into.getOffsetZ() * 2.0,
				speed);
		creature.setClimbFacing(into);
	}

	/** The nearest solid face at about body height that a creature could get onto. */
	private boolean findWall() {
		BlockPos origin = creature.getBlockPos();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		BlockPos best = null;
		Direction bestFacing = null;
		double bestDistance = Double.MAX_VALUE;

		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
				for (int dy = 0; dy <= 1; dy++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!creature.getWorld().getBlockState(cursor).isSolidBlock(creature.getWorld(), cursor)) {
						continue;
					}
					// It is only a wall if there is something above it to climb onto. A single
					// block on the floor is a step, and walking into it forever looks broken.
					cursor.set(origin.getX() + dx, origin.getY() + dy + 2, origin.getZ() + dz);
					if (!creature.getWorld().getBlockState(cursor).isSolidBlock(creature.getWorld(), cursor)) {
						continue;
					}

					double distance = dx * dx + dz * dz;
					if (distance < 1.0 || distance >= bestDistance) continue;

					Direction facing = Direction.getFacing(dx, 0, dz);
					best = new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					bestFacing = facing;
					bestDistance = distance;
				}
			}
		}

		wall = best;
		into = bestFacing;
		return wall != null;
	}
}
