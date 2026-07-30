package dev.jsz.primordia.entity.goal;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.EnumSet;

/**
 * Sends a climber up a wall for its own sake.
 * <p>
 * The physics live on {@link CreatureEntity#setClimbing}; this only decides when and where. Nothing
 * about climbing is passive any more — see that method for why the engine's own mechanism could not
 * be made to carry it.
 * <p>
 * Two ways this goes wrong and both look the same from outside, so both are checked: aiming at a wall
 * the creature is not actually against, and carrying on after it has stopped rising.
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
	/** How many blocks of solid wall a climb needs above the creature's feet to be worth starting. */
	private static final int WALL_HEIGHT = 3;

	/**
	 * Ticks held against the wall without gaining height before the attempt is abandoned.
	 * <p>
	 * Short enough that a creature at the foot of something it cannot climb gives up while the player
	 * is still watching, rather than grinding away at it for ten seconds.
	 */
	private static final int STALL_LIMIT = 35;
	/** Height gained that counts as progress, in blocks. */
	private static final double PROGRESS = 0.4;
	/**
	 * Ticks before a creature that stalled will try again.
	 * <p>
	 * Far longer than {@link #SEARCH_INTERVAL}, and the point of the stall check. Without it the goal
	 * restarted every three seconds against the same unclimbable spot, which reads as an animal stuck
	 * forever rather than one that tried something and gave up.
	 */
	private static final int STALL_COOLDOWN = 600;
	/** Ticks of rest after a climb that worked, so arriving somewhere is not immediately undone. */
	private static final int CLIMBED_COOLDOWN = 200;

	/** Blocks of drop below a ledge before it counts as a wall to be climbed down. */
	private static final int MIN_DESCENT = 3;

	private final CreatureEntity creature;
	private final double speed;
	private BlockPos wall;
	private Direction into;
	/** Set when this attempt is going down a ledge rather than up a wall. */
	private boolean descending;
	private Direction edge;
	private int cooldown;
	private int climbTicks;
	private int approachTicks;

	/** Highest point reached since reaching the wall, and how long since it last improved. */
	private double highestY;
	private int stalledTicks;
	/** This attempt is over. Distinct from {@link #stalled}, which says it failed. */
	private boolean finished;
	private boolean stalled;
	private boolean gainedHeight;

	public ClimbWallGoal(CreatureEntity creature, double speed) {
		this.creature = creature;
		this.speed = speed;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
	}

	@Override
	public boolean canUse() {
		if (!creature.canClimb()) return false;
		if (creature.isCarcass() || creature.isAsleep() || creature.isPosing()) return false;
		if (creature.isTamed() || creature.getTarget() != null) return false;
		if (cooldown-- > 0) return false;
		cooldown = SEARCH_INTERVAL;
		if (creature.getRandom().nextFloat() > URGE) return false;

		if (findWall()) {
			descending = false;
			return true;
		}
		// Nothing to go up. A ledge to go down is the other half of being a climber, and the half that
		// stops one stranding itself on top of everything it ever climbed — the pathfinder will not take
		// an animal down a sheer face, so without this the only way off is to fall.
		edge = creature.ledgeEdge(null, MIN_DESCENT);
		descending = edge != null;
		return descending;
	}

	@Override
	public boolean canContinueToUse() {
		if (finished) return false;
		if (descending) return climbTicks <= MAX_CLIMB_TICKS;
		if (wall == null || into == null) return false;
		if (creature.getTarget() != null || creature.getLastHurtByMob() != null) return false;
		if (climbTicks > MAX_CLIMB_TICKS) return false;
		return approachTicks <= APPROACH_TIMEOUT;
	}

	@Override
	public void start() {
		climbTicks = 0;
		approachTicks = 0;
		stalledTicks = 0;
		finished = false;
		stalled = false;
		gainedHeight = false;
		highestY = creature.getY();
		if (descending) creature.beginDescent(edge);
	}

	@Override
	public void stop() {
		wall = null;
		into = null;
		edge = null;
		descending = false;
		climbTicks = 0;
		creature.getNavigation().stop();
		// A spot that could not be climbed is not worth retrying on the next search tick, and neither is
		// one that was just climbed — a creature that goes straight back up the wall it arrived from
		// reads as stuck rather than as a climber.
		if (stalled) cooldown = STALL_COOLDOWN;
		else if (gainedHeight) cooldown = CLIMBED_COOLDOWN;
		finished = false;
		stalled = false;
	}

	@Override
	public boolean isInterruptable() {
		return true;
	}

	@Override
	public void tick() {
		if (descending) {
			tickDescent();
			return;
		}
		if (wall == null || into == null) return;

		// Looking along the climb rather than at the block it picked, so the head still points the way
		// the body is going once the creature has risen past that block.
		creature.getLookControl().setLookAt(
				creature.getX() + into.getStepX() * 2.0,
				creature.getY() + 2.0,
				creature.getZ() + into.getStepZ() * 2.0);

		// A mantle is the creature committing to the top; leave it alone until it lands.
		if (creature.isMantling()) return;

		// Asked of the creature's own position, not assumed from where the wall was when it was chosen —
		// a creature that walked past the corner it aimed at is no longer on that wall. Except once it
		// is actually on one, where the answer is the wall it is on: re-deciding mid-climb lets a
		// creature sitting on a block boundary flip in and out of climbing every other tick.
		Direction surface = creature.isClimbing() ? creature.getClimbFacing() : creature.wallAdjacent(into);
		if (surface == null) {
			approachTicks++;
			// Coming off the wall after a climb has started is the end of the attempt either way: over
			// the top is a success and must not be punished with a cooldown, sliding off without having
			// risen is exactly what the cooldown is for.
			if (climbTicks > 0) {
				finished = true;
				stalled = !gainedHeight;
				return;
			}
			if (creature.getNavigation().isDone()) {
				creature.getNavigation().moveTo(
						wall.getX() + 0.5, wall.getY(), wall.getZ() + 0.5, speed);
			}
			return;
		}

		into = surface;
		creature.getNavigation().stop();
		approachTicks = 0;
		if (climbTicks++ == 0) {
			// Measured from the foot of the wall, not from wherever the goal happened to start. The walk
			// in can be downhill, and a baseline taken up there is one the creature cannot beat by
			// climbing — it would stall while visibly going up.
			highestY = creature.getY();
		}

		creature.setClimbing(into, 1f);

		if (creature.getY() > highestY + PROGRESS) {
			highestY = creature.getY();
			gainedHeight = true;
			stalledTicks = 0;
		} else if (++stalledTicks > STALL_LIMIT) {
			stalled = true;
			finished = true;
		}
	}

	/**
	 * Rides a descent down, from backing over the lip to arriving at the bottom.
	 * <p>
	 * The measure of progress is inverted — height lost, not gained — but everything else is the same
	 * stall check as the way up, and for the same reason: a creature grinding against an overhang it
	 * cannot get past has to give up rather than hang there.
	 */
	private void tickDescent() {
		// Backing over the edge is committed; nothing to do until it has hold of the face.
		if (creature.isDescending()) return;

		if (!creature.isClimbing()) {
			// Down and standing, or it never found a face and simply fell. Either way it is over.
			finished = true;
			return;
		}

		if (climbTicks++ == 0) highestY = creature.getY();
		creature.setClimbing(creature.getClimbFacing(), -1f);

		if (creature.getY() < highestY - PROGRESS) {
			highestY = creature.getY();
			gainedHeight = true;
			stalledTicks = 0;
		} else if (++stalledTicks > STALL_LIMIT) {
			stalled = true;
			finished = true;
		}
	}

	/**
	 * Finds the nearest climbable wall face, searching straight out along the four compass directions.
	 * <p>
	 * Scanning the surrounding box and then rounding the offset to a compass direction — which is what
	 * this used to do — picks the nearest solid block and then guesses which way it lies. For anything
	 * off the axes the guess points past the wall rather than into it, and the creature walks along the
	 * face it meant to climb. Going out one axis at a time means the direction it presses in is the
	 * direction the wall was found in.
	 */
	private boolean findWall() {
		BlockPos origin = creature.blockPosition();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int distance = 1; distance <= SEARCH_RADIUS; distance++) {
			for (Direction facing : Direction.Plane.HORIZONTAL) {
				int x = origin.getX() + facing.getStepX() * distance;
				int z = origin.getZ() + facing.getStepZ() * distance;
				if (!isWall(cursor, x, origin.getY(), z)) continue;

				wall = new BlockPos(x, origin.getY(), z);
				into = facing;
				return true;
			}
		}

		wall = null;
		into = null;
		return false;
	}

	/** Whether this column is solid from the creature's feet up far enough to be worth climbing. */
	private boolean isWall(BlockPos.MutableBlockPos cursor, int x, int y, int z) {
		for (int dy = 0; dy < WALL_HEIGHT; dy++) {
			cursor.set(x, y + dy, z);
			if (!creature.level().getBlockState(cursor).isSolidRender()) return false;
		}
		return true;
	}
}
