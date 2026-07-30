package dev.jsz.primordia.client;

import dev.jsz.primordia.anim.GroundProbe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.Level;

/**
 * {@link GroundProbe} backed by a live world. Finds the surface a foot could actually stand on
 * near a given column.
 * <p>
 * "Could actually stand on" is doing real work here. A naive probe that returns the first solid
 * block found scanning downward will happily latch onto the side of a wall or a tree trunk: the
 * foot's target column passes through the trunk, the scan finds the trunk block, and the leg
 * stretches up to plant on it — the limb appears glued to the wall as the creature walks by.
 * <p>
 * Two rules prevent that. A candidate surface must have <b>headroom</b> — the space just above it
 * has to be clear, which a solid wall column never is — and it must be within a <b>step height</b>
 * of the creature's own feet, so nothing plants on a surface it could not have climbed. When
 * neither rule can be satisfied the probe reports {@link Float#NaN} and the animator falls back to
 * the creature's own foot level, leaving the leg hanging naturally beside the obstacle instead of
 * reaching for it.
 * <p>
 * A block-state scan is used rather than {@code Level#raycast} because this runs once per foot per
 * frame — up to eight times per creature — and a raycast allocates a context object each call.
 */
public final class WorldGroundProbe implements GroundProbe {
	/** How far above the creature's feet a surface may be and still be steppable. */
	private static final float MAX_STEP_UP = 1.3f;
	/** How far below the creature's feet the scan will look before giving up. */
	private static final float MAX_DROP = 4.0f;
	/** Vertical clearance a candidate surface needs above it to count as standable. */
	private static final double REQUIRED_HEADROOM = 0.4;
	/**
	 * How far below a water surface a foot rests when there is no reachable bottom under it.
	 * <p>
	 * Enough to read as "in the water" rather than "on the water", and no more: the foot is being
	 * placed where the gait wanted it, not where the creature would have chosen to put it, so
	 * plunging the whole leg in would look as deliberate and as wrong as standing on top.
	 */
	private static final double WADE_DEPTH = 0.35;

	private Level world;
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	public GroundProbe forWorld(Level world) {
		this.world = world;
		return this;
	}

	/**
	 * @param referenceY the creature's own foot level, used as the origin for the step-up and
	 *                   drop limits — not a raw scan start height
	 */
	@Override
	public float groundY(double x, double z, double referenceY) {
		if (world == null) return Float.NaN;

		int blockX = (int) Math.floor(x);
		int blockZ = (int) Math.floor(z);
		int top = (int) Math.floor(referenceY + MAX_STEP_UP);
		int bottom = (int) Math.floor(referenceY - MAX_DROP);

		// Highest water surface passed on the way down, if any.
		double fluidSurface = Double.NaN;

		for (int y = top; y >= bottom; y--) {
			cursor.set(blockX, y, blockZ);
			BlockState state = world.getBlockState(cursor);

			if (Double.isNaN(fluidSurface)) {
				FluidState fluid = state.getFluidState();
				if (!fluid.isEmpty()) {
					fluidSurface = y + fluid.getHeight(world, cursor);
				}
			}

			VoxelShape shape = state.getCollisionShape(world, cursor);
			if (shape.isEmpty()) continue;

			// Surface height of this block, including partial blocks like slabs and stairs.
			double surface = y + shape.max(Direction.Axis.Y);

			// Too high to have been stepped onto — this is a wall face, not a floor.
			if (surface > referenceY + MAX_STEP_UP) continue;

			if (!hasHeadroom(blockX, blockZ, surface)) continue;

			// A bottom within reach is stood on even with water over it — that is wading, and it is
			// what a creature at the edge of a pond should do.
			return (float) surface;
		}

		// Nothing standable, but the column is open water.
		//
		// Returning NaN here is what made creatures appear to stand on the surface of a lake. NaN
		// means "no answer", and the animator's response to no answer is to search the four
		// neighbouring columns for something to rescue the foot onto — a behaviour that exists so a
		// foot at a cliff edge finds the ledge rather than dangling. At a shoreline those
		// neighbours include the bank the creature is standing on, so the foot snapped back up to
		// the land's height while sitting out over the water. Failing that, the final fallback is
		// the creature's own foot level, which lands in exactly the same place.
		//
		// Water is not an absence of an answer. The foot goes just under the surface, which is both
		// true and specific enough that neither rescue is reached.
		if (!Double.isNaN(fluidSurface)) {
			return (float) (fluidSurface - WADE_DEPTH);
		}
		return Float.NaN;
	}

	/**
	 * True when there is clear space directly above {@code surface}. This is what distinguishes a
	 * floor from the side of a wall: a wall's blocks are stacked, so every one of them fails.
     */
	private boolean hasHeadroom(int blockX, int blockZ, double surface) {
		int checkY = (int) Math.floor(surface + 1e-4);
		for (int offset = 0; offset <= 1; offset++) {
			cursor.set(blockX, checkY + offset, blockZ);
			VoxelShape above = world.getBlockState(cursor).getCollisionShape(world, cursor);
			if (above.isEmpty()) {
				// Fully clear block: definitely enough room.
				if (offset == 0) return true;
				continue;
			}
			double blockBottom = (checkY + offset) + above.min(Direction.Axis.Y);
			double gap = blockBottom - surface;
			if (gap < REQUIRED_HEADROOM) return false;
			return true;
		}
		return true;
	}
}
