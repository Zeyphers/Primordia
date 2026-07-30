package dev.jsz.primordia.entity.ai;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;

/**
 * Ground navigation that can follow a path up a wall.
 * <p>
 * The graph side lives in {@link SurfaceNodeEvaluator}; this is the follower. Ground segments are
 * vanilla's business, untouched: waypoint advancing, MoveControl, stuck detection. The moment the
 * next node is a hanging cell — or the creature is already on a wall — the vanilla machinery is
 * bypassed entirely, because all of it assumes a floor: MoveControl produces forward walking,
 * {@code followThePath} measures arrival against floor height, and stuck detection is calibrated to
 * the walking speed attribute. Climb segments instead renew the creature's per-tick climbing intent
 * with {@link CreatureEntity#climbToward}, which is the same lapse-if-not-renewed contract every
 * other consumer of the climbing physics uses: if this navigation stops ticking for any reason, the
 * creature lets go rather than hanging forever.
 * <p>
 * A mantle or a dismount is a committed move. While one is running this does nothing at all — no
 * waypoint advancing, no intent, no recomputation — and picks the path back up when the body arrives.
 */
public class SurfaceClimberNavigation extends GroundPathNavigation {
	/** How close to a node's centre, in blocks and in all three axes, counts as reached. */
	private static final double NODE_REACHED = 0.6;
	/** Ticks pressed at a node without getting nearer before the path is abandoned as unclimbable. */
	private static final int CLIMB_STALL_TICKS = 40;
	/** Progress smaller than this is shivering, not travel. */
	private static final double PROGRESS_EPSILON = 0.01;
	/** The drop behind a dismount, matching the graph's own gate of two open cells below the lip. */
	private static final int DISMOUNT_MIN_DROP = 2;

	/** Stall tracking for the climb segments, which vanilla's speed-based detection cannot judge. */
	private BlockPos stallNode;
	private double stallBestSq;
	private int stallTicks;

	public SurfaceClimberNavigation(CreatureEntity creature, Level level) {
		super(creature, level);
		// Without this, a target in the air — a cell partway up a cliff face — is snapped down to the
		// surface below it before the search even starts, and no wall is ever pathed to.
		setCanPathToTargetsBelowSurface(true);
	}

	private CreatureEntity creature() {
		return (CreatureEntity) this.mob;
	}

	@Override
	protected PathFinder createPathFinder(int maxVisitedNodes) {
		this.nodeEvaluator = new SurfaceNodeEvaluator();
		return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Vanilla's answer is "on the ground or in liquid", which goes false the moment a climb starts —
	 * and with it every recomputation and every new {@code moveTo}, freezing the path mid-wall.
	 */
	@Override
	protected boolean canUpdatePath() {
		CreatureEntity creature = creature();
		return super.canUpdatePath() || creature.isClimbing() || creature.isMantling() || creature.isDescending();
	}

	@Override
	public void tick() {
		CreatureEntity creature = creature();
		// Committed: the body is mid-push over a lip or backing over a ledge. setClimbing is a no-op
		// during both, so there is nothing useful to say, and advancing waypoints against a body that
		// is not steering would desynchronise the path from the creature.
		if (creature.isMantling() || creature.isDescending()) return;

		if (!isDone() && (creature.isClimbing() || isHangingCell(this.path.getNextNode()))) {
			this.tick++;
			if (this.hasDelayedRecomputation) recomputePath();
			if (!isDone()) tickClimb();
			return;
		}

		resetStall();
		super.tick();
	}

	private void tickClimb() {
		CreatureEntity creature = creature();
		BlockPos nodePos = this.path.getNextNodePos();
		Vec3 target = Vec3.atBottomCenterOf(nodePos);
		double dx = target.x - creature.getX();
		double dy = target.y - creature.getY();
		double dz = target.z - creature.getZ();
		double distSq = dx * dx + dy * dy + dz * dz;

		if (distSq < NODE_REACHED * NODE_REACHED) {
			this.path.advance();
			resetStall();
			if (this.path.isDone()) stop();
			return;
		}

		if (stalled(nodePos, distSq)) {
			stop();
			return;
		}

		// Standing at a lip with the path going down over it: back over the edge. Committed once
		// begun; the descent hands itself over to the climb when it finds the face.
		if (!creature.isClimbing() && creature.onGround() && nodePos.getY() < creature.getBlockY()) {
			Direction over = creature.ledgeEdge(toward(dx, dz), DISMOUNT_MIN_DROP);
			if (over != null) {
				creature.beginDescent(over);
				return;
			}
		}

		Direction face = resolveFace(nodePos, dx, dz);
		if (face == null) {
			// No wall to hold. The graph said there was one, the world disagrees — blocks changed, or
			// the body drifted somewhere unexpected. Give up rather than steer at nothing.
			stop();
			return;
		}
		creature.climbToward(face, target.x, target.y, target.z);
	}

	/**
	 * The face to press into for the next stretch, re-derived from the blocks every tick.
	 * <p>
	 * The graph never stored one — see {@link SurfaceNodeEvaluator} — and deriving it fresh is what
	 * makes mid-column face switches work: when the wall a creature is on runs out but another face
	 * continues beside the same column, the facing swaps before the grip is lost, so the physics
	 * never mistakes the transition for a top-out. Keeping the current facing while it is still a
	 * wall wins over switching, because a body that re-picks its lean every tick shivers.
	 */
	private Direction resolveFace(BlockPos nodePos, double dx, double dz) {
		CreatureEntity creature = creature();
		BlockPos cell = creature.blockPosition();

		// Heading for the cell on top of a neighbouring column — the mantle edge. The face is the
		// column itself; driving up it is what triggers the committed push over the lip.
		if (nodePos.getY() > cell.getY()) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos beside = cell.relative(direction);
				if (beside.getX() == nodePos.getX() && beside.getZ() == nodePos.getZ()
						&& wallAt(cell, direction)) {
					return direction;
				}
			}
		}

		Direction current = creature.getClimbFacing();
		if (current != null && wallAt(cell, current)) return current;

		Direction preferred = toward(dx, dz);
		if (preferred != null && wallAt(cell, preferred)) return preferred;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (wallAt(cell, direction)) return direction;
		}
		return null;
	}

	/**
	 * Whether there is anything to grip in this direction, at feet or head height.
	 * <p>
	 * Deliberately looser than {@link CreatureEntity#wallBeside}, which wants two solid blocks
	 * because it decides whether a climb is worth <i>starting</i>. Mid-route, a single block of grip
	 * is a wall — the graph guaranteed continuity when it emitted the edge.
	 */
	private boolean wallAt(BlockPos cell, Direction direction) {
		return solid(cell.relative(direction)) || solid(cell.above().relative(direction));
	}

	private boolean solid(BlockPos pos) {
		return !this.level.getBlockState(pos).getCollisionShape(this.level, pos).isEmpty();
	}

	/** A cell with nothing underneath it — reachable only by holding onto something. */
	private boolean isHangingCell(Node node) {
		BlockPos below = new BlockPos(node.x, node.y - 1, node.z);
		return this.level.getBlockState(below).getCollisionShape(this.level, below).isEmpty();
	}

	/** Which way this offset mostly points, or null when it is all height. */
	private static Direction toward(double dx, double dz) {
		if (dx * dx + dz * dz < 0.04) return null;
		return Math.abs(dx) > Math.abs(dz)
				? (dx > 0 ? Direction.EAST : Direction.WEST)
				: (dz > 0 ? Direction.SOUTH : Direction.NORTH);
	}

	/**
	 * Whether the climb has stopped getting anywhere.
	 * <p>
	 * Nearest-approach with a deadline, the same shape as {@link
	 * dev.jsz.primordia.entity.goal.ClimbWallGoal}'s stall check and for the same reason: a creature
	 * grinding at geometry it cannot pass must give up while the player is still watching. Vanilla's
	 * own detection compares against the walking speed attribute, which says nothing about a climb.
	 */
	private boolean stalled(BlockPos nodePos, double distSq) {
		if (!nodePos.equals(this.stallNode)) {
			this.stallNode = nodePos;
			this.stallBestSq = distSq;
			this.stallTicks = 0;
			return false;
		}
		if (distSq < this.stallBestSq - PROGRESS_EPSILON) {
			this.stallBestSq = distSq;
			this.stallTicks = 0;
			return false;
		}
		return ++this.stallTicks > CLIMB_STALL_TICKS;
	}

	private void resetStall() {
		this.stallNode = null;
		this.stallTicks = 0;
	}
}
