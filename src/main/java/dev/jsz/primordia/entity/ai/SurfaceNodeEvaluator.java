package dev.jsz.primordia.entity.ai;

import dev.jsz.primordia.entity.CreatureEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * The ground node evaluator, plus wall surfaces.
 * <p>
 * Everything vanilla considers traversable stays traversable — walking, jumping, dropping. On top of
 * that, for a creature that {@link CreatureEntity#canClimb() can climb}, cells of open air beside a
 * solid face become part of the graph, so a path can run along the floor, up a wall, over the lip and
 * on — which is what turns {@code climbToward}'s line-of-sight steering into actual route planning.
 * A target behind an overhang, or on a face only reachable the long way round, now gets a route
 * instead of a creature pressed against the nearest point.
 * <p>
 * Nodes stay what vanilla thinks they are: a position, nothing more. A cell is in the graph if the
 * creature could be there somehow — standing or clinging — and <i>which</i> face it clings to is
 * re-derived from the blocks while following the path. That keeps every vanilla assumption about node
 * identity intact, and it collapses inside corners to nothing at all: same cell, different lean.
 * <p>
 * Every added edge is a move {@code climbTravel} already executes. The catalogue, for a creature in
 * cell {@code C}, a wall direction {@code S} (solid block beside the cell), and {@code A} across:
 * <ul>
 * <li><b>vertical</b> {@code C ↔ C±Y} — both cells open, some wall solid beside both, so the body has
 *     something to touch the whole way;</li>
 * <li><b>lateral</b> {@code C → C+A} — across the face, shared wall, target floorless (a floored
 *     target is already a vanilla walk edge);</li>
 * <li><b>outside corner</b> {@code C → C+S+A} — wrapping round the end of a wall, which is the
 *     diagonal drift {@code turnOutsideCorner} produces;</li>
 * <li><b>mantle</b> {@code C → C+S+Y} — over the lip onto the wall's top block; the push is committed
 *     movement, the graph just has to know the top is reachable;</li>
 * <li><b>dismount</b> {@code G → G+O−Y} — backing over a ledge onto the face below it, gated exactly
 *     the way {@link CreatureEntity#ledgeEdge} gates it: rock under the lip to hang from, and a real
 *     drop rather than a step.</li>
 * </ul>
 * No ceilings: {@code climbFacing} is horizontal, and the graph must not promise moves the body
 * cannot make.
 * <p>
 * All block tests are block-relative, never measured from the body. The collision box is floored at
 * 0.50 wide while a cave crawler's body is a third of a block, so anything measured outward from the
 * box fails for exactly the creatures this is for.
 */
public class SurfaceNodeEvaluator extends WalkNodeEvaluator {
	/**
	 * Extra cost on a hanging cell. Climbing is slower than walking ({@code CLIMB_SPEED} is about half
	 * a stride), so a route over a wall has to actually be shorter to win, not merely exist.
	 */
	private static final float CLIMB_MALUS = 2.0f;

	/**
	 * {@inheritDoc}
	 * <p>
	 * Vanilla scans <i>down</i> for a floor when the mob is airborne, which for a creature hanging
	 * halfway up a cliff starts every path at the bottom of it. A climber's start is the cell it is
	 * actually in.
	 */
	@Override
	public Node getStart() {
		if (this.mob instanceof CreatureEntity creature
				&& (creature.isClimbing() || creature.isMantling() || creature.isDescending())) {
			return getStartNode(this.mob.blockPosition());
		}
		return super.getStart();
	}

	@Override
	public int getNeighbors(Node[] neighbors, Node node) {
		int count = super.getNeighbors(neighbors, node);
		if (!(this.mob instanceof CreatureEntity creature) || !creature.canClimb()) return count;

		boolean aboveOpen = openCell(node.x, node.y + 1, node.z);
		boolean standing = standable(node.x, node.y, node.z);

		for (Direction wall : Direction.Plane.HORIZONTAL) {
			int wx = node.x + wall.getStepX();
			int wz = node.z + wall.getStepZ();
			if (!solidBlock(wx, node.y, wz)) continue;

			// Up the face. The wall must be solid beside both cells: the body's grip probes never
			// leave the cell the feet are in, so a gap mid-column is a fall, not a route.
			if (aboveOpen && solidBlock(wx, node.y + 1, wz)) {
				count = add(neighbors, count, node, climbNode(node.x, node.y + 1, node.z, CLIMB_MALUS));
			}

			// Down the face. Into open air needs the wall to continue; onto a floor is the landing at
			// the bottom, where dropping the last stride is what ending a descent already is.
			PathType below = getCachedPathType(node.x, node.y - 1, node.z);
			if (below == PathType.WALKABLE) {
				count = add(neighbors, count, node, climbNode(node.x, node.y - 1, node.z, 0f));
			} else if (below == PathType.OPEN && solidBlock(wx, node.y - 1, wz)) {
				count = add(neighbors, count, node, climbNode(node.x, node.y - 1, node.z, CLIMB_MALUS));
			}

			// Over the lip: the cell on top of the wall block, reached by the mantle.
			if (aboveOpen && standable(wx, node.y + 1, wz)) {
				count = add(neighbors, count, node, climbNode(wx, node.y + 1, wz, 0f));
			}

			for (Direction across : new Direction[]{wall.getClockWise(), wall.getCounterClockWise()}) {
				int ax = node.x + across.getStepX();
				int az = node.z + across.getStepZ();
				if (!openCell(ax, node.y, az)) continue;

				// Across the face, wall continuing alongside.
				if (solidBlock(ax + wall.getStepX(), node.y, az + wall.getStepZ())) {
					count = add(neighbors, count, node, climbNode(ax, node.y, az, CLIMB_MALUS));
				}

				// Round the end of the wall: the face that continues there is at right angles, one
				// cell diagonally on, and the sideways press carries the body through the corner.
				int cx = wx + across.getStepX();
				int cz = wz + across.getStepZ();
				PathType cornerType = getCachedPathType(cx, node.y, cz);
				if (cornerType == PathType.OPEN || cornerType == PathType.WALKABLE) {
					count = add(neighbors, count, node,
							climbNode(cx, node.y, cz, cornerType == PathType.OPEN ? CLIMB_MALUS : 0f));
				}
			}
		}

		// Backing over a ledge. Only from solid standing, only when the lip is backed by rock to hang
		// from, and only over a real drop — a single step down is vanilla's edge, not a dismount.
		if (standing && solidBlock(node.x, node.y - 2, node.z)) {
			for (Direction over : Direction.Plane.HORIZONTAL) {
				int ox = node.x + over.getStepX();
				int oz = node.z + over.getStepZ();
				if (openCell(ox, node.y, oz)
						&& openCell(ox, node.y - 1, oz)
						&& openCell(ox, node.y - 2, oz)) {
					count = add(neighbors, count, node, climbNode(ox, node.y - 1, oz, CLIMB_MALUS));
				}
			}
		}

		return count;
	}

	/**
	 * Appends a climb neighbour, keeping the array in bounds and free of duplicates — several edge
	 * types can land on the same cell, and vanilla may already have offered it as a walk or a drop.
	 */
	private int add(Node[] neighbors, int count, Node from, Node candidate) {
		if (candidate == null || count >= neighbors.length) return count;
		if (!isNeighborValid(candidate, from)) return count;
		for (int i = 0; i < count; i++) {
			if (neighbors[i] == candidate) return count;
		}
		neighbors[count] = candidate;
		return count + 1;
	}

	/**
	 * The node for a cell reached by climbing.
	 * <p>
	 * Typed {@code WALKABLE} so every vanilla check treats it as ordinary traversable ground; the
	 * climbing is the follower's business. That deliberately overwrites {@code BLOCKED} — vanilla
	 * marks a cell blocked when it is too far to <i>fall</i> to, and a climber does not fall.
	 */
	private Node climbNode(int x, int y, int z, float extraMalus) {
		Node node = getNode(x, y, z);
		if (node.type == null || node.type == PathType.OPEN || node.type == PathType.BLOCKED) {
			node.type = PathType.WALKABLE;
		}
		node.costMalus = Math.max(node.costMalus, extraMalus);
		return node;
	}

	/** Open air with no floor and no hazard — the only thing a body can hang in. */
	private boolean openCell(int x, int y, int z) {
		return getCachedPathType(x, y, z) == PathType.OPEN;
	}

	/** A cell the creature could stand in. */
	private boolean standable(int x, int y, int z) {
		return getCachedPathType(x, y, z) == PathType.WALKABLE;
	}

	/** Whether this block has any collision at all — the same test the climbing physics uses. */
	private boolean solidBlock(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = this.currentContext.getBlockState(pos);
		return !state.getCollisionShape(this.currentContext.level(), pos).isEmpty();
	}
}
